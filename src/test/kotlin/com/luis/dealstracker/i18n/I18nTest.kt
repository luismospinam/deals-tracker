package com.luis.dealstracker.i18n

import com.luis.dealstracker.web.resolveLang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class I18nTest {

    @Test
    fun `the browser's first supported language wins, in its own order`() {
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("es-CO,es;q=0.9,en;q=0.8"))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage("en-US,en;q=0.9,es;q=0.8"))
        // French is not offered, so the next one the browser accepts is used.
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("fr-FR, es;q=0.8, en;q=0.5"))
        // Weights, not position: an iPhone may list a low-weight language first.
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("en;q=0.2, es;q=0.9"))
        assertNull(Lang.fromAcceptLanguage("fr, de"))
        assertNull(Lang.fromAcceptLanguage(null))
        assertNull(Lang.fromAcceptLanguage("es;q=0"))
    }

    /** The switch exists for "Spanish, even though my phone is in English". */
    @Test
    fun `the switch's cookie beats the browser, and English is the last resort`() {
        assertEquals(Lang.ES, resolveLang("es", "en-US,en;q=0.9"))
        assertEquals(Lang.EN, resolveLang("en", "es-CO,es;q=0.9"))
        assertEquals(Lang.ES, resolveLang(null, "es-CO"))
        // A tampered cookie falls through to the browser rather than erroring.
        assertEquals(Lang.ES, resolveLang("klingon", "es-CO"))
        assertEquals(Lang.EN, resolveLang(null, null))
    }

    @Test
    fun `tr follows the language set around a render, and English outside one`() {
        assertEquals("Apply", tr("Apply", "Aplicar"))
        assertEquals("Aplicar", I18n.with(Lang.ES) { tr("Apply", "Aplicar") })
        assertEquals("Apply", tr("Apply", "Aplicar"), "the language must not leak past the block")
        // Nested, as an htmx fragment rendered inside a page would be.
        I18n.with(Lang.ES) {
            I18n.with(Lang.EN) { assertEquals("Apply", tr("Apply", "Aplicar")) }
            assertEquals("Aplicar", tr("Apply", "Aplicar"))
        }
    }

    /** The stored value is the English name; only the label changes, and an unknown name survives. */
    @Test
    fun `category and aisle labels translate, and pass unknown names through`() {
        I18n.with(Lang.ES) {
            assertEquals("Electrodomésticos", categoryLabel("Appliances"))
            assertEquals("Despensa", aisleLabel("Pantry"))
            assertEquals("Something New", categoryLabel("Something New"))
        }
        assertEquals("Appliances", categoryLabel("Appliances"))
    }

    /**
     * The banners have no browser to ask. A choice made with the switch is kept over whatever the
     * next browser happens to send, so opening the app on an English phone does not flip the Mac's
     * Spanish banners back.
     */
    @Test
    fun `the notification language follows the browser until the switch is used`() {
        val saved = mutableListOf<Pair<Lang, Boolean>>()
        AppLanguage.init(null, false) { l, chosen -> saved += l to chosen }
        assertEquals(Lang.EN, AppLanguage.current)

        AppLanguage.remember(Lang.ES, isChoice = false)
        assertEquals(Lang.ES, AppLanguage.current)
        AppLanguage.remember(Lang.ES, isChoice = false)
        assertEquals(1, saved.size, "an unchanged language is not written again")

        AppLanguage.remember(Lang.ES, isChoice = true)
        AppLanguage.remember(Lang.EN, isChoice = false)
        assertEquals(Lang.ES, AppLanguage.current, "a browser does not override a choice")
        assertEquals(Lang.ES to true, saved.last())

        AppLanguage.remember(Lang.EN, isChoice = true)
        assertEquals(Lang.EN, AppLanguage.current, "a new choice does")
        assertTrue(AppLanguage.wouldChange(Lang.ES, isChoice = true))
        assertFalse(AppLanguage.wouldChange(Lang.ES, isChoice = false))

        AppLanguage.init(null, false) { _, _ -> }
    }
}
