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

    /**
     * A meat tray is priced per kg, not whole: the tray's weight changes between crawls, and a lighter
     * tray read as a 52% drop on a Ribeye. Sign price 1,990,000 cents is $19,900/kg (the tray: 1.398 kg,
     * $27,820.20).
     */
    @Test
    fun `a weighed tray is priced per kg`() {
        val d = deal("127788")!!
        assertEquals(BigDecimal("19900.00"), d.price)
        assertEquals("g", d.unitMeasure)
        assertEquals(0, BigDecimal(1000).compareTo(d.unitQuantity))
        assertTrue(d.title.endsWith("(por kg)"), d.title)
    }

    /** The per-kg row cannot continue the tray-priced history, so it is a row of its own. */
    @Test
    fun `a weighed item has its own id, a fixed pack keeps the pid`() {
        assertEquals("127788:kg", deal("127788")!!.externalId)
        assertEquals("420380", deal("420380")!!.externalId)
    }

    /** Two trays of one cut at different weights are the same price, which is the point. */
    @Test
    fun `tray weight does not move the price`() {
        val light = weighedDoc(weight = "1.398", trayCents = "2782020")
        val heavy = weighedDoc(weight = "2.796", trayCents = "5564040")
        assertEquals(
            source.toRawDeal(light, "https://www.pricesmart.com", "6106", "COP", "Meat & fish")!!.price,
            source.toRawDeal(heavy, "https://www.pricesmart.com", "6106", "COP", "Meat & fish")!!.price,
        )
    }

    /** Without a sign price the tray price over its weight is the same number; a saving scales with it. */
    @Test
    fun `no sign price falls back to tray over weight, and the list price is per kg too`() {
        val doc = weighedDoc(weight = "2", trayCents = "4000000", signCents = null, originalPesos = "50000")
        val d = source.toRawDeal(doc, "https://www.pricesmart.com", "6106", "COP", "Meat & fish")!!
        assertEquals(BigDecimal("20000.00"), d.price)
        assertEquals(BigDecimal("25000.00"), d.listPrice)
    }

    private fun weighedDoc(weight: String, trayCents: String, signCents: String? = "1990000", originalPesos: String? = null) =
        Json.parseToJsonElement(
            """{"pid":"900","title":"Filete Bandeja","price_CO_6106":$trayCents,"weight_CO_6106":$weight,
               "sold_by_weight_CO_6106":"true","weight_uom_description_CO_6106":"kg","inventory_CO_6106":"in stock"
               ${signCents?.let { ""","sign_price_CO_6106":$it""" } ?: ""}
               ${originalPesos?.let { ""","original_price_without_saving_CO_6106":$it""" } ?: ""}}""",
        ).jsonObject

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
