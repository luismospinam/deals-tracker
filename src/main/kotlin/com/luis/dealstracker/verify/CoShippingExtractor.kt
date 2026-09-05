package com.luis.dealstracker.verify

import com.luis.dealstracker.model.CoShipping

/**
 * The text of the page regions that carry shipping eligibility. Separating this from the
 * browser is what makes the decision logic testable offline against captured fixtures,
 * the same way every other parser in this project is tested.
 *
 * Both `availability` and `deliveryBlock` are populated by **AJAX** and are empty on load,
 * so whoever fills this in must poll for them. Reading once yields "" and looks
 * indistinguishable from "the page said nothing".
 */
data class PageSignals(
    /** `#availability` — carries the refusal message when an item cannot be shipped. */
    val availability: String = "",
    /** `#deliveryBlockMessage` — carries "FREE delivery … to <Country>" when it can. */
    val deliveryBlock: String = "",
    /** `#amazonGlobal_feature_div` — import charges and "FREE Shipping to <Country>". */
    val amazonGlobal: String = "",
    /**
     * `#buybox` — how Amazon offers (or declines to offer) the purchase. When nothing can
     * be delivered to the address it replaces the buy box with "See Similar Items" /
     * "See All Buying Options" and says nothing explicit about shipping.
     */
    val buybox: String = "",
    /**
     * The deal title, already stored, used only to spot digital items. Kindle books and
     * gift codes carry "(eBook)" / "Digital Code" here far more reliably than anywhere in
     * the shipping widgets — which for a digital item never render at all.
     */
    val title: String = "",
)

/** A verdict plus the evidence behind it, so a stored result can always be audited. */
data class CoVerdict(
    val value: CoShipping,
    /** True when the page gave no usable signal, as opposed to a definite YES/NO. */
    val unclear: Boolean,
    /** The exact page text the decision rests on. Stored alongside the verdict. */
    val evidence: String,
    /** Whether Amazon said import charges are already covered, when it said anything. */
    val importChargesIncluded: Boolean? = null,
    /**
     * International shipping cost quoted for the destination. `ZERO` means Amazon said
     * FREE; null means it quoted nothing.
     *
     * Captured because eligibility alone misleads: "No Import Charges & FREE Shipping" and
     * "No Import Charges & $60.66 Shipping" are the same verdict but very different deals.
     */
    val shippingCost: java.math.BigDecimal? = null,
)

/**
 * Decides Colombia shipping eligibility from page text.
 *
 * Verified against four ASINs whose verdicts a human confirmed in their own browser
 * (see `fixtures/amazon/co-shipping-signals.json`). The rules follow that evidence:
 *
 *  - **NO** — `#availability` carries Amazon's explicit refusal:
 *    *"This item cannot be shipped to your selected delivery location."*
 *    Note a refused item leaves `#deliveryBlockMessage` **empty**, so the delivery block
 *    alone can never distinguish "refused" from "not loaded yet".
 *  - **YES** — the delivery block or the global-shipping widget names the destination
 *    country: *"FREE delivery … to Colombia"* / *"FREE Shipping to Colombia"*.
 *  - **UNCLEAR** otherwise.
 *
 * Deliberately conservative: a YES requires the country to be named, not merely the
 * absence of a refusal. An absent signal is far more likely to mean "page not fully
 * loaded" than "ships fine", and recording a wrong YES would send someone to buy
 * something that cannot arrive.
 */
class CoShippingExtractor(private val country: String = "Colombia") {

    fun extract(signals: PageSignals): CoVerdict {
        val refusal = REFUSAL_PHRASES.firstOrNull { signals.availability.contains(it, ignoreCase = true) }
        if (refusal != null) {
            return CoVerdict(
                value = CoShipping.NO,
                unclear = false,
                evidence = signals.availability.take(EVIDENCE_LIMIT),
            )
        }

        // Digital items have nothing to ship: a Kindle book reads "Available instantly" and
        // renders no delivery block, so the shipping question is a category error. Left as
        // UNCLEAR they would be re-checked forever against a question with no answer.
        //
        // Recorded as YES because the practical question — can this be bought from
        // Colombia — is yes for digital delivery. The caveat is stated in the evidence
        // rather than hidden: Kindle titles CAN be region-restricted, so a YES here is an
        // inference about delivery mechanics, not a confirmation Amazon gave us.
        if (isDigital(signals)) {
            return CoVerdict(
                value = CoShipping.YES,
                unclear = false,
                evidence = "digital item (no shipping required); assumed purchasable from " +
                    "$country — Amazon did not confirm regional availability",
            )
        }

        val toCountry = "to $country"
        val positive = when {
            signals.deliveryBlock.contains(toCountry, ignoreCase = true) -> signals.deliveryBlock
            signals.amazonGlobal.contains(toCountry, ignoreCase = true) -> signals.amazonGlobal
            else -> null
        }
        if (positive != null) {
            return CoVerdict(
                value = CoShipping.YES,
                unclear = false,
                evidence = positive.take(EVIDENCE_LIMIT),
                importChargesIncluded = importCharges(signals.amazonGlobal),
                shippingCost = shippingCost(signals.amazonGlobal),
            )
        }

        // No buy box for this destination. Amazon states no refusal here — it simply
        // withdraws the purchase option and offers alternatives instead, leaving both the
        // delivery block and availability empty.
        //
        // Treated as NO on the strength of a manual check: all 7 items showing this pattern
        // were confirmed unshippable to Colombia in a real browser. Noted as inferred, since
        // unlike the NO above it rests on an absence rather than on Amazon's own words.
        if (noOfferForDestination(signals)) {
            return CoVerdict(
                value = CoShipping.NO,
                unclear = false,
                evidence = "no buy box for this destination (Amazon offered only " +
                    "\"See Similar Items\" / \"See All Buying Options\") — inferred, not stated",
            )
        }

        // No refusal and no named destination. Could be a partially rendered page, a bot
        // wall, or wording we have not seen. Never guessed at.
        return CoVerdict(
            value = CoShipping.UNKNOWN,
            unclear = true,
            evidence = listOf(signals.availability, signals.deliveryBlock, signals.amazonGlobal)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .take(EVIDENCE_LIMIT)
                .ifEmpty { "no eligibility signal on page" },
        )
    }

