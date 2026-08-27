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
    // Joined from price_observations for the feed view.
    val price: BigDecimal,
    val listPrice: BigDecimal?,
    val minPriceEver: BigDecimal?,
    val maxPriceEver: BigDecimal?,
    val observationCount: Int,
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
}

data class PriceObservation(
    val dealId: Long,
    val observedAt: Instant,
    val price: BigDecimal,
    val listPrice: BigDecimal?,
    val currency: String,
)
