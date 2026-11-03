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

    private fun deal(
        id: Long = 7,
        co: CoShipping = CoShipping.UNKNOWN,
        asin: String? = "B01ABCDEFG",
        state: DealState = DealState.NEW,
        isActive: Boolean = true,
        pinned: Boolean = false,
    ) = Deal(
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
        isActive = isActive,
        state = state,
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
        pinnedAt = if (pinned) now else null,
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

    /**
     * The feed's favorite became the pin every other tab has (V33): the same ☆ pin, stored in
     * `pinned_at`, and the toggle undoes itself.
     */
    @Test
    fun `the pin toggle posts the opposite action`() {
        assertTrue(rowFragment(deal()).contains("/deal/7/pin\""), "an unpinned row must offer to pin")
        assertTrue(rowFragment(deal()).contains("☆ pin"))
        val pinned = rowFragment(deal(pinned = true))
        assertTrue(pinned.contains("/deal/7/unpin\""), "a pinned row must offer to unpin")
        assertTrue(pinned.contains("★ pinned"))
        assertFalse(pinned.contains("favorite"), "no trace of the old name")
    }

    /** Hiding a row you chose to keep is a contradiction, so the button is not offered -- unpin first. */
    @Test
    fun `hide is withheld from a pinned row`() {
        assertTrue(rowFragment(deal()).contains("value=DISMISSED"), "an unpinned row keeps Hide")
        assertFalse(rowFragment(deal(pinned = true)).contains("value=DISMISSED"))
    }

    /**
     * On /pinned the row leaves the page, so unpinning there posts to a route that answers empty.
     * Swapping in a rendered row would leave it claiming to be pinned on the Pinned page — the same
     * trap POST /tracked/{id}/hide exists to avoid.
     */
    @Test
    fun `unpinning from the Pinned screen posts to the removing route`() {
        val html = rowFragment(deal(pinned = true), screen = FeedScreen.PINNED)
        assertTrue(html.contains("/pinned/7/remove"), "must post to the route that removes the row")
        assertFalse(html.contains("/deal/7/unpin"), "the in-place swap would leave a stale row")
    }

    /**
     * A retired pin is kept rather than hidden, so the row has to say the offer ended — the
     * price beside it is the last one recorded, not a current one.
     */
    @Test
    fun `a retired row is marked expired`() {
        assertTrue(
            rowFragment(deal(pinned = true, isActive = false)).contains("expired"),
            "a retired row must say so rather than presenting a stale price as live",
        )
        assertFalse(
            rowFragment(deal(pinned = true)).contains("chip-expired"),
            "a live row must not be marked expired",
        )
    }
}
