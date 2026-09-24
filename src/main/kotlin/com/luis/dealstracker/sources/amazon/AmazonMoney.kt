package com.luis.dealstracker.sources.amazon

import com.luis.dealstracker.model.asMoney
import java.math.BigDecimal

/**
 * Amazon's money format — the **fourth** convention in this codebase, and the first that
 * uses both separators at once.
 *
 * The three that came before, and why none of them can be reused here:
 *
 * | source | example | dot means | comma means |
 * |---|---|---|---|
 * | Shopify | `"1645900.00"` | decimal point | absent |
 * | PrestaShop, Falabella | `"1.829.900"` | thousands | decimal |
 * | Slickdeals | `"$1,299.99"` | decimal point | thousands |
 * | **Amazon (here)** | `"COP 234,124.82"` | decimal point | thousands |
 *
 * Amazon looks like Slickdeals but is not interchangeable with it: it prefixes an explicit
 * currency word, sometimes omits the space (`COP117,125`), and sometimes omits the decimals
 * altogether on COP. Feeding one of these strings to `PrestashopSource.parseCopAmount` —
 * which strips dots as thousands separators — turns 234,124.82 into 23,412,482. That is the
 * factor-of-100 class of error this project already pins with tests in both directions, so
 * this parser gets the same treatment.
 *
 * ## Why the currency is returned rather than assumed
 *
 * Measured on 2026-09-17: amazon.com served **COP** prices to this machine, not USD —
 * `COP 234,124.82` on a search page, `COP117,125` on a product page. The existing Amazon
 * rows in this database come from Slickdeals and are USD. So neither "Amazon is USD" nor
 * "Amazon is COP" is a safe constant, and a provider-level `currency:` setting would be a
 * guess applied to every row.
 *
 * Reading the unit off the price string itself is the only version that cannot silently lie
 * about what a number means, which matters because `deals.currency` is per row and the
 * export formats each cell from it (`DealsExport.money`). The cost is that one source can
 * carry mixed currencies; see `WatchEvaluator.evaluate`, which converts before it compares.
 */
internal object AmazonMoney {

    /**
     * Currency words and symbols Amazon puts in front of an amount, longest first so `COP`
     * is tried before a bare `$` — `COP $1.234` would otherwise report USD.
     */
    private val CURRENCY_MARKERS = listOf(
        "COP" to "COP",
        "US$" to "USD",
        "USD" to "USD",
        "$" to "USD",
    )

    /**
     * The numeric core: digit groups separated by commas, with an optional decimal tail.
     *
     * Anchored on a leading digit so a stray `-` or a page's `Save $20` copy cannot be read
     * as a price. The decimal tail is limited to two places, matching `NUMERIC(14,2)`.
     */
    private val AMOUNT_RE = Regex("""\d[\d,]*(?:\.\d{1,2})?""")

    /**
     * Parses a displayed Amazon price into an amount and the currency it was quoted in.
     *
     * Returns null — never a zero or a default currency — when the text carries no usable
     * amount. A blank price on a search tile is a real and common case (one of the 48 tiles
     * captured in the fixture has none), and the caller drops that row rather than inventing
     * a price for it.
     *
     * Examples, all real strings observed on 2026-09-17:
     * ```
     * "COP 234,124.82" -> 234124.82 COP
     * "COP117,125"     -> 117125.00 COP
     * "$1,299.99"      -> 1299.99 USD
     * "US$18.99"       -> 18.99 USD
     * ```
     */
    fun parse(text: String?): Pair<BigDecimal, String>? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Currency first, because detecting it is what tells us the digits are a price at
        // all. An amount with no currency marker is rejected rather than assumed: on these
        // pages a bare number is far more likely to be a review count or a size than money.
        val currency = CURRENCY_MARKERS.firstOrNull { (marker, _) ->
            trimmed.contains(marker, ignoreCase = true)
        }?.second ?: return null

        val digits = AMOUNT_RE.find(trimmed)?.value ?: return null

        // Commas are always thousands separators here and the dot is always a decimal point,
        // so stripping only commas is correct for both currencies. Never via Double, matching
        // the NUMERIC(14,2) columns.
        val amount = runCatching { BigDecimal(digits.replace(",", "")) }.getOrNull() ?: return null
        if (amount.signum() <= 0) return null

        return amount.asMoney() to currency
    }

    /**
     * Whether a USD amount is believable for tracked clothing and footwear.
     *
     * A backstop, not a substitute for reading the right element. The real fix for the `$9,500`
     * Nike Revolution 8 was the buy-box selector; this catches whatever the next markup change
     * sends through, because the failure mode is uniquely nasty: a wrong price is stored, becomes
     * the recorded peak, and then every later observation reads as an enormous drop. It poisons
     * the price history the tracked tab exists to show, and it looks entirely normal doing it.
     *
     * The band is wide on purpose — this rejects absurdity, not bargains. Real observed prices for
     * these searches run roughly $29-$300; a $9,500 sneaker and a $2 one are both markup bugs
     * rather than deals. Only applied to USD, since the adapter pins that and a COP amount would
     * legitimately be in the hundreds of thousands.
     */
    fun isPlausibleApparelPrice(amount: BigDecimal, currency: String): Boolean {
        if (currency != "USD") return true
        return amount >= MIN_USD && amount <= MAX_USD
    }

    private val MIN_USD = BigDecimal("3")
    private val MAX_USD = BigDecimal("2000")
}
