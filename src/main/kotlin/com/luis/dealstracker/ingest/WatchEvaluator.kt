package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchAlert
import com.luis.dealstracker.model.WatchCandidate
import com.luis.dealstracker.model.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Decides which of a watch's candidates are worth announcing.
 *
 * Kept free of SQL and side effects so both rules can be tested against hand-built price
 * sets. The rules are where this feature is either useful or noise, and the failure mode is
 * subtle — a rule that is trivially true fires on every row and teaches you to ignore it,
 * which is exactly what happened to the feed's "lowest yet" badge before it was tightened
 * (see `Deal.isAtLowestEver`).
 */
object WatchEvaluator {

    private val HUNDRED = BigDecimal(100)

    fun evaluate(watch: Watch, candidates: List<WatchCandidate>): List<WatchAlert> =
        lowestInWindow(watch, candidates) + crossSiteSpread(watch, candidates)

    /**
     * Fires when a price beats the cheapest price previously recorded for anything this
     * watch matches, across every source.
     *
     * The floor is built only from [WatchCandidate.priorFloor] — prices recorded *before*
     * the observation being judged. Two consequences, both wanted:
     *
     *  - **Nothing fires on a watch's first run.** With one observation per listing there is
     *    no prior floor at all, so a fresh watch does not immediately announce whichever
     *    fridge happens to be the cheapest of forty. "The minimum of a list" is not news.
     *  - **A brand-new cheap listing does fire**, even with no history of its own, because
     *    the floor comes from the other candidates.
     *
     * A floor that included the price under test would be one that price could never beat,
     * which is the mistake `Deal.isAtLowestEver` originally made — `price <= minPriceEver`
     * is trivially true when the minimum contains the current price, so "lowest yet"
     * appeared on every row and meant nothing.
     */
    internal fun lowestInWindow(watch: Watch, candidates: List<WatchCandidate>): List<WatchAlert> {
        // The incumbent to beat: cheapest price this watch has on record from before now.
        val incumbent = candidates
            .filter { it.priorFloor != null }
            .minByOrNull { it.priorFloor!! }
            ?: return emptyList()
        val floor = incumbent.priorFloor!!

        return candidates.mapNotNull { candidate ->
            if (candidate.price >= floor) return@mapNotNull null
            val saving = floor.subtract(candidate.price)
            WatchAlert(
                dealId = candidate.dealId,
                reason = MatchReason.LOWEST_IN_WINDOW,
                price = candidate.price,
                evidence = "beat the ${formatMoney(floor, watch.currency)} " +
                    "${watch.lookbackDays}-day floor held by ${incumbent.source}, " +
                    "by ${formatMoney(saving, watch.currency)}",
            )
        }
    }

    /**
     * Fires when the same product — same [WatchCandidate.matchKey] — is materially cheaper
     * here than at another retailer.
     *
     * The only signal in this app that no single retailer can manufacture: a discount
     * percentage is a claim about the seller's own past price, whereas a competitor
     * charging more is independent evidence. Requires a different `source`, since two
     * listings of one product at one retailer are a duplicate, not a comparison.
     *
     * A null match key never clusters — see `V8__product_identity`. Loose title similarity
     * is deliberately not accepted here: a wrong "cheaper elsewhere" claim sends you
     * shopping for the wrong appliance, which is worse than saying nothing.
     */
    internal fun crossSiteSpread(watch: Watch, candidates: List<WatchCandidate>): List<WatchAlert> {
        // Index by every key each listing carries, so a GTIN-keyed listing and a
        // model-keyed one can find each other. Matching is direct rather than transitive:
        // two listings are compared when they share a key, and no chain of shared keys is
        // followed, which keeps one loose model match from merging unrelated clusters.
        val byKey = mutableMapOf<String, MutableList<WatchCandidate>>()
        candidates.forEach { candidate ->
            candidate.keys.forEach { key -> byKey.getOrPut(key) { mutableListOf() } += candidate }
        }

        return candidates.mapNotNull { candidate ->
            val rivals = candidate.keys
                .flatMap { byKey[it].orEmpty() }
                .distinctBy { it.dealId }
                // A second listing at the same retailer is a duplicate, not a comparison.
                .filter { it.source != candidate.source }
                // Two prices in different currencies are not comparable, and this rule does no
                // conversion. Comparing them numerically would report a US$189.99 item as
                // ~100% cheaper than a COP 700,000 one -- a spectacular false positive on the
                // one signal in this app that is meant to be independent evidence. Skipping is
                // the safe failure the rest of this file already argues for: a missing match
                // costs nothing, a wrong one sends you shopping for the wrong thing.
                .filter { it.currency == candidate.currency }
            val elsewhere = rivals.minByOrNull { it.price } ?: return@mapNotNull null

            if (elsewhere.price <= candidate.price) return@mapNotNull null
            val spread = elsewhere.price.subtract(candidate.price)
                .divide(elsewhere.price, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(1, RoundingMode.HALF_UP)
            if (spread < watch.crossSiteMinSpread) return@mapNotNull null

            val shared = candidate.keys.firstOrNull { it in elsewhere.keys }
            WatchAlert(
                dealId = candidate.dealId,
                reason = MatchReason.CROSS_SITE_SPREAD,
                price = candidate.price,
                evidence = "$spread% cheaper than ${elsewhere.source} " +
                    "at ${formatMoney(elsewhere.price, watch.currency)} (same $shared)",
            )
        }
    }
}
