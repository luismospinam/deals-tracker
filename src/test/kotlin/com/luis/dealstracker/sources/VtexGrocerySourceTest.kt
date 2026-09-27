package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.sources.vtex.VtexGrocerySource
import com.luis.dealstracker.sources.vtex.VtexProduct
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VtexGrocerySourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = VtexGrocerySource()

    /** The value carulla.com's own store picker produces for Carulla Laureles. */
    @Test
    fun `builds the region id the store picker uses`() {
        assertEquals("U1cjY2FydWxsYTcyOQ==", VtexGrocerySource.regionId("carulla729"))
    }

    @Test
    fun `nested paths become facet paths`() {
        assertEquals(
            "category-1/mercado/category-2/despensa",
            VtexGrocerySource.facetPath("mercado/despensa"),
        )
        val url = VtexGrocerySource.searchUrl("https://www.exito.com", "abc=", "mercado/despensa", 3)
        assertTrue(url.startsWith("https://www.exito.com/api/io/_v/api/intelligent-search/product_search/category-1/mercado/category-2/despensa?"))
        // Every request carries the store and hides what it does not stock -- without these the
        // response is the national catalogue, priced 0 for some produce.
        assertTrue("regionId=abc%3D" in url, url)
        assertTrue("hideUnavailableItems=true" in url, url)
        assertTrue("page=3" in url && "count=50" in url, url)
    }

    @Test
    fun `categories carry an aisle label, shared across stores`() {
        val cfg = ProviderConfig(
            id = "carulla-grocery", type = "vtex-grocery",
            listOptions = mapOf(
                "categories" to listOf("pollo-carnes-y-pescado = Meat & fish", "/panaderia/", " "),
            ),
        )
        assertEquals(
            listOf("pollo-carnes-y-pescado" to "Meat & fish", "panaderia" to "panaderia"),
            source.groupsOf(cfg),
        )
    }

    /**
     * A real Intelligent Search product at Carulla Laureles. Nationally the same item prices at 0;
     * here it is 5,838 against a list 8,340.
     */
    @Test
    fun `maps a store-priced product with its pack size and aisle`() {
        val raw = """
            {"productId":"3249629","productName":"Mix de brotes  30  gr","brand":"VIDALIA",
             "linkText":"mix-de-brotes-vidalia-30-gr-3249629",
             "categories":["/Frutas y verduras/Verduras y hortalizas/","/Frutas y verduras/"],
             "items":[{"itemId":"1","ean":"7707","measurementUnit":"un","unitMultiplier":1,
               "sellers":[{"sellerId":"1","commertialOffer":{"Price":5838,"ListPrice":8340,"AvailableQuantity":10000}}]}]}
        """.trimIndent()
        val deal = source.toGroceryDeal(
            json.decodeFromString<VtexProduct>(raw), "https://www.carulla.com", "COP", "Fruit & veg",
        )!!
        assertEquals(BigDecimal("5838.00"), deal.price)
        assertEquals(BigDecimal("8340.00"), deal.listPrice)
        assertEquals("g", deal.unitMeasure)
        assertEquals(0, BigDecimal(30).compareTo(deal.unitQuantity))
        assertEquals("Fruit & veg", deal.groceryGroup)
        assertEquals("https://www.carulla.com/mix-de-brotes-vidalia-30-gr-3249629/p", deal.url)
    }

    /** Weighed goods are quoted per kg on Intelligent Search too, so the multiplier still applies. */
    @Test
    fun `weighed goods are priced for the pack and sized by weight`() {
        val raw = """
            {"productId":"478735","productName":"Uva Verde Sin Semilla","linkText":"uva-verde-478735",
             "items":[{"itemId":"1","measurementUnit":"kg","unitMultiplier":0.5,
               "sellers":[{"sellerId":"1","commertialOffer":{"Price":20240,"ListPrice":25300,"AvailableQuantity":10000}}]}]}
        """.trimIndent()
        val deal = source.toGroceryDeal(
            json.decodeFromString<VtexProduct>(raw), "https://www.exito.com", "COP", "Fruit & veg",
        )!!
        assertEquals(BigDecimal("10120.00"), deal.price)
        assertEquals("g", deal.unitMeasure)
        assertEquals(0, BigDecimal(500).compareTo(deal.unitQuantity))
    }
}
