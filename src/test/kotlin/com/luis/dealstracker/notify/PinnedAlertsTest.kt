package com.luis.dealstracker.notify

import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinnedAlertsTest {
    private val zone = ZoneId.of("America/Bogota")
    private val times = parseSlotTimes(listOf("08:00", "19:00", "nonsense"))
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 15, h, m, 0, 0, zone)
    private fun slot(h: Int) = at(h).toInstant()

    private fun deal(id: Long = 1, price: String = "100", min: String? = null, max: String? = null) = Deal(
        id = id, source = "amazon", sourceDealId = "$id", title = "Item [x] $id", url = "https://x/$id",
        merchant = "amazon", asin = "A$id", category = null, imageUrl = null, brand = null, gtin = null,
        modelCode = null, matchKey = null, modelKey = null, currency = "USD",
        firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
        state = DealState.NEW, coShipping = CoShipping.YES, promoted = false, promotedAt = null,
        communityScore = null, postedAt = null, categoryGroup = null,
        coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null, coShippingCost = null,
        price = BigDecimal(price), listPrice = null,
        minPriceEver = min?.let(::BigDecimal), maxPriceEver = max?.let(::BigDecimal), observationCount = 5,
    )

    private fun groceryRow(price: String, usual: String = "100", days: Int = 5, pinned: Boolean = true, onShelf: Boolean = true) =
        GroceryRow(
            deal = deal(price = price), size = null, group = null, pinnedAt = if (pinned) Instant.EPOCH else null,
            usual = BigDecimal(usual), low = BigDecimal(usual), days = days, onShelf = onShelf, store = "Carulla",
        )

    // --- slots ---

    @Test
    fun `nothing is due before the first time of the day`() {
        assertNull(dueSlot(at(7, 59), times, null))
    }

    @Test
    fun `the morning slot is due from eight and not again once recorded`() {
        assertEquals(slot(8), dueSlot(at(8, 0), times, null))
        assertNull(dueSlot(at(12), times, slot(8)))
    }

    @Test
    fun `after the evening time only the evening slot is due, even when the morning was missed`() {
        assertEquals(slot(19), dueSlot(at(20), times, null))
        assertEquals(slot(19), dueSlot(at(20), times, slot(8)))
        assertNull(dueSlot(at(20), times, slot(19)))
    }

    @Test
    fun `yesterday's slot does not satisfy today's`() {
        val yesterday = slot(19).minusSeconds(24 * 3600)
        assertEquals(slot(8), dueSlot(at(9), times, yesterday))
    }

    @Test
    fun `malformed times are dropped`() {
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(19, 0)), times)
    }

    // --- groceries ---

    @Test
    fun `groceries need a pin, the shelf and ten percent under usual`() {
        val rows = listOf(
            groceryRow("90"),                       // exactly 10% under: a deal
            groceryRow("95"),                       // 5%: not
            groceryRow("80", pinned = false),       // not pinned
            groceryRow("80", onShelf = false),      // off the shelf
            groceryRow("80", days = 2),             // too little history to judge
            groceryRow("70"),                       // deepest
        )
        assertEquals(listOf("70", "90"), pinnedGroceryDeals(rows).map { it.deal.price.toPlainString() })
    }

    // --- tracked ---

    @Test
    fun `a tracked item is on sale when ten percent under its usual price with enough history`() {
        val d = deal(price = "90")
        val out = trackedDealsOnSale(listOf(d), mapOf(d.id to (BigDecimal(100) to 3)))
        assertEquals(1, out.size)
        assertEquals(BigDecimal("10.0"), out[0].belowUsual)
        assertTrue("−10.0% vs usual" in trackedLine(out[0]), trackedLine(out[0]))
    }

    @Test
    fun `too little history or too small a drop is not a sale`() {
        val d = deal(price = "90")
        assertTrue(trackedDealsOnSale(listOf(d), mapOf(d.id to (BigDecimal(100) to 2))).isEmpty())
        assertTrue(trackedDealsOnSale(listOf(deal(price = "95")), mapOf(1L to (BigDecimal(100) to 9))).isEmpty())
        assertTrue(trackedDealsOnSale(listOf(d), emptyMap()).isEmpty())
    }

    @Test
    fun `a tracked item at its lowest recorded price is on sale without a usual price`() {
        val d = deal(price = "50", min = "50", max = "80")
        val out = trackedDealsOnSale(listOf(d), emptyMap())
        assertEquals(1, out.size)
        assertTrue("lowest recorded" in trackedLine(out[0]))
    }

    @Test
    fun `a price that never moved is not lowest recorded`() {
        assertTrue(trackedDealsOnSale(listOf(deal(price = "50", min = "50", max = "50")), emptyMap()).isEmpty())
    }

    @Test
    fun `lines link the title with brackets neutralised`() {
        val line = groceryLine(groceryRow("90"))
        assertTrue(line.startsWith("[Item (x) 1](https://x/1)"), line)
        assertTrue("Carulla" in line, line)
    }

    // --- discord ---

    @Test
    fun `a long list is cut on a line boundary with a count of what was left out`() {
        val lines = (1..100).map { "line number $it ".padEnd(50, '.') }
        val text = fitDescription(lines, limit = 500)
        assertTrue(text.length <= 500, "${text.length}")
        assertTrue(text.endsWith("more"), text)
        val kept = text.lines().count { it.startsWith("line number") }
        assertTrue("…and ${100 - kept} more" in text, text)
    }

    @Test
    fun `a list that fits is untouched`() {
        assertEquals("a\nb", fitDescription(listOf("a", "b")))
    }

    @Test
    fun `the payload is one embed carrying title, description, colour and footer`() {
        val json = DiscordWebhook.payload("T", "D", "F", DiscordWebhook.GREEN, "bot").toString()
        listOf("\"username\":\"bot\"", "\"title\":\"T\"", "\"description\":\"D\"", "\"text\":\"F\"").forEach {
            assertTrue(it in json, json)
        }
    }
}
