package com.luis.dealstracker.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * How many pesos one US dollar is worth, and where that number came from.
 *
 * The app compared currencies nowhere until watches started fetching amazon.com directly: a TV
 * watch then holds Colombian retailers in COP and Amazon in USD, and "which is cheapest" has no
 * answer without a rate. So both prices are brought to COP, which is what the operator pays in.
 *
 * [source] travels with the number because a converted price is only as good as its rate, and an
 * alert saying "undercuts exito" on the strength of a stale fallback must be able to say so.
 */
data class FxRate(
    val copPerUsd: BigDecimal,
    /** `"TRM"` for the official daily rate, `"config"` for the fallback in sources.yaml. */
    val source: String,
    /** The day the rate applies to. Null for the configured fallback, which has no date. */
    val asOf: LocalDate? = null,
) {
    /**
     * The amount in COP, or null when the currency is neither COP nor USD.
     *
     * Null rather than a guess for anything else: no source here quotes a third currency today,
     * and one that appears should stay uncompared rather than be converted at a made-up rate.
     */
    fun toCop(amount: BigDecimal, currency: String): BigDecimal? = convert(amount, currency, "COP")

    /**
     * [amount] in [from] expressed in [to], or null when either is not COP or USD.
     *
     * COP results are whole pesos, since nothing here is priced in centavos; USD keeps cents.
     * [scale] overrides that for a conversion FACTOR, which must not be rounded to a whole unit.
     */
    fun convert(amount: BigDecimal, from: String, to: String, scale: Int? = null): BigDecimal? {
        val copPerUnit = mapOf("COP" to BigDecimal.ONE, "USD" to copPerUsd)
        val inCop = copPerUnit[from]?.let(amount::multiply) ?: return null
        val per = copPerUnit[to] ?: return null
        return inCop.divide(per, scale ?: if (to == "COP") 0 else 2, RoundingMode.HALF_UP)
    }

    /** `3,264.39 COP/USD (TRM 2026-09-23)` — the rate as a reader should see it quoted. */
    fun describe(): String =
        String.format("%,.2f", copPerUsd) + " COP/USD (" + source + (asOf?.let { " $it" } ?: "") + ")"
}
