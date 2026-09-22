package com.luis.dealstracker.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Normalizes a monetary amount to two decimal places, matching the NUMERIC(14,2)
 * columns.
 *
 * Sources are inconsistent about scale: VTEX sends `7185900.0` for a COP price with
 * no cents, Slickdeals sends `3.67`. Without normalizing, the same price parsed from
 * two payloads compares unequal and price-history diffs report phantom changes.
 */
fun BigDecimal.asMoney(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

/**
 * COP has no cents in practice; USD does. Formatting reflects that.
 *
 * Lives here rather than in `web` because notifications format prices too, and having
 * `ingest` reach into the view layer for it would invert the dependency.
 */
fun formatMoney(amount: BigDecimal, currency: String): String = when (currency) {
    "COP" -> "$" + String.format("%,d", amount.toBigInteger())
    "USD" -> "US$" + String.format("%,.2f", amount)
    else -> "$currency " + String.format("%,.2f", amount)
}

enum class DealState { NEW, SEEN, DISMISSED, SAVED }

/** Whether the item can be shipped to Colombia. */
enum class CoShipping { UNKNOWN, YES, NO }

/**
 * The normalized shape every adapter produces.
 *
 * This is the seam that keeps the rest of the app provider-agnostic: ingest,
 * dedupe, promotion rules, price history and the UI never learn which site a
 * deal came from beyond its [ProviderConfig] id.
 */
data class RawDeal(
    /** Stable id within the provider. Combined with the provider id, this is the dedupe key. */
    val externalId: String,
    val title: String,
    val url: String,
    val price: BigDecimal,
    val currency: String,
    /** Retailer's claimed "before" price. Frequently inflated — treated as a claim, not a fact. */
    val listPrice: BigDecimal? = null,
    /** Retailer actually selling the item, e.g. "amazon". Basis of the Slickdeals merchant filter. */
    val merchant: String? = null,
    val asin: String? = null,
    /**
     * Manufacturer, where the source publishes one. Half of the fallback identity key when
     * no valid GTIN is available — see [com.luis.dealstracker.ingest.ProductIdentity].
     */
    val brand: String? = null,
    /**
     * Barcode as published, unvalidated. Kept raw on purpose: sources put internal
     * references in this field too (Exito sends both `7705191041773` and `14636`), and
     * storing what arrived makes a bad feed diagnosable rather than silently discarded.
     * Validation happens where the key is derived, not here.
     */
    val gtin: String? = null,
    /**
     * Manufacturer model code, where the source publishes it as a field of its own rather
     * than burying it in the title. Homecenter does; Exito and Falabella do not. Preferred
     * over reading the title when present, being the retailer's own structured value.
     */
    val model: String? = null,
    val category: String? = null,
    val imageUrl: String? = null,
    val available: Boolean = true,
    /**
     * The source's own community deal-quality score, where it publishes one.
     * Null (not 0) when the source has no equivalent -- VTEX and MercadoLibre don't.
     */
    val communityScore: Int? = null,
    /**
     * When the source published the deal, if it says. Distinct from our own discovery
     * time: Slickdeals' paginated search reaches back over a year, so a deal we see for
     * the first time today may have been posted in 2025.
     */
    val postedAt: Instant? = null,
) {
    /**
     * Advertised discount as a percentage, or null when there's no usable list price.
     *
     * Null rather than zero on purpose: "no list price published" and "list price
     * equals the sale price" are different facts, and collapsing them would let
     * the promotion rule treat missing data as a 0% discount.
     */
    val discountPercent: BigDecimal?
        get() {
            val list = listPrice ?: return null
            if (list <= BigDecimal.ZERO || price > list) return null
            return BigDecimal.ONE.subtract(price.divide(list, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal(100))
                .setScale(1, RoundingMode.HALF_UP)
        }
}

data class Deal(
    val id: Long,
    val source: String,
    val sourceDealId: String,
    val title: String,
    val url: String,
    val merchant: String?,
    val asin: String?,
    val category: String?,
    val imageUrl: String?,
    /**
     * Manufacturer as the source published it, or null where it publishes none.
     *
     * Stored as well as used for identity keys since V12. It was previously consumed by
     * `upsertDeal` to derive `model_key` and then discarded, so the fact was known at ingest and
     * unavailable afterwards — which left no honest way to filter by brand.
     *
     * Null is a real value: a PrestaShop grid publishes no brand, and Slickdeals none either.
     */
    val brand: String?,
    /** Barcode as published, unvalidated — see [RawDeal.gtin]. */
    val gtin: String?,
    /** Model code lifted from the title at ingest, e.g. `RMA247FXCG`. */
    val modelCode: String?,
    /**
     * Derived cross-site cluster key, or null when nothing reliable was available.
     * Two listings sharing this are treated as the same product; null never clusters.
     */
    val matchKey: String?,
    /**
     * Model-tier key, present even when [matchKey] came from a GTIN — the two tiers must be
     * able to join each other across retailers that publish different identifiers.
     */
    val modelKey: String?,
    val currency: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val isActive: Boolean,
    val state: DealState,
    val coShipping: CoShipping,
    val promoted: Boolean,
    val promotedAt: Instant?,
    val communityScore: Int?,
    val postedAt: Instant?,
    /** Normalized bucket derived by Categorizer, not published by the source. */
    val categoryGroup: String?,
    /** 'MANUAL' if Luis decided it, 'AUTO' if the verifier did, null if nobody has. */
    val coShippingSource: String?,
    /** The page text a verdict rests on, so it can be audited without re-fetching. */
    val coShippingEvidence: String?,
    /** Whether Amazon said import charges are already covered. Null = it said nothing. */
    val coShippingImportFree: Boolean?,
    /** International shipping fee quoted. ZERO = free, null = Amazon quoted nothing. */
    val coShippingCost: BigDecimal?,
    // Joined from price_observations for the feed view.
    val price: BigDecimal,
    val listPrice: BigDecimal?,
    val minPriceEver: BigDecimal?,
    val maxPriceEver: BigDecimal?,
    val observationCount: Int,
    /**
     * The feed's composed ranking score, computed in SQL by `DealScore` — never here.
     *
     * Defaulted so a [Deal] can still be built in a test without reproducing the formula.
     * A duplicate Kotlin implementation is exactly what `DealScore` exists to prevent: the
     * value shown to the reader must be the value that did the sorting.
     */
    val relevance: Double = 0.0,
) {
    val discountPercent: BigDecimal?
        get() {
            val list = listPrice ?: return null
            if (list <= BigDecimal.ZERO || price > list) return null
            return BigDecimal.ONE.subtract(price.divide(list, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal(100))
                .setScale(1, RoundingMode.HALF_UP)
        }

    /**
     * True when the price actually came down from something higher we recorded.
     *
     * Requires [maxPriceEver] to exceed the current price, not just
     * `price <= minPriceEver` — that comparison is trivially true for every deal whose
     * price has never moved, since the minimum includes the current observation. Using
     * it as the badge condition put "lowest yet" on every single row, which made the
     * badge meaningless.
     */
    val isAtLowestEver: Boolean
        get() {
            val min = minPriceEver ?: return false
            val max = maxPriceEver ?: return false
            return price <= min && max > price
        }

    /** How far the price has fallen from the highest value we recorded, if at all. */
    val dropFromPeak: BigDecimal?
        get() = maxPriceEver?.takeIf { it > price }?.subtract(price)

    /**
     * Quoted international shipping as a percentage of the item's own price.
     *
     * The number that decides whether a YES verdict is worth acting on, and the reason it is
     * surfaced rather than left implicit: of the 27 verified deals carrying a quoted fee, 23
     * had a fee above half the item's price and 10 above the whole price — $248.68 of freight
     * on a $267.99 television. Every one displayed as a plain green "ships".
     *
     * Null when Amazon quoted nothing (which is not the same as free) or the price is zero.
     * A derived ratio, not a re-implementation of the ranking weight — `DealScore` remains
     * the only place the score itself is computed.
     */
    val shippingCostPercent: BigDecimal?
        get() {
            val cost = coShippingCost ?: return null
            if (price <= BigDecimal.ZERO) return null
            return cost.divide(price, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
        }

    /**
     * True when the freight quote is a large enough share of the price to change the answer.
     *
     * 50% is where the observed distribution clusters hard rather than a round number picked
     * for looks: 23 of the 27 quoted fees sat above this line, and 10 above 100%. Deliberately
     * a display threshold only — the ranking penalty in `DealScore` is graduated, so a fee
     * just under the line is not silently treated as free.
     */
    val shippingCostDominates: Boolean
        get() = (shippingCostPercent ?: BigDecimal.ZERO) >= BigDecimal(50)
}

data class PriceObservation(
    val dealId: Long,
    val observedAt: Instant,
    val price: BigDecimal,
    val listPrice: BigDecimal?,
    val currency: String,
)
