package com.luis.dealstracker.db

import java.math.BigDecimal
import javax.sql.DataSource

/**
 * One listing whose price today is lower than it was before today.
 *
 * "Today" is the calendar day in the database's time zone, the same `current_date` the grocery
 * tab's "usual price" uses, so the two never disagree about which reading belongs to which day.
 * [yesterday] is the LAST reading before today -- whichever day that was, so a Monday digest of an
 * item last read on Saturday still compares against Saturday -- and [today] the latest one since
 * midnight.
 */
data class DayChange(
    val dealId: Long,
    val yesterday: BigDecimal,
    val today: BigDecimal,
    /** Cheapest reading ever recorded before today. */
    val priorLow: BigDecimal,
) {
    /** Cheaper than the last reading before today. */
    val isDrop: Boolean get() = today < yesterday

    /**
     * Cheaper than every earlier reading. Strict, so it needs history to beat: a first-ever reading
     * has no [yesterday] and never appears here at all -- "the cheapest of one" is not news.
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
    fun forDeals(dealIds: Collection<Long>): Map<Long, DayChange> {
        if (dealIds.isEmpty()) return emptyMap()
        return query("SELECT unnest(?::bigint[]) AS deal_id") { conn, st, i ->
            st.setArray(i, conn.createArrayOf("bigint", dealIds.toTypedArray()))
        }
    }

    /** Changes among every active listing of the given providers. */
    fun forSources(sources: Collection<String>): Map<Long, DayChange> {
        if (sources.isEmpty()) return emptyMap()
        return query("SELECT id AS deal_id FROM deals WHERE source = ANY (?) AND is_active") { conn, st, i ->
            st.setArray(i, conn.createArrayOf("text", sources.toTypedArray()))
        }
    }

    private fun query(
        candidates: String,
        bind: (java.sql.Connection, java.sql.PreparedStatement, Int) -> Unit,
    ): Map<Long, DayChange> {
        // One index lookup per listing for its latest reading today and its latest before today, and
        // the all-history minimum only for the few that fell. The previous shape aggregated every
        // reading of every candidate (~1s for the grocery tab). A new low means today is below EVERY
        // earlier price, including yesterday's, so `today < yesterday` is a precondition for both
        // conditions the digest reports and the minimum need not be read for anything else.
        val sql = """
            SELECT c.deal_id, p.price AS yesterday, t.price AS today, l.price AS prior_low
            FROM ($candidates) c
            JOIN LATERAL (
                SELECT price FROM price_observations
                WHERE deal_id = c.deal_id AND observed_at >= current_date
                ORDER BY observed_at DESC LIMIT 1
            ) t ON TRUE
            JOIN LATERAL (
                SELECT price FROM price_observations
                WHERE deal_id = c.deal_id AND observed_at < current_date
                ORDER BY observed_at DESC LIMIT 1
            ) p ON TRUE
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
                            put(id, DayChange(id, rs.getBigDecimal("yesterday"), rs.getBigDecimal("today"), rs.getBigDecimal("prior_low")))
                        }
                    }
                }
            }
        }
    }
}
