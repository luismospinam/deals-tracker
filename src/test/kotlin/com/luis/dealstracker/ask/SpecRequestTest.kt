package com.luis.dealstracker.ask

import com.luis.dealstracker.db.DealSpecs
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecRequestTest {
    private val now: Instant = Instant.parse("2026-10-06T12:00:00Z")

    private fun deal(id: Long, title: String = "Sofa $id") = Deal(
        id = id, source = "easy", sourceDealId = "x-$id", title = title, url = "https://example.com/$id",
        merchant = null, asin = null, category = null, imageUrl = null, brand = null, gtin = null,
        modelCode = null, matchKey = null, modelKey = null, currency = "COP", firstSeenAt = now,
        lastSeenAt = now, isActive = true, state = DealState.NEW, coShipping = CoShipping.YES, promoted = true,
        promotedAt = now, communityScore = null, postedAt = null, categoryGroup = null,
        coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
        coShippingCost = null, price = BigDecimal("1000000"), listPrice = null,
        minPriceEver = BigDecimal("1000000"), maxPriceEver = BigDecimal("1000000"), observationCount = 2,
    )

    // 1 and 2 have specs, 3 is shown but has none, 99 is not on the screen at all.
    private val ctx = AskContext("watch \"Sofá\"", emptyList(), listOf(deal(1), deal(2), deal(3))).withSpecIds(setOf(1, 2))

    @Test
    fun `an ordinary answer is not a request`() {
        assertNull(ctx.parseSpecRequest("The cheapest is [#1] at 1,000,000."))
        assertNull(ctx.parseSpecRequest(""))
    }

    @Test
    fun `a request lists ids in the order asked, however they are punctuated`() {
        assertEquals(listOf(2L, 1L), ctx.parseSpecRequest("NEED_SPECS: 2, 1"))
        assertEquals(listOf(2L, 1L), ctx.parseSpecRequest("  need_specs: [#2] [#1]\nignored second line"))
    }

    @Test
    fun `only rows that were shown and have specs can be requested`() {
        // 3 has no specs, 99 was never on screen, 2 repeats.
        assertEquals(listOf(1L, 2L), ctx.parseSpecRequest("NEED_SPECS: 1, 3, 99, 2, 2"))
    }

    @Test
    fun `a request is capped so a runaway list cannot become a huge prompt`() {
        val many = (1L..200L).map { deal(it) }
        val big = AskContext("w", emptyList(), many).withSpecIds(many.map { it.id }.toSet())
        assertEquals(MAX_SPEC_ROWS, big.parseSpecRequest("NEED_SPECS: " + (1..200).joinToString(","))!!.size)
    }

    @Test
    fun `the first prompt names the rows that have specs, and omits the line where none do`() {
        assertTrue(ctx.firstPrompt("which has the left chaise?").contains("Rows with stored specifications"))
        assertTrue(ctx.firstPrompt("q").contains(": 1,2\n"))
        assertFalse(AskContext("w", emptyList(), listOf(deal(1))).firstPrompt("q").contains("stored specifications"))
    }

    @Test
    fun `the specs prompt carries the specs and a capped description, and says which rows have none`() {
        val specs = mapOf(
            1L to DealSpecs(mapOf("Lado Chaise" to "Izquierdo", "Ancho" to "265 cm"), "x".repeat(2000)),
        )
        val text = ctx.specsPrompt(specs, listOf(1, 2))
        assertTrue(text.contains("#1 Sofa 1"))
        assertTrue(text.contains("Lado Chaise: Izquierdo; Ancho: 265 cm"))
        assertTrue(text.contains("About: " + "x".repeat(700)) && !text.contains("x".repeat(701)))
        assertTrue(text.contains("No specifications stored for: 2."))
    }
}
