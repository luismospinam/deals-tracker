package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.sources.amazon.AmazonMoney
import com.luis.dealstracker.sources.amazon.AmazonParser
import com.luis.dealstracker.sources.amazon.AmazonSelectors
import com.luis.dealstracker.sources.amazon.AmazonSource
import com.luis.dealstracker.sources.amazon.Garment
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Amazon adapter, driven entirely off captured HTML.
 *
 * Fixtures are real captures from 2026-09-17 taken with the project's user agent:
 *
 *  - `search-nike-shoes.html` — four genuine result tiles from `/s?k=nike+men+shoes`, three
 *    priced and one carrying no price, so the dropped-row path runs against real markup.
 *  - `product-price-traps.html` — the buy-box hazards that produced a $9,500 Nike.
 *
 * The variation-payload fixtures are gone with the code that read them: Amazon now filters by size
 * server-side, so there is no per-item size resolution left to test. See `SizeRefinementTest`.
 *
 * As with every other adapter here, `fetch()` is never called: it is I/O and loop control, and
 * the parsing it wraps is a pure function over this HTML.
 */
class AmazonSourceTest {

    private val source = AmazonSource()
    private val cfg = ProviderConfig(id = "amazon", type = "amazon", baseUrl = "https://www.amazon.com")
    private val selectors = AmazonSelectors.from(cfg)
    private val base = "https://www.amazon.com"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/amazon/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun hits() = AmazonParser.parseSearch(fixture("search-nike-shoes.html"), selectors, base)

    // --- search results ---





    /**
     * Absence of a stock element means available, matching every other adapter. The opposite
     * default would empty the feed the first time Amazon renamed the element.
     */
    @Test
    fun `a missing availability block reads as available`() {
        assertTrue(AmazonParser.parseAvailable("<html><body></body></html>", selectors))
        assertFalse(
            AmazonParser.parseAvailable(
                """<html><body><div id="availability">Currently unavailable</div></body></html>""",
                selectors,
            ),
        )
    }

    /**
     * Amazon's own strikethrough "List:" price, and the discount computed from it.
     *
     * The fixture is one real tile: sale $59.97 against list $65.00. Both numbers are Amazon's, so
     * the discount shown is its claim about its own former price — which is why the tracked tab
     * keeps it in a separate column from our recorded Lowest/Highest.
     */
    @Test
    fun `reads Amazon's list price and derives its advertised discount`() {
        val hit = AmazonParser
            .parseSearch(fixture("search-with-list-price.html"), selectors, base)
            .single()

        assertEquals(BigDecimal("59.97"), hit.price)
        assertEquals(BigDecimal("65.00"), hit.listPrice)

        val raw = source.toRawDeal(hit, Garment.SHOES, size = "8")
        assertEquals(BigDecimal("65.00"), raw.listPrice)
        assertEquals(BigDecimal("7.7"), raw.discountPercent)
    }

    /**
     * `a-text-price` carries `a-price` as well, so a bare `span.a-price` selector matches the
     * strikethrough list price too. On the captured page the sale price came first on all 48 tiles,
     * so it worked by accident; the selector now excludes the was-price explicitly, and this pins
     * that the SALE price is the one read.
     */
    @Test
    fun `the sale price is read, never the strikethrough list price`() {
        val hit = AmazonParser
            .parseSearch(fixture("search-with-list-price.html"), selectors, base)
            .single()
        assertEquals(BigDecimal("59.97"), hit.price, "must be the sale price, not the list price")
        assertTrue(hit.listPrice!! > hit.price, "a list price must be above the sale price")
    }

    /** Most tiles carry no list price at all, and that must stay a normal, blank outcome. */
    @Test
    fun `a tile with no list price yields null, not a zero discount`() {
        hits().forEach { hit ->
            assertNull(hit.listPrice, "the plain fixture's tiles carry no List: price")
            assertNull(
                source.toRawDeal(hit, Garment.SHOES, size = "8").discountPercent,
                "no list price means no discount — never 0%",
            )
        }
    }

    // --- the deal that results ---

    @Test
    fun `a tracked deal carries the ASIN, merchant and no list price`() {
        val deal = source.toRawDeal(hits().first { it.asin == "B0CZNT94BG" }, Garment.SHOES, size = null)
        assertEquals("B0CZNT94BG", deal.externalId)
        assertEquals("B0CZNT94BG", deal.asin, "the ASIN is what lets the shipping verifier pick this up")
        assertEquals("amazon", deal.merchant)
        // Amazon publishes no list price on a search tile, so no discount can be claimed.
        assertNull(deal.listPrice)
        assertNull(deal.discountPercent)
    }

