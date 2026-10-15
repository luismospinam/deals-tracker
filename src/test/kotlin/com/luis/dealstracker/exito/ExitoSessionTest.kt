package com.luis.dealstracker.exito

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitoSessionTest {

    private fun row(domain: String, value: String, name: String = EXITO_COOKIE_NAME) = CookieRow(name, domain, value)

    private val zone = java.time.ZoneId.of("America/Bogota")

    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    /** A token issued at [issued] local time, expiring 24 hours later as Exito's do. */
    private fun tokenIssued(issued: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val exp = at(issued).plusSeconds(24 * 3600).epochSecond
        return enc.encodeToString("{}".toByteArray()) + "." + enc.encodeToString("""{"exp":$exp}""".toByteArray()) + ".sig"
    }

    @Test
    fun `the renewal waits for the start of the day`() {
        val yesterday = tokenIssued("2026-10-14T13:00:00")
        assertFalse(renewalDue(at("2026-10-15T05:59:00"), yesterday, null, zone))
        assertTrue(renewalDue(at("2026-10-15T06:00:00"), yesterday, null, zone))
    }

    @Test
    fun `a login already issued today is left alone, and one from yesterday or none is renewed`() {
        val now = at("2026-10-15T09:00:00")
        assertFalse(renewalDue(now, tokenIssued("2026-10-15T06:05:00"), null, zone))
        assertTrue(renewalDue(now, tokenIssued("2026-10-14T18:00:00"), null, zone))
        assertTrue(renewalDue(now, null, null, zone))
        assertTrue(renewalDue(now, "opaque", null, zone), "an unreadable token cannot be shown fresh")
    }

    @Test
    fun `a Mac that slept through the morning renews as soon as it is up`() {
        assertTrue(renewalDue(at("2026-10-15T14:30:00"), tokenIssued("2026-10-14T06:05:00"), null, zone))
    }

    @Test
    fun `a failed attempt is not retried for an hour`() {
        val stale = tokenIssued("2026-10-14T06:05:00")
        val tried = at("2026-10-15T06:00:00")
        assertFalse(renewalDue(at("2026-10-15T06:59:00"), stale, tried, zone))
        assertTrue(renewalDue(at("2026-10-15T07:00:00"), stale, tried, zone))
    }

    @Test
    fun `the freshest login wins over a stale copy under the shop host`() {
        val stale = tokenIssued("2026-10-14T06:00:00")
        val fresh = tokenIssued("2026-10-15T06:00:00")
        val jar = listOf(row(".www.exito.com", stale), row(".tienda.exito.com", fresh))
        assertEquals(fresh, loginTokenFrom(jar))
        assertEquals(fresh, loginTokenFrom(jar.reversed()))
    }

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
