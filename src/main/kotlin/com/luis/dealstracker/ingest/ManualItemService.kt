package com.luis.dealstracker.ingest

import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.ManualItemRepository
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.amazon.AmazonFetcher
import com.luis.dealstracker.sources.amazon.AmazonSelectors
import com.luis.dealstracker.sources.amazon.ManualItemFetcher

/**
 * Adds and refreshes the items tracked by URL.
 *
 * ## Why it refreshes every poll
 *
 * Price tracking is only worth anything if the price is re-read, and `deactivateStale` retires any
 * row unseen for `staleAfterHours` (48h) — so an un-refreshed manual item would silently vanish from
 * the tab, which is the worst outcome: it looks like the item stopped existing rather than like the
 * app stopped looking. These are hand-picked, so one request each per hour is earned.
 *
 * Oldest-checked first, so an item that repeatedly fails cannot starve the rest of the list.
 *
 * ## Why it uses the same `deals` rows as the search path
 *
 * `upsertDeal` is keyed on `(source, source_deal_id)` and a manual item's `source_deal_id` is its
 * ASIN — the same key a searched row would get. So if a search later returns the same ASIN, the two
 * converge on one row with one continuous price history rather than competing. The tracked tab needs
 * no special case to show them.
 */
class ManualItemService(
    private val deals: DealRepository,
    private val items: ManualItemRepository,
    private val app: AppConfig,
    private val http: PoliteHttp,
    private val categorizer: Categorizer = Categorizer(emptyList()),
) {

    /** The provider manual rows belong to, so they behave exactly like searched ones. */
    private val provider get() = app.providers.firstOrNull { it.type == "amazon" }

    data class AddOutcome(val ok: Boolean, val message: String)

    /**
     * Fetches a pasted URL and starts tracking it.
     *
     * Fetches BEFORE inserting, deliberately: a URL that cannot be read is not yet a tracked item,
     * and recording the intent anyway would leave a row that never produces a price and can only be
     * diagnosed by reading logs. The one exception is a transient refusal — see the message text,
     * which says the retry will happen rather than implying the URL is wrong.
     */
    suspend fun add(url: String, brand: String?, productType: String?, lang: Lang = Lang.EN): AddOutcome {
        val cfg = provider ?: return AddOutcome(false, lang.tr("no amazon provider is configured", "no hay un proveedor de Amazon configurado"))

        val fetcher = AmazonFetcher(
            http = http,
            allowBrowser = cfg.options["allowBrowserFallback"]?.toBoolean() ?: true,
            logPrefix = "manual",
        )
        try {
            val result = ManualItemFetcher(fetcher, AmazonSelectors.from(cfg), cfg.requireBaseUrl().trimEnd('/'))
                .fetch(url, brand, productType)

            return when (result) {
                is ManualItemFetcher.Result.Failed -> {
                    // Recorded in English, as the fetch log is; the notice is in the page's language.
                    val why = ManualItemFetcher.describe(result.reason)
                    val shown = describeIn(lang, result.reason)
                    // A readable-but-refused URL is still worth remembering, so the poll can retry
                    // it. A malformed one is not: there is nothing to retry.
                    if (result.reason == ManualItemFetcher.Failure.Unreadable && result.asin != null) {
                        items.add(url, result.asin, brand, productType)
                        items.recordFetch(items.all().first { it.asin == result.asin }.id, why)
                        AddOutcome(true, lang.tr("added, but $shown", "agregado, pero $shown"))
                    } else {
                        AddOutcome(false, shown)
                    }
                }

                is ManualItemFetcher.Result.Ok -> {
                    val (item, isNew) = items.add(url, result.asin, brand, productType)
                    store(result.deal)
                    items.recordFetch(item.id, null)
                    AddOutcome(
                        true,
                        if (isNew) {
                            val title = result.deal.title.take(60)
                            lang.tr("now tracking $title", "ahora en seguimiento: $title")
                        } else {
                            lang.tr(
                                "already tracking that item — refreshed its price",
                                "ese producto ya está en seguimiento; se actualizó su precio",
                            )
                        },
                    )
                }
            }
        } finally {
            fetcher.close()
        }
    }

    /** [ManualItemFetcher.describe] in [lang], for the notice shown after an add. */
    private fun describeIn(lang: Lang, reason: ManualItemFetcher.Failure): String =
        if (lang == Lang.EN) {
            ManualItemFetcher.describe(reason)
        } else {
            when (reason) {
                ManualItemFetcher.Failure.NotAProductUrl ->
                    "esa URL no corresponde a un producto; pega un enlace amazon.com/dp/<ASIN>"
                ManualItemFetcher.Failure.Unreadable ->
                    "Amazon no entregó la página en este momento; se reintentará en la próxima consulta"
                ManualItemFetcher.Failure.NoPrice ->
                    "la página no tiene precio de compra (agotado, o sin oferta destacada)"
            }
        }

    /**
     * Re-reads every tracked URL. Called once per poll, after the searches.
     *
     * Never throws: a manual item failing must not stop the rest of the cycle, the same guarantee
     * `IngestService.runAll` gives each provider.
     */
    /**
     * @param alreadySeen ASINs a search already fetched this cycle. Those are skipped.
     *
     * Without this a manually-tracked item that a search ALSO returns is written twice per poll,
     * two seconds apart. Observed on a New Balance boxer brief: every hourly cycle left two
     * price_observations rows for one item, so its history read as a price oscillating between two
     * values when nothing had changed. "Lowest ever" and the change percentage are both computed
     * from that series, so a duplicate is not cosmetic — it fabricates movement.
     *
     * The search wins because it already paid for the request. Skipping is therefore also cheaper.
     */
    suspend fun refreshAll(alreadySeen: Set<String> = emptySet()): Int {
        val cfg = provider ?: return 0
        val all = items.all()
        val list = all.filterNot { it.asin in alreadySeen }
        if (list.isEmpty()) {
            if (all.isNotEmpty()) {
                println("[manual] all ${all.size} tracked URL(s) were already fetched by a search this cycle")
            }
            return 0
        }

        val fetcher = AmazonFetcher(
            http = http,
            allowBrowser = cfg.options["allowBrowserFallback"]?.toBoolean() ?: true,
            logPrefix = "manual",
        )
        var refreshed = 0
        var failed = 0

        try {
            val reader = ManualItemFetcher(fetcher, AmazonSelectors.from(cfg), cfg.requireBaseUrl().trimEnd('/'))
            for (item in list) {
                // try/catch rather than runCatching: `continue` is not allowed inside an inline
                // lambda without an experimental flag, and an unreadable item must not abort the run.
                val result = try {
                    reader.fetch(item.url, item.brand, item.productType)
                } catch (e: Exception) {
                    items.recordFetch(item.id, e.message ?: "fetch threw")
                    failed++
                    continue
                }

                when (result) {
                    is ManualItemFetcher.Result.Ok -> {
                        store(result.deal)
                        items.recordFetch(item.id, null)
                        refreshed++
                    }
                    is ManualItemFetcher.Result.Failed -> {
                        items.recordFetch(item.id, ManualItemFetcher.describe(result.reason))
                        failed++
                    }
                }
            }
        } finally {
            fetcher.close()
        }

        // Counted, never silent -- the house rule. A manual list that has quietly stopped refreshing
        // is indistinguishable in the UI from one whose prices have not moved.
        val skipped = all.size - list.size
        println(
            "[manual] refreshed $refreshed of ${list.size} tracked URL(s)" +
                (if (failed > 0) ", $failed failed" else "") +
                // Counted, so a shrinking manual refresh is explained rather than mysterious.
                (if (skipped > 0) ", $skipped already fetched by a search" else ""),
        )
        return refreshed
    }

    /**
     * Writes the deal and its observation, reading the historical minimum BEFORE inserting.
     *
     * That order is load-bearing and is the same rule `IngestService.ingest` follows: read the
     * minimum after recording, and the new price is inside its own minimum, so a genuine drop can
     * never be detected.
     */
    private fun store(raw: RawDeal) {
        val cfg = provider ?: return
        val coShipping = runCatching { CoShipping.valueOf(cfg.coShippingDefault.uppercase()) }
            .getOrDefault(CoShipping.UNKNOWN)
        val group = categorizer.categorize(raw.category, raw.title)

        deals.withTransaction { conn ->
            val upsert = deals.upsertDeal(conn, cfg.id, raw, coShipping, group)
            deals.recordObservation(conn, upsert.dealId, raw)
        }
    }
}
