package com.luis.dealstracker.exito

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitoSessionTest {

    private fun row(domain: String, value: String, name: String = EXITO_COOKIE_NAME) = CookieRow(name, domain, value)

    @Test
    fun `the login token comes from the scope the cart API is called on`() {
        // Seen 2026-10-14: the same cookie under the sign-in host and under the shop host.
        val jar = listOf(row(".tienda.exito.com", "from-tienda"), row(".www.exito.com", "from-www"))
        assertEquals("from-www", loginTokenFrom(jar))
        assertEquals("from-www", loginTokenFrom(jar.reversed()))
    }

    @Test
    fun `any copy will do when only the sign-in host has one`() {
        assertEquals("only-tienda", loginTokenFrom(listOf(row(".tienda.exito.com", "only-tienda"))))
    }

    @Test
    fun `no usable login cookie yields nothing`() {
        assertNull(loginTokenFrom(emptyList()))
        assertNull(loginTokenFrom(listOf(row(".www.exito.com", "  "))))
        assertNull(loginTokenFrom(listOf(row(".www.exito.com", "x", name = "vid_rt"))))
        // Other accounts' VTEX cookies carry a different name and must not be mistaken for it.
        assertNull(loginTokenFrom(listOf(row(".tienda.exito.com", "x", name = "VtexIdclientAutCookie_f4531a3b-776e"))))
    }

    @Test
    fun `a pasted-style value is cleaned the same as a typed one`() {
        assertEquals("eyJabc.def", loginTokenFrom(listOf(row(".www.exito.com", "VtexIdclientAutCookie_exitocol=eyJabc.def"))))
    }

    @Test
    fun `the browser's own cart answer is read, and a broken one is not guessed at`() {
        assertEquals(
            BrowserCart("abc", loggedIn = true, items = 2),
            parseBrowserCart("""{"id":"abc","loggedIn":true,"items":["1","2"]}"""),
        )
        assertEquals(BrowserCart(null, loggedIn = false, items = 0), parseBrowserCart("""{"loggedIn":null}"""))
        assertNull(parseBrowserCart("<html>"))
    }

    @Test
    fun `Cloudflare's check page is recognised by its title`() {
        assertTrue(looksLikeBotWall("Just a moment..."))
        assertTrue(looksLikeBotWall("Attention Required! | Cloudflare"))
        assertFalse(looksLikeBotWall("Éxito - Compra en línea"))
    }
}
