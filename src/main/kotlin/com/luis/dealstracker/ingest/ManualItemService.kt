package com.luis.dealstracker.ingest

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
    suspend fun add(url: String, brand: String?, productType: String?): AddOutcome {
        val cfg = provider ?: return AddOutcome(false, "no amazon provider is configured")

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
                    val why = ManualItemFetcher.describe(result.reason)
                    // A readable-but-refused URL is still worth remembering, so the poll can retry
                    // it. A malformed one is not: there is nothing to retry.
                    if (result.reason == ManualItemFetcher.Failure.Unreadable && result.asin != null) {
                        items.add(url, result.asin, brand, productType)
                        items.recordFetch(items.all().first { it.asin == result.asin }.id, why)
                        AddOutcome(true, "added, but $why")
                    } else {
                        AddOutcome(false, why)
                    }
                }

                is ManualItemFetcher.Result.Ok -> {
                    val (item, isNew) = items.add(url, result.asin, brand, productType)
                    store(result.deal)
                    items.recordFetch(item.id, null)
                    AddOutcome(
                        true,
                        if (isNew) {
                            "now tracking ${result.deal.title.take(60)}"
                        } else {
                            "already tracking that item — refreshed its price"
                        },
                    )
                }
            }
        } finally {
            fetcher.close()
        }
    }

    /**
     * Re-reads every tracked URL. Called once per poll, after the searches.
     *
     * Never throws: a manual item failing must not stop the rest of the cycle, the same guarantee
     * `IngestService.runAll` gives each provider.
     */
    suspend fun refreshAll(): Int {
        val cfg = provider ?: return 0
        val list = items.all()
        if (list.isEmpty()) return 0

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
        println("[manual] refreshed $refreshed of ${list.size} tracked URL(s)" + if (failed > 0) ", $failed failed" else "")
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
