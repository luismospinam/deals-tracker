package com.luis.dealstracker.db

import java.math.BigDecimal
import javax.sql.DataSource

/**
 * How far back the digest looks. [days] == 1 is "since yesterday"; a longer range compares today
 * against the cheapest reading in that many days (2026-10-14).
 */
enum class DigestRange(val param: String, val days: Int) {
    YESTERDAY("1", 1),
    WEEK("7", 7),
    FORTNIGHT("14", 14);

    companion object {
        /** Unknown or missing values fall back to yesterday, same tolerance as the other filters. */
        fun of(param: String?): DigestRange = entries.firstOrNull { it.param == param } ?: YESTERDAY
    }
}

/**
 * One listing whose price today is lower than it was before today.
 *
 * "Today" is the calendar day in the database's time zone, the same `current_date` the grocery
 * tab's "usual price" uses, so the two never disagree about which reading belongs to which day.
 * [baseline] is what today is compared against. For [DigestRange.YESTERDAY] it is the LAST reading
 * before today -- whichever day that was, so a Monday digest of an item last read on Saturday still
 * compares against Saturday. For a longer range it is the CHEAPEST reading in the window, so a drop
 * means "cheaper than at any point in the past N days". [today] is the latest reading since midnight.
 */
data class DayChange(
    val dealId: Long,
    val baseline: BigDecimal,
    val today: BigDecimal,
    /** Cheapest reading ever recorded before today. */
    val priorLow: BigDecimal,
) {
    /** Cheaper than the [baseline]. */
    val isDrop: Boolean get() = today < baseline

    /**
     * Cheaper than every earlier reading. Strict, so it needs history to beat: a first-ever reading
     * has no [baseline] and never appears here at all -- "the cheapest of one" is not news.
     */
    val isNewLow: Boolean get() = today < priorLow
}

/**
 * Reads what moved since the previous day, from the append-only `price_observations` log.
 *
 * Only DOWNWARD moves are returned. A price rising or an item merely appearing is not what the
 * digest is for, and leaving them out keeps the result small enough to bind as an id list.
 */
class DailyChangeRepository(private val ds: DataSource) {

    /** Changes among the given listings. */
    fun forDeals(dealIds: Collection<Long>, range: DigestRange = DigestRange.YESTERDAY): Map<Long, DayChange> {
        if (dealIds.isEmpty()) return emptyMap()
        return query("SELECT unnest(?::bigint[]) AS deal_id", range) { conn, st, i ->
            st.setArray(i, conn.createArrayOf("bigint", dealIds.toTypedArray()))
        }
    }

    /** Changes among every active listing of the given providers. */
    fun forSources(sources: Collection<String>, range: DigestRange = DigestRange.YESTERDAY): Map<Long, DayChange> {
        if (sources.isEmpty()) return emptyMap()
        return query("SELECT id AS deal_id FROM deals WHERE source = ANY (?) AND is_active", range) { conn, st, i ->
            st.setArray(i, conn.createArrayOf("text", sources.toTypedArray()))
        }
    }

    private fun query(
        candidates: String,
        range: DigestRange,
        bind: (java.sql.Connection, java.sql.PreparedStatement, Int) -> Unit,
    ): Map<Long, DayChange> {
        // One index lookup per listing for its latest reading today and its latest before today, and
        // the all-history minimum only for the few that fell. The previous shape aggregated every
        // reading of every candidate (~1s for the grocery tab). A new low means today is below EVERY
        // earlier price, including yesterday's, so `today < baseline` is a precondition for both
        // conditions the digest reports and the minimum need not be read for anything else.
        // The baseline is the last reading before today, or for a longer range the cheapest one in
        // the window. A listing with no reading in the window (first seen today) has no baseline
        // and drops out of the join, as a first-ever reading always has.
        val baseline = if (range == DigestRange.YESTERDAY) {
            """
                SELECT price FROM price_observations
                WHERE deal_id = c.deal_id AND observed_at < current_date
                ORDER BY observed_at DESC LIMIT 1
            """
        } else {
            """
                SELECT MIN(price) AS price FROM price_observations
                WHERE deal_id = c.deal_id AND observed_at >= current_date - ${range.days}
                  AND observed_at < current_date
                HAVING COUNT(*) > 0
            """
        }
        val sql = """
            SELECT c.deal_id, p.price AS baseline, t.price AS today, l.price AS prior_low
            FROM ($candidates) c
            JOIN LATERAL (
                SELECT price FROM price_observations
                WHERE deal_id = c.deal_id AND observed_at >= current_date
                ORDER BY observed_at DESC LIMIT 1
            ) t ON TRUE
            JOIN LATERAL ($baseline) p ON TRUE
            JOIN LATERAL (
                SELECT MIN(price) AS price FROM price_observations
                WHERE deal_id = c.deal_id AND observed_at < current_date AND t.price < p.price
            ) l ON TRUE
            WHERE t.price < p.price
        """.trimIndent()
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                bind(conn, st, 1)
                st.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val id = rs.getLong("deal_id")
                            put(id, DayChange(id, rs.getBigDecimal("baseline"), rs.getBigDecimal("today"), rs.getBigDecimal("prior_low")))
                        }
                    }
                }
            }
        }
    }
}
