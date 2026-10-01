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
    /**
     * What to search amazon.com for, pipe-separated like [terms]. Null means this watch does not
     * search Amazon.
     *
     * Separate from [terms] because those are written for Colombian retailers, in Spanish, and
     * amazon.com answers `televisor` poorly — and each Amazon search spends a scarce request
     * budget, so it should be phrased for that site ("55 inch 4k tv").
     */
    val amazonTerms: String? = null,
)

/**
 * One Amazon search a watch asked for, handed to the `amazon-watch` adapter.
 *
 * Carries the watch's id so the rows found can be linked back to it, and its category so they land
 * in the bucket the watch is pinned to rather than wherever an English title happens to match.
 */
data class WatchSearch(
    val watchId: Long,
    val term: String,
    val categoryGroup: String?,
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
    /**
     * The currency [price] is quoted in.
     *
     * Carried because the cross-site rule compares two listings' prices numerically, and
     * until now nothing stopped it comparing 189.99 USD against 700,000 COP and announcing a
     * ~100% saving. That was latent while every source but Slickdeals was COP and Slickdeals
     * rarely clustered with them; the Amazon adapter stores COP prices for rows that may share
     * an identity key with a Colombian retailer, which makes the collision realistic.
     *
     * The lowest-in-window rule needs it for a different reason, and the failure there is
     * silence rather than a false positive: that rule takes ONE minimum across all candidates,
     * so a watch whose terms span both languages ("soporte para TV | TV wall mount") would set
     * its floor at the USD figure — $22.99 — which no COP listing can ever be below. The watch
     * then never fires again while still reporting candidates and looking healthy.
     */
    val currency: String,
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
    /**
     * The price as the retailer quoted it, when [price] and [priorFloor] have been converted to the
     * watch's currency for comparison — see `WatchEvaluator.inWatchCurrency`. Null when no
     * conversion happened.
     *
     * Kept so every place that shows a converted price can also show the real one: an Amazon TV
     * reads `US$399.99 ≈ $1,305,756`, never a bare peso figure Amazon did not charge.
     */
    val quotedPrice: BigDecimal? = null,
    val quotedCurrency: String? = null,
    /**
     * Today's card-only price on this listing, where the retailer runs one — Falabella and
     * Homecenter's CMR, Exito and Carulla's Mastercard.
     *
     * **Display only, and that is the whole design.** Neither rule reads it: an alert has to rest on
     * the price anyone can pay, or the app would announce a saving the operator cannot get without
     * that particular card. It also could not be compared fairly even if it were wanted — a
     * Homecenter category listing publishes a CMR price on about 2% of its rows, so a row carrying
     * one would appear to undercut rivals that simply do not publish theirs. Same call
     * `/groceries` already makes: "the deal rule ignores the card price" (`V17__card_price`).
     */
    val cardOffer: CardOffer? = null,
    /**
     * The cheapest card price recorded for this listing inside the window, excluding the current
     * observation — [priorFloor]'s counterpart.
     *
     * Null both when the listing has no history and when it has never carried a card price, which
     * are different facts but the same absence of evidence; the column renders `—` for both rather
     * than inventing a distinction the data does not support.
     */
    val cardFloor: BigDecimal? = null,
) {
    /** `US$399.99 ≈ $1,305,756` for a converted row, the plain price otherwise. */
    fun priceLabel(): String =
        if (quotedPrice != null && quotedCurrency != null) {
            "${formatMoney(quotedPrice, quotedCurrency)} ≈ ${formatMoney(price, currency)}"
        } else {
            formatMoney(price, currency)
        }
}

/** A fired rule, ready to be written to the ledger and possibly announced. */
data class WatchAlert(
    val dealId: Long,
    val reason: MatchReason,
    val price: BigDecimal,
    /** The sentence the alert rests on, stored so it can be audited without recomputing. */
    val evidence: String,
    /**
     * [price] in the watch's currency, which is what the rules compared, so alerts on listings in
     * two currencies can still be ranked against each other. Null when no conversion happened.
     *
     * [price] itself stays in the deal's own currency, because that is what the ledger compares a
     * later price against — converting it would re-announce an unchanged price every time the
     * exchange rate moved.
     */
    val comparablePrice: BigDecimal? = null,
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
