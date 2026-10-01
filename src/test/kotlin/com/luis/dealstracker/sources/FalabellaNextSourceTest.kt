package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.ProductIdentity
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.sources.falabella.FalabellaNextSource
import com.luis.dealstracker.sources.falabella.NextQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are real `__NEXT_DATA__` islands captured 2026-09-08, trimmed to the cases that
 * matter: the shared Electrolux ERB163HB, a model code containing a slash, a Homecenter row
 * with no discount, and a Falabella row carrying a `cmrPrice`.
 */
class FalabellaNextSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = FalabellaNextSource()

    private fun island(name: String): JsonObject =
        json.parseToJsonElement(
            requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
                .bufferedReader().readText(),
        ).jsonObject

    private val homecenter get() = source.readResults(island("homecenter-neveras.json"), "searchProps.searchData")
    private val falabella get() = source.readResults(island("falabella-nevera.json"), "")

    /**
     * Homecenter rows carrying a CMR price, captured 2026-09-29 from `cat90039/combos-sanitarios`:
     * the Sensi Dacqua toilet `487197` the operator asked about (589,900 CMR / 799,900 cash), a
     * shallower Corona combo, a cash-only row, and the nevera whose CMR benefit is a basket
     * threshold rather than a price. A separate fixture because the neveras capture has no CMR row
     * at all — that listing publishes one on 0 of 40 rows, which is itself the measurement.
     */
    private val cmrIsland get() = source.readResults(island("homecenter-cmr.json"), "searchProps.searchData")

    private fun cmrDeals() = cmrIsland.mapNotNull {
        source.toRawDeal(it, "https://www.homecenter.com.co", "homecenter-co", "COP")
    }

    private fun hcDeals() = homecenter.mapNotNull {
        source.toRawDeal(it, "https://www.homecenter.com.co", "homecenter-co", "COP")
    }

    private fun fbDeals() = falabella.mapNotNull {
        source.toRawDeal(it, "https://www.falabella.com.co", "falabella-co", "COP")
    }

    // --- locating the results ---

    @Test
    fun `finds results at each store's own depth`() {
        assertEquals(4, homecenter.size, "homecenter nests results under searchProps.searchData")
        assertEquals(2, falabella.size, "falabella puts results directly on pageProps")
    }

    @Test
    fun `a wrong results path yields nothing rather than throwing`() {
        // A frontend rewrite should surface as "no products", which the adapter logs, not as a
        // crash that takes the whole poll cycle down.
        assertTrue(source.readResults(island("falabella-nevera.json"), "nope.missing").isEmpty())
    }

    @Test
    fun `extracts the json island from a real page and rejects a page without one`() {
        val html = """<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{}}}</script></body></html>"""
        assertNotNull(source.extractNextData(html))
        assertNull(source.extractNextData("<html><body>no island here</body></html>"))
    }

    // --- the cmrPrice trap ---

    /**
     * The single most important assertion in this file. `cmrPrice` is the price with
     * Falabella's own store credit card and it is often the lowest number in the array — it
     * appears on 32 of 56 real rows. Reading it as the sale price would understate Falabella
     * systematically, which would not merely be inaccurate: it would make Falabella win every
     * cross-site comparison it takes part in.
     */
    @Test
    fun `never reads the store credit-card price as the sale price`() {
        val electrolux = falabella.single { "ERB163HB" in it.toString() }
        val prices = electrolux["prices"] as JsonArray
        // The fixture really does contain the cheaper cmr price, or this proves nothing.
        assertTrue(prices.toString().contains("cmrPrice"), "fixture must carry a cmrPrice")
        assertTrue(prices.toString().contains("969.900"), "fixture must carry the cheaper cmr amount")

        val offer = assertNotNull(source.resolveOffer(prices))
        assertEquals("999900.00", offer.price.toPlainString(), "must be the eventPrice, not the 969.900 cmr price")
        assertEquals("1829900.00", offer.listPrice!!.toPlainString())
    }

    @Test
    fun `prefers an event price over the standing internet price`() {
        val prices = json.parseToJsonElement(
            """[{"type":"cmrPrice","price":["100"]},
                {"type":"internetPrice","price":["300"]},
                {"type":"eventPrice","price":["200"]},
                {"type":"normalPrice","crossed":true,"price":["500"]}]""",
        ) as JsonArray
        val offer = assertNotNull(source.resolveOffer(prices))
        assertEquals("200.00", offer.price.toPlainString())
        assertEquals("500.00", offer.listPrice!!.toPlainString())
    }

    // --- capturing the CMR price without selling at it ---

    /**
     * The other half of the trap above. Discarding `cmrPrice` from the sale price is right; losing
     * it altogether threw away a real saving — the Sensi Dacqua toilet is 799,900 cash and 589,900
     * with CMR at both stores, and that 210,000 was invisible.
     */
    @Test
    fun `captures the card price while still selling at the cash price`() {
        val toilet = cmrDeals().single { it.externalId == "487197" }
        assertEquals("799900.00", toilet.price.toPlainString(), "the sale price is what anyone pays")
        val card = assertNotNull(toilet.cardOffer, "the CMR price must be captured")
        assertEquals("589900.00", card.price.toPlainString())
        assertEquals(CardScope.CMR, card.scope)
    }

    /**
     * Falabella spells it `cmrPrice`, Homecenter spells it `CMR`, and one adapter serves both.
     * Accepting only one name would silently halve the coverage.
     */
    @Test
    fun `reads the card price under both of the names the two storefronts use`() {
        // The fixtures really do disagree about the name, or this proves nothing.
        assertTrue(cmrIsland.toString().contains("\"CMR\""), "homecenter fixture must say CMR")
        assertTrue(falabella.toString().contains("cmrPrice"), "falabella fixture must say cmrPrice")

        assertEquals("589900.00", assertNotNull(cmrDeals().single { it.externalId == "487197" }.cardOffer).price.toPlainString())
        assertEquals("969900.00", assertNotNull(fbDeals().single { it.externalId == "136275851" }.cardOffer).price.toPlainString())
    }

    /**
     * The percentage is derived here, the mirror image of VTEX where it is published and the price
     * is computed. It must be measured against the price we actually store: the Sensi Dacqua CMR
     * saving is 26.25% against the 799,900 cash price, and would read as a far larger number
     * against a crossed-out "normal" price — which is the shelf discount the Off column already
     * reports, not what the card saves.
     */
    @Test
    fun `derives the percentage against the cash price, not the crossed-out one`() {
        val prices = json.parseToJsonElement(
            """[{"type":"cmrPrice","price":["969.900"]},
                {"type":"eventPrice","price":["999.900"]},
                {"type":"normalPrice","crossed":true,"price":["1.829.900"]}]""",
        ) as JsonArray
        val card = assertNotNull(source.cardOfferOf(prices, BigDecimal("999900.00")))
        // (999900 - 969900) / 999900 = 3.00%. Against the 1,829,900 normalPrice it would be 47%.
        assertEquals("3.00", card.percent.toPlainString())
    }

    /**
     * Homecenter also advertises CMR as a basket threshold — "Por compras superiores a $1.699.900
     * … Ahorra $150.000 pagando con tu CMR" — in `multiPurposeIcon`. That is a condition on a whole
     * order, not a price for this product, so it must produce nothing.
     */
    @Test
    fun `a basket-threshold CMR blurb is not a card price`() {
        val row = cmrIsland.single { it.toString().contains("compras superiores") }
        assertTrue(row.toString().contains("pagando con tu CMR"), "fixture must carry the blurb")
        assertNull(
            source.toRawDeal(row, "https://www.homecenter.com.co", "homecenter-co", "COP")!!.cardOffer,
            "a spend threshold is not this product's price",
        )
    }

    @Test
    fun `a row with no card price at all gets none`() {
        assertNull(cmrDeals().single { it.externalId == "707218" }.cardOffer)
    }

    @Test
    fun `a card price that does not beat the cash price is dropped`() {
        val equal = json.parseToJsonElement("""[{"type":"CMR","priceWithoutFormatting":500}]""") as JsonArray
        assertNull(source.cardOfferOf(equal, BigDecimal("500.00")), "equal saves nothing")

        val dearer = json.parseToJsonElement("""[{"type":"CMR","priceWithoutFormatting":600}]""") as JsonArray
        assertNull(source.cardOfferOf(dearer, BigDecimal("500.00")), "dearer is not a discount")

        assertNull(source.cardOfferOf(null, BigDecimal("500.00")))
    }

    /**
     * `card_percent` is `CHECK (> 0 AND < 100)`, so a saving too small to survive two decimals
     * would fail the INSERT and lose the whole observation. Dropped here instead.
     */
    @Test
    fun `a saving too small to express as a percentage is dropped rather than written as zero`() {
        val prices = json.parseToJsonElement("""[{"type":"CMR","priceWithoutFormatting":9999999}]""") as JsonArray
        assertNull(source.cardOfferOf(prices, BigDecimal("10000000.00")))
    }

    // --- prices per store ---

    @Test
    fun `reads Homecenter's numeric price and Falabella's dotted string identically`() {
        val hc = hcDeals().single { it.title.contains("Electrolux", ignoreCase = true) }
        assertEquals("1074900.00", hc.price.toPlainString())
        assertEquals("1349900.00", hc.listPrice!!.toPlainString())

        val fb = fbDeals().single { it.brand == "LG" }
        assertEquals("6269900.00", fb.price.toPlainString())
        assertEquals("10999900.00", fb.listPrice!!.toPlainString())
    }

    /** COP has eight-digit prices; a binary float is exactly the drift NUMERIC(14,2) prevents. */
    @Test
    fun `parses dot-grouped COP amounts exactly`() {
        assertEquals("10999900", FalabellaNextSource.parseCopAmount("10.999.900")!!.toPlainString())
        assertEquals("999900", FalabellaNextSource.parseCopAmount("999.900")!!.toPlainString())
        assertEquals("6269900", FalabellaNextSource.parseCopAmount("$ 6.269.900")!!.toPlainString())
        // A comma is the decimal separator in the same convention, not a thousands group.
        assertEquals("1829900.50", FalabellaNextSource.parseCopAmount("1.829.900,50")!!.toPlainString())
        assertNull(FalabellaNextSource.parseCopAmount(""))
        assertNull(FalabellaNextSource.parseCopAmount("n/a"))
    }

    /**
     * 5 of 47 real Homecenter rows carry only a NORMAL price. That is the selling price and
     * there is no discount — reporting it as both would invent a 0% discount, and this app's
     * rule is that blank means unknown, never zero.
     */
    @Test
    fun `a list price with no sale price is the selling price, with no discount`() {
        val undiscounted = hcDeals().single { it.listPrice == null }
        assertEquals("1899900.00", undiscounted.price.toPlainString())
        assertNull(undiscounted.discountPercent)
    }

    @Test
    fun `ignores a list price that is not above the sale price`() {
        val prices = json.parseToJsonElement(
            """[{"type":"INTERNET","priceWithoutFormatting":500},
                {"type":"NORMAL","priceWithoutFormatting":500}]""",
        ) as JsonArray
        assertNull(assertNotNull(source.resolveOffer(prices)).listPrice)
    }

    @Test
    fun `a record with no prices at all is dropped`() {
        assertNull(source.resolveOffer(null))
        assertNull(source.resolveOffer(json.parseToJsonElement("[]") as JsonArray))
        // cmr-only is the same as no usable price: nobody without the card can pay it.
        val cmrOnly = json.parseToJsonElement("""[{"type":"cmrPrice","price":["100"]}]""") as JsonArray
        assertNull(source.resolveOffer(cmrOnly))
    }

    // --- urls and identity ---

    @Test
    fun `uses Falabella's absolute url and builds Homecenter's from the sku`() {
        val fb = fbDeals().single { it.brand == "LG" }
        assertTrue(fb.url.startsWith("https://www.falabella.com.co/falabella-co/product/"), fb.url)

        val hc = hcDeals().single { it.title.contains("Electrolux", ignoreCase = true) }
        // Homecenter publishes no url; /product/<skuId>/ redirects to the canonical slug.
        assertEquals("https://www.homecenter.com.co/homecenter-co/product/793947/", hc.url)
    }

    @Test
    fun `carries brand, and Homecenter's published model`() {
        val hc = hcDeals().single { it.title.contains("Electrolux", ignoreCase = true) }
        assertEquals("Electrolux", hc.brand)
        assertEquals("ERB163HB", hc.model)

        // Falabella publishes no model field, so it must fall back to the title.
        val fb = fbDeals().single { it.brand == "ELECTROLUX" }
        assertNull(fb.model)
        assertEquals("ERB163HB", ProductIdentity.modelCode(fb.title, fb.brand))
    }

    /**
     * The payoff: this exact fridge is listed at Homecenter, Falabella and Exito, and the three
     * describe it differently. All must reduce to one key or the cross-site comparison cannot
     * see them as the same product.
     */
    @Test
    fun `the same fridge reduces to one key across three retailers`() {
        val hc = hcDeals().single { it.title.contains("Electrolux", ignoreCase = true) }
        val fb = fbDeals().single { it.brand == "ELECTROLUX" }

        val hcKey = ProductIdentity.matchKey(hc.gtin, hc.brand, ProductIdentity.modelCode(hc.model!!, hc.brand))
        val fbKey = ProductIdentity.matchKey(fb.gtin, fb.brand, ProductIdentity.modelCode(fb.title, fb.brand))
        // Exito's own wording, from its live feed.
        val exitoKey = ProductIdentity.matchKey(
            null,
            "ELECTROLUX",
            ProductIdentity.modelCode("NEVERA ELECTROLUX Frost Tipo europeo 173 Litros ERB163HB ERB163H", "ELECTROLUX"),
        )

        assertEquals("mdl:electrolux|erb163hb", hcKey)
        assertEquals(hcKey, fbKey, "Homecenter and Falabella must agree")
        assertEquals(hcKey, exitoKey, "Exito must agree too")
    }

    /**
     * Homecenter sends "RS23T5B00S9/CO" where another retailer's title yields "RS23T5B00S9".
     * Running a published model through the same extractor as a title is what makes those the
     * same key instead of two.
     */
    @Test
    fun `a published model with a country suffix canonicalises like a title-derived one`() {
        val samsung = hcDeals().single { it.brand == "Samsung" }
        assertEquals("RS23T5B00S9/CO", samsung.model)
        assertEquals("RS23T5B00S9", ProductIdentity.modelCode(samsung.model!!, samsung.brand))
    }

    @Test
    fun `records the marketplace seller rather than assuming the storefront sells it`() {
        // Both storefronts carry third-party sellers, so the retailer in the URL is not
        // necessarily who you are buying from -- and a Falabella listing sold by Homecenter is
        // not an independent price to compare against Homecenter.
        val fb = fbDeals().single { it.brand == "ELECTROLUX" }
        assertEquals("Electrolux Group", fb.merchant)
    }

    // --- query building ---

    private fun cfg(
        categories: List<String> = emptyList(),
        searches: List<String> = emptyList(),
        searchEnabled: String? = null,
    ) = ProviderConfig(
        id = "homecenter",
        type = "falabella-next",
        baseUrl = "https://www.homecenter.com.co",
        options = buildMap { searchEnabled?.let { put("searchEnabled", it) } },
        listOptions = mapOf("categories" to categories, "searches" to searches),
    )

    /**
     * Robots, not preference: Homecenter disallows its search URLs. `IngestService` injects
     * every active watch's terms into `searches` for *every* provider, so a store that did not
     * gate this would start requesting disallowed URLs the moment a watch existed.
     */
    @Test
    fun `ignores search terms unless the store opts in`() {
        val gated = source.buildQueries(cfg(categories = listOf("category/cat10850/neveras"), searches = listOf("nevera")))
        assertEquals(1, gated.size, "search must be off by default")
        assertEquals(NextQuery.Kind.CATEGORY, gated.single().kind)

        val allowed = source.buildQueries(cfg(searches = listOf("nevera"), searchEnabled = "true"))
        assertEquals(1, allowed.size)
        assertEquals(NextQuery.Kind.SEARCH, allowed.single().kind)
    }

    @Test
    fun `builds category and search urls with paging`() {
        val category = source.buildQueries(cfg(categories = listOf("category/cat10850/neveras-y-nevecones"))).single()
        assertEquals(
            "https://www.homecenter.com.co/homecenter-co/category/cat10850/neveras-y-nevecones?page=2",
            category.url("https://www.homecenter.com.co", "homecenter-co", 2),
        )

        val search = source.buildQueries(cfg(searches = listOf("nevera no frost"), searchEnabled = "true")).single()
        assertEquals(
            "https://www.falabella.com.co/falabella-co/search?Ntt=nevera%20no%20frost&page=1",
            search.url("https://www.falabella.com.co", "falabella-co", 1),
        )
    }

    @Test
    fun `every fixture product maps to a usable deal`() {
        val all = hcDeals() + fbDeals()
        assertEquals(6, all.size)
        all.forEach { deal ->
            assertTrue(deal.externalId.isNotBlank(), "missing id: ${deal.title}")
            assertTrue(deal.title.isNotBlank())
            assertTrue(deal.url.startsWith("https://"), deal.url)
            assertTrue(deal.price > BigDecimal.ZERO, "${deal.title} has no price")
            assertEquals("COP", deal.currency)
            assertNotNull(deal.imageUrl, "${deal.title} has no image")
        }
    }
}
