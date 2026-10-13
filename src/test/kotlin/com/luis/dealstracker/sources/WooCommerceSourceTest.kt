package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.woocommerce.WooCommerceSource
import com.luis.dealstracker.sources.woocommerce.WooPrices
import com.luis.dealstracker.sources.woocommerce.WooProduct
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture is a trimmed real `/wp-json/wc/store/v1/products?category=salas` response from
 * kaiuhome.com, captured 2026-10-06.
 */
class WooCommerceSourceTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = WooCommerceSource()
    private val base = "https://www.kaiuhome.com"

    private val products = json.decodeFromString<List<WooProduct>>(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/kaiuhome-salas.json")) { "missing fixture" }
            .bufferedReader().readText(),
    )

    private fun deals() = products.mapNotNull { source.toRawDeal(it, base, "COP", "salas") }

    @Test
    fun `reads price, the was-price as list price, image and the most specific category`() {
        val sofa = deals().single { it.title == "Sofá Cama Ultimate" }
        assertEquals("1799900.00", sofa.price.toPlainString())
        assertEquals("3199900.00", sofa.listPrice!!.toPlainString())
        assertEquals("Sofá Camas", sofa.category)
        assertTrue(sofa.url.startsWith("https://www.kaiuhome.com/producto/"))
        assertTrue(sofa.imageUrl!!.startsWith("https://"))
        assertEquals("kaiuhome.com", sofa.merchant)
    }

    @Test
    fun `html entities in a title are decoded`() {
        assertTrue(deals().any { it.title == "Puff Baul Cubox – Cuero Sintetico Gris" })
        assertTrue(deals().none { it.title.contains("&#") })
    }

    @Test
    fun `money is in minor units, so two decimals divides by 100`() {
        assertEquals("1799900", WooCommerceSource.amount("1799900", 0)!!.toPlainString())
        assertEquals("1799900.00", WooCommerceSource.amount("179990000", 2)!!.toPlainString())
        assertNull(WooCommerceSource.amount("", 0))
        assertNull(WooCommerceSource.amount("0", 0))
    }

    @Test
    fun `no list price above the price means no discount, not zero percent`() {
        val flat = WooProduct(1, "Silla", "https://x/p", WooPrices("100000", "100000", 0))
        assertNull(source.toRawDeal(flat, base, "COP")!!.listPrice)
    }

    @Test
    fun `an out of stock row is flagged unavailable and a row with no price is dropped`() {
        val gone = WooProduct(2, "Silla", "https://x/p", WooPrices("100000", "120000", 0), isInStock = false)
        assertEquals(false, source.toRawDeal(gone, base, "COP")!!.available)
        assertNull(source.toRawDeal(WooProduct(3, "Silla", "https://x/p", WooPrices(null, null, 0)), base, "COP"))
    }
}
