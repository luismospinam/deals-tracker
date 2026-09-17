package com.luis.dealstracker.sources

import com.luis.dealstracker.ingest.ProductIdentity
import com.luis.dealstracker.sources.algolia.AlgoliaSource
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
 * Fixture is a real Algolia response from alkomprar.com captured 2026-09-15, trimmed to three
 * hits and to the fields the adapter reads (each live hit carries 66).
 */
class AlgoliaSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = AlgoliaSource()
    private val base = "https://www.alkomprar.com"

    private val hits: List<JsonObject> = json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/alkomprar-neveras.json")) {
            "missing fixture"
        }.bufferedReader().readText(),
    ).jsonObject["hits"].let { it as JsonArray }.map { it.jsonObject }

    private fun deals() = hits.mapNotNull { source.toRawDeal(it, base, "COP") }

    private fun hit(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `maps the real response`() {
        assertEquals(3, hits.size)
        assertEquals(3, deals().size)
    }

    @Test
    fun `reads the discounted price and the base price as the list price`() {
        val haceb = deals().single { it.title.contains("391 litros") }
        assertEquals("2049900.00", haceb.price.toPlainString())
        assertEquals("2974900.00", haceb.listPrice!!.toPlainString())
        val pct = assertNotNull(haceb.discountPercent)
        assertTrue(pct > BigDecimal("31") && pct < BigDecimal("32"), "got $pct")
    }

    /**
     * The reason this retailer was worth adding: it publishes the EAN *as* the product code, so
     * its rows cluster with Éxito, Easy and Grupo Mansión with no extra work. This is the exact
     * MABE side-by-side that started the whole cross-retailer thread.
     */
    @Test
    fun `the product code is a real GTIN and clusters with other retailers`() {
        val mabe = deals().single { it.brand == "MABE" }
        assertEquals("757638435765", mabe.gtin)
        // Padded to GTIN-14 by ProductIdentity, which is what makes the UPC-12 and EAN-13 forms
        // of this same barcode one key.
        assertEquals("gtin:00757638435765", ProductIdentity.matchKey(mabe.gtin, mabe.brand, null))
        assertEquals("2549900.00", mabe.price.toPlainString())
    }

    @Test
    fun `every fixture code validates as a GTIN`() {
        deals().forEach { deal ->
            assertNotNull(
                ProductIdentity.validGtin(deal.gtin),
                "${deal.title.take(40)} has code ${deal.gtin}, which should be a valid GTIN",
            )
        }
    }

    @Test
    fun `builds an absolute url from the published path`() {
        val haceb = deals().single { it.title.contains("391 litros") }
        assertTrue(haceb.url.startsWith("$base/nevera-haceb"), haceb.url)
        assertTrue(haceb.url.endsWith("/p/7704353462326"), haceb.url)
    }

    @Test
    fun `carries brand from its single-element array, and the retailer's category path`() {
        val haceb = deals().single { it.title.contains("391 litros") }
        assertEquals("HACEB", haceb.brand)
        assertEquals("Electrodomésticos > Refrigeración > Neveras", haceb.category)
    }

    // --- branches the live sample did not contain, so they are built by hand rather than
    // faked inside a "real capture" fixture ---

    /**
     * Stock is published explicitly here, unlike most sources. An out-of-stock row must be
     * marked unavailable so the caller drops it: a price you cannot buy would otherwise plant a
     * phantom cheaper rival in the cross-site comparison.
     */
    @Test
    fun `marks an out-of-stock hit unavailable`() {
        val out = hit(
            """{"code_string":"7704353462326","name_text_es":"Nevera X",
                "discountprice_double":1000.0,"instockflag_boolean":false}""",
        )
        assertEquals(false, source.toRawDeal(out, base, "COP")!!.available)

        val inStock = hit(
            """{"code_string":"7704353462326","name_text_es":"Nevera X",
                "discountprice_double":1000.0,"instockflag_boolean":true}""",
        )
        assertTrue(source.toRawDeal(inStock, base, "COP")!!.available)
    }

    @Test
    fun `a base price equal to the sale price is no discount, not zero percent`() {
        val flat = hit(
            """{"code_string":"7704353462326","name_text_es":"Nevera X",
                "discountprice_double":500.0,"baseprice_cop_string":500.0}""",
        )
        val deal = assertNotNull(source.toRawDeal(flat, base, "COP"))
        assertNull(deal.listPrice)
        assertNull(deal.discountPercent)
    }

    @Test
    fun `falls back through the price fields, and drops a hit with none`() {
        val lowestOnly = hit(
            """{"code_string":"7704353462326","name_text_es":"Nevera X","lowestprice_double":777.0}""",
        )
        assertEquals("777.00", source.toRawDeal(lowestOnly, base, "COP")!!.price.toPlainString())

        assertNull(
            source.toRawDeal(hit("""{"code_string":"1","name_text_es":"No price"}"""), base, "COP"),
            "a hit with no usable price must be dropped rather than priced at zero",
        )
        assertNull(
            source.toRawDeal(hit("""{"name_text_es":"No code","discountprice_double":10.0}"""), base, "COP"),
            "no code means no stable id",
        )
    }

    /** COP reaches eight digits; a binary float is the drift NUMERIC(14,2) exists to prevent. */
    @Test
    fun `preserves large COP amounts exactly`() {
        val big = hit(
            """{"code_string":"7704353462326","name_text_es":"Nevecón",
                "discountprice_double":39999900.0,"baseprice_cop_string":45999900.0}""",
        )
        val deal = source.toRawDeal(big, base, "COP")!!
        assertEquals("39999900.00", deal.price.toPlainString())
        assertEquals("45999900.00", deal.listPrice!!.toPlainString())
    }
}
