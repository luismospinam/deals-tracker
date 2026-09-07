package com.luis.dealstracker.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for a false positive that recorded shippable deals as blocked.
 *
 * The marker list contained a bare "Continue shopping". That phrase appears in the markup of
 * ordinary product pages, and — worse — in the help banner this tool injects when it thinks a
 * wall is up. So the check matched its own banner: once tripped, the clear-loop could never
 * succeed, and the deal was recorded as blocked and put into exponential backoff. A page
 * stating "FREE Shipping to Colombia" was reported as needing a human.
 */
class BotWallMarkerTest {

    @Test
    fun `a real interstitial is still detected`() {
        val wall = """
            <html><head><title>Amazon.com</title></head><body>
            <h4>Click the button below to continue shopping</h4>
            <button>Continue shopping</button>
            </body></html>
        """.trimIndent()
        assertTrue(botWallMarkers(wall).isNotEmpty(), "the actual wall wording must still match")
    }

    @Test
    fun `captcha wording is still detected`() {
        listOf(
            "<p>Enter the characters you see below</p>",
            "<p>Sorry, we just need to make sure you're not a robot</p>",
            """<form action="/errors/validateCaptcha">""",
            "<title>Robot Check</title>",
        ).forEach { assertTrue(botWallMarkers(it).isNotEmpty(), "should match: $it") }
    }

    @Test
    fun `the phrase alone does not constitute a wall`() {
        // Lifted from the shape of a real product page: the phrase turns up in ordinary
        // chrome and hidden markup, nowhere near an actual challenge.
        val product = """
            <html><body>
            <span id="productTitle">COSLUS Facial Cleansing Brush Face Scrubber</span>
            <div id="availability"><span>In Stock</span></div>
            <div id="deliveryBlockMessage">FREE delivery Friday, September 11 to Colombia</div>
            <div id="amazonGlobal_feature_div">No Import Charges &amp; FREE Shipping to Colombia</div>
            <a href="/cart">Continue shopping</a>
            </body></html>
        """.trimIndent()
        assertEquals(
            emptyList(), botWallMarkers(product),
            "a product page that merely contains the phrase is not a wall",
        )
    }

    @Test
    fun `our own help banner does not read as a wall`() {
        // This is the self-sustaining case. The banner names the button it is asking for, so
        // scanning it made the wall permanent: the condition announcing the problem also
        // satisfied it.
        val bannerHtml = """
            <div id="dt-help-banner">Deals Tracker needs you: click Amazon's "Continue shopping"
            button below to carry on verifying (B0FYNLTW3Y). Waiting…</div>
        """.trimIndent()
        assertEquals(
            emptyList(), botWallMarkers(bannerHtml),
            "the tool's own banner must never satisfy its own wall check",
        )
    }
}
