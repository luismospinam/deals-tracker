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

/**
 * Where a deal stands with Luis.
 *
 * Keeping a row is not a state: it is `pinned_at`, the same on every tab. FAVORITE (SAVED before V14)
 * was the feed's own copy of that gesture until V33 folded it into the pin.
 */
enum class DealState { NEW, SEEN, DISMISSED }

/**
 * Who can pay a card-only price. See `V17__card_price` and `V20__cmr_card_scope`.
 *
 * [MASTERCARD] includes the store's own card, which is a Mastercard; [STORE_CARD] is that card
 * alone. [CMR] is the Falabella group's card, at Falabella and Homecenter — a different programme
 * at a different retailer, so it is not [STORE_CARD]: a chip reading "Store card only" on a
 * Homecenter row would name Tarjeta Éxito y Carulla, which cannot pay it.
 */
enum class CardScope(val label: String) {
    MASTERCARD("Mastercard"),
    STORE_CARD("Store card"),
    CMR("CMR"),
    /** Tarjeta Más Cencosud, at Easy. Also covers a teaser good on both TMC and Codensa. */
    TMC("TMC"),
    CODENSA("Codensa"),
    /** Any Visa, as Exito's `visa_aliado_*` price tables name it (payment ids 2 and 209). See V35. */
    VISA("Visa"),
    /** Addi, the buy-now-pay-later service, at Exito and Easy (payment id 102). Not a card, but paid the same way. */
    ADDI("Addi"),
    OTHER("Card");
}

/** The best offer among [offers] that one of [held] pays, or null when none does. */
fun bestHeldOffer(offers: List<CardOffer>, held: Set<CardScope>): CardOffer? =
    offers.filter { it.scope in held }.minByOrNull { it.price }

/**
 * The card offer that counts for the operator: the best one a held card pays, or -- with no cards
 * picked at all -- the best overall, which is what every screen showed before "My cards" existed.
 * Null when they hold cards but none of [offers]' -- a price they cannot pay is not theirs to sort by.
 */
fun usableOffer(offers: List<CardOffer>, held: Set<CardScope>): CardOffer? =
    if (held.isEmpty()) offers.minByOrNull { it.price } else bestHeldOffer(offers, held)

/**
 * The cards the operator holds, as the scopes they pay (`SettingsRepository.heldCards`): one global
 * value, because every tab shows card prices and they must all agree on whose. Loaded at boot and set
 * by the "My cards" page; a single-user app, so process-wide state is the honest model of it.
 */
object HeldCards {
    @Volatile
    var current: Set<CardScope> = emptySet()
}

/**
 * The price with a particular card, as the store's page shows it ("$3.280 Mastercard / $4.100 Otros").
 *
 * [percent] is taken off the price before any shelf promotion, not the shelf price -- the two do not
 * stack. [price] is kept only when it beats the shelf price.
 */
data class CardOffer(val price: BigDecimal, val percent: BigDecimal, val scope: CardScope)

/** What a promotion does. See `V18__promotions`. */
enum class PromotionKind { SHELF, CARD, MULTIBUY, OTHER }

/**
 * One promotion attached to an offer, e.g. `cmp_20_prov_mcdo_fruver_franja_ID19665_1ene_31dic`.
 *
 * [name] is the store's own identifier and the only field taken as given; the rest is decoded from
 * it or from the teaser's structure, and is null wherever the name does not say.
 */
