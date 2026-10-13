package com.luis.dealstracker.ingest

import com.luis.dealstracker.db.DealSpecStore
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.falabella.SpecPageParser
import javax.sql.DataSource

/** What one run did, for the log line and the CLI. */
data class SpecPageReport(val tried: Int, val stored: Int, val empty: Int, val failed: Int, val unparseable: Int) {
    fun summary() = "[specs] $tried page(s): $stored with specs, $empty with none, $failed failed, $unparseable unparseable"
}

/**
 * Reads the specification table off product pages that publish it nowhere else.
 *
 * Falabella and Homecenter put prices in their search results and the spec table only on each
 * product's own page, so the adapters cannot capture it for free. This is the throttled second step:
 * a small batch per poll cycle, only for products an enabled watch is hunting (see
 * [DealSpecStore.pendingPages]), politely spaced by [PoliteHttp]'s per-host delay. It cannot keep up
 * with a huge new watch in one cycle and is not meant to -- the backlog drains over a few cycles, and
 * `--fetch-specs` drains it faster on demand.
 *
 * Never throws: a failing page is counted and the rest go on, like every other phase of the poll.
 */
class SpecPageJob(
    private val ds: DataSource,
    private val http: PoliteHttp,
    /** The `falabella-next` providers' ids, i.e. the `deals.source` values whose pages carry specs. */
    private val sources: List<String>,
) {
    suspend fun run(limit: Int, watchId: Long? = null): SpecPageReport {
        val targets = runCatching { DealSpecStore.pendingPages(ds, sources, limit, watchId) }
            .getOrElse { println("[specs] could not list pending pages: ${it.message}"); emptyList() }
        var stored = 0
        var empty = 0
        var failed = 0
        var unparseable = 0
        for (target in targets) {
            // A colour variant shares its product page with a deal already read: copy, don't fetch.
            if (DealSpecStore.copyFromSameUrl(ds, target.dealId, target.url)) {
                stored++
                continue
            }
            val result = try {
                http.get(target.url, mapOf("Accept" to "text/html"))
            } catch (e: Exception) {
                failed++
                println("[specs] ${target.url} failed: ${e.message}")
                continue
            }
            // A page that is gone has no specs and never will; marking it stops it being retried.
            if (result.status == 404 || result.status == 410) {
                DealSpecStore.record(ds, target.dealId, com.luis.dealstracker.db.DealSpecs(emptyMap(), null))
                empty++
                continue
            }
            val body = result.body
            if (!result.ok || body == null) {
                failed++
                println("[specs] ${target.url} -> HTTP ${result.status}")
                continue
            }
            val parsed = SpecPageParser.parse(body)
            if (parsed == null) {
                // Not recorded: a redesign must not mark every product as checked.
                unparseable++
                continue
            }
            DealSpecStore.record(ds, target.dealId, parsed)
            if (parsed.specs.isEmpty() && parsed.description == null) empty++ else stored++
        }
        return SpecPageReport(targets.size, stored, empty, failed, unparseable)
    }
}
