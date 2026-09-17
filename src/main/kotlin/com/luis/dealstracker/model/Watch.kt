package com.luis.dealstracker.model

import java.math.BigDecimal
import java.time.Instant

/** Why a watch fired. Stored as TEXT with a CHECK constraint, like [DealState]. */
enum class MatchReason {
    /** Cheaper than every other matching listing seen inside the watch's window. */
    LOWEST_IN_WINDOW,

    /** Materially cheaper here than the same product at another retailer. */
    CROSS_SITE_SPREAD,
}

/**
 * A standing intent to buy something — "a fridge, under 3.5M, keep looking".
 *
 * Two jobs, and the second is the one that makes this more than a saved search: a watch
 * *steers fetching* on sources that support keyword search, so the thing gets found even
 * though no configured category would have surfaced it, and it *scores what arrives* on
 * every source regardless.
 */
data class Watch(
    val id: Long,
    val name: String,
    /** Pipe-separated, same syntax as the feed search box, parsed by `parseSearchTerms`. */
    val terms: String,
    /**
     * Terms that disqualify a match. Load-bearing rather than polish: searching "nevera"
     * on Exito returns "Organizador De Nevera", a ~20k COP plastic box, ranked above real
     * fridges. Without this a lowest-price rule fires on accessories forever.
     */
    val excludeTerms: String?,
    val categoryGroup: String?,
    /** Empty means every source. */
    val sources: List<String>,
    /** The junk floor — see [excludeTerms]. Accessories are cheap; the real item is not. */
    val minPrice: BigDecimal?,
    /** The actual budget. */
    val maxPrice: BigDecimal?,
    val currency: String,
    /** The N in "cheapest seen in N days". */
    val lookbackDays: Int,
    /** Percent cheaper than another retailer before that counts as news. */
    val crossSiteMinSpread: BigDecimal,
    val enabled: Boolean,
    val createdAt: Instant,
)

/**
 * One deal that satisfies a watch's filters, with the history needed to score it.
 *
 * [priorFloor] is the cheapest price recorded for this deal inside the window **excluding
 * its current observation**, and null when there is no earlier observation. That exclusion
 * is what makes the lowest-price rule mean something: a floor that contains the price being
 * judged is one the price can never beat, so the rule would either never fire or fire
 * trivially. It is the same reason `DealRepository.minPriceEver` must be read before the
 * new observation is inserted.
 */
data class WatchCandidate(
    val dealId: Long,
    val source: String,
    val title: String,
    /** The retailer's own page. Carried here so listing candidates costs no extra queries. */
    val url: String,
    val price: BigDecimal,
    val priorFloor: BigDecimal?,
    /**
     * Every identity key this listing carries — a GTIN key, a brand+model key, or both.
     *
     * A list rather than one value because Colombian retailers publish different
     * identifiers: Éxito sends a GS1 EAN where Homecenter sends none. Two listings are the
     * same product if they share **any** key, which is what lets those two be compared at
     * all. Empty means no reliable identity, and must never match anything.
     */
    val keys: List<String> = emptyList(),
)

/** A fired rule, ready to be written to the ledger and possibly announced. */
data class WatchAlert(
    val dealId: Long,
    val reason: MatchReason,
    val price: BigDecimal,
    /** The sentence the alert rests on, stored so it can be audited without recomputing. */
    val evidence: String,
)

/** A ledger row, used to decide whether an alert is actually news. */
data class WatchMatch(
    val id: Long,
    val watchId: Long,
    val dealId: Long,
    val reason: MatchReason,
    val matchedAt: Instant,
    val priceAtMatch: BigDecimal,
    val notifiedAt: Instant?,
    val notifiedPrice: BigDecimal?,
    val evidence: String?,
)
