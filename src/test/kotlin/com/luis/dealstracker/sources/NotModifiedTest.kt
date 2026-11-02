package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.fakeAppConfig
import com.luis.dealstracker.sources.shopify.ShopifySource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An unchanged page answered `304 Not Modified` must still yield its products.
 *
 * `PoliteHttp` sends `If-None-Match` once it has seen an ETag, and every adapter used to stop at
 * a 304 as if the page were empty. Shopify sends ETags, so Fullhogar recorded 114 zero-item runs
 * in the week to 2026-11-02, all marked `ok`, and its rows went unseen for hours at a time. The
 * cached body is now returned with the 304 and parsed like a fresh one.
 *
 * Runs against a local server standing in for the store, so the real request path is used.
 */
class NotModifiedTest {

    private val etag = "\"v1\""
    private val conditional = mutableListOf<Boolean>()
    private var withEtag = true

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/collections/neveras/products.json") { ex ->
            val revalidating = ex.requestHeaders.getFirst("If-None-Match") == etag
            conditional += revalidating
            if (revalidating) {
                ex.sendResponseHeaders(304, -1)
                ex.close()
            } else {
                reply(ex, PRODUCTS)
            }
        }
        createContext("/plain") { ex -> conditional += ex.requestHeaders.containsKey("If-None-Match"); reply(ex, "x") }
        createContext("/always-304") { ex -> ex.sendResponseHeaders(304, -1); ex.close() }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun reply(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        if (withEtag) ex.responseHeaders.add("ETag", etag)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private val cfg get() = ProviderConfig(
        id = "fullhogar", type = "shopify", baseUrl = base, currency = "COP",
        options = mapOf("pageSize" to "50", "maxPagesPerCollection" to "1"),
        listOptions = mapOf("collections" to listOf("neveras")),
    )

    @Test
    fun `a collection answered 304 still yields its products`() = runBlocking {
        PoliteHttp("test", 0).use { http ->
            val first = ShopifySource().fetch(cfg, fakeAppConfig(), http)
            val second = ShopifySource().fetch(cfg, fakeAppConfig(), http)

            assertEquals(listOf(false, true), conditional, "the second request must revalidate")
            assertEquals(1, first.size)
            assertEquals(first.map { it.externalId to it.price }, second.map { it.externalId to it.price })
        }
    }

    @Test
    fun `the 304 result carries the cached body and reads as ok`() = runBlocking {
        PoliteHttp("test", 0).use { http ->
            val url = "$base/collections/neveras/products.json"
            http.get(url)
            val again = http.get(url)
            assertTrue(again.notModified)
            assertTrue(again.ok)
            assertEquals(PRODUCTS, again.body)
        }
    }

    @Test
    fun `nothing is cached, or revalidated, without a validator`() = runBlocking {
        withEtag = false
        PoliteHttp("test", 0).use { http ->
            http.get("$base/plain")
            http.get("$base/plain")
        }
        assertEquals(listOf(false, false), conditional)
    }

    /** A 304 we never asked for has nothing behind it, so it must read as a failure, not an empty page. */
    @Test
    fun `an unsolicited 304 is not ok`() = runBlocking {
        PoliteHttp("test", 0).use { http ->
            val result = http.get("$base/always-304")
            assertFalse(result.ok)
            assertNull(result.body)
        }
    }

    @Test
    fun `old bodies are evicted once the cache is full`() = runBlocking {
        // Room for one copy of the body only: caching a second URL evicts the first.
        PoliteHttp("test", 0, maxCachedChars = PRODUCTS.length.toLong()).use { http ->
            val url = "$base/collections/neveras/products.json"
            http.get(url)
            http.get("$url?page=2")
            http.get(url)
        }
        assertEquals(listOf(false, false, false), conditional, "an evicted URL is fetched fresh, not revalidated")
    }

    private companion object {
        const val PRODUCTS = """{"products":[{"id":101,"title":"Nevera Mabe RMA250","handle":"nevera-mabe","vendor":"Mabe","variants":[{"id":1,"price":"1645900.00","compare_at_price":"1999900.00","available":true}],"images":[]}]}"""
    }
}
