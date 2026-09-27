package com.luis.dealstracker.ingest

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How much a grocery pack holds, in a base unit: grams, millilitres or units.
 *
 * The unit price is derived from this and the current price on read -- see [unitPrice] -- rather
 * than stored, so it can never disagree with the price beside it.
 */
data class UnitSize(val measure: String, val quantity: BigDecimal) {
    init {
        require(measure in MEASURES) { "unknown measure $measure" }
        require(quantity.signum() > 0) { "quantity must be positive" }
    }

    companion object {
        const val GRAMS = "g"
        const val MILLILITRES = "ml"
        const val UNITS = "un"
        private val MEASURES = setOf(GRAMS, MILLILITRES, UNITS)

        /**
         * The pack size of a grocery item, or null when the listing does not say.
         *
         * Two routes, tried in this order:
         *
         *  1. **A weighed item** (`measurementUnit = kg`) is sold by weight, and `unitMultiplier` is
         *     the pack's weight in kg -- the same field `VtexSource` scales the price by. `Zapayo`
         *     at 1.173 kg is 1,173 g. This beats the title, which for weighed goods names no size.
         *  2. **Anything else** reads the size from the title. Both stores end titles with it, and
         *     almost always in parentheses: `Café SELLO ROJO molido fuerte (600  gr)`,
         *     `Huevos AAA AVINAL rojo (12  und)`. Produce drops the parentheses:
         *     `Espinaca Baby  150  gr`.
         *
         * The LAST size in the title wins, because a multipack names the part before the whole:
         * `Jugo HIT Surtido x 6und 200ml (1200  ml)` is 1,200 ml, not 200. A size written inside
         * the title only counts when nothing trails it.
         *
         * Null rather than a guess when neither route yields anything -- `12 x Limón Tahití` names
         * a count but no unit word, and inventing "12 und" from a bare number would also turn model
         * numbers into pack sizes. A count WITH a unit word (`Aguacates x 3 unidades`) is a size.
         */
        fun of(title: String, measurementUnit: String?, unitMultiplier: Double?): UnitSize? {
            val unit = measurementUnit?.trim()?.lowercase()
            val multiplier = unitMultiplier?.takeIf { it > 0 && it.isFinite() }?.toBigDecimal()
            if (multiplier != null) {
                when (unit) {
                    "kg" -> return UnitSize(GRAMS, multiplier.multiply(THOUSAND))
                    "g", "gr" -> return UnitSize(GRAMS, multiplier)
                    "l", "lt" -> return UnitSize(MILLILITRES, multiplier.multiply(THOUSAND))
                    "ml" -> return UnitSize(MILLILITRES, multiplier)
                }
            }
            return fromTitle(title)
        }

        internal fun fromTitle(title: String): UnitSize? {
            val match = SIZE.findAll(title).lastOrNull() ?: return null
            val amount = parseAmount(match.groupValues[1]) ?: return null
            val (measure, factor) = UNIT_WORDS[match.groupValues[2].lowercase()] ?: return null
            return amount.multiply(factor).takeIf { it.signum() > 0 }?.let { UnitSize(measure, it.stripTrailingZeros()) }
        }

        /**
         * `148.8` is a decimal and so is `2,5`; `1.000` is a thousand. Colombian titles use both a
         * dot and a comma as the decimal mark, but a dot followed by exactly three digits is a
         * thousands group -- `1.000 ml` is a litre, and reading it as 1 ml makes water look
         * a thousand times dearer per litre than it is.
         */
        private fun parseAmount(raw: String): BigDecimal? {
            val s = when {
                Regex("""^\d{1,3}(\.\d{3})+$""").matches(raw) -> raw.replace(".", "")
                else -> raw.replace(',', '.')
            }
            return s.toBigDecimalOrNull()
        }

        private val THOUSAND = BigDecimal(1000)

        private val UNIT_WORDS: Map<String, Pair<String, BigDecimal>> = buildMap {
            listOf("g", "gr", "grs", "gramos").forEach { put(it, GRAMS to BigDecimal.ONE) }
            listOf("kg", "kgs", "kilo", "kilos").forEach { put(it, GRAMS to THOUSAND) }
            listOf("ml", "mililitros").forEach { put(it, MILLILITRES to BigDecimal.ONE) }
            listOf("l", "lt", "lts", "litro", "litros").forEach { put(it, MILLILITRES to THOUSAND) }
            listOf("und", "un", "unidad", "unidades", "uds").forEach { put(it, UNITS to BigDecimal.ONE) }
        }

        /**
         * A number and a unit word, as a whole word. The unit must not run on into letters, so
         * `12 gramos` matches and `8 grande` does not; the number must not be glued to a letter
         * before it, so a model code like `X5` is not a size.
         */
        private val SIZE = Regex(
            """(?<![\p{L}\d.,])(\d+(?:[.,]\d+)*)\s*(""" +
                UNIT_WORDS.keys.sortedByDescending { it.length }.joinToString("|") +
                """)(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Price per 100 g, per litre, or per unit -- the figure shelf labels quote, so it reads as familiar.
 *
 * Null when the size is unknown. Never falls back to the shelf price: a blank is "can't compare",
 * while the shelf price in this column would claim a comparison that was never made.
 */
fun unitPrice(price: BigDecimal, size: UnitSize?): BigDecimal? {
    size ?: return null
    val per = when (size.measure) {
        UnitSize.GRAMS -> BigDecimal(100)
        UnitSize.MILLILITRES -> BigDecimal(1000)
        else -> BigDecimal.ONE
    }
    return price.multiply(per).divide(size.quantity, 0, RoundingMode.HALF_UP)
}

/** The label for [unitPrice]'s denominator. */
fun unitPriceLabel(size: UnitSize?): String = when (size?.measure) {
    UnitSize.GRAMS -> "/100 g"
    UnitSize.MILLILITRES -> "/L"
    UnitSize.UNITS -> "/und"
    else -> ""
}