data class PromotionTag(
    val name: String,
    val kind: PromotionKind,
    val percent: BigDecimal? = null,
    val cardIds: String? = null,
    val payQty: Int? = null,
    val takeQty: Int? = null,
    val validFrom: java.time.LocalDate? = null,
    val validTo: java.time.LocalDate? = null,
)

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
    /**
     * Watches whose own Amazon search returned this row, so it becomes their candidate even though
     * an English title matches none of their Spanish terms. Empty for every other source. See
     * `V15__watch_amazon_terms`.
     */
    val watchIds: Set<Long> = emptySet(),
    /**
     * Pack size in a base unit -- `g`, `ml` or `un` -- for unit pricing. Only the grocery adapter
     * sets these; see `V16__groceries` and [com.luis.dealstracker.ingest.UnitSize].
     */
    val unitMeasure: String? = null,
    val unitQuantity: BigDecimal? = null,
    /** The grocery tab's subcategory label, e.g. "Meat & fish". Null outside the grocery providers. */
    val groceryGroup: String? = null,
    /** A card-only price, where the store runs one on this item today. See [CardOffer]. */
    val cardOffer: CardOffer? = null,
    /**
     * Every card offer on this reading, the best per card (V35). [cardOffer] is the cheapest of them,
     * kept as the history's single card price; this list is what lets each operator see the price
     * their own card pays when it is not the cheapest. Empty means "only [cardOffer]".
     */
    val cardOffers: List<CardOffer> = emptyList(),
    /**
     * Every promotion the store attached to this offer, by its own name. Recorded for analysis, not
     * used for pricing -- see `V18__promotions`.
     */
    val promotions: List<PromotionTag> = emptyList(),
    /**
     * Units the store reports in stock for this offer. VTEX caps it at 10,000, which is what almost
     * every grocery row reads -- so only a value under the cap says anything. Null where the source
     * publishes no count.
     */
    val stockQty: Int? = null,
    /**
     * The retailer's specification table, label to value ("Lado Chaise" -> "Izquierdo"), exactly as
     * published. Empty where the source gives none. Stored in `deal_specs` for the Ask panel; no rule
     * or price depends on it.
     */
    val specs: Map<String, String> = emptyMap(),
    /** The retailer's description as plain text, where it publishes one. Often holds the dimensions. */
    val description: String? = null,
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
    /**
     * When the operator pinned this row, or null. A timestamp column on `deals` that `upsertDeal`
     * never writes, so a pin survives every poll. Groceries pioneered it; the tracked tab reuses it.
     */
    val pinnedAt: Instant? = null,
    /** When the Colombia shipping verdict was last read from the page; null if never. */
    val coShippingCheckedAt: Instant? = null,
    /**
     * The latest reading's card-only price, kept on the deal by V34's trigger. Display only: no deal
     * rule reads it, since holding that particular card is not a given (see `V17__card_price`).
     */
    val card: CardOffer? = null,
    /** The lowest card-only price ever recorded for this deal, or null if it never carried one. */
    val minCardPriceEver: BigDecimal? = null,
    /** Every card offer on the latest reading, best per card (V35). Falls back to [card] when empty. */
    val cardOffers: List<CardOffer> = emptyList(),
    /** The lowest price recorded per card (V35), for "lowest with your card". */
    val cardLows: Map<CardScope, BigDecimal> = emptyMap(),
) {
    /** [cardOffers], or [card] alone for a row built before V35 or in a test. */
    val allCardOffers: List<CardOffer> get() = cardOffers.ifEmpty { listOfNotNull(card) }

    val discountPercent: BigDecimal?
        get() {
            val list = listPrice ?: return null
            if (list <= BigDecimal.ZERO || price > list) return null
            return BigDecimal.ONE.subtract(price.divide(list, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal(100))
                .setScale(1, RoundingMode.HALF_UP)
        }

    /**
     * The discount as the store's own badge shows it: a whole number, rounded DOWN.
     *
     * Exito and Carulla truncate rather than round. Measured against their rendered pages:
     * 11,215 against 14,160 is 20.8% and the page reads `-20%`; 5,990 against 9,350 is 35.9% and
     * it reads `-35%`. Rounding would show -21% and -36%, a number the operator would then fail to
     * find on the store's own page.
     */
    val storeDiscountPercent: Int?
        get() = discountPercent?.setScale(0, RoundingMode.DOWN)?.toInt()?.takeIf { it > 0 }

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

    /**
     * Price plus the quoted freight: what the item costs delivered to Colombia. A US$7.41 shirt with
     * US$15.05 freight is a US$22.46 shirt, and the order of a list by sticker price says otherwise.
     *
     * Null when no freight was quoted -- unchecked is not free -- and for an item that does not ship.
     * Import charges are not added, for the reason the Tracked tab's Extra column gives: Amazon flags
     * them without always quoting an amount.
     */
    val deliveredPrice: BigDecimal?
        get() = coShippingCost?.takeIf { coShipping != CoShipping.NO }?.let { price.add(it) }
}

data class PriceObservation(
    val dealId: Long,
    val observedAt: Instant,
    val price: BigDecimal,
    val listPrice: BigDecimal?,
    val currency: String,
    /** The card-only price this reading carried, if any. See `V17__card_price`. */
    val card: CardOffer? = null,
)
