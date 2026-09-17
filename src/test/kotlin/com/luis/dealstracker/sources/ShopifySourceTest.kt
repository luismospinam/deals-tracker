package com.luis.dealstracker.sources

import com.luis.dealstracker.ingest.ProductIdentity
import com.luis.dealstracker.sources.falabella.FalabellaNextSource
import com.luis.dealstracker.sources.shopify.ShopifyFeed
import com.luis.dealstracker.sources.shopify.ShopifySource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture is a real `/collections/neveras/products.json` from fullhogar.com.co captured
 * 2026-09-08, trimmed to three products: two in stock and one out of stock.
 */
class ShopifySourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = ShopifySource()

    private val products = json.decodeFromString<ShopifyFeed>(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/fullhogar-neveras.json")) { "missing fixture" }
            .bufferedReader().readText(),
    ).products

    private fun deals() = products.mapNotNull {
        source.toRawDeal(it, "https://www.fullhogar.com.co", "COP", "neveras")
    }

    @Test
    fun `parses the real feed`() {
        assertEquals(3, products.size)
        assertTrue(products.all { it.id > 0 && it.title.isNotBlank() })
    }

    /**
     * The trap this adapter is most likely to be broken by. Shopify writes `"1645900.00"`
     * where the dot is a **decimal point**; the Falabella adapter reads `"1.829.900"` where
     * dots **group thousands**. The strings look alike and mean opposite things, so applying
     * either parser to the other's data is off by a factor of 100.
     */
    @Test
    fun `a Shopify decimal point is not a thousands separator`() {
        assertEquals("1645900.00", ShopifySource.amount("1645900.00")!!.toPlainString())
        // The same text through the Falabella parser, to make the difference explicit and to
        // fail loudly if either convention is ever "unified".
        assertEquals("164590000", FalabellaNextSource.parseCopAmount("1645900.00")!!.toPlainString())
    }

    @Test
    fun `reads price and the compare-at price as the list price`() {
        val lg = deals().single { it.brand == "LG" }
        assertEquals("1599900.00", lg.price.toPlainString())
        assertEquals("1819900.00", lg.listPrice!!.toPlainString())
        val pct = assertNotNull(lg.discountPercent)
        assertTrue(pct > java.math.BigDecimal("11") && pct < java.math.BigDecimal("13"), "got $pct")
    }

    @Test
    fun `ignores a missing or non-superior compare-at price`() {
        assertNull(ShopifySource.amount(null))
        assertNull(ShopifySource.amount(""))
        // "0.00" is Shopify's way of saying there is no was-price; it must not read as free.
        assertNull(ShopifySource.amount("0.00"))
        assertNull(ShopifySource.amount("not-a-price"))
    }

    /**
     * Many rows in the live feed are out of stock. Advertising a price you cannot buy is worse
     * than omitting it, and it would also poison the cross-site comparison with a phantom
     * cheaper rival.
     */
    @Test
    fun `marks an out-of-stock product unavailable while still reading its price`() {
        val haceb = deals().single { it.brand == "Haceb" }
        assertEquals(false, haceb.available)
        // The price is still parsed, so the row is filtered by `available` rather than lost
        // silently to a null.
        assertEquals("1699900.00", haceb.price.toPlainString())

        assertTrue(deals().single { it.brand == "LG" }.available)
    }

    @Test
    fun `builds the product url from the handle`() {
        val lg = deals().single { it.brand == "LG" }
        assertTrue(lg.url.startsWith("https://www.fullhogar.com.co/products/"), lg.url)
        assertTrue(lg.url.endsWith("vt22bpy-gris") || lg.url.contains("nevera"), lg.url)
    }

    @Test
    fun `falls back to the collection when product_type is blank`() {
        // product_type is empty on every row observed, and a blank category forfeits
        // Categorizer's preference for a retailer's own taxonomy over title guessing.
        assertTrue(products.all { it.productType.isNullOrBlank() }, "fixture should have blank types")
        assertTrue(deals().all { it.category == "neveras" })
    }

    @Test
    fun `carries brand, and derives a model code from the title`() {
        val lg = deals().single { it.brand == "LG" }
        // No barcode in the public feed, so identity rests on brand plus the title's code.
        assertNull(lg.gtin)
        assertEquals("VT22BPY", ProductIdentity.modelCode(lg.title, lg.brand))
        assertEquals("mdl:lg|vt22bpy", ProductIdentity.matchKey(lg.gtin, lg.brand, "VT22BPY"))
    }

    @Test
    fun `every fixture product maps to a usable deal`() {
        val all = deals()
        assertEquals(3, all.size)
        all.forEach { deal ->
            assertTrue(deal.externalId.isNotBlank())
            assertTrue(deal.url.startsWith("https://"), deal.url)
            assertTrue(deal.price > java.math.BigDecimal.ZERO, "${deal.title} has no price")
            assertEquals("COP", deal.currency)
            assertNotNull(deal.brand, "${deal.title} has no brand")
            assertNotNull(deal.imageUrl, "${deal.title} has no image")
        }
    }
}
