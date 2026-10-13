package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.falabella.SpecPageParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are the `__NEXT_DATA__` of real product pages captured 2026-10-06, trimmed to the parts
 * the parser reads: a Falabella sofá cama and a Homecenter toilet.
 */
class SpecPageParserTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing $name" }.bufferedReader().readText()

    @Test
    fun `Falabella specs come from the page's specification list`() {
        val parsed = assertNotNull(SpecPageParser.parse(fixture("falabella-product-page.html")))
        assertEquals("Sofá cama con baúl y chaise longue", parsed.specs["Forma de uso"])
        assertEquals("200x160", parsed.specs["Dimensiones (extendido)"])
        assertEquals("5 puestos", parsed.specs["Tamaño del sillón"])
        assertEquals("Tela", parsed.specs["Material del tapiz"])
    }

    @Test
    fun `Falabella boilerplate is dropped from the table`() {
        val specs = SpecPageParser.parse(fixture("falabella-product-page.html"))!!.specs
        assertFalse(specs.keys.any { it.contains("arant", ignoreCase = true) || it.contains("origen", ignoreCase = true) }, "got ${specs.keys}")
    }

    @Test
    fun `the description is plain text, including the seller's escaped note`() {
        val description = SpecPageParser.parse(fixture("falabella-product-page.html"))!!.description!!
        assertTrue(description.contains("esquinero en L"), description)
        assertFalse(description.contains("<") || description.contains("&lt;") || description.contains("&quot;"), description)
    }

    @Test
    fun `Homecenter keeps only the customer-facing attributes`() {
        val specs = SpecPageParser.parse(fixture("homecenter-product-page.html"))!!.specs
        assertEquals("4.8 litros", specs["Capacidad volumétrica"])
        assertEquals("Porcelana", specs["Material"])
        assertEquals("66.5 cm", specs["Alto"])
        // Internal flags in the same list must not leak through.
        assertTrue(specs.keys.none { it.contains("provider") || it.startsWith("is_") }, "got ${specs.keys}")
    }

    @Test
    fun `a repeated label keeps both values`() {
        val tipo = SpecPageParser.parse(fixture("homecenter-product-page.html"))!!.specs["Tipo"]!!
        assertTrue(tipo.contains("Sanitarios de una pieza") && tipo.contains("Una pieza"), tipo)
    }

    @Test
    fun `a page with neither shape is unparseable, not empty`() {
        assertNull(SpecPageParser.parse("<html><body>nothing here</body></html>"))
        val other = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"something":1}}}</script>"""
        assertNull(SpecPageParser.parse(other))
    }

    @Test
    fun `a product page with no table is parsed as empty, which is a real answer`() {
        val bare = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"productData":{"attributes":{"specifications":[]}}}}}</script>"""
        val parsed = assertNotNull(SpecPageParser.parse(bare))
        assertTrue(parsed.specs.isEmpty())
        assertNull(parsed.description)
    }
}
