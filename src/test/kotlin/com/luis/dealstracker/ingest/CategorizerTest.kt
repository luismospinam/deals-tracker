package com.luis.dealstracker.ingest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs against the real config/categories.yaml rather than a fixture, so the rules Luis
 * actually edits are the ones under test. All example titles and category paths below are
 * real values pulled from the live sources.
 */
class CategorizerTest {

    private val categorizer = Categorizer.load(File("."))

    private fun of(title: String, sourceCategory: String? = null) =
        categorizer.categorize(sourceCategory, title)

    @Test
    fun `config loads and defines the expected buckets`() {
        assertTrue(categorizer.categoryNames.size >= 10, "expected ~13 buckets + Other")
        assertEquals(Categorizer.OTHER, categorizer.categoryNames.last(), "Other must be the fallback")
        assertTrue("Groceries" in categorizer.categoryNames)
    }

    /**
     * The whole reason normalization exists: Exito writes "Tecnología" and Carulla writes
     * "Tecnologia" for the same category. One pattern must match both.
     */
    @Test
    fun `accent differences between the two VTEX stores resolve to the same bucket`() {
        assertEquals(
            of("Celular X", "Tecnología > Celulares"),
            of("Celular X", "Tecnologia > Celulares"),
        )
        assertEquals("Phones & Wearables", of("Celular X", "Tecnología > Celulares"))
    }

    @Test
    fun `normalize strips accents and case`() {
        assertEquals("tecnologia", Categorizer.normalize("Tecnología"))
        assertEquals("electrodomesticos", Categorizer.normalize("ELECTRODOMÉSTICOS"))
        assertEquals("panaderia", Categorizer.normalize("Panadería"))
    }

    @Test
    fun `maps VTEX source categories`() {
        assertEquals("Appliances", of("Lavadora LG", "Electrodomésticos > Lavado y secado > Lavadoras"))
        assertEquals("Appliances", of("Nevera", "Electrodomésticos > Refrigeración > Neveras"))
        assertEquals("TV & Video", of("Televisor", "Tecnología > Televisores"))
        assertEquals("Computers", of("Portátil", "Tecnología > Computadores > Portátiles"))
        assertEquals("Groceries", of("Leche", "Mercado > Lácteos, huevos y refrigerados > Leche"))
        assertEquals("Groceries", of("Arroz", "Despensa > Granos"))
        assertEquals("Toys & Baby", of("Muñeca", "Juguetería > Muñecas y accesorios"))
    }

    /** Slickdeals' only "category" is its forum name, so titles are all there is to go on. */
    @Test
    fun `classifies slickdeals titles despite the useless forum category`() {
        assertEquals("Audio", of("\$189.99* | Apple AirPods Pro 3 Wireless Earbuds at Amazon", "Hot Deals"))
        assertEquals("Computers", of("Apple MacBook Pro: 14\", M5 Pro, 24GB RAM, 2TB SSD", "Hot Deals"))
        assertEquals("Gaming", of("Absolum (PS5) \$31.89 + Free Shipping", "Hot Deals"))
        assertEquals("Toys & Baby", of("384-Pc LEGO City Red Double-Decker Bus \$19.20", "Hot Deals"))
        assertEquals("Phones & Wearables", of("Apple iPhone 12 Mini Clear Polycarbonate Case \$5.05", "Hot Deals"))
        assertEquals("Books & Media", of("\$1.99 | Snow Crash: A Novel (eBook) by Neal Stephenson", "Hot Deals"))
    }

    /**
     * Three orderings forced by real misclassifications. Each of these silently produced a
     * wrong bucket before the categories were reordered.
     */
    @Test
    fun `books beat musical instrument keywords`() {
        // Matched Audio's "piano" pattern until Books & Media was moved ahead of Audio.
        assertEquals("Books & Media", of("\$1.99 | Player Piano: A Novel (eBook) by Kurt Vonnegut", "Hot Deals"))
    }

    @Test
    fun `tv accessories beat the audio-and-video source path`() {
        // Exito files TV mounts under "Accesorios audio y video", which Audio claimed.
        assertEquals(
            "TV & Video",
            of("Soporte De Pared Móvil Para Televisor De 40 A 85", "Tecnología > Accesorios audio y video > Soportes Para Tv"),
        )
    }

