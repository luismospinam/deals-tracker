package com.luis.dealstracker.db

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * What a price reading says, for deciding whether the next one adds anything.
 *
 * `stock_qty` is left out on purpose: grocery stock reads 10,000 ("plenty") almost always, and
 * counting it would keep ~12k extra rows in exchange for nothing the app shows.
 * [promotions] is in, because `observation_promotions` is the only record of *why* a price moved.
 */
internal data class ReadingSignature(
    val price: BigDecimal,
    val listPrice: BigDecimal?,
    val currency: String,
    val cardPrice: BigDecimal?,
    val cardPercent: BigDecimal?,
    val cardScope: String?,
    val promotions: List<Long>,
) {
    companion object {
        // numeric(14,2) columns arrive with a fixed scale, but a data class compares BigDecimal by
        // scale as well as value, so normalise rather than lean on that.
        fun of(
            price: BigDecimal, listPrice: BigDecimal?, currency: String, cardPrice: BigDecimal?,
            cardPercent: BigDecimal?, cardScope: String?, promotions: List<Long>,
        ) = ReadingSignature(
            price.stripTrailingZeros(), listPrice?.stripTrailingZeros(), currency,
            cardPrice?.stripTrailingZeros(), cardPercent?.stripTrailingZeros(), cardScope, promotions.sorted(),
        )
    }
}

internal data class Reading(val id: Long, val observedAt: Instant, val signature: ReadingSignature)

/**
 * The ids of readings that repeat their predecessor and can go.
 *
 * [readings] is ONE deal's, oldest first. A reading is redundant when it is older than [cutoff] and
 * identical to the one before it, so the first reading of every unchanged stretch survives and so does
 * every reading where something changed. Two more are always kept:
 *
 *  - **the first**, which has no predecessor; and
 *  - **the last**, because it is the deal's current price. A deal nobody has seen in weeks has its
 *    final reading in the old zone, and deleting it would make the last known price vanish.
 *
 * Equality with the *original* predecessor is enough: within a stretch it is transitive, so every
 * member equals the first one.
 */
internal fun redundantReadings(readings: List<Reading>, cutoff: Instant): List<Long> {
    if (readings.size < 3) return emptyList()
    return (1 until readings.lastIndex).mapNotNull { i ->
        val r = readings[i]
        if (r.observedAt < cutoff && r.signature == readings[i - 1].signature) r.id else null
    }
}

/** Whether the periodic prune is due: never run, or [everyDays] have passed. 0 or less means never. */
internal fun pruneDue(lastRun: Instant?, now: Instant, everyDays: Int): Boolean =
    everyDays > 0 && (lastRun == null || Duration.between(lastRun, now) >= Duration.ofDays(everyDays.toLong()))

data class PruneReport(
    val scanned: Long,
    val deleted: Long,
    val bySource: Map<String, Long>,
    val dryRun: Boolean,
) {
    fun summary(): String = buildString {
        val verb = if (dryRun) "would delete" else "deleted"
        appendLine("scanned $scanned readings; $verb $deleted (${if (scanned > 0) deleted * 100 / scanned else 0}%)")
        bySource.entries.sortedByDescending { it.value }.forEach { appendLine("  ${it.key.padEnd(30)} ${it.value}") }
        if (dryRun) append("dry run: nothing deleted")
    }.trimEnd()
}

/**
 * Thins `price_observations`: see [redundantReadings] for the rule and README "Price history" for why.
 *
 * Deletes cascade to `observation_promotions`. Pinned items are skipped entirely. The rule runs in
 * Kotlin over a deal-id range at a time rather than as one SQL statement, so it is testable without a
 * database and a run never holds more than one batch in memory.
 */
class HistoryPruner(private val ds: DataSource) {

    fun run(olderThanDays: Int, dryRun: Boolean, now: Instant = Instant.now()): PruneReport {
        require(olderThanDays > 0) { "olderThanDays must be positive" }
        val cutoff = now.minus(Duration.ofDays(olderThanDays.toLong()))
        var scanned = 0L
        var deleted = 0L
        val bySource = mutableMapOf<String, Long>()

        val (minId, maxId) = ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MIN(id), 0), COALESCE(MAX(id), -1) FROM deals").use { rs ->
                    rs.next(); rs.getLong(1) to rs.getLong(2)
                }
            }
        }

        var from = minId
        while (from <= maxId) {
            val to = from + DEAL_BATCH
            val victims = mutableListOf<Long>()
            ds.connection.use { conn ->
                conn.prepareStatement(SELECT_BATCH).use { st ->
                    st.setLong(1, from)
                    st.setLong(2, to)
                    st.executeQuery().use { rs ->
                        var dealId = -1L
                        var source = ""
                        var current = mutableListOf<Reading>()
                        fun flush() {
                            if (current.isEmpty()) return
                            val ids = redundantReadings(current, cutoff)
                            if (ids.isNotEmpty()) {
                                victims += ids
                                bySource.merge(source, ids.size.toLong(), Long::plus)
                            }
                            current = mutableListOf()
                        }
                        while (rs.next()) {
                            if (rs.getLong("deal_id") != dealId) {
                                flush()
                                dealId = rs.getLong("deal_id")
                                source = rs.getString("source")
                            }
                            @Suppress("UNCHECKED_CAST")
                            val promos = (rs.getArray("promos").array as Array<Long>).toList()
                            current += Reading(
                                rs.getLong("id"),
                                rs.getTimestamp("observed_at").toInstant(),
                                ReadingSignature.of(
                                    rs.getBigDecimal("price"), rs.getBigDecimal("list_price"), rs.getString("currency"),
                                    rs.getBigDecimal("card_price"), rs.getBigDecimal("card_percent"),
                                    rs.getString("card_scope"), promos,
                                ),
                            )
                            scanned++
                        }
                        flush()
                    }
                }
            }
            if (!dryRun && victims.isNotEmpty()) {
                victims.chunked(DELETE_CHUNK).forEach { chunk ->
                    ds.connection.use { conn ->
                        conn.prepareStatement("DELETE FROM price_observations WHERE id = ANY (?)").use { st ->
                            st.setArray(1, conn.createArrayOf("bigint", chunk.toTypedArray()))
                            st.executeUpdate()
                        }
                    }
                }
            }
            deleted += victims.size
            from = to
        }

        // Deleting leaves dead tuples; refresh statistics so the planner sees the smaller table.
        if (!dryRun && deleted > 0) {
            ds.connection.use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { it.execute("VACUUM (ANALYZE) price_observations") }
            }
        }
        return PruneReport(scanned, deleted, bySource, dryRun)
    }

    private companion object {
        const val DEAL_BATCH = 1_000L
        const val DELETE_CHUNK = 10_000

        // Pinned items are excluded from the scan, so they are never candidates. ORDER BY makes each
        // deal's readings contiguous and oldest first, which is what redundantReadings expects.
        const val SELECT_BATCH = """
            SELECT po.deal_id, d.source, po.id, po.observed_at, po.price, po.list_price, po.currency,
                   po.card_price, po.card_percent, po.card_scope,
                   COALESCE((SELECT array_agg(op.promotion_id ORDER BY op.promotion_id)
                             FROM observation_promotions op WHERE op.observation_id = po.id),
                            '{}'::bigint[]) AS promos
            FROM price_observations po
            JOIN deals d ON d.id = po.deal_id
            WHERE po.deal_id >= ? AND po.deal_id < ? AND d.pinned_at IS NULL
            ORDER BY po.deal_id, po.observed_at, po.id
        """
    }
}
