package com.luis.dealstracker.web

import com.luis.dealstracker.model.PriceObservation
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryRowsTest {
    private val today = LocalDate.of(2026, 10, 29)

    private fun obs(day: LocalDate, hour: Int, price: String, list: String? = null) = PriceObservation(
        1, day.atTime(hour, 0).toInstant(ZoneOffset.UTC), BigDecimal(price), list?.let(::BigDecimal), "COP",
    )

    private fun rows(vararg o: PriceObservation) = historyRows(o.toList(), today, ZoneOffset.UTC)

    @Test
    fun `a price repeated within an earlier day shows once, at its first reading`() {
        val d = today.minusDays(1)
        val r = rows(obs(d, 9, "100"), obs(d, 10, "100"), obs(d, 11, "100"), obs(d, 12, "90"), obs(d, 13, "90"))
        assertEquals(listOf("90", "100"), r.map { it.price.toPlainString() })
        assertEquals(12, r[0].observedAt.atZone(ZoneOffset.UTC).hour)
        assertEquals(9, r[1].observedAt.atZone(ZoneOffset.UTC).hour)
    }

    @Test
    fun `the same price on different days shows once per day`() {
        val r = rows(obs(today.minusDays(2), 1, "100"), obs(today.minusDays(2), 2, "100"), obs(today.minusDays(1), 1, "100"))
        assertEquals(2, r.size)
    }

    @Test
    fun `a price that returns later the same day is not repeated`() {
        val d = today.minusDays(2)
        val r = rows(obs(d, 1, "100"), obs(d, 2, "90"), obs(d, 3, "100"))
        assertEquals(listOf("90", "100"), r.map { it.price.toPlainString() })
    }

    @Test
    fun `today keeps every reading even when they repeat`() {
        val r = rows(obs(today, 1, "100"), obs(today, 2, "100"), obs(today, 3, "100"))
        assertEquals(3, r.size)
    }

    @Test
    fun `yesterday and today are separate, so a price held across midnight shows in both`() {
        val y = today.minusDays(1)
        val r = rows(obs(y, 20, "100"), obs(y, 21, "100"), obs(today, 1, "100"), obs(today, 2, "100"))
        assertEquals(3, r.size)
    }

    @Test
    fun `a changed list price is a distinct reading, and 100 equals 100 point 00`() {
        val d = today.minusDays(1)
        assertEquals(2, rows(obs(d, 1, "100", "120"), obs(d, 2, "100", "130")).size)
        assertEquals(1, rows(obs(d, 1, "100", "120"), obs(d, 2, "100.00", "120.0")).size)
    }

    private fun withCard(o: PriceObservation, card: String?) =
        o.copy(card = card?.let { com.luis.dealstracker.model.CardOffer(BigDecimal(it), BigDecimal("40"), com.luis.dealstracker.model.CardScope.OTHER) })

    @Test
    fun `a card price appearing on an unchanged shelf price is its own row`() {
        val d = today.minusDays(1)
        val r = rows(obs(d, 9, "100"), withCard(obs(d, 10, "100"), "60"), withCard(obs(d, 11, "100"), "60"))
        assertEquals(listOf("60", null), r.map { it.card?.price?.toPlainString() })
    }
}
