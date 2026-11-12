package com.luis.dealstracker.web

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelGateTest {
    private val key = "k".repeat(32).toByteArray()
    private val now = Instant.parse("2026-11-12T12:00:00Z")
    private fun headers(vararg h: Pair<String, String>): (String) -> String? = { name -> h.toMap()[name] }

    @Test
    fun `only this Mac's own requests are local, and the tunnel's are not`() {
        assertTrue(isLocalRequest("127.0.0.1", headers()))
        assertTrue(isLocalRequest("::1", headers()))
        // cloudflared runs here, so its traffic is loopback too: the Cloudflare headers tell it apart.
        assertFalse(isLocalRequest("127.0.0.1", headers("Cf-Connecting-Ip" to "190.1.2.3")))
        assertFalse(isLocalRequest("127.0.0.1", headers("Cf-Ray" to "8abc")))
        assertFalse(isLocalRequest("192.168.1.20", headers()))
    }

    @Test
    fun `without a session only the sign-in and its stylesheet are reachable`() {
        assertTrue(isOpenPath("/login"))
        assertTrue(isOpenPath("/static/app.css"))
        listOf("/", "/config", "/groceries", "/login/x", "/health", "/tracked/add").forEach { assertFalse(isOpenPath(it), it) }
    }

    @Test
    fun `a session is good until it ends, and only with this install's key and the current passcode`() {
        val token = sessionToken(now.plus(SESSION_LENGTH), key, "rosebud")
        assertTrue(validSession(token, now, key, "rosebud"))
        assertTrue(validSession(token, now.plus(Duration.ofDays(29)), key, "rosebud"))
        assertFalse(validSession(token, now.plus(Duration.ofDays(31)), key, "rosebud"), "expired")
        assertFalse(validSession(token, now, key, "changed"), "a new passcode ends old sessions")
        assertFalse(validSession(token, now, "x".repeat(32).toByteArray(), "rosebud"), "another install's key")
    }

    @Test
    fun `a forged or broken session is refused`() {
        val far = now.plus(Duration.ofDays(365)).epochSecond
        val real = sessionToken(now.plus(SESSION_LENGTH), key, "rosebud")
        // The expiry pushed out, the signature kept: the signature covers the expiry.
        assertFalse(validSession("$far.${real.substringAfter('.')}", now, key, "rosebud"))
        listOf(null, "", "abc", "123.", ".abc", "notanumber.abc").forEach { assertFalse(validSession(it, now, key, "rosebud"), "$it") }
    }

    @Test
    fun `the passcode is compared whole, ignoring spaces a phone keyboard adds`() {
        assertTrue(passcodeMatches(" rosebud ", "rosebud"))
        assertFalse(passcodeMatches("rosebu", "rosebud"))
        assertFalse(passcodeMatches("", "rosebud"))
    }

    @Test
    fun `five wrong passcodes lock that visitor for fifteen minutes, and nobody else`() {
        val attempts = SignInAttempts()
        repeat(4) { attempts.failed("1.1.1.1", now) }
        assertFalse(attempts.locked("1.1.1.1", now))
        attempts.failed("1.1.1.1", now)
        assertTrue(attempts.locked("1.1.1.1", now.plusSeconds(60)))
        assertFalse(attempts.locked("2.2.2.2", now.plusSeconds(60)))
        assertFalse(attempts.locked("1.1.1.1", now.plus(Duration.ofMinutes(16))), "the lock ages out")
    }

    @Test
    fun `a guesser changing address is stopped by the overall cap, and signing in clears a visitor's own count`() {
        val attempts = SignInAttempts(perVisitor = 5, overall = 10)
        repeat(10) { attempts.failed("9.9.9.$it", now) }
        assertTrue(attempts.locked("7.7.7.7", now))
        val mine = SignInAttempts()
        repeat(4) { mine.failed("1.1.1.1", now) }
        mine.succeeded("1.1.1.1")
        mine.failed("1.1.1.1", now)
        assertFalse(mine.locked("1.1.1.1", now))
    }

    @Test
    fun `after signing in the visitor goes back to a page of this app only`() {
        assertEquals("/groceries?view=pinned", safeBack("/groceries?view=pinned"))
        listOf(null, "", "https://evil.example/", "//evil.example", "/login", "/login?back=/", "/\\evil").forEach { assertEquals("/", safeBack(it), "$it") }
    }

    @Test
    fun `the passcode comes from the env file, is re-read when it changes, and blank is not set`() {
        val file = File.createTempFile("gate", ".env")
        try {
            file.writeText("POSTGRES_PASSWORD=x\nDEALS_PASSCODE=\n")
            val source = PasscodeSource(file) { null }
            assertNull(source.current())
            file.writeText("DEALS_PASSCODE=\"rosebud\"\n")
            file.setLastModified(file.lastModified() + 5000)
            assertEquals("rosebud", source.current())
            assertEquals("fromenv", PasscodeSource(file) { "fromenv" }.current(), "the environment wins")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the sign-in page has one passcode field, and none when no passcode is set`() {
        val page = signInHtml("/tracked", SignInProblem.WRONG)
        assertTrue("type=\"password\"" in page && "name=\"back\" value=\"/tracked\"" in page.replace("value=\"/tracked\" name=\"back\"", "name=\"back\" value=\"/tracked\""))
        assertTrue("That passcode is not right." in page)
        assertFalse("<form" in signInHtml("/", SignInProblem.NO_PASSCODE))
    }
}
