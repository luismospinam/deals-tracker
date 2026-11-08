package com.luis.dealstracker.web

import com.luis.dealstracker.db.PINNED_RECHECK_DUE
import com.luis.dealstracker.config.Defaults
import java.time.Instant
import kotlinx.html.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Puma shoe kept showing $46.60 for most of a day after Amazon went back to $59.99 (2026-11-04): it had
 * dropped out of search results, so only its page check refreshed it. The price now says how old it is.
 */
class PriceAgeTest {
    private val now = Instant.parse("2026-11-04T20:00:00Z")

    @Test
    fun `fresh prices read in hours, a day old reads in days and goes stale`() {
        assertEquals("price from <1h ago", priceAge(now.minusSeconds(20 * 60), now).label)
        val nine = priceAge(now.minusSeconds(9 * 3600), now)
        assertEquals("price from 9h ago", nine.label)
        assertFalse(nine.stale)
        val two = priceAge(now.minusSeconds(50 * 3600), now)
        assertEquals("price from 2d ago", two.label)
        assertTrue(two.stale)
        assertTrue(priceAge(now.minusSeconds(24 * 3600), now).stale, "stale from exactly a day")
    }

    @Test
    fun `a clock running ahead of the database never reads negative`() {
        assertEquals("price from <1h ago", priceAge(now.plusSeconds(120), now).label)
    }

    @Test
    fun `the price is as old as the later of a search sighting and a page read`() {
        val searched = Instant.parse("2026-11-02T10:51:18Z")
        val paged = Instant.parse("2026-11-04T10:55:14Z")
        assertEquals(paged, deal(lastSeen = searched, pageValid = paged).priceReadAt, "out of search results, the page read is newer")
        assertEquals(paged, deal(lastSeen = paged, pageValid = searched).priceReadAt, "a fresh search sighting wins")
        assertEquals(searched, deal(lastSeen = searched, pageValid = null).priceReadAt)
    }

    @Test
    fun `pinned rows are re-read every few hours, the rest every two days`() {
        assertEquals(6, Defaults().pinnedPageRecheckHours)
        assertTrue(PINNED_RECHECK_DUE.contains("d.pinned_at IS NOT NULL"))
        assertTrue(PINNED_RECHECK_DUE.contains("make_interval(hours => ?)"))
        assertTrue(PINNED_RECHECK_DUE.startsWith("? > 0"), "0 turns it off")
    }

    @Test
    fun `the line warns once the price is a day old`() {
        val old = kotlinx.html.stream.createHTML().div { priceAgeSpan(deal(lastSeen = now.minusSeconds(30 * 3600), pageValid = null), now) }
        assertTrue(old.contains("class=\"price-age stale-prov\""), old)
        assertTrue(old.contains("price from 1d ago"))
        val fresh = kotlinx.html.stream.createHTML().div { priceAgeSpan(deal(lastSeen = now.minusSeconds(3 * 3600), pageValid = null), now) }
        assertTrue(fresh.contains("class=\"price-age\""), fresh)
    }

    private fun deal(lastSeen: Instant, pageValid: Instant?) = com.luis.dealstracker.model.Deal(
        id = 1, source = "amazon", sourceDealId = "B0DWV33683", title = "Future 8 Play", url = "https://example/1",
        merchant = null, asin = "B0DWV33683", category = null, imageUrl = null, brand = null, gtin = null,
        modelCode = null, matchKey = null, modelKey = null, currency = "USD", firstSeenAt = lastSeen, lastSeenAt = lastSeen,
        isActive = true, state = com.luis.dealstracker.model.DealState.NEW, coShipping = com.luis.dealstracker.model.CoShipping.YES,
        promoted = false, promotedAt = null, communityScore = null, postedAt = null, categoryGroup = null,
        coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null, coShippingCost = null,
        price = java.math.BigDecimal("46.60"), listPrice = null, minPriceEver = null, maxPriceEver = null, observationCount = 1,
        pageValidAt = pageValid,
    )
}
