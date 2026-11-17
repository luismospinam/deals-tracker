package com.luis.dealstracker.web

import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.Lang
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MastheadTest {
    private fun header(): String = createHTML().html { page("t") {} }.substringAfter("<header>").substringBefore("</header>")

    @Test
    fun `the tabs are the five screens, and config is not one of them`() {
        val tabs = Regex("""<span class="sub"><a href="([^"]+)">([^<]+)</a>""").findAll(header()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            listOf("/" to "feed", "/tracked" to "tracked", "/groceries" to "groceries", "/household" to "household", "/watches" to "watches"),
            tabs,
        )
    }

    @Test
    fun `config is a named icon link after the language switch`() {
        val h = header()
        val link = Regex("""<a [^>]*class="mast-config"[^>]*>""").find(h)?.value ?: error("no config link in: $h")
        assertTrue("href=\"/config\"" in link, link)
        assertTrue("aria-label=\"Config\"" in link, "an icon needs a name: $link")
        assertTrue("title=\"Config\"" in link, link)
        assertTrue("aria-hidden=\"true\"" in h.substringAfter("mast-config"), "the drawing itself is not announced")
        assertTrue(h.indexOf("langswitch") < h.indexOf("mast-config"), "the gear closes the masthead, after EN / ES")
        assertFalse(">config<" in h)
    }

    @Test
    fun `the config link is named in Spanish too`() {
        I18n.with(Lang.ES) { assertTrue("aria-label=\"Configuración\"" in header()) }
    }
}
