package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.SourceRegistry
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

data class IngestReport(
    val source: String,
    val fetched: Int = 0,
    val newDeals: Int = 0,
    val promoted: Int = 0,
    val priceDrops: Int = 0,
    val error: String? = null,
) {
    fun summary(): String = when {
        error != null -> "[$source] FAILED: $error"
        else -> "[$source] $fetched fetched, $newDeals new, $promoted promoted ($priceDrops real price drops)"
    }
}

/** Why a deal was promoted, kept explicit so the UI and logs can explain it. */
enum class PromotionReason { DISCOUNT_FLOOR, PRICE_DROP, CURATED_SOURCE, NONE }

class IngestService(
    private val repo: DealRepository,
    private val registry: SourceRegistry,
    private val app: AppConfig,
    private val http: PoliteHttp,
    private val categorizer: Categorizer = Categorizer(emptyList()),
    /**
     * Search terms contributed by the active watches, or null when the watchlist is not in
     * play. A lambda rather than a `WatchRepository` so ingest keeps no opinion about where
     * watches live, and so tests can drive it without a database.
     */
    private val watchTerms: () -> List<String>? = { null },
    /**
     * Records which watch's own search found which deal, keyed by deal id. Called after the
     * ingest transaction commits, so the deal rows exist. See `V15__watch_amazon_terms`.
     */
    private val linkWatchDeals: (Map<Long, Set<Long>>) -> Unit = {},
) {

    suspend fun runAll(): List<IngestReport> =
        app.enabledProviders.map { provider ->
            // One provider failing must not take the others down; the whole point of
            // fetch_runs is that a broken adapter is visible without being fatal.
            runCatching { run(provider) }.getOrElse { e ->
                val report = IngestReport(provider.id, error = e.message ?: e::class.simpleName ?: "unknown")
                println(report.summary())
                report
            }
        }.also { reportCategoryDrift() }

    /**
     * Warns when the share of deals stuck in `Other` has drifted past the configured threshold.
     *
     * Measured **across the stored active feed**, not the batch just ingested — which is the
     * whole reason this exists as its own check. `ingest` already prints a per-provider
     * uncategorised percentage, but that is a share of one 25-item Slickdeals fetch, so it
     * swings wildly and reads as noise; nobody would act on it. The stored share is the number
     * that actually reflects rule quality, and it had drifted from the tuned 33-of-612 back up
     * to 78-of-618 with nothing ever remarking on it.
     *
     * Names the command that fixes it, because a warning whose remedy you have to go and look
     * up is a warning that gets ignored.
     */
    fun reportCategoryDrift() {
        val threshold = app.defaults.categoryOtherWarnPercent
        if (threshold <= 0) return

        val counts = runCatching { repo.categoryGroupCounts() }.getOrElse { return }
        val total = counts.sumOf { it.second }
        if (total == 0) return

        val other = counts.firstOrNull { it.first == Categorizer.OTHER }?.second ?: 0
        val percent = other * 100 / total
        if (percent < threshold) return

        println(
            "[categories] WARNING: $other of $total active deals ($percent%) are '${Categorizer.OTHER}', " +
                "over the ${threshold}% threshold — a rule is probably missing or too narrow.\n" +
                "[categories]   see what is unmatched:  ./dt run --args=\"--recategorize --show-other\"",
        )
    }

    suspend fun run(provider: ProviderConfig): IngestReport {
        val runId = repo.startRun(provider.id)
        try {
            val source = registry.forType(provider.type)
            val fetched = source.fetch(withWatchTerms(provider), app, http)
            val deals = applyAgeLimit(provider, applyExclusions(provider, fetched))
            val report = ingest(provider, deals)
            repo.finishRun(runId, report.fetched, report.newDeals, null)
            println(report.summary())
            return report
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "unknown error"
            repo.finishRun(runId, 0, 0, message)
            throw e
        }
    }

    /**
     * Adds the active watches' terms to a provider's configured `searches`.
     *
     * This is the half of a watch that steers fetching rather than filtering it: without
     * it, a fridge would only ever be found if someone had configured an appliance category
     * on every site. With it, "nevera" becomes a real query against any source that can
     * search — `VtexSource` via `?ft=`, `SlickdealsSource` via `q=`.
     *
     * Merged rather than replaced, so config-declared sweeps (Slickdeals' "amazon" and
     * "apple") survive. Adapters that cannot keyword-search simply never read the key and
     * are matched on whatever their category crawl returns, which is why Homecenter still
     * works despite robots.txt disallowing its search URLs.
     *
     * Returns the provider untouched when there are no watches, so the copy is not made on
     * a system nobody is using yet.
     */
    internal fun withWatchTerms(provider: ProviderConfig): ProviderConfig {
        val terms = watchTerms() ?: return provider
        val merged = (provider.list("searches") + terms).distinct()
        if (merged.isEmpty()) return provider
        println("[${provider.id}] searching for ${merged.size} term(s): ${merged.take(6)}")
        return provider.copy(listOptions = provider.listOptions + ("searches" to merged))
    }

    /**
     * Drops deals whose title matches an exclusion pattern.
     *
     * Motivation: Amazon does ship internationally, but not groceries, liquids or
     * medicines — and bulky low-value consumables are not worth shipping to Colombia
     * even when permitted. Surfacing them wastes the manual shipping check.
     */
    internal fun applyExclusions(provider: ProviderConfig, deals: List<RawDeal>): List<RawDeal> {
        val patterns = excludePatterns(provider)
        if (patterns.isEmpty()) return deals

        val (excluded, kept) = deals.partition { deal -> patterns.any { it.containsMatchIn(deal.title) } }
        if (excluded.isNotEmpty()) {
            // Report rather than drop silently, so an over-broad pattern is visible.
            println("[${provider.id}] ${excluded.size} excluded by title patterns (e.g. ${excluded.take(2).map { it.title.take(48) }})")
        }
        return kept
    }

    /**
     * Drops deals the source published longer ago than `maxAgeDays`.
     *
     * Needed because Slickdeals' paginated search is not ordered strictly by date and
     * reaches back over 400 days — page 8 of a term query had a median age of 437 days.
     * Limiting page depth alone is not sufficient: page 7 came back *newer* than page 6,
     * so ordering cannot be relied on. The published date is the only real guard.
     *
     * Deals with no `postedAt` are kept: VTEX and MercadoLibre publish no posting date,
     * and discarding them would empty the Colombian sources entirely.
     */
    internal fun applyAgeLimit(provider: ProviderConfig, deals: List<RawDeal>): List<RawDeal> {
        val maxAgeDays = provider.int("maxAgeDays", 0).takeIf { it > 0 } ?: return deals
        val now = Instant.now()

        // Compared in whole elapsed days rather than raw instants. Comparing instants
        // makes a deal posted exactly maxAgeDays ago fall either side of the boundary
        // depending on microsecond timing, which is untestable and surprising. "14 days
        // old" now reliably survives a 14-day window; 15 days does not.
        val (tooOld, kept) = deals.partition { deal ->
            deal.postedAt?.let { ChronoUnit.DAYS.between(it, now) > maxAgeDays } == true
        }
        if (tooOld.isNotEmpty()) {
            val oldestDays = tooOld.mapNotNull { it.postedAt }
                .maxOfOrNull { ChronoUnit.DAYS.between(it, now) }
            println(
                "[${provider.id}] ${tooOld.size} dropped as older than ${maxAgeDays}d" +
                    (oldestDays?.let { " (oldest was ${it}d)" } ?: ""),
            )
        }
        return kept
    }

    /**
     * Title patterns that disqualify a deal, compiled from provider config.
     *
     * Used for both ingest filtering and the `--purge-excluded` cleanup, so the two can
     * never disagree about what counts as excluded.
     */
    fun excludePatterns(provider: ProviderConfig): List<Regex> =
        provider.list("excludeTitlePatterns").mapNotNull { pattern ->
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                .onFailure { println("[${provider.id}] bad excludeTitlePatterns regex '$pattern': ${it.message}") }
                .getOrNull()
        }

    internal fun ingest(provider: ProviderConfig, deals: List<RawDeal>): IngestReport {
        if (deals.isEmpty()) return IngestReport(provider.id)

        val coShippingDefault = runCatching { CoShipping.valueOf(provider.coShippingDefault.uppercase()) }
            .getOrDefault(CoShipping.UNKNOWN)
        val floor = app.minDiscount(provider)
        // Sources that are themselves curated deal feeds (Slickdeals frontpage) publish
        // no list price, so the discount floor can never fire. Without this, such a
        // source would be silently filtered down to nothing.
        val curated = provider.options["promoteAllNew"]?.toBoolean() ?: false

        val watchLinks = mutableMapOf<Long, Set<Long>>()
        var newCount = 0
        var promotedCount = 0
        var dropCount = 0
        val byCategory = mutableMapOf<String, Int>()

        repo.withTransaction { conn ->
            deals.forEach { raw ->
                val group = categorizer.categorize(raw.category, raw.title)
                byCategory[group] = (byCategory[group] ?: 0) + 1
                val upsert = repo.upsertDeal(conn, provider.id, raw, coShippingDefault, group)
                if (upsert.isNew) newCount++
                if (raw.watchIds.isNotEmpty()) watchLinks[upsert.dealId] = raw.watchIds

                // Read the historical minimum BEFORE recording this observation --
                // otherwise the new price is inside its own minimum and a genuine
                // drop can never be detected.
                val previousMin = repo.minPriceEver(conn, upsert.dealId)
                repo.recordObservation(conn, upsert.dealId, raw)

                val reason = promotionReason(raw, previousMin, floor, curated, upsert.isNew)
                if (reason == PromotionReason.PRICE_DROP) dropCount++
                if (reason != PromotionReason.NONE) {
                    repo.markPromoted(conn, upsert.dealId)
                    promotedCount++
                }
            }

            // Age-based, not "absent from this fetch": a single poll is not an
            // authoritative snapshot of the source (see deactivateStale).
            val staleAfterHours = provider.int("staleAfterHours", 48)
            val deactivated = repo.deactivateStale(conn, provider.id, staleAfterHours)
            if (deactivated > 0) {
                println("[${provider.id}] $deactivated deal(s) retired (unseen for ${staleAfterHours}h)")
            }
        }

        // Outside the transaction on purpose: a failed link costs a watch some candidates until the
        // next poll, and must not roll back the prices just recorded.
        runCatching { linkWatchDeals(watchLinks) }
            .onFailure { println("[${provider.id}] linking deals to watches failed: ${it.message}") }

        // Surfaced so an over-broad or missing rule shows up as a suspicious "Other" count
        // rather than passing unnoticed.
        val other = byCategory[Categorizer.OTHER] ?: 0
        if (byCategory.isNotEmpty()) {
            println(
                "[${provider.id}] categories: " +
                    byCategory.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key}=${it.value}" } +
                    (if (other > 0) "  (${other * 100 / deals.size}% uncategorised)" else ""),
            )
        }

        return IngestReport(provider.id, deals.size, newCount, promotedCount, dropCount)
    }

    /**
     * Decides whether a deal is worth surfacing.
     *
     * Necessary because a retailer's own discount flag is close to meaningless —
     * Exito marks roughly 94% of its catalog as discounted. Two independent signals:
     *
     *  - [PromotionReason.DISCOUNT_FLOOR]: advertised discount clears the configured
     *    threshold. Cheap, but inherits inflated list prices.
     *  - [PromotionReason.PRICE_DROP]: cheaper than anything we have recorded before.
     *    Immune to inflated list prices, but produces nothing until history exists —
     *    so it contributes little on day one and more as time goes on.
     */
    internal fun promotionReason(
        raw: RawDeal,
        previousMin: BigDecimal?,
        floor: BigDecimal,
        curated: Boolean,
        isNew: Boolean,
    ): PromotionReason {
        if (previousMin != null && raw.price < previousMin) return PromotionReason.PRICE_DROP

        val discount = raw.discountPercent
        if (discount != null && discount >= floor) return PromotionReason.DISCOUNT_FLOOR

        // Only applies on first sight; otherwise a curated source would re-promote
        // every unchanged item on every poll.
        if (curated && isNew) return PromotionReason.CURATED_SOURCE

        return PromotionReason.NONE
    }
}