    /**
     * True when Amazon offers no purchasable option for the destination.
     *
     * Requires ALL of: no delivery estimate anywhere, nothing in availability, and a buybox
     * offering only alternatives. Demanding all three keeps a merely slow-loading page from
     * being mistaken for an unavailable one.
     */
    internal fun noOfferForDestination(signals: PageSignals): Boolean =
        signals.deliveryBlock.isBlank() &&
            signals.availability.isBlank() &&
            NO_OFFER_RE.containsMatchIn(signals.buybox)

    /** Digital goods: nothing is shipped, so no shipping widget ever renders. */
    internal fun isDigital(signals: PageSignals): Boolean =
        DIGITAL_TITLE_RE.containsMatchIn(signals.title) ||
            DIGITAL_AVAILABILITY_RE.containsMatchIn(signals.availability)

    private fun importCharges(amazonGlobal: String): Boolean? = when {
        amazonGlobal.contains("No Import Charges", ignoreCase = true) -> true
        amazonGlobal.contains("Import Fees Deposit", ignoreCase = true) -> false
        amazonGlobal.contains("Import Charges", ignoreCase = true) -> false
        else -> null
    }

    /**
     * Reads the quoted shipping fee from the global-shipping widget, which phrases it as
     * either "… & FREE Shipping to <Country>" or "… & $60.66 Shipping to <Country>".
     *
     * Returns ZERO for FREE so "free" is a recorded fact rather than an absent value —
     * null has to keep meaning "Amazon quoted nothing", which is a different thing.
     */
    internal fun shippingCost(amazonGlobal: String): java.math.BigDecimal? {
        if (amazonGlobal.isBlank()) return null
        FREE_SHIPPING_RE.find(amazonGlobal)?.let { return java.math.BigDecimal.ZERO.setScale(2) }
        PAID_SHIPPING_RE.find(amazonGlobal)?.let { m ->
            return runCatching {
                java.math.BigDecimal(m.groupValues[1].replace(",", "")).setScale(2)
            }.getOrNull()
        }
        return null
    }

    companion object {
        /** What Amazon shows in place of a buy box when nothing can be delivered. */
        private val NO_OFFER_RE = Regex(
            """See (Similar Items|All Buying Options)""",
            RegexOption.IGNORE_CASE,
        )

        /** "(eBook)", "Kindle Edition", "$10 Nintendo eShop Gift Card (Digital Code)". */
        private val DIGITAL_TITLE_RE = Regex(
            """\b(e-?books?|kindle|audible|audiobooks?|digital codes?|digital downloads?|gift cards?|eshop)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Amazon's wording for a digital purchase in the availability slot. */
        private val DIGITAL_AVAILABILITY_RE = Regex(
            """Available (instantly|now)""",
            RegexOption.IGNORE_CASE,
        )

        /** "& FREE Shipping to Colombia" */
        private val FREE_SHIPPING_RE = Regex("""FREE\s+Shipping""", RegexOption.IGNORE_CASE)

        /** "& $60.66 Shipping to Colombia" */
        private val PAID_SHIPPING_RE =
            Regex("""\$\s*([0-9][0-9,]*\.?[0-9]{0,2})\s+Shipping""", RegexOption.IGNORE_CASE)

        /** Amazon's refusal wording. Matched against `#availability` only. */
        private val REFUSAL_PHRASES = listOf(
            "cannot be shipped to your selected delivery location",
            "does not ship to",
            "not available for shipping to",
            "cannot be delivered to the selected address",
        )

        /** Enough to audit a verdict without bloating the row. */
        const val EVIDENCE_LIMIT = 300
    }
}
