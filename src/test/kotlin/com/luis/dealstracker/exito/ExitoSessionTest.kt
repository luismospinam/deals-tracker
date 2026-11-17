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

    @Test
    fun `only a token with a full day left counts as renewed`() {
        val now = java.time.Instant.parse("2026-11-12T14:00:00Z")
        fun token(exp: java.time.Instant): String {
            val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${exp.epochSecond}}""".toByteArray())
            return "h.$payload.s"
        }
        assertTrue(isFullDay(token(now.plus(java.time.Duration.ofHours(24))), now))
        assertFalse(isFullDay(token(now.plus(java.time.Duration.ofHours(9))), now), "yesterday's token, still in the profile")
        assertFalse(isFullDay("not-a-token", now))
    }

    @Test
    fun `a refresh answer with no verdict is described by its shape, never its values`() {
        val page = refreshAnswerShape("text/html; charset=UTF-8", null)
        kotlin.test.assertTrue("not JSON (text/html)" in page && "check page" in page, page)
        kotlin.test.assertEquals("JSON with fields [expiresIn, userId]", refreshAnswerShape("application/json", setOf("userId", "expiresIn")))
        kotlin.test.assertTrue("no content type" in refreshAnswerShape(null, null))
    }

    /** 2026-11-15: the saved login had expired at 06:11 while the profile held one good until 22:07. */
    @Test
    fun `a login in the profile that outlasts the saved one is worth saving, even when it is not new`() {
        val now = at("2026-11-15T09:30:00")
        val saved = tokenIssued("2026-11-14T06:11:00")
        val profile = tokenIssued("2026-11-14T22:07:00")
        assertFalse(isFullDay(profile, now), "not a fresh renewal")
        assertTrue(outlastsSaved(profile, saved, now))
        assertTrue(outlastsSaved(profile, null, now), "nothing saved yet")
        assertFalse(outlastsSaved(saved, profile, now), "the older one never replaces the newer")
        assertFalse(outlastsSaved(profile, saved, at("2026-11-15T22:08:00")), "itself expired")
        assertFalse(outlastsSaved("opaque", saved, now))
    }

    @Test
    fun `a renewal that left the old login is only alerted once that login is running out`() {
        val login = tokenIssued("2026-11-14T22:07:00")
        assertFalse(loginRunningOut(login, at("2026-11-15T09:30:00")), "works for twelve more hours; the hourly retry will renew it")
        assertTrue(loginRunningOut(login, at("2026-11-15T20:30:00")), "under two hours left")
        assertTrue(loginRunningOut(login, at("2026-11-16T07:00:00")), "expired")
        assertTrue(loginRunningOut(null, at("2026-11-15T09:30:00")))
        assertTrue(loginRunningOut("opaque", at("2026-11-15T09:30:00")))
    }

    /** 2026-11-16: signed in 2026-10-17 03:06 UTC, and the refresh token was gone thirty days later. */
    @Test
    fun `the failure alert says the sign-in ended when there is no refresh token left`() {
        val (gone, goneBody) = renewalFailedAlert(signInGone = true)
        assertEquals("Exito sign-in ended", gone)
        assertTrue("30-day sign-in has run out" in goneBody && "sign in to Exito there" in goneBody, goneBody)
        assertTrue(EXITO_SIGNIN_COMMAND in goneBody)
        val (stale, staleBody) = renewalFailedAlert(signInGone = false)
        assertEquals("Exito login was not renewed", stale)
        assertTrue(EXITO_SIGNIN_COMMAND in staleBody)
        assertEquals("./dt run --args=\"--exito-session --renew --save\"", EXITO_SIGNIN_COMMAND)
    }

    @Test
    fun `the sign-in is warned about in its last three days, not before and not after`() {
        val ends = java.time.Instant.parse("2026-12-17T03:06:00Z")
        assertFalse(signInEndingSoon(ends, ends.minus(java.time.Duration.ofDays(4))))
        assertTrue(signInEndingSoon(ends, ends.minus(java.time.Duration.ofDays(3))))
        assertTrue(signInEndingSoon(ends, ends.minus(java.time.Duration.ofHours(1))))
        assertFalse(signInEndingSoon(ends, ends.plusSeconds(1)), "over: that is the failure alert's case")
        assertFalse(signInEndingSoon(null, ends), "not known yet")
        val (title, body) = signInEndingAlert(ends, zone)
        assertEquals("Exito sign-in ends soon", title)
        assertTrue("Wednesday 16 December at 22:06" in body, body)
    }

    @Test
    fun `the Exito browser does not announce that it is automated`() {
        val opts = exitoLaunchOptions(headless = false)
        assertEquals(listOf("--enable-automation"), opts.ignoreDefaultArgs)
        assertTrue("--disable-blink-features=AutomationControlled" in opts.args)
        assertEquals("chrome", opts.channel, "Playwright's Chrome, whose cookie key the saved profile uses")
    }
}
