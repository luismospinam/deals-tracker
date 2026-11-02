package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.fakeAppConfig
import com.luis.dealstracker.sources.vtex.VtexSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A keyword search over Intelligent Search also asks `catalog_system`, and keeps what the first
 * missed. Measured 2026-11-02 at Exito: `sofacama` finds nothing in Intelligent Search and 24
 * sofa-beds in `catalog_system`; Intelligent Search is the one that carries card prices.
 *
 * Runs against a local HTTP server standing in for the store, so the real request path is used.
 */
class VtexSearchFallbackTest {

    private val requested = mutableListOf<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/io/_v/api/intelligent-search/product_search") { ex ->
            requested += ex.requestURI.toString()
            val q = ex.requestURI.query.orEmpty()
            // Page 1 only; the second page is empty, as when a term has few hits.
            val body = if ("page=1" in q && "query=sofa" == q.substringBefore('&')) {
                """{"products":[${product("111", "Sofa uno", "1000000", "1500000", teaser = true)}]}"""
            } else {
                """{"products":[]}"""
            }
            reply(ex, 406, body) // Intelligent Search answers 406 on success
        }
        createContext("/api/catalog_system/pub/products/search") { ex ->
            requested += ex.requestURI.toString()
            val q = ex.requestURI.query.orEmpty()
            val body = if ("_from=0" in q) {
                // 111 again (must not replace the card-priced copy) and 222, which only this API has.
                "[${product("111", "Sofa uno", "1000000", "1500000", teaser = false)},${product("222", "Sofacama dos", "800000", "1600000", teaser = false)}]"
            } else {
                "[]"
            }
            reply(ex, 206, body)
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun product(id: String, name: String, price: String, list: String, teaser: Boolean): String {
        val teasers = if (teaser) {
            """[{"name":"mastercard_20_x","conditions":{"minimumQuantity":0,"parameters":[{"name":"PaymentMethodId","value":"4"}]},"effects":{"parameters":[{"name":"PercentualDiscount","value":"50"}]}}]"""
        } else {
            "[]"
        }
        return """{"productId":"$id","productName":"$name","linkText":"p-$id","link":"/p-$id/p","items":[{"itemId":"1","sellers":[{"sellerId":"1","commertialOffer":{"Price":$price,"ListPrice":$list,"PriceWithoutDiscount":$list,"AvailableQuantity":5,"teasers":$teasers}}]}]}"""
    }

    private fun cfg(vararg options: Pair<String, String>) = ProviderConfig(
        id = "store", type = "vtex", baseUrl = "http://127.0.0.1:${server.address.port}",
        options = mapOf("pageSize" to "24", "maxPagesPerSearch" to "1", "useIntelligentSearch" to "true") + options,
        listOptions = mapOf("searches" to listOf("sofa")),
    )

    private fun fetch(c: ProviderConfig) = runBlocking {
        PoliteHttp("test", 0).use { VtexSource().fetch(c, fakeAppConfig(), it) }.associateBy { it.externalId }
    }

    @Test
    fun `a search keeps what only catalog_system found, and the card-priced copy wins`() {
        val deals = fetch(cfg())
        assertEquals(setOf("111", "222"), deals.keys)
        assertNotNull(deals.getValue("111").cardOffer, "the Intelligent Search copy keeps its card price")
        assertNull(deals.getValue("222").cardOffer, "a catalog_system row has none")
    }

    @Test
    fun `the fallback can be turned off`() {
        val deals = fetch(cfg("searchFallbackToCatalog" to "false"))
        assertEquals(setOf("111"), deals.keys)
        assertEquals(false, requested.any { "catalog_system" in it })
    }

    @Test
    fun `a category walk never asks catalog_system`() {
        val c = cfg().copy(listOptions = mapOf("categories" to listOf("electrodomesticos")))
        runCatching { fetch(c) }
        assertEquals(false, requested.any { "catalog_system/pub/products/search" in it })
    }
}
