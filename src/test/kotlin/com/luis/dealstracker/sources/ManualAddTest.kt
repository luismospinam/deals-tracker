package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.sources.amazon.AmazonParser
import com.luis.dealstracker.sources.amazon.AmazonSelectors
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Adding a tracked item from a pasted URL.
 *
 * The fixture is a trimmed real capture of a New Balance boxer-brief page, 2026-09-22. Everything
 * the form needs comes off that one page, which is why a manual add costs a single request.
 */
class ManualAddTest {

    private val selectors = AmazonSelectors.from(
        ProviderConfig(id = "amazon", type = "amazon", baseUrl = "https://www.amazon.com"),
    )

    private val page by lazy {
        requireNotNull(javaClass.getResourceAsStream("/fixtures/amazon/product-manual-add.html")) {
            "missing fixture"
        }.bufferedReader().readText()
    }

    // --- the URL a person pastes ---

    /** The shapes actually pasted from a browser, including the `?th=1&psc=1` twist parameters. */
    @Test
    fun `reads the ASIN from the URL forms a browser produces`() {
        val expected = "B074JLDHK3"
        listOf(
            "https://www.amazon.com/dp/B074JLDHK3",
            "https://www.amazon.com/dp/B074JLDHK3?th=1&psc=1",
            "https://www.amazon.com/gp/product/B074JLDHK3",
            "https://www.amazon.com/New-Balance-Mens-Trunks/dp/B074JLDHK3/ref=sr_1_3?dib=eyJ2IjoiMSJ9",
            "  https://www.amazon.com/dp/B074JLDHK3/  ",
        ).forEach { assertEquals(expected, AmazonParser.asinFromUrl(it), "failed on $it") }
    }

    /**
     * A tracking payload is full of ten-character tokens, so the match is anchored on the path
     * segment rather than scanning the whole string.
     */
    @Test
    fun `does not mistake a tracking token for an ASIN`() {
        assertNull(AmazonParser.asinFromUrl("https://www.amazon.com/s?k=nike&dib=EYJ2IJOIMSJ9"))
        assertNull(AmazonParser.asinFromUrl("https://www.amazon.com/"))
        assertNull(AmazonParser.asinFromUrl("not a url at all"))
    }

    // --- what the page yields, so you rarely have to type anything ---

    /**
     * There is no dedicated brand element: `#bylineInfo` holds the prose "Visit the New Balance
     * Store". Every more obvious route — a `brandName` json field, a Brand row in the detail table,
     * an attribute list — was absent on the live page.
     */
    @Test
    fun `parses the brand out of the byline prose`() {
        assertEquals("New Balance", AmazonParser.parseBrand(page))
    }

    /** No store link means no brand. A wrong brand makes a wrong identity key, so this never guesses. */
    @Test
    fun `reports no brand rather than guessing one`() {
        assertNull(AmazonParser.parseBrand("<html><body><span id='bylineInfo'>Brand: Nike</span></body></html>"))
    }

    /**
     * The breadcrumb is handed to `Categorizer` as a source-published path, which it prefers over
     * guessing from a title.
     */
    @Test
    fun `parses the breadcrumb as the category path`() {
        assertEquals(
            "Clothing, Shoes & Jewelry > Men > Clothing > Underwear",
            AmazonParser.parseBreadcrumb(page),
        )
    }

    /**
     * The size this ASIN actually is — the whole reason a manual add is more trustworthy than the
     * search path.
     *
     * Amazon's size FILTER returns a representative of the product family rather than strictly the
     * matching child: a size-8 search returned the 8.5 ASIN at $130.26 while size 8 sold for
     * $107.41. Here the ASIN is given, so its own variation entry settles it.
     */
    @Test
    fun `reads the size Amazon records for this exact ASIN`() {
        assertEquals("Small", AmazonParser.parseOwnSize(page))
    }

    @Test
    fun `an unsized listing reports no size rather than inventing one`() {
        assertNull(AmazonParser.parseOwnSize("<html><body>no twister payload here</body></html>"))
    }

    /**
     * The per-unit trap: a multipack shows two prices and the cheaper one is not the price.
     *
     * A New Balance 3-pack put `$6.33` in `.apex-priceperunit-value` ("$6.33 per count") and
     * `$18.98` in `.apex-pricetopay-value`. The item was stored at $6.33, which made a 3-pack look
     * like a bargain and — because the searches ALSO fetched it — left a history alternating between
     * the two values, fabricating price movement that never happened.
     *
     * Two traps in one page, both reproduced by the fixture: the per-unit block comes first in
     * document order, AND the pricetopay block's own `.a-offscreen` span is empty, so the shortcut
     * used everywhere else finds nothing and the loose alternatives get reached.
     */
    @Test
    fun `a multipack's per-unit price is never read as the price`() {
        val page = requireNotNull(
            javaClass.getResourceAsStream("/fixtures/amazon/product-price-per-unit.html"),
        ) { "missing fixture" }.bufferedReader().readText()

        val (amount, currency) = assertNotNull(AmazonParser.parsePrice(page, selectors))
        assertEquals(BigDecimal("18.98"), amount, "must be the price to pay, not \$6.33 per count")
        assertEquals("USD", currency)
    }

    // --- and the price, which is the point ---

    @Test
    fun `reads the buy-box price and title`() {
        val (amount, currency) = assertNotNull(AmazonParser.parsePrice(page, selectors))
        // 18.98, not 6.33: the page also shows "$6.33 per count" as a per-unit figure, and the
        // price to pay is what an item costs.
        assertEquals(BigDecimal("18.98"), amount)
        assertEquals("USD", currency)

        assertNotNull(AmazonParser.parseTitle(page, selectors)).also {
            assertEquals(true, it.contains("Boxer Brief", ignoreCase = true))
        }
        assertEquals(true, AmazonParser.looksLikeProductPage(page))
    }
}
