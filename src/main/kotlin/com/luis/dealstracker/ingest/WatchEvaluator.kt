package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchAlert
import com.luis.dealstracker.model.WatchCandidate
import com.luis.dealstracker.model.FxRate
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

    /** For callers judging one currency with nothing converted, where the rate is never read. */
    private val NO_FX = FxRate(BigDecimal.ONE, "none")

    /**
     * Brings every candidate to the watch's currency, then evaluates both rules **per currency**.
     *
     * The conversion is what lets an Amazon TV in USD be judged against Colombian retailers in
     * COP — the reason a watch fetches Amazon at all. Once converted they share a currency, so
     * they share a floor and can undercut one another.
     *
     * The per-currency grouping stays for whatever the rate cannot cover (a currency that is
     * neither COP nor USD). Those keep being judged only against their own kind, because 22.99 is
     * a smaller *number* than 15,000 but not a cheaper mount — the failure this grouping was
     * written for, when a USD floor of $22.99 silenced a COP watch permanently.
     */
    fun evaluate(watch: Watch, candidates: List<WatchCandidate>, fx: FxRate): List<WatchAlert> =
        comparable(watch, candidates, fx).groupBy { it.currency }.flatMap { (_, sameCurrency) ->
            (lowestInWindow(watch, sameCurrency, fx) + crossSiteSpread(watch, sameCurrency, fx))
                .map { it.inOwnCurrency(sameCurrency) }
        }

    /**
     * Every candidate with its price and prior floor in the watch's currency, where the rate
     * allows. Shared with the watch page, so the table and the rules cannot rank rows differently.
     */
    fun comparable(watch: Watch, candidates: List<WatchCandidate>, fx: FxRate): List<WatchCandidate> =
        candidates.map { inWatchCurrency(watch, it, fx) ?: it }

    /** A candidate expressed in the watch's currency, or null when the rate does not cover it. */
    internal fun inWatchCurrency(watch: Watch, c: WatchCandidate, fx: FxRate): WatchCandidate? {
        if (c.currency == watch.currency) return c
        val price = fx.convert(c.price, c.currency, watch.currency) ?: return null
        return c.copy(
            price = price,
            currency = watch.currency,
            priorFloor = c.priorFloor?.let { fx.convert(it, c.currency, watch.currency) },
            quotedPrice = c.price,
            quotedCurrency = c.currency,
        )
    }

    /**
     * Puts an alert's price back in the deal's own currency, keeping the converted one alongside.
     *
     * The ledger re-announces only on a lower price than the one it last announced. Storing the
     * converted figure would make that comparison move with the exchange rate, so a TV whose
     * dollar price never changed would be announced again on the first day the peso weakened.
     */
    private fun WatchAlert.inOwnCurrency(from: List<WatchCandidate>): WatchAlert {
        val quoted = from.firstOrNull { it.dealId == dealId }?.quotedPrice ?: return this
        return copy(price = quoted, comparablePrice = price)
    }

    /**
     * " — US$399.99 at 3,264.39 COP/USD (TRM 2026-09-23)" when a converted price took part.
     *
     * An alert resting on a conversion says so, and at what rate, because the claim is only as
     * good as the rate: a rival 4% dearer at today's TRM may not be dearer next week.
     */
    private fun conversionNote(fx: FxRate, vararg involved: WatchCandidate): String {
        val converted = involved.filter { it.quotedPrice != null }
        if (converted.isEmpty()) return ""
        val quoted = converted.joinToString(", ") {
            "${it.source} ${formatMoney(it.quotedPrice!!, it.quotedCurrency!!)}"
        }
        return " — converted: $quoted at ${fx.describe()}"
    }

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
    internal fun lowestInWindow(
        watch: Watch,
        candidates: List<WatchCandidate>,
        fx: FxRate = NO_FX,
    ): List<WatchAlert> {
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
                // The candidate's own currency, not the watch's. Every candidate in this call
                // shares one currency (see `evaluate`), but that need not be the watch's
                // nominal one -- a COP watch legitimately matches USD listings -- and the
                // evidence is the audit trail, so labelling $22.99 as "$22,99 COP" would make
                // a correct alert unreadable.
                evidence = "beat the ${formatMoney(floor, candidate.currency)} " +
                    "${watch.lookbackDays}-day floor held by ${incumbent.source}, " +
                    "by ${formatMoney(saving, candidate.currency)}" +
                    conversionNote(fx, candidate, incumbent),
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
    internal fun crossSiteSpread(
        watch: Watch,
        candidates: List<WatchCandidate>,
        fx: FxRate = NO_FX,
    ): List<WatchAlert> {
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
                // Two prices in different currencies are not comparable. `evaluate` has already
                // converted everything the rate covers, so this only ever removes a currency it
                // could not -- but it stays, because comparing raw numbers would report a
                // US$189.99 item as ~100% cheaper than a COP 700,000 one, a spectacular false
                // positive on the one signal in this app meant to be independent evidence.
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
                    "at ${formatMoney(elsewhere.price, candidate.currency)} (same $shared)" +
                    conversionNote(fx, candidate, elsewhere),
            )
        }
    }
}
