package com.luis.dealstracker.web

import com.luis.dealstracker.db.DayPrice
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceTrendTest {
    private val today = LocalDate.of(2026, 11, 12)
    private fun day(ago: Long, price: String) = DayPrice(today.minusDays(ago), BigDecimal(price))

    @Test
    fun `one day of history is not a trend`() {
        assertNull(trendPoints(emptyList(), today))
        assertNull(trendPoints(listOf(day(0, "10")), today))
    }

    @Test
    fun `the line steps at the day the price changed and runs on to today`() {
        // 10 for ten days, then 5 since five days ago, in a 64x20 box with 2px padding.
        val points = trendPoints(listOf(day(10, "10"), day(5, "5")), today)!!.split(" ")
        assertEquals(listOf("2.0,2.0", "32.0,2.0", "32.0,18.0", "62.0,18.0"), points)
    }

    @Test
    fun `days are placed by date, so a gap in the readings is a longer step`() {
        val xs = trendPoints(listOf(day(20, "10"), day(18, "9"), day(2, "8")), today)!!.split(" ").map { it.substringBefore(',').toDouble() }
        assertTrue(xs[1] - xs[0] < xs[3] - xs[2], "two days apart is drawn shorter than sixteen")
    }

    @Test
    fun `a price that never moved is a flat line through the middle`() {
        val ys = trendPoints(listOf(day(9, "7"), day(4, "7"), day(0, "7")), today)!!.split(" ").map { it.substringAfter(',') }.toSet()
        assertEquals(setOf("10.0"), ys)
    }

    @Test
    fun `numbers are written with a dot whatever the machine's language`() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale("es", "CO"))
        try {
            assertFalse(Regex("\\d,\\d+,").containsMatchIn(trendPoints(listOf(day(3, "10.5"), day(1, "9.25")), today)!!))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `today's dot says whether the price ends at the line's low, its high, or between`() {
        assertEquals("low", trendEnd(listOf(day(5, "10"), day(0, "8"))))
        assertEquals("high", trendEnd(listOf(day(5, "8"), day(0, "10"))))
        assertEquals("mid", trendEnd(listOf(day(9, "8"), day(5, "12"), day(0, "10"))))
        assertEquals("mid", trendEnd(listOf(day(5, "8"), day(0, "8"))))
    }

    @Test
    fun `a row draws its line only when the page loaded its history, and says the range in words`() {
        val html = PriceTrends.with(mapOf(7L to listOf(day(6, "10000"), day(0, "8000")))) {
            createHTML(prettyPrint = false).div { priceTrend(7, "COP", today); priceTrend(8, "COP", today) }
        }
        assertEquals(1, Regex("<svg").findAll(html).count())
        assertTrue("trend-low" in html && "Last 30 days: from $8,000 to $10,000" in html)
        assertEquals("", createHTML(prettyPrint = false).div { priceTrend(7, "COP", today) }.removePrefix("<div>").removeSuffix("</div>"), "nothing outside a page that loaded it")
    }
}