    /**
     * Two sizes of one garment are two rows, and their titles must differ or the feed reads as
     * having duplicates. The size goes in the title because `deals` has no size column.
     */
    @Test
    fun `the size is appended to the title, and only once`() {
        assertEquals("Nike Hoodie — Medium", source.titleWithSize("Nike Hoodie", "Medium"))
        assertEquals(
            "Nike Hoodie — Medium",
            source.titleWithSize("Nike Hoodie — Medium", "Medium"),
            "re-ingesting must not keep appending the size",
        )
        assertEquals("Nike Backpack", source.titleWithSize("Nike Backpack", null))
    }

    /**
     * The rows must land in `Fashion`, not `Other` — `Other` would also hide them from a watch
     * pinned to a category. The adapter attaches a category because a search tile publishes
     * none, and `Categorizer` prefers a source's own taxonomy to guessing from a title.
     */
    @Test
    fun `tracked deals categorise as Fashion`() {
        val categorizer = Categorizer.load(File("."))
        hits().forEach { hit ->
            val deal = source.toRawDeal(hit, Garment.SHOES, size = "8")
            assertEquals(
                "Fashion",
                categorizer.categorize(deal.category, deal.title),
                "'${deal.title}' should be Fashion",
            )
        }
    }

    /**
     * The regression from the first live run: 33 televisions and toilets were stored as
     * `Fashion`.
     *
     * Two causes, both fixed. Watch terms were being injected into this provider's searches, so
     * it searched Amazon for `televisor` and `inodoro`; and the adapter attached a clothing
     * category to every row unconditionally. An unsized item now publishes no category and lets
     * `Categorizer` read the title — including when the honest answer is not Fashion.
     */
    @Test
    fun `an unsized item publishes no category rather than claiming it is clothing`() {
        assertNull(Garment.UNSIZED.category, "an unsized item must not assert a clothing category")
        assertEquals("calzado", Garment.SHOES.category)
        assertEquals("ropa", Garment.TOPS.category)

        val categorizer = Categorizer.load(File("."))
        val tv = source.toRawDeal(hits().first(), Garment.UNSIZED, size = null)
            .copy(title = "Samsung Smart TV Class HD H5000F de 32 pulgadas")
        assertEquals(
            "TV & Video",
            categorizer.categorize(tv.category, tv.title),
            "a TV reached by an unsized search must not be labelled Fashion",
        )
    }

    /** Brand-only titles must reach Fashion too, since that is what Amazon returns. */
    @Test
    fun `a brand-only athletic title categorises as Fashion on the title alone`() {
        val categorizer = Categorizer.load(File("."))
        listOf(
            "Nike Air Max Excee",
            "adidas Essentials Fleece Joggers",
            "Nike Sportswear Club Crewneck",
        ).forEach {
            assertEquals("Fashion", categorizer.categorize(null, it), "'$it' should be Fashion")
        }
    }

    /**
     * A brand element holding a product title is rejected; a genuine multi-word brand is kept.
     *
     * The fixture is two real tiles with only the brand element's text changed, so the adapter's
     * actual selectors run against Amazon's own structure.
     *
     * Three live rows had "brands" of 43, 109 and 118 characters — e.g. "Men's Lightweight Hiking
     * Pants Breathable Quick Dry Fit Waterproof…" — and each would have become its own entry in the
     * brand dropdown, once, burying Nike and adidas. The split in the data is clean: every genuine
     * brand was 22 characters or fewer, every bogus one 43 or more.
     */
    @Test
    fun `a product title in the brand element is not treated as a brand`() {
        val hits = AmazonParser.parseSearch(fixture("search-brand-cases.html"), selectors, base)
        assertEquals(2, hits.size, "both tiles must still yield a row")

        assertEquals(
            listOf("Franchise Polo by Nike"),
            hits.mapNotNull { it.brand },
            "the 22-character brand survives; the 73-character product title does not",
        )

        // The row whose brand was dropped is NOT dropped itself — only its brand is unknown.
        assertTrue(hits.all { it.price > BigDecimal.ZERO })
    }
}
