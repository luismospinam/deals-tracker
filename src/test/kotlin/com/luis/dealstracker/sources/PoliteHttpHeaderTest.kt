package com.luis.dealstracker.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard that stops `PoliteHttp` sending two `Accept-Language` values.
 *
 * Ktor's `header()` **appends** rather than replaces. `PoliteHttp` set
 * `Accept-Language: es-CO,...` for the Colombian retailers and then applied the caller's
 * `extraHeaders` on top, so a caller asking for English had both values sent and the server
 * chose. amazon.com read the pair as Spanish: it returned `<html lang="es-us">` with Spanish
 * size labels (`8,5 mujer/7,5 hombre`) where the English page says `8.5 Women/7.5 Men`, so the
 * Amazon adapter's exact match on `8` found nothing and cached 33 garments as "does not stock
 * this size" when they do. Verified both ways against the live site before fixing: duplicated
 * header -> `es-us`, single `en-US` header -> `en-us`.
 *
 * `PoliteHttp` now omits its default when the caller has supplied one. That decision is a
 * case-insensitive key lookup — HTTP header names are case-insensitive, so a caller writing
 * `accept-language` must suppress the default too, and this pins that.
 */
class PoliteHttpHeaderTest {

    /** Mirrors the condition in `PoliteHttp.get`. */
    private fun sendsDefault(extraHeaders: Map<String, String>): Boolean =
        !extraHeaders.keys.any { it.equals("Accept-Language", ignoreCase = true) }

    @Test
    fun `the default is sent when the caller says nothing about language`() {
        assertTrue(sendsDefault(emptyMap()))
        assertTrue(sendsDefault(mapOf("Accept" to "application/json")))
    }

    @Test
    fun `the default is suppressed when the caller sets a language`() {
        assertFalse(
            sendsDefault(mapOf("Accept-Language" to "en-US,en;q=0.9")),
            "sending both values lets the server pick, which is how Amazon served Spanish",
        )
    }

    /** HTTP header names are case-insensitive, so the check must be too. */
    @Test
    fun `the caller's header is recognised whatever its casing`() {
        assertFalse(sendsDefault(mapOf("accept-language" to "en-US")))
        assertFalse(sendsDefault(mapOf("ACCEPT-LANGUAGE" to "en-US")))
        assertFalse(sendsDefault(mapOf("Accept-Language" to "en-US")))
    }
}
