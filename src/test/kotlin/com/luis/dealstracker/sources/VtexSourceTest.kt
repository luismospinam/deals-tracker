package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.sources.vtex.BEST_DISCOUNT_ORDER
import com.luis.dealstracker.sources.vtex.CategoryTarget
import com.luis.dealstracker.sources.vtex.VtexCategory
import com.luis.dealstracker.sources.vtex.VtexProduct
import com.luis.dealstracker.sources.vtex.VtexSource
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture is a real Exito /tecnologia response captured 2026-08-23, trimmed to 3 products. */
class VtexSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = VtexSource()

    private val products: List<VtexProduct> = json.decodeFromString(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/exito-tecnologia.json")) {
            "missing fixture"
        }.bufferedReader().readText(),
    )

    @Test
    fun `parses the trimmed real response`() {
        assertEquals(3, products.size)
        assertTrue(products.all { it.productId.isNotBlank() })
    }

    @Test
    fun `computes discount from list price versus sale price`() {
        val deals = products.mapNotNull { source.toRawDeal(it, "https://www.exito.com", "COP") }
        assertEquals(3, deals.size)
        deals.forEach { deal ->
            assertNotNull(deal.listPrice, "${deal.title} should have a list price above the sale price")
            assertTrue(deal.listPrice!! > deal.price)
            val pct = deal.discountPercent
            assertNotNull(pct)
            assertTrue(pct > BigDecimal.ZERO && pct < BigDecimal(100), "implausible discount $pct")
        }
    }

    /** COP prices reach ~9.7M with no decimal part; they must survive exactly. */
    @Test
    fun `preserves large COP amounts without float drift`() {
        val iphone = products.map { source.toRawDeal(it, "https://www.exito.com", "COP")!! }
            .single { it.title.contains("Iphone 17 Pro Max") }
        assertEquals("7185900.00", iphone.price.toPlainString())
        assertEquals("9700900.00", iphone.listPrice!!.toPlainString())
        // 1 - 7185900/9700900 = 25.9256%, kept to one decimal rather than rounded to 26.
        assertEquals("25.9", iphone.discountPercent!!.toPlainString())
    }

    /**
     * Regression guard for user-visible broken links.
     *
     * VTEX's own `link` field returns the store's *internal* hostname
     * (`tienda.exito.com`, `secure.carulla.com`). Those hosts answer HTTP 200, so
     * curl says they are fine — but they serve a bare SPA shell with no
     * canonical/og:url/title, and a real browser bounces to the homepage. The URL must
     * therefore be built from the configured public host.
     */
    @Test
    fun `ignores the vtex link field and builds the url from the public host`() {
        val product = products.first()
        assertTrue(
            product.link!!.contains("tienda.exito.com"),
            "fixture must retain the internal host to keep this test meaningful",
        )
        val deal = source.toRawDeal(product, "https://www.exito.com", "COP")!!
        assertTrue(deal.url.startsWith("https://www.exito.com/"), "got ${deal.url}")
        assertTrue(!deal.url.contains("tienda."), "must not use the internal host, got ${deal.url}")
        assertEquals("https://www.exito.com/${product.linkText}/p", deal.url)
    }

    @Test
    fun `builds urls against whichever store baseUrl is configured`() {
        val raw = """
            [{"productId":"1","productName":"Thing","linkText":"thing-1","items":[
              {"itemId":"s","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":10.0,"ListPrice":20.0,"AvailableQuantity":1}}]}]}]
        """.trimIndent()
        val deal = source.toRawDeal(json.decodeFromString<List<VtexProduct>>(raw).first(), "https://www.carulla.com", "COP")!!
        assertEquals("https://www.carulla.com/thing-1/p", deal.url)
    }

    @Test
    fun `flattens the category path`() {
        val deal = source.toRawDeal(products.first(), "https://www.exito.com", "COP")!!
        val category = assertNotNull(deal.category)
        assertTrue(!category.startsWith("/"), "should be flattened, got $category")
        assertTrue(!category.endsWith("/"), "should be flattened, got $category")
        assertTrue(category.contains(" > "), "nested path should use a separator, got $category")
    }

    @Test
    fun `list price equal to sale price is treated as no discount rather than zero percent`() {
        val raw = """
            [{"productId":"1","productName":"Thing","linkText":"thing","items":[
              {"itemId":"s1","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":1000.0,"ListPrice":1000.0,"AvailableQuantity":5}}]}]}]
        """.trimIndent()
        val deal = source.toRawDeal(json.decodeFromString<List<VtexProduct>>(raw).first(), "https://x.com", "COP")!!
        // Null, not zero: "no discount published" and "0% off" are different facts,
        // and conflating them lets the discount floor treat missing data as real.
        assertNull(deal.listPrice)
        assertNull(deal.discountPercent)
    }

    @Test
    fun `picks the cheapest in-stock sku over a cheaper out-of-stock one`() {
        val raw = """
            [{"productId":"9","productName":"Multi SKU","linkText":"multi","items":[
              {"itemId":"a","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":500.0,"ListPrice":900.0,"AvailableQuantity":0}}]},
              {"itemId":"b","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":800.0,"ListPrice":900.0,"AvailableQuantity":3}}]},
              {"itemId":"c","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":950.0,"ListPrice":900.0,"AvailableQuantity":7}}]}]}]
        """.trimIndent()
        val deal = source.toRawDeal(json.decodeFromString<List<VtexProduct>>(raw).first(), "https://x.com", "COP")!!
        assertEquals("800.00", deal.price.toPlainString(), "should ignore the out-of-stock 500")
        assertTrue(deal.available)
    }

    @Test
    fun `marks fully out of stock products unavailable`() {
        val raw = """
            [{"productId":"7","productName":"Gone","linkText":"gone","items":[
              {"itemId":"a","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":100.0,"ListPrice":200.0,"AvailableQuantity":0}}]}]}]
        """.trimIndent()
        val deal = source.toRawDeal(json.decodeFromString<List<VtexProduct>>(raw).first(), "https://x.com", "COP")!!
        assertTrue(!deal.available)
    }

    @Test
    fun `skips products with no usable offer`() {
        val raw = """
            [{"productId":"5","productName":"No offer","linkText":"n","items":[
              {"itemId":"a","sellers":[{"sellerId":"1","commertialOffer":{"AvailableQuantity":3}}]}]},
             {"productId":"6","productName":"Zero price","linkText":"z","items":[
              {"itemId":"b","sellers":[{"sellerId":"1","commertialOffer":
                {"Price":0.0,"AvailableQuantity":3}}]}]}]
        """.trimIndent()
        val parsed = json.decodeFromString<List<VtexProduct>>(raw)
        assertTrue(parsed.all { source.toRawDeal(it, "https://x.com", "COP") == null })
    }

    /**
     * The regression that made this whole mechanism necessary.
     *
     * Without `O=`, VTEX returns its default order — which is best-sellers, and stable, so
     * an hourly poll re-fetched an identical set of mostly full-price products forever.
     */
    @Test
    fun `discount-ordered categories carry the sort parameter and plain ones do not`() {
        val plain = source.searchUrl("https://www.exito.com", CategoryTarget("tecnologia", null), 0, 23)
        assertEquals(
            "https://www.exito.com/api/catalog_system/pub/products/search/tecnologia?_from=0&_to=23",
            plain,
        )
        assertTrue(!plain.contains("&O="), "default order must send no O= parameter")

        val sorted = source.searchUrl(
            "https://www.exito.com",
            CategoryTarget("mercado/pollo-carne-y-pescado", BEST_DISCOUNT_ORDER),
            24,
            47,
        )
        assertEquals(
            "https://www.exito.com/api/catalog_system/pub/products/search/" +
                "mercado/pollo-carne-y-pescado?_from=24&_to=47&O=OrderByBestDiscountDESC",
            sorted,
        )
    }

    @Test
    fun `both category lists are walked, discount-sorted ones flagged as such`() {
        val cfg = ProviderConfig(
            id = "exito",
            type = "vtex",
            baseUrl = "https://www.exito.com",
            listOptions = mapOf(
                "categories" to listOf("tecnologia"),
                "bestDiscountCategories" to listOf("mercado/pollo-carne-y-pescado"),
            ),
        )
        val targets = source.targetsOf(cfg)
        assertEquals(2, targets.size)
        assertNull(targets[0].order, "plain categories keep the store's default order")
        assertEquals(BEST_DISCOUNT_ORDER, targets[1].order)
        // The log has to distinguish the two, or a misconfigured list is invisible.
        assertEquals("tecnologia", targets[0].label)
        assertTrue(targets[1].label.contains("by discount"), "got ${targets[1].label}")
    }

    /**
     * Tree urls carry the store's INTERNAL host, exactly as `VtexProduct.link` does, so only
     * the path can be compared against a configured slug.
     */
    @Test
    fun `category paths are extracted from the internal host url`() {
        assertEquals("mercado", source.categoryPath("https://tienda.exito.com/mercado"))
        assertEquals(
            "mercado/pollo-carne-y-pescado",
            source.categoryPath("https://tienda.exito.com/mercado/pollo-carne-y-pescado/"),
        )
        assertEquals("despensa", source.categoryPath("https://secure.carulla.com/despensa"))
        // A bare path (no scheme) is already what we want, and the host row itself is not
        // a category — returning "" for it would match a blank configured slug.
        assertEquals("mercado/despensa", source.categoryPath("/mercado/despensa"))
        assertNull(source.categoryPath("https://tienda.exito.com/"))
    }

    /**
     * Nested slugs are only reachable if the tree walk recurses. It previously read
     * top-level nodes only, so every `mercado/...` path would have been reported as unknown
     * — a warning firing on correct config, which trains you to ignore it.
     */
    @Test
    fun `known paths include nested categories`() {
        val tree = json.decodeFromString<List<VtexCategory>>(
            """
            [{"id":1,"name":"Mercado","url":"https://tienda.exito.com/mercado","children":[
               {"id":2,"name":"Pollo","url":"https://tienda.exito.com/mercado/pollo-carne-y-pescado",
                "children":[{"id":3,"name":"Res","url":"https://tienda.exito.com/mercado/pollo-carne-y-pescado/carne-de-res"}]},
               {"id":4,"name":"Despensa","url":"https://tienda.exito.com/mercado/despensa"}]},
             {"id":5,"name":"Tecnologia","url":"https://tienda.exito.com/tecnologia"}]
            """.trimIndent(),
        )
        val known = source.knownPaths(tree)
        assertTrue("mercado" in known)
        assertTrue("tecnologia" in known)
        assertTrue("mercado/pollo-carne-y-pescado" in known)
        assertTrue("mercado/pollo-carne-y-pescado/carne-de-res" in known, "must recurse past depth 2")
        assertTrue("mercado/despensa" in known)
        // Carulla's plural spelling must NOT validate against Exito's tree; one letter of
        // difference otherwise yields an unfiltered product list that looks legitimate.
        assertTrue("mercado/pollo-carnes-y-pescado" !in known)
    }

    @Test
    fun `tolerates unknown fields since vtex sends hundreds we ignore`() {
        val raw = """
            [{"productId":"1","productName":"Thing","linkText":"t","somethingNew":{"a":[1,2]},
              "items":[{"itemId":"s","futureField":true,"sellers":[{"sellerId":"1",
              "commertialOffer":{"Price":10.0,"ListPrice":20.0,"AvailableQuantity":1,"Extra":"x"}}]}]}]
        """.trimIndent()
        val deal = source.toRawDeal(json.decodeFromString<List<VtexProduct>>(raw).first(), "https://x.com", "COP")
        assertNotNull(deal)
        assertEquals("10.00", deal.price.toPlainString())
    }
}
