package com.luis.dealstracker.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * Shared HTTP layer for every adapter.
 *
 * Enforces a per-host delay, sends a descriptive User-Agent, honours ETag /
 * Last-Modified so unchanged feeds cost nothing, and retries transient failures
 * with backoff. The point is to keep this tool's footprint well below what a
 * single human browsing the same sites would produce.
 */
class PoliteHttp(
    private val userAgent: String,
    private val perHostDelayMillis: Long,
    private val maxRetries: Int = 3,
) : Closeable {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    private val lastRequestAt = mutableMapOf<String, Long>()
    private val hostLock = Mutex()

    // Conditional-request cache, keyed by URL.
    private val etags = mutableMapOf<String, String>()
    private val lastModified = mutableMapOf<String, String>()

    data class Result(val body: String?, val status: Int, val notModified: Boolean) {
        val ok: Boolean get() = status in 200..299 || notModified
    }

    /**
     * GETs [url], returning `notModified = true` (and a null body) when the server
     * says nothing changed.
     *
     * Note: VTEX returns 206 Partial Content for `_from`/`_to` paged requests, so
     * any success check here has to accept the whole 2xx range rather than just 200.
     */
    suspend fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): Result {
        throttle(Url(url).host)

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                val response: HttpResponse = client.get(url) {
                    header("User-Agent", userAgent)
                    header("Accept-Language", "es-CO,es;q=0.9,en;q=0.8")
                    etags[url]?.let { header("If-None-Match", it) }
                    lastModified[url]?.let { header("If-Modified-Since", it) }
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                }

                if (response.status == HttpStatusCode.NotModified) {
                    return Result(null, 304, notModified = true)
                }

                // Retry rate limiting and server errors; return anything else as-is
                // so the caller can log a real status rather than a generic failure.
                if (response.status.value == 429 || response.status.value >= 500) {
                    attempt++
                    if (attempt >= maxRetries) return Result(null, response.status.value, false)
                    delay(backoffMillis(attempt))
                    continue
                }

                response.headers["ETag"]?.let { etags[url] = it }
                response.headers["Last-Modified"]?.let { lastModified[url] = it }

                return Result(response.bodyAsText(), response.status.value, notModified = false)
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt >= maxRetries) break
                delay(backoffMillis(attempt))
            }
        }
        throw lastError ?: IllegalStateException("GET $url failed after $maxRetries attempts")
    }

    /**
     * POSTs a JSON body, with the same per-host throttle and backoff as [get].
     *
     * Added for Algolia-backed stores, whose search API takes the query in a POST body.
     * Deliberately routed through this class rather than a bare client, so a new adapter
     * cannot accidentally opt out of the politeness this app applies everywhere else — the
     * rate limit is the whole point of the type.
     *
     * No conditional-request caching here: ETags are keyed by URL, and every request to an
     * Algolia index hits the same URL with a different body, so a cached ETag would be
     * matched against the wrong query and return 304 for a request never made.
     */
    suspend fun postJson(
        url: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result {
        throttle(Url(url).host)

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                val response: HttpResponse = client.post(url) {
                    header("User-Agent", userAgent)
                    header("Accept-Language", "es-CO,es;q=0.9,en;q=0.8")
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                if (response.status.value == 429 || response.status.value >= 500) {
                    attempt++
                    if (attempt >= maxRetries) return Result(null, response.status.value, false)
                    delay(backoffMillis(attempt))
                    continue
                }

                return Result(response.bodyAsText(), response.status.value, notModified = false)
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt >= maxRetries) break
                delay(backoffMillis(attempt))
            }
        }
        throw lastError ?: IllegalStateException("POST $url failed after $maxRetries attempts")
    }

    private fun backoffMillis(attempt: Int): Long = perHostDelayMillis * (1L shl attempt)

    private suspend fun throttle(host: String) {
        val waitFor = hostLock.withLock {
            val now = System.currentTimeMillis()
            val earliest = (lastRequestAt[host] ?: 0L) + perHostDelayMillis
            val wait = (earliest - now).coerceAtLeast(0L)
            lastRequestAt[host] = now + wait
            wait
        }
        if (waitFor > 0) delay(waitFor)
    }

    override fun close() = client.close()
}
