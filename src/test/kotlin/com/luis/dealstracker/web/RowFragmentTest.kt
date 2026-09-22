package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for a swap that wrecked the feed's layout.
 *
 * Row actions responded with `respondHtml`, i.e. a whole `<html><body><table><tbody><tr>`
 * document. htmx, swapping that into a `<tr>` slot with `outerHTML`, took the body's
 * children and inserted an entire nested `<table>` where the row belonged. The nested table
 * carried its own column widths, so pressing any action button — ships, no, save, hide,
 * verify — visibly broke the alignment of every row below it.
 */
class RowFragmentTest {

    private val now: Instant = Instant.parse("2026-09-05T12:00:00Z")

    private fun deal(id: Long = 7, co: CoShipping = CoShipping.UNKNOWN, asin: String? = "B01ABCDEFG") = Deal(
        id = id,
        source = "slickdeals",
        sourceDealId = "sd-$id",
        title = "Anker 65W 3-Port Foldable USB-C Charger",
        url = "https://slickdeals.net/f/12345",
        merchant = "amazon",
        asin = asin,
        category = "Hot Deals",
        imageUrl = null,
        currency = "USD",
        firstSeenAt = now,
        lastSeenAt = now,
        isActive = true,
        state = DealState.NEW,
        coShipping = co,
        promoted = true,
        promotedAt = now,
        communityScore = 12,
        postedAt = now,
        categoryGroup = "Computers",
        brand = null,
        gtin = null,
        modelCode = null,
        matchKey = null,
        modelKey = null,
        coShippingSource = null,
        coShippingEvidence = null,
        coShippingImportFree = null,
        coShippingCost = null,
        price = BigDecimal("32.99"),
        listPrice = BigDecimal("45.99"),
        minPriceEver = BigDecimal("32.99"),
        maxPriceEver = BigDecimal("45.99"),
        observationCount = 3,
    )

    @Test
    fun `the swap payload is a bare tr, not a document`() {
        val html = rowFragment(deal())
        assertTrue(html.startsWith("<tr"), "must start with the row itself, got: ${html.take(60)}")
        listOf("<html", "<body", "<table", "<tbody", "<!DOCTYPE").forEach { wrapper ->
            assertFalse(
                html.contains(wrapper, ignoreCase = true),
                "a wrapper leaks into the swap and nests a table inside the row: $wrapper",
            )
        }
    }

    @Test
    fun `the fragment keeps the id htmx targets`() {
        assertTrue(rowFragment(deal(id = 42)).contains("""id="deal-42""""))
    }

    @Test
    fun `an unresolved outcome is stated on the row`() {
        val note = "the page did not state it either way"
        assertTrue(rowFragment(deal(), note).contains(note), "the note must reach the row")
        assertFalse(rowFragment(deal()).contains("co-note"), "no note element when there is nothing to say")
    }

    /** The button promises a lookup by ASIN, so it must not appear when there is none. */
    @Test
    fun `verify is offered only when an ASIN exists`() {
        assertTrue(rowFragment(deal(asin = "B01ABCDEFG")).contains("/verify"))
        assertFalse(rowFragment(deal(asin = null)).contains("/verify"))
        assertFalse(rowFragment(deal(asin = "")).contains("/verify"))
    }

    /** A decided verdict needs no check, so the button costs nothing and is absent. */
    @Test
    fun `verify is not offered on an already decided row`() {
        assertFalse(rowFragment(deal(co = CoShipping.YES)).contains("/verify"))
        assertFalse(rowFragment(deal(co = CoShipping.NO)).contains("/verify"))
    }
}
