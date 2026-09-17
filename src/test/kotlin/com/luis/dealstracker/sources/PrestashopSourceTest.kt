package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.ProductIdentity
import com.luis.dealstracker.sources.prestashop.PrestashopSource
import com.luis.dealstracker.sources.shopify.ShopifySource
import org.jsoup.Jsoup
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture is a trimmed capture of grupomansion.com/15-refrigeracion from 2026-09-08, keeping
 * two real product tiles so the adapter's actual Jsoup selectors are exercised.
 */
class PrestashopSourceTest {

    private val source = PrestashopSource()
    private val selectors = PrestashopSource.Selectors.from(
        ProviderConfig(id = "grupomansion", type = "prestashop"),
    )

    private val base = "https://www.grupomansion.com"

    private fun tiles() = Jsoup.parse(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/grupomansion-refrigeracion.html")) {
            "missing fixture"
        }.bufferedReader().readText(),
        "$base/15-refrigeracion",
    ).select(selectors.tile)

    private fun deals() = tiles().mapNotNull {
        source.toRawDeal(it, base, "COP", selectors, "15-refrigeracion")
    }

    @Test
    fun `finds the product tiles with the default theme selectors`() {
        assertEquals(2, tiles().size)
        assertEquals(2, deals().size)
    }

    /**
     * The theme exposes an unformatted integer in `content`, which is why it is preferred over
     * the displayed "$ 3.569.000". No un-formatting, nothing to get wrong.
     */
    @Test
    fun `reads the numeric content attribute for the sale price`() {
        val freezer = deals().single { it.model == "EFC70W3HTW" }
        assertEquals("3569000.00", freezer.price.toPlainString())
        // The was-price has no content attribute, so this one does come from the text.
        assertEquals("4619000.00", freezer.listPrice!!.toPlainString())
        val pct = assertNotNull(freezer.discountPercent)
        assertTrue(pct > BigDecimal("22") && pct < BigDecimal("23"), "got $pct")
    }

    /**
     * Dots group thousands here, the opposite of Shopify where the dot is a decimal point.
     * Getting these two the wrong way round is a factor-of-100 error, so both are pinned.
     */
    @Test
    fun `dots group thousands, unlike Shopify's decimal point`() {
        assertEquals("4619000", PrestashopSource.parseCopAmount("$ 4.619.000")!!.toPlainString())
        assertEquals("1709000", PrestashopSource.parseCopAmount("$ 1.709.000")!!.toPlainString())
        assertEquals("2999000", PrestashopSource.parseCopAmount("2.999.000")!!.toPlainString())
        // Same text, other convention, other meaning.
        assertEquals("1645900.00", ShopifySource.amount("1645900.00")!!.toPlainString())
        assertNull(PrestashopSource.parseCopAmount("agotado"))
    }

    @Test
    fun `takes the product id from the url slug`() {
        assertEquals("80", PrestashopSource.productId("$base/refrigeracion/80-congelador-x-786.html"))
        assertNull(PrestashopSource.productId("$base/content/6-nuestras-tiendas"))
        assertEquals(setOf("80", "81"), deals().map { it.externalId }.toSet())
    }

    /**
     * This store appends the EAN to its own slug, which is where the cross-site key comes from:
     * `…-rs57dg4000b4co-8806095746456.html`. That is the same barcode Carulla and Olímpica
     * publish for the Samsung, so these rows join an existing cluster with no extra work.
     */
    @Test
    fun `takes the gtin from the url slug`() {
        val url = "$base/refrigeracion/284-nevecon-samsung-583-litros-rs57dg4000b4co-8806095746456.html"
        assertEquals("8806095746456", PrestashopSource.gtinFromSlug(url))
        // And it validates, so it becomes a real key rather than being discarded.
        assertEquals("08806095746456", ProductIdentity.validGtin(PrestashopSource.gtinFromSlug(url)))
    }

    @Test
    fun `no gtin in the slug yields null rather than a guess`() {
        assertNull(PrestashopSource.gtinFromSlug("$base/refrigeracion/80-congelador-electrolux.html"))
        // Too short to be a GTIN; the product id itself must not be mistaken for one.
        assertNull(PrestashopSource.gtinFromSlug("$base/refrigeracion/80-x.html"))
    }

    @Test
    fun `reads the store's manufacturer reference as the model`() {
        val freezer = deals().single { it.model == "EFC70W3HTW" }
        assertEquals("EFC70W3HTW", freezer.model)
        assertEquals("EFC70W3HTW", ProductIdentity.modelCode(freezer.model!!, freezer.brand))
    }

    /**
     * A spaced reference like "COH 251 CE BL" yields no model code, because a spaced token
     * cannot be told from marketing words. It degrades to *no* key rather than a wrong one,
     * which is the safe direction — a wrong key claims two products are the same.
     */
    @Test
    fun `a spaced reference degrades to no key rather than a wrong one`() {
        val other = deals().single { it.model == "COH 251 CE BL" }
        assertNull(ProductIdentity.modelCode(other.model!!, other.brand))
        assertNull(ProductIdentity.modelKey(other.brand, ProductIdentity.modelCode(other.model!!, other.brand)))
    }

    /**
     * The grid publishes no brand. Left null rather than guessed from the title: a wrong brand
     * makes a wrong identity key, whereas a missing one only costs a match. These rows still
     * cluster on GTIN, which this store does publish.
     */
    @Test
    fun `publishes no brand, and does not invent one`() {
        assertTrue(deals().all { it.brand == null })
    }

    @Test
    fun `resolves the lazy-loaded image rather than the placeholder`() {
        val freezer = deals().single { it.model == "EFC70W3HTW" }
        val image = assertNotNull(freezer.imageUrl)
        assertTrue(image.startsWith("https://"), image)
        // The theme leaves a data: URI svg in src until the image scrolls into view.
        assertFalse(image.startsWith("data:"), image)
    }

    @Test
    fun `ignores a list price that is not above the sale price`() {
        val html = """
            <article class="product-miniature">
              <h3 class="product-title"><a href="$base/x/9-thing-123.html">Thing</a></h3>
              <span class="product-price" content="500">$ 500</span>
              <span class="regular-price">$ 500</span>
            </article>
        """.trimIndent()
        val tile = Jsoup.parse(html, base).selectFirst(selectors.tile)!!
        val deal = assertNotNull(source.toRawDeal(tile, base, "COP", selectors))
        assertNull(deal.listPrice, "equal prices carry no discount and must stay unknown")
    }

    @Test
    fun `a tile with no price at all is dropped`() {
        val html = """
            <article class="product-miniature">
              <h3 class="product-title"><a href="$base/x/9-thing.html">Thing</a></h3>
            </article>
        """.trimIndent()
        val tile = Jsoup.parse(html, base).selectFirst(selectors.tile)!!
        assertNull(source.toRawDeal(tile, base, "COP", selectors))
    }

    @Test
    fun `selectors are overridable, since PrestaShop themes differ`() {
        val custom = PrestashopSource.Selectors.from(
            ProviderConfig(
                id = "other",
                type = "prestashop",
                options = mapOf("tileSelector" to "div.item", "priceSelector" to ".cost"),
            ),
        )
        assertEquals("div.item", custom.tile)
        assertEquals(".cost", custom.price)
        // Unspecified ones keep the default theme's values.
        assertEquals("span.regular-price", custom.listPrice)
    }

    @Test
    fun `every fixture product maps to a usable deal`() {
        deals().forEach { deal ->
            assertTrue(deal.externalId.isNotBlank())
            assertTrue(deal.title.isNotBlank())
            assertTrue(deal.url.startsWith("$base/"), deal.url)
            assertTrue(deal.price > BigDecimal.ZERO, "${deal.title} has no price")
            assertEquals("COP", deal.currency)
            assertEquals("refrigeracion", deal.category)
        }
    }
}