    @Test
    fun `personal care beats the fashion catch-all`() {
        // Fashion's broad "men's <word>" pattern claimed this until soap was added to Beauty.
        assertEquals("Beauty & Health", of("3-Pack 5-Oz Dr. Squatch Men's Bar Soap \$12", "Hot Deals"))
    }

    @Test
    fun `gaming peripherals are gaming not computers`() {
        assertEquals("Gaming", of("Corsair K55 RGB PRO Gaming Keyboard", "Hot Deals"))
        assertEquals("Computers", of("Logitech MX Master Wireless Mouse", "Hot Deals"))
    }

    /** Source taxonomy is more reliable than a marketing title, so it must win. */
    @Test
    fun `source category takes precedence over the title`() {
        // Title says "guitar" (Audio) but the retailer filed it under toys.
        assertEquals("Toys & Baby", of("Guitar toy for kids", "Juguetería > Juguetes"))
    }

    @Test
    fun `unmatched deals fall back to Other rather than being guessed`() {
        assertEquals(Categorizer.OTHER, of("Zzzz Unrecognisable Widget 9000", "Hot Deals"))
        assertEquals(Categorizer.OTHER, of("Something", null))
    }

    /**
     * Patterns must tolerate plurals. A trailing `\b` after a singular noun cannot match
     * its plural -- there is no word boundary between "cheesecloth" and the "s" -- which
     * silently broke many rules until every group got an `s?`.
     */
    @Test
    fun `matches plural forms of keywords`() {
        assertEquals("Home & Kitchen", of("6-Pack 20x20 Reusable Cheesecloths", "Hot Deals"))
        assertEquals("Toys & Baby", of("ZMLM 24-Pack Reusable Water Coloring Books", "Hot Deals"))
        assertEquals("Tools & Outdoor", of("10-Pc Bosch Impact Tough Power Bits", "Hot Deals"))
    }

    /**
     * Real titles put several words between a keyword pair, so the gap allowances have to
     * be generous. "Smart Wi-Fi Apple HomeKit Outdoor Plug" spans 29 characters between
     * "smart" and "plug".
     */
    @Test
    fun `matches keyword pairs separated by several words`() {
        assertEquals("Home & Kitchen", of("TP-Link Kasa Smart Wi-Fi Apple HomeKit Outdoor Plug", "Hot Deals"))
        assertEquals("Phones & Wearables", of("Jogrytece 4-in-1 USB-C Multi Charging Cable", "Hot Deals"))
        assertEquals(
            "Tools & Outdoor",
            of("12-Count Solar Halloween Pumpkin Pathway Stake Lights", "Hot Deals"),
        )
    }

    /**
     * A bare "blade" pattern intended for knives claimed a ceiling fan's "5-Blade" and an
     * ice machine's "Adjustable Blade" for Tools. Both are appliances.
     */
    @Test
    fun `fan and ice machine blades are not knives`() {
        assertEquals("Appliances", of("ZMISHIBO 52 Inch Ceiling Fan (no Light) Black 5-Blade", "Hot Deals"))
        assertEquals("Appliances", of("Dash Pro Shaved Ice Machine w/ Adjustable Blade", "Hot Deals"))
        // …while a genuine knife still resolves to Tools.
        assertEquals("Tools & Outdoor", of("COLD STEEL Hunters Fixed Blade Knife", "Hot Deals"))
    }

    @Test
    fun `no rules configured means everything is Other`() {
        val empty = Categorizer(emptyList())
        assertEquals(Categorizer.OTHER, empty.categorize("Tecnología > Celulares", "iPhone"))
        assertEquals(listOf(Categorizer.OTHER), empty.categoryNames)
    }

    @Test
    fun `a bad regex is skipped rather than fatal`() {
        val c = Categorizer(
            listOf(
                CategoryRule("Broken", titlePatterns = listOf("[unclosed")),
                CategoryRule("Good", titlePatterns = listOf("widget")),
            ),
        )
        assertEquals("Good", c.categorize(null, "a widget"))
    }
}
