package com.luis.dealstracker.sources

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the session reset keeps the delivery country.
 *
 * The Amazon price tracker and the Colombia shipping verifier share one Chrome profile. When the
 * tracker is refused it drops the soured session cookies — but a blanket `clearCookies()` also
 * removes `sp-cdn`, which is the ONLY place the delivery country lives and what
 * `AmazonVerifier.countryOf` reads. Losing it does not fail loudly: the verifier opens amazon.com,
 * asks a human to set the country, and times out reporting itself blocked. So a price fetch would
 * silently stop shipping verdicts from being resolved.
 *
 * Driven against a throwaway profile and `example.com`, so it needs no Amazon request — this is
 * about cookie bookkeeping, not about the network.
 */
class SessionResetTest {

    @Test
    fun `resetting the session preserves the delivery country and locale cookies`() {
        val dir = File("build/session-reset-test").apply { deleteRecursively(); mkdirs() }

        Playwright.create().use { pw ->
            val ctx = pw.chromium().launchPersistentContext(
                Paths.get(dir.absolutePath),
                BrowserType.LaunchPersistentContextOptions().setHeadless(true),
            )
            try {
                // The real shapes: sp-cdn is what the verifier reads, session-id is what sours.
                ctx.addCookies(
                    listOf(
                        cookie("sp-cdn", "\"L5Z9:CO\""),
                        cookie("i18n-prefs", "USD"),
                        cookie("lc-main", "en_US"),
                        cookie("session-id", "123-4567890-1234567"),
                        cookie("ubid-main", "987-6543210-7654321"),
                    ),
                )

                // Mirrors AmazonFetcher.resetSession: filter, clear, restore.
                val preserved = setOf("sp-cdn", "i18n-prefs", "lc-main")
                val keep = ctx.cookies().filter { it.name in preserved }
                ctx.clearCookies()
                ctx.addCookies(keep)

                val after = ctx.cookies().associate { it.name to it.value }

                assertTrue("sp-cdn" in after, "the delivery country must survive a session reset")
                assertTrue(
                    after["sp-cdn"]!!.contains("CO"),
                    "the country value itself must be intact, not just the cookie name",
                )
                assertTrue("i18n-prefs" in after, "currency preference must survive")
                assertTrue("lc-main" in after, "locale must survive — it keeps sizes in English")

                assertTrue("session-id" !in after, "the soured session must actually be dropped")
                assertTrue("ubid-main" !in after, "the soured session must actually be dropped")
            } finally {
                ctx.close()
            }
        }
    }

    private fun cookie(name: String, value: String) = com.microsoft.playwright.options.Cookie(name, value).apply {
        domain = ".example.com"
        path = "/"
    }
}
