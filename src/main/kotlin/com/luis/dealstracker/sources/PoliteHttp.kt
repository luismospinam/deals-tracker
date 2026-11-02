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
    private val maxCachedChars: Long = 64L * 1024 * 1024,
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

    /**
     * Conditional-request cache, keyed by URL: the validators a server sent with a body, and
     * that body. A 304 says "what you already have is current", which is only useful if we
     * still have it — without the body an adapter read 304 as "no products", so Fullhogar's
     * unchanged collections came back empty on 114 runs in a week and their rows stopped being
     * marked as seen. Least-recently-used entries go once the bodies pass [maxCachedChars].
     */
    private class Cached(val etag: String?, val lastModified: String?, val body: String, val status: Int)

    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {}
    private var cachedChars = 0L

    data class Result(val body: String?, val status: Int, val notModified: Boolean) {
        val ok: Boolean get() = status in 200..299 || notModified
    }

    /**
     * GETs [url]. When the server says nothing changed, returns `notModified = true` with the
     * body last received for that URL, so callers parse an unchanged page exactly like a fresh one.
     *
     * Note: VTEX returns 206 Partial Content for `_from`/`_to` paged requests, so
     * any success check here has to accept the whole 2xx range rather than just 200.
     */
    suspend fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): Result {
        throttle(Url(url).host)
        val cached = synchronized(cache) { cache[url] }

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < maxRetries) {
            try {
                val response: HttpResponse = client.get(url) {
                    header("User-Agent", userAgent)
                    // Only when the caller has not asked for something else.
                    //
                    // `header()` APPENDS rather than replaces, so setting this unconditionally
                    // and letting `extraHeaders` add to it sent BOTH values -- and the server
                    // picks, not us. amazon.com read the pair as Spanish and served
                    // `<html lang="es-us">` with Spanish size labels (`8,5 mujer/7,5 hombre`),
                    // so the Amazon adapter's exact match on `8` found nothing and cached 33
                    // items as "does not stock this size". Verified both ways against the live
                    // site: duplicated header -> es-us, single en-US header -> en-us.
                    if (!extraHeaders.keys.any { it.equals("Accept-Language", ignoreCase = true) }) {
                        header("Accept-Language", "es-CO,es;q=0.9,en;q=0.8")
                    }
                    cached?.etag?.let { header("If-None-Match", it) }
                    cached?.lastModified?.let { header("If-Modified-Since", it) }
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                }

                // Validators are only sent while a body is cached, but check anyway: a 304
                // with nothing to fall back on must not read as an empty page.
                if (response.status == HttpStatusCode.NotModified) {
                    return if (cached != null) Result(cached.body, cached.status, notModified = true)
                    else Result(null, 304, notModified = false)
                }

                // Retry rate limiting and server errors; return anything else as-is
                // so the caller can log a real status rather than a generic failure.
                if (response.status.value == 429 || response.status.value >= 500) {
                    attempt++
                    if (attempt >= maxRetries) return Result(null, response.status.value, false)
                    delay(backoffMillis(attempt))
                    continue
                }

                val body = response.bodyAsText()
                remember(url, response.headers["ETag"], response.headers["Last-Modified"], body, response.status.value)
                return Result(body, response.status.value, notModified = false)
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

    /** Caches a 2xx body that came with a validator; anything else drops what was cached for [url]. */
    private fun remember(url: String, etag: String?, lastModified: String?, body: String, status: Int) =
        synchronized(cache) {
            cache.remove(url)?.let { cachedChars -= it.body.length }
            if (status !in 200..299 || (etag == null && lastModified == null)) return@synchronized
            cache[url] = Cached(etag, lastModified, body, status)
            cachedChars += body.length
            val oldest = cache.entries.iterator()
            while (cachedChars > maxCachedChars && oldest.hasNext()) {
                val evicted = oldest.next()
                if (evicted.key == url) break
                cachedChars -= evicted.value.body.length
                oldest.remove()
            }
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
