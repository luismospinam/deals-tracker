package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.sources.vtex.CategoryTarget
import com.luis.dealstracker.sources.vtex.IsSearchResponse
import com.luis.dealstracker.sources.vtex.VtexSource
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Easy's card prices, which arrive in a shape unlike Exito's and Carulla's.
 *
 * Fixture: real Intelligent Search offers captured 2026-09-29 from easy.com.co, trimmed to five
 * rows that cover every case — the Milán toilet `1505557` the operator asked about (299,900 cash,
 * 269,900 with TMC, and a second teaser that merely restates the cash price), a fridge whose teaser
 * is good on both cards, a washer-dryer on Codensa only, a lavamanos whose only teaser is a
 * percentage, and a vanity with no teaser at all. Every expected price was read off the store's own
 * page the same day.
 *
 * Exito and Carulla are covered by [CardOfferTest] and must keep passing untouched — their shape
 * (`PaymentMethodId` + `PercentualDiscount`) is a different branch of the same function.
 */
class EasyCardOfferTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = VtexSource()
    private val products = json.decodeFromString<IsSearchResponse>(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/easy-tmc.json")).bufferedReader().readText(),
    ).products.associateBy { it.productId }

    private fun deal(id: String) =
        source.toRawDeal(products.getValue(id), "https://www.easy.com.co", "COP")!!

    /**
     * The row that started this. `PRECMAX` is *precio máximo* — the teaser publishes the price to
     * pay, not a discount — so the percentage is derived against the cash price.
     */
    @Test
    fun `the milan toilet is captured at its TMC price`() {
        val d = deal("1505557")
        assertEquals(BigDecimal("299900.00"), d.price, "the cash price stays the price")
        val card = assertNotNull(d.cardOffer, "the TMC price must be captured")
        assertEquals(BigDecimal("269900.00"), card.price)
        // (299,900 - 269,900) / 299,900 = 10.00%
        assertEquals(BigDecimal("10.00"), card.percent)
        assertEquals(CardScope.TMC, card.scope)
    }

    /**
     * That same row carries two caps, 269,900 and 299,900. The second equals the cash price and
     * saves nothing, so the cheaper one has to win — this is why the strict `<` matters here
     * rather than being defensive boilerplate.
     */
    @Test
    fun `a cap that only restates the cash price never wins`() {
        val raw = products.getValue("1505557").items.first().sellers.first().commertialOffer!!
        val caps = raw.teasers.orEmpty().mapNotNull { t ->
            t.effects?.parameters?.firstOrNull { it.name == "MaximumUnitPriceDiscount" }?.value
        }
        assertTrue(caps.size >= 2, "fixture must carry both caps to prove anything: $caps")
        assertTrue(caps.any { it == "29990000" }, "fixture must carry the 299,900 cap: $caps")
        assertEquals(BigDecimal("269900.00"), deal("1505557").cardOffer?.price)
    }

    /** `COD Y TMC` — good on either card. Recorded as the one in hand. */
    @Test
    fun `a teaser good on both cards is recorded as TMC`() {
        val card = assertNotNull(deal("1437399").cardOffer)
        assertEquals(CardScope.TMC, card.scope)
        // Cheapest of its three caps.
        assertEquals(BigDecimal("1549900.00"), card.price)
    }

    @Test
    fun `a Codensa-only teaser is its own scope`() {
        val card = assertNotNull(deal("1448501").cardOffer)
        assertEquals(CardScope.CODENSA, card.scope)
        assertEquals(BigDecimal("1699900.00"), card.price)
    }

    /**
     * The deliberate omission, and the one most likely to be "fixed" later. A BIN-restricted
     * percentage teaser is frequently ALREADY APPLIED in the shelf price — Combo Tairona is 411,920
     * against a `PriceWithoutDiscount` of 514,900 with a 20% teaser, and 514,900 × 0.80 reproduces
     * 411,920 exactly (7 of 32 such rows did that on 2026-09-29). Others compute to more than the
     * cash price. With no way to tell a live one from a spent one, none is taken.
     */
    @Test
    fun `a percentage teaser restricted by card bin yields no card price`() {
        val offer = products.getValue("1422852").items.first().sellers.first().commertialOffer!!
        assertTrue(
            offer.teasers.orEmpty().any { t ->
                t.effects?.parameters?.any { it.name == "PercentualDiscount" } == true
            },
            "fixture row must carry a percentage teaser, or this proves nothing",
        )
        assertNull(deal("1422852").cardOffer, "a bin-restricted percentage is not a card price")
    }

    @Test
    fun `a row with no teaser has no card price`() {
        assertNull(deal("1141712").cardOffer)
    }

    // --- the two APIs ---

    private fun cfg(vararg options: Pair<String, String>) = ProviderConfig(
        id = "easy", type = "vtex", enabled = true,
        baseUrl = "https://www.easy.com.co", currency = "COP",
        options = mapOf(*options),
    )

    /**
     * The switch is per provider and off by default. `catalog_system` never fills in `teasers`, so
     * Easy needs Intelligent Search; Exito must NOT be moved onto it, because it answers
     * `query=nevera` with nothing while `ft=nevera` returns results.
     */
    @Test
    fun `the api is chosen per provider and defaults to catalog_system`() {
        assertNull(cfg().options["useIntelligentSearch"], "no option means the old API")
        assertEquals("true", cfg("useIntelligentSearch" to "true").options["useIntelligentSearch"])
    }

    @Test
    fun `a category becomes a facet path and a search becomes a query`() {
        val base = "https://www.easy.com.co"
        val category = CategoryTarget("banos", null)
        assertEquals(
            "$base/api/io/_v/api/intelligent-search/product_search/category-1/banos?count=24&page=1",
            source.intelligentSearchUrl(base, category, page = 1, count = 24),
        )
        assertEquals(
            "$base/api/io/_v/api/intelligent-search/product_search/?query=freidora%20de%20aire&count=24&page=3",
            source.intelligentSearchUrl(
                base,
                CategoryTarget(path = "", order = null, term = "freidora de aire"),
                page = 3,
                count = 24,
            ),
        )
        // Neither store pinning nor server-side stock filtering: this adapter marks an
        // out-of-stock row rather than letting it vanish from the feed.
        val url = source.intelligentSearchUrl(base, category, page = 1, count = 24)
        assertTrue("regionId" !in url, url)
        assertTrue("hideUnavailableItems" !in url, url)
    }

    /** The old URL form is untouched, so every other vtex store keeps its exact requests. */
    @Test
    fun `the catalog_system url is unchanged`() {
        val base = "https://www.exito.com"
        assertEquals(
            "$base/api/catalog_system/pub/products/search/tecnologia?_from=0&_to=23",
            source.searchUrl(base, CategoryTarget("tecnologia", null), 0, 23),
        )
    }
}
