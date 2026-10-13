package com.luis.dealstracker.db

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryPrunerTest {

    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val cutoff = now.minus(Duration.ofDays(14))
    private fun daysAgo(d: Long) = now.minus(Duration.ofDays(d))

    private fun sig(
        price: String = "100.00",
        list: String? = null,
        card: String? = null,
        cardPct: String? = null,
        scope: String? = null,
        promos: List<Long> = emptyList(),
    ) = ReadingSignature.of(
        BigDecimal(price), list?.let(::BigDecimal), "COP", card?.let(::BigDecimal), cardPct?.let(::BigDecimal), scope, promos,
    )

    private fun r(id: Long, d: Long, s: ReadingSignature = sig()) = Reading(id, daysAgo(d), s)

    @Test
    fun `an unchanged old stretch keeps only its first reading`() {
        val rs = listOf(r(1, 30), r(2, 29), r(3, 28), r(4, 27), r(5, 1))
        // 1 is the first; 5 is recent and last; 2, 3 and 4 repeat 1.
        assertEquals(listOf(2L, 3L, 4L), redundantReadings(rs, cutoff))
    }

    @Test
    fun `a price change is kept, and so is the reading after it that starts a new stretch`() {
        val rs = listOf(
            r(1, 30, sig("100")), r(2, 29, sig("100")), r(3, 28, sig("80")),
            r(4, 27, sig("80")), r(5, 26, sig("100")), r(6, 1, sig("100")),
        )
        assertEquals(listOf(2L, 4L), redundantReadings(rs, cutoff))
    }

    @Test
    fun `a stretch that returns to an earlier price is not merged with the earlier one`() {
        // 100 -> 80 -> 100: the second 100 is a change from 80, so it must survive.
        val rs = listOf(r(1, 30, sig("100")), r(2, 29, sig("80")), r(3, 28, sig("100")), r(4, 27, sig("100")), r(5, 1, sig("100")))
        assertEquals(listOf(4L), redundantReadings(rs, cutoff))
    }

    @Test
    fun `readings inside the cutoff are never deleted`() {
        val rs = listOf(r(1, 5), r(2, 4), r(3, 3), r(4, 2), r(5, 1))
        assertEquals(emptyList(), redundantReadings(rs, cutoff))
    }

    @Test
    fun `a stretch that crosses the cutoff loses only its old repeats`() {
        val rs = listOf(r(1, 20), r(2, 16), r(3, 13), r(4, 8), r(5, 1))
        assertEquals(listOf(2L), redundantReadings(rs, cutoff))
    }

    @Test
    fun `the last reading survives even when old and unchanged`() {
        // A deal unseen for weeks: its final reading is its current price.
        val rs = listOf(r(1, 40), r(2, 39), r(3, 38))
        assertEquals(listOf(2L), redundantReadings(rs, cutoff))
    }

    @Test
    fun `fewer than three readings are untouched`() {
        assertEquals(emptyList(), redundantReadings(emptyList(), cutoff))
        assertEquals(emptyList(), redundantReadings(listOf(r(1, 40)), cutoff))
        assertEquals(emptyList(), redundantReadings(listOf(r(1, 40), r(2, 39)), cutoff))
    }

    @Test
    fun `a changed list price, card price or promotion set counts as a change`() {
        val base = sig("100", list = "150")
        val rs = listOf(
            r(1, 30, base),
            r(2, 29, sig("100", list = "160")),                       // list price moved
            r(3, 28, sig("100", list = "160", card = "80", cardPct = "20", scope = "MASTERCARD")), // card appeared
            r(4, 27, sig("100", list = "160", card = "80", cardPct = "20", scope = "MASTERCARD", promos = listOf(7))), // promo began
            r(5, 26, sig("100", list = "160", card = "80", cardPct = "20", scope = "MASTERCARD", promos = listOf(7))), // repeat
            r(6, 1, base),
        )
        assertEquals(listOf(5L), redundantReadings(rs, cutoff))
    }

    @Test
    fun `promotion order and decimal scale do not make equal readings differ`() {
        val a = ReadingSignature.of(BigDecimal("100.00"), BigDecimal("150.0"), "COP", null, null, null, listOf(3, 1, 2))
        val b = ReadingSignature.of(BigDecimal("100"), BigDecimal("150.00"), "COP", null, null, null, listOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(listOf(2L), redundantReadings(listOf(Reading(1, daysAgo(30), a), Reading(2, daysAgo(29), b), Reading(3, daysAgo(1), a)), cutoff))
    }

    @Test
    fun `a different currency is a change`() {
        val usd = ReadingSignature.of(BigDecimal("100"), null, "USD", null, null, null, emptyList())
        val rs = listOf(r(1, 30), Reading(2, daysAgo(29), usd), r(3, 1))
        assertEquals(emptyList(), redundantReadings(rs, cutoff))
    }

    @Test
    fun `prune is due when never run, or after the interval, and never when off`() {
        assertTrue(pruneDue(null, now, 7))
        assertFalse(pruneDue(now.minus(Duration.ofDays(6)), now, 7))
        assertTrue(pruneDue(now.minus(Duration.ofDays(7)), now, 7))
        assertFalse(pruneDue(null, now, 0))
        assertFalse(pruneDue(now.minus(Duration.ofDays(30)), now, 0))
    }

    @Test
    fun `the report states a dry run plainly`() {
        val text = PruneReport(1000, 900, mapOf("exito" to 600L, "easy" to 300L), dryRun = true).summary()
        assertTrue("would delete 900" in text, text)
        assertTrue("nothing deleted" in text, text)
        assertTrue(text.indexOf("exito") < text.indexOf("easy"), "largest source first")
    }
}
