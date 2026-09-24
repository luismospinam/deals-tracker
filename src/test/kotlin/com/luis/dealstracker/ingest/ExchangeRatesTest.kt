package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.FxRate
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExchangeRatesTest {

    /** A real response from datos.gov.co dataset 32sa-8pi3, captured 2026-09-23. */
    private val body = """
        [{"valor":"3264.39","unidad":"COP","vigenciadesde":"2026-09-24T00:00:00.000","vigenciahasta":"2026-09-24T00:00:00.000"}]
    """.trimIndent()

    @Test
    fun `parses the newest TRM row`() {
        assertEquals(
            FxRate(BigDecimal("3264.39"), "TRM", LocalDate.parse("2026-09-24")),
            ExchangeRates.parseTrm(body),
        )
    }

    /**
     * The rate multiplies every USD price on a watch, so a value three orders of magnitude off
     * would move every Amazon row by the same factor. Falling back is the safer failure.
     */
    @Test
    fun `an implausible or malformed rate is rejected`() {
        assertNull(ExchangeRates.parseTrm(body.replace("3264.39", "3.26")))
        assertNull(ExchangeRates.parseTrm(body.replace("\"COP\"", "\"USD\"")))
        assertNull(ExchangeRates.parseTrm("[]"))
        assertNull(ExchangeRates.parseTrm("<html>maintenance</html>"))
    }

    @Test
    fun `the configured fallback applies until a fetch succeeds`() {
        val rates = ExchangeRates(http = null, fallbackCopPerUsd = BigDecimal("3200"))
        assertEquals(FxRate(BigDecimal("3200"), "config"), rates.current())
    }

    @Test
    fun `conversion rounds COP to whole pesos and keeps USD cents`() {
        val fx = FxRate(BigDecimal("3264.39"), "TRM")
        assertEquals(BigDecimal("1305723"), fx.toCop(BigDecimal("399.99"), "USD"))
        assertEquals(BigDecimal("399.99"), fx.convert(BigDecimal("1305723"), "COP", "USD"))
        assertNull(fx.toCop(BigDecimal("10"), "EUR"))
    }
}
