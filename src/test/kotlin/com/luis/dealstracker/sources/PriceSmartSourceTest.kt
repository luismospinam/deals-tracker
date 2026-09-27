package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.sources.pricesmart.PriceSmartSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture: real Bloomreach docs from PriceSmart Colombia, club 6106 (Medellín Las Américas), 2026-09-27. */
class PriceSmartSourceTest {

    private val source = PriceSmartSource()
    private val page = source.parsePage(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/pricesmart-alimentos.json")).bufferedReader().readText(),
    )!!
    private val docs = page.docs.associateBy { it["pid"]!!.jsonPrimitive.content }

    private fun deal(pid: String) =
        source.toRawDeal(docs.getValue(pid), "https://www.pricesmart.com", "6106", "COP", "Pantry")

    /** Prices are in cents: 5,990,000 is $59,900. */
    @Test
    fun `prices are converted from cents`() {
        val d = deal("420380")!!
        assertEquals(BigDecimal("59900.00"), d.price)
        assertEquals("Member's Selection Aceite de Aguacate Refinado 1 L / 33.8 oz", d.title)
        assertEquals("https://www.pricesmart.com/es-co/producto/members-selection-aceite-de-aguacate-refinado-1-l-33-8-oz-420380/420380", d.url)
        assertEquals("Pantry", d.groceryGroup)
    }

    /**
     * A doc carrying only national fields is not sold at the club. `price_CO` is a different number, and
     * pricing a row from it is the national-catalogue mistake the grocery adapters exist to avoid.
     */
    @Test
    fun `a product with no club price is skipped`() {
        assertNull(deal("420380-national"))
        assertNull(deal("252232"))
    }

    /** Instant savings are in PESOS, beside a price in cents: 48,500 + 11,400 = 59,900. */
    @Test
    fun `an instant saving makes the pre-saving price the list price`() {
        val d = deal("406118")!!
        assertEquals(BigDecimal("48500.00"), d.price)
        assertEquals(BigDecimal("59900.00"), d.listPrice)
    }

    @Test
    fun `no saving means no list price`() {
        assertNull(deal("446204")!!.listPrice)
    }

    /** A meat tray is priced whole; its weight in kg is its pack size. */
    @Test
    fun `a weighed tray is sized by its own weight`() {
        val d = deal("127788")!!
        assertEquals(BigDecimal("27820.20"), d.price)
        assertEquals("g", d.unitMeasure)
        assertEquals(0, BigDecimal(1398).compareTo(d.unitQuantity))
    }

    /** "508 g / 1.12 lb": the metric size, not the trailing imperial one. */
    @Test
    fun `a fixed pack reads its metric size from the title`() {
        val blueberries = deal("446204")!!
        assertEquals("g", blueberries.unitMeasure)
        assertEquals(0, BigDecimal(508).compareTo(blueberries.unitQuantity))
        val apples = deal("82165")!!
        assertEquals(0, BigDecimal(1360).compareTo(apples.unitQuantity), "1.36 kg")
    }

    @Test
    fun `out of stock at the club is not available`() {
        assertFalse(deal("600447")!!.available)
        assertTrue(deal("446204")!!.available)
    }

    /** The per-club fields are what select the club; nothing else in the request changes. */
    @Test
    fun `the request asks for the club's own fields`() {
        val cfg = ProviderConfig(id = "ps", type = "pricesmart", baseUrl = "https://www.pricesmart.com", options = mapOf("club" to "6106"))
        val body = Json.parseToJsonElement(source.requestBody(cfg, "https://www.pricesmart.com", "6106", "G10D59015", 48, 48)).jsonObject
        val fl = body["fl"]!!.jsonPrimitive.content.split(",")
        for (f in listOf("price_CO_6106", "inventory_CO_6106", "sold_by_weight_CO_6106", "saving_amount_CO_6106", "promoid_CO")) {
            assertTrue(f in fl, "missing $f in $fl")
        }
        assertFalse("price_CO_6110" in fl)
        assertEquals("G10D59015", body["q"]!!.jsonPrimitive.content)
        assertEquals("48", body["start"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pages report their total`() {
        assertEquals(page.docs.size, page.total)
        assertNotNull(docs["127788"])
    }
}
