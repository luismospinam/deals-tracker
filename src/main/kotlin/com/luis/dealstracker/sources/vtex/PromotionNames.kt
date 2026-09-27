package com.luis.dealstracker.sources.vtex

import com.luis.dealstracker.model.PromotionKind
import com.luis.dealstracker.model.PromotionTag
import java.math.BigDecimal
import java.time.LocalDate
import java.time.MonthDay

/**
 * Reads what Exito and Carulla encode in their promotion names.
 *
 * The names are the stores' internal identifiers, not an API contract, so everything here is
 * best-effort and null where a name does not say. The raw name is always kept alongside, so a decoding
 * mistake is fixable later by re-reading it, never a loss.
 *
 * Shapes seen on 2026-09-25 across 117 distinct names:
 *
 *     cmp_20_prov_mcdo_fruver_franja_ID19665_1ene_31dic        shelf 20%, 1 Jan .. 31 Dec
 *     cmp_20_fisi_1735466_mercado_plus_ID38297_17sep_18oct     shelf 20%
 *     cmp_7650_fisi_1768612_mercado_plus_ID39869_19sep_30nov   shelf, a fixed PRICE (7,650), not a %
 *     mastercard_20_aliado_prov_mercado_categorias_id40239_sep25_sep25   card 20%, today only
 *     paguexllevey_2_3_fisi_1737193_mercado_plus_ID38297_17sep_18oct     pay 2 take 3
 *
 * Dates come in both orders (`sep25`, `25sep`), in English and Spanish (`ene`, `dic`), and with
 * `sept`. A range whose end is before its start runs over New Year (`10jul_01ene`).
 */
internal object PromotionNames {

    /** A shelf promotion from `discountHighlights` -- already reflected in the offer's `Price`. */
    fun shelf(name: String): PromotionTag {
        val (from, to) = dates(name)
        return PromotionTag(
            name = name,
            kind = PromotionKind.SHELF,
            percent = leadingNumber(name, "cmp")?.takeIf { it > BigDecimal.ZERO && it < BigDecimal(100) },
            validFrom = from,
            validTo = to,
        )
    }

    /** A teaser: a card-only price, a multi-buy, or something not seen before. */
    fun teaser(teaser: VtexTeaser): PromotionTag? {
        val name = teaser.name?.takeIf { it.isNotBlank() } ?: return null
        val params = teaser.conditions?.parameters.orEmpty()
        val percent = teaser.effects?.parameters
            ?.firstOrNull { it.name == "PercentualDiscount" }?.value?.trim()?.toBigDecimalOrNull()
        val (from, to) = dates(name)
        val cardIds = params.filter { it.name == "PaymentMethodId" }.mapNotNull { it.value }.joinToString(",")
            .takeIf { it.isNotBlank() }
        val multibuy = MULTIBUY.find(name)
        val kind = when {
            multibuy != null || params.any { it.name == "SkuIdsList" } -> PromotionKind.MULTIBUY
            cardIds != null -> PromotionKind.CARD
            else -> PromotionKind.OTHER
        }
        return PromotionTag(
            name = name,
            kind = kind,
            // A multi-buy's "100%" is the free unit, not a discount on the item -- left out so the
            // column always means a percentage off.
            percent = percent?.takeIf { kind != PromotionKind.MULTIBUY },
            cardIds = cardIds,
            payQty = multibuy?.groupValues?.get(1)?.toIntOrNull(),
            takeQty = multibuy?.groupValues?.get(2)?.toIntOrNull(),
            validFrom = from,
            validTo = to,
        )
    }

    /** `cmp_20_...` -> 20. Null when the name does not start that way. */
    private fun leadingNumber(name: String, prefix: String): BigDecimal? =
        Regex("^${prefix}_(\\d+)_", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toBigDecimalOrNull()

    private val MULTIBUY = Regex("""^paguexllevey_(\d+)_(\d+)""", RegexOption.IGNORE_CASE)

    private val MONTHS = mapOf(
        "ene" to 1, "jan" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4, "may" to 5,
        "jun" to 6, "jul" to 7, "ago" to 8, "aug" to 8, "sep" to 9, "sept" to 9, "set" to 9,
        "oct" to 10, "nov" to 11, "dic" to 12, "dec" to 12,
    )

    private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    /** One date token: `sep25` or `25sep`. */
    private val DATE = Regex("""(?:($MONTH)(\d{1,2})|(\d{1,2})($MONTH))""", RegexOption.IGNORE_CASE)

    /** The two date tokens that END the name, `_<from>_<to>`. Anywhere else is not a range. */
    private val RANGE = Regex(
        """_((?:$MONTH)\d{1,2}|\d{1,2}(?:$MONTH))_((?:$MONTH)\d{1,2}|\d{1,2}(?:$MONTH))$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The name's date range, placed in the year of [today].
     *
     * The year is not in the name, so it is inferred: the range is taken to be the one that contains
     * or is nearest to today, and an end before its start rolls into the next year. A wrong year is
     * possible for a name read long after it was written; these are records for analysis, and the
     * raw name is kept beside them.
     */
    fun dates(name: String, today: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> {
        val m = RANGE.find(name) ?: return null to null
        val from = monthDay(m.groupValues[1]) ?: return null to null
        val to = monthDay(m.groupValues[2]) ?: return null to null
        var start = from.atYear(today.year)
        var end = to.atYear(today.year)
        if (end.isBefore(start)) end = end.plusYears(1)
        // A range entirely in the future by more than half a year was almost certainly last year's.
        if (start.isAfter(today.plusMonths(6))) { start = start.minusYears(1); end = end.minusYears(1) }
        return start to end
    }

    private fun monthDay(token: String): MonthDay? {
        val m = DATE.matchEntire(token) ?: return null
        val month = MONTHS[(m.groupValues[1].ifEmpty { m.groupValues[4] }).lowercase()] ?: return null
        val day = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return null
        return runCatching { MonthDay.of(month, day) }.getOrNull()
    }
}
