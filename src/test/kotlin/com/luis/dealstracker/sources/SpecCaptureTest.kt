package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.vtex.VtexProperty
import com.luis.dealstracker.sources.vtex.VtexSource
import com.luis.dealstracker.sources.woocommerce.WooCommerceSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecCaptureTest {
    /** The shape of Easy's `catalog_system` product: names in allSpecifications, each value under its own key. */
    private val easyBody = """
        [{"productId":"1528810","productName":"Sofá cama en L Napoles","brand":"HOGAR VENECIA","linkText":"sofa-1528810",
          "description":"<p>Sofá Cama En L &amp; baúl.</p><p>Medidas: 265 x 146 cm</p>",
          "allSpecifications":["Lado Chaise","Dimensiones","Vídeo Producto","Garantía Proveedor","Color"],
          "Lado Chaise":["Derecho"],"Dimensiones":["265 x 146 x 80 cm"],
          "Vídeo Producto":["<div class=\"x\"><iframe src=\"y\"></iframe></div>"],
          "Garantía Proveedor":["1 año de garantía"],"Color":["Beige"],
          "items":[]}]
    """.trimIndent()

    @Test
    fun `VTEX catalog_system specs are read off the per-name keys`() {
        val product = VtexSource.withSpecs(easyBody).single()
        assertEquals("Derecho", product.specs["Lado Chaise"])
        assertEquals("265 x 146 x 80 cm", product.specs["Dimensiones"])
        assertEquals("Beige", product.specs["Color"])
    }

    @Test
    fun `video embeds and warranty boilerplate are dropped from the specs`() {
        val specs = VtexSource.withSpecs(easyBody).single().specs
        assertFalse(specs.keys.any { it.contains("deo") || it.contains("arant") }, "got $specs")
    }

    @Test
    fun `Intelligent Search properties give the same table`() {
        val specs = VtexSource.specsOf(listOf(VtexProperty("Lado Chaise", listOf("Izquierdo")), VtexProperty("Color", listOf("Gris", "Negro"))))
        assertEquals(mapOf("Lado Chaise" to "Izquierdo", "Color" to "Gris; Negro"), specs)
    }

    @Test
    fun `a description is plain text, decoded and capped`() {
        val text = SpecText.description("<p>Sofá Cama En L &amp; baúl.</p><p>Medidas: 265 x 146 cm</p>")!!
        assertEquals("Sofá Cama En L & baúl. Medidas: 265 x 146 cm", text)
        assertEquals(SpecText.MAX_DESCRIPTION, SpecText.description("y".repeat(10_000))!!.length)
        assertNull(SpecText.description("  <p> </p> "))
    }

    @Test
    fun `an over-long value is cut and a blank one is dropped`() {
        assertEquals(SpecText.MAX_VALUE, SpecText.pair("Nota", "z".repeat(900))!!.second.length)
        assertNull(SpecText.pair("Color", "  "))
        assertNull(SpecText.pair(null, "x"))
    }

    @Test
    fun `Kaiu keeps the colour attribute and the description that carries its measurements`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val body = """[{"id":1,"name":"Sofá Cama Ultimate","permalink":"https://x/p","prices":{"price":"1799900","regular_price":"3199900","currency_minor_unit":0},
            "short_description":"<p>Reclinable de 3 posiciones.</p>","description":"<p>Medidas como sofá: 228 cm de largo × 91 cm de ancho</p>",
            "attributes":[{"name":"Color","terms":[{"name":"Beige"},{"name":"Gris"}]}],
            "dimensions":{"length":"","width":"","height":""},"is_in_stock":true}]"""
        val deal = WooCommerceSource().toRawDeal(
            json.decodeFromString<List<com.luis.dealstracker.sources.woocommerce.WooProduct>>(body).single(),
            "https://www.kaiuhome.com", "COP",
        )!!
        assertEquals(mapOf("Color" to "Beige; Gris"), deal.specs)
        assertTrue(deal.description!!.contains("228 cm de largo"))
        assertTrue(deal.description!!.startsWith("Reclinable de 3 posiciones."))
    }
}
