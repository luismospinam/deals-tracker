package com.luis.dealstracker.verify

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the cost of reading a page region that Amazon did not render.
 *
 * This is a **timing** test, which is normally a bad idea — it earns its place because the bug it
 * guards was purely a timing bug with no effect on any verdict, so nothing else in the suite could
 * have caught it. Non-shippable items took 30s+ each while shippable ones took seconds, and the
 * verdicts were correct throughout: `innerText()` is a Playwright *action*, bounded by an action
 * timeout that `setDefaultNavigationTimeout` does not set, so it waited out its own 30s default on
 * every selector that was absent. Amazon omits the global-shipping widget on an item it refuses to
 * ship, so the items with absent regions were exactly the items that end in NO.
 *
 * Driven with `setContent` against a local DOM, so it makes no Amazon request: the behaviour under
 * test is Playwright's, not Amazon's.
 *
 * The thresholds are deliberately loose. The point is the difference between "bounded by the value
 * we passed" and "bounded by Playwright's 30s default", which is an order of magnitude — not the
 * exact millisecond count, which varies with machine load.
 */
class PageRegionTimingTest {

    /** Well under Playwright's 30s default, so a regression cannot pass by being merely slow. */
    private val readTimeoutMillis = 1_000L

    /** The real shape of a refused product page: see the table in [regionText]. */
    private val refusedPage =
        """
        <html><body>
          <div id="availability">This item cannot be shipped to your selected delivery location.</div>
          <!-- present but empty, exactly as Amazon leaves it on a refusal -->
          <div id="deliveryBlockMessage" class="a-section a-spacing-none"></div>
          <!-- #amazonGlobal_feature_div is ABSENT: Amazon renders no global-shipping widget
               for an item it will not ship. This is the element that used to cost 30s. -->
          <div id="buybox">See Similar Items</div>
        </body></html>
        """.trimIndent()

    private fun <T> withPage(html: String, block: (com.microsoft.playwright.Page) -> T): T =
        Playwright.create().use { pw ->
            val browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            try {
                val page = browser.newPage()
                // Exactly what AmazonVerifier sets -- and deliberately NOT setDefaultTimeout,
                // so this test fails if the bound is ever moved back to relying on that.
                page.setDefaultNavigationTimeout(20_000.0)
                page.setContent(html)
                block(page)
            } finally {
                browser.close()
            }
        }

    @Test
    fun `an absent region gives up quickly instead of waiting out Playwright's default`() {
        val elapsed = withPage(refusedPage) { page ->
            val t0 = System.currentTimeMillis()
            val text = regionText(page, "#amazonGlobal_feature_div", readTimeoutMillis)
            val ms = System.currentTimeMillis() - t0
            assertEquals("", text, "an absent region must read as empty")
            ms
        }
        assertTrue(
            elapsed < 10_000,
            "reading an absent region took ${elapsed}ms — it is waiting out Playwright's 30s " +
                "action default again rather than the ${readTimeoutMillis}ms bound passed in",
        )
    }

    /**
     * The verdict must be unchanged by the bound. The speedup would be worthless if it turned a
     * NO into an UNCLEAR, which is the one way this optimisation could do damage.
     */
    @Test
    fun `a refused page still reads as NO`() {
        val signals = withPage(refusedPage) { page ->
            PageSignals(
                availability = regionText(page, "#availability", readTimeoutMillis),
                deliveryBlock = regionText(page, "#deliveryBlockMessage", readTimeoutMillis),
                amazonGlobal = regionText(page, "#amazonGlobal_feature_div", readTimeoutMillis),
                buybox = regionText(page, "#buybox", readTimeoutMillis),
            )
        }
        val verdict = CoShippingExtractor().extract(signals)
        assertEquals(com.luis.dealstracker.model.CoShipping.NO, verdict.value)
        assertTrue(verdict.evidence.contains("cannot be shipped", ignoreCase = true))
    }

    /**
     * A region that is present but empty must stay fast and stay distinguishable from an absent
     * one: the extractor relies on an empty `#deliveryBlockMessage` to tell a refusal apart from a
     * page that has not finished loading.
     */
    @Test
    fun `a present region is read on its merits, empty or not`() {
        withPage(refusedPage) { page ->
            assertEquals("", regionText(page, "#deliveryBlockMessage", readTimeoutMillis))
            assertEquals("See Similar Items", regionText(page, "#buybox", readTimeoutMillis))
            assertTrue(
                regionText(page, "#availability", readTimeoutMillis).contains("cannot be shipped"),
            )
        }
    }
}
