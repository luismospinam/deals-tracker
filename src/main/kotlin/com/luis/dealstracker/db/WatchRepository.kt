package com.luis.dealstracker.db

import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchAlert
import com.luis.dealstracker.model.WatchCandidate
import com.luis.dealstracker.model.WatchMatch
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

/** A watch's fields as submitted by the form, before it has an id. */
data class WatchDraft(
    val name: String,
    val terms: String,
    val excludeTerms: String? = null,
    val categoryGroup: String? = null,
    val sources: List<String> = emptyList(),
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val currency: String = "COP",
    val lookbackDays: Int = 30,
    val crossSiteMinSpread: BigDecimal = BigDecimal("15.00"),
    val enabled: Boolean = true,
)

/**
 * An alert that survived the ledger check and should actually be announced.
 *
 * Separate from [WatchAlert] because "this rule fired" and "this is news" are different
 * questions: a standing price re-fires its rule on every cycle and must stay silent.
 */
data class PendingNotification(
    val matchId: Long,
    val watchName: String,
    val alert: WatchAlert,
    val title: String,
    val source: String,
    val currency: String,
)

class WatchRepository(private val ds: DataSource) {

    // --- watches ---

    fun all(): List<Watch> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM watches ORDER BY enabled DESC, created_at DESC").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toWatch()) } }
        }
    }

    fun enabled(): List<Watch> = all().filter { it.enabled }

    fun findById(id: Long): Watch? = ds.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM watches WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toWatch() else null }
        }
    }

    /**
     * Every search term across enabled watches, for [com.luis.dealstracker.ingest.IngestService]
     * to hand to the adapters.
     *
     * This is the half of a watch that steers fetching rather than filtering it, and it is
     * why a fridge gets found on a site whose configured categories are all groceries.
     * Sources that cannot keyword-search — Homecenter's robots.txt disallows its search
     * URLs — simply ignore these and are matched on what their category crawl brings back.
     */
    fun activeSearchTerms(): List<String> =
        enabled().flatMap { parseSearchTerms(it.terms) }.distinct()

    fun create(draft: WatchDraft): Long = ds.connection.use { conn ->
        val sql = """
            INSERT INTO watches (name, terms, exclude_terms, category_group, sources,
                                 min_price, max_price, currency, lookback_days,
                                 cross_site_min_spread, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.bindDraft(conn, draft)
            st.executeQuery().use { rs -> rs.next(); rs.getLong("id") }
        }
    }

    fun update(id: Long, draft: WatchDraft): Boolean = ds.connection.use { conn ->
        val sql = """
            UPDATE watches SET name = ?, terms = ?, exclude_terms = ?, category_group = ?,
                               sources = ?, min_price = ?, max_price = ?, currency = ?,
                               lookback_days = ?, cross_site_min_spread = ?, enabled = ?
            WHERE id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.bindDraft(conn, draft)
            st.setLong(12, id)
            st.executeUpdate() > 0
        }
    }

    /** Flips enabled without touching anything else, for the pause button. */
    fun setEnabled(id: Long, enabled: Boolean): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("UPDATE watches SET enabled = ? WHERE id = ?").use { st ->
            st.setBoolean(1, enabled)
            st.setLong(2, id)
            st.executeUpdate() > 0
        }
    }

    /** Matches cascade, per the FK in V7 — a match is meaningless without its watch. */
    fun delete(id: Long): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("DELETE FROM watches WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeUpdate() > 0
        }
    }

    // --- candidates ---

    /**
     * Deals satisfying a watch's filters, with the history needed to score them.
     *
     * Term matching reuses the feed's `title ILIKE ? OR title % ?` pair and therefore the
     * existing `deals_title_trgm_idx` GIN index — ILIKE for exact substrings, trigram
     * similarity for the fuzzy tail. Same semantics as the search box, so a watch behaves
     * the way the UI already taught you to expect.
     *
     * The `prior` lateral is the subtle part: it deliberately excludes the latest
     * observation (`observed_at < po.observed_at`), because a floor containing the price
     * under test is one that price can never beat. See [WatchCandidate.priorFloor].
     */
    fun candidates(watch: Watch): List<WatchCandidate> {
        val where = mutableListOf("d.is_active")
        val params = mutableListOf<Any>()

        // Dismissed deals are excluded: dismissing something is exactly the instruction not
        // to be told about it again.
        where += "d.state <> 'DISMISSED'"

        val terms = parseSearchTerms(watch.terms)
        require(terms.isNotEmpty()) { "watch ${watch.id} has no usable terms" }
        // unaccent on both sides, so one term matches Exito's "Nevecon" and Carulla's
        // "Nevecón" alike -- see V9__unaccent. The trigram clause stays on the raw title so
        // it still uses deals_title_trgm_idx for the fuzzy tail.
        where += terms.joinToString(" OR ", prefix = "(", postfix = ")") {
            params += "%${escapeLike(it)}%"
            params += it
            "unaccent(d.title) ILIKE unaccent(?) OR d.title % ?"
        }

        parseSearchTerms(watch.excludeTerms).takeIf { it.isNotEmpty() }?.let { excludes ->
            // Substring-only, no trigram: an exclusion should be predictable. Fuzzy matching
            // here would silently drop legitimate results. Accent-folded for the same reason
            // as the include terms -- "portatil" must also exclude "portátil".
            where += excludes.joinToString(" AND ") {
                params += "%${escapeLike(it)}%"
                "unaccent(d.title) NOT ILIKE unaccent(?)"
            }
        }

        watch.categoryGroup?.takeIf { it.isNotBlank() }?.let {
            where += "d.category_group = ?"
            params += it
        }
        if (watch.sources.isNotEmpty()) {
            where += "d.source = ANY (?)"
            // .add, not +=: `params += aList` calls addAll and splats the list into separate
            // parameters, leaving the placeholders misaligned. Same trap as DealRepository.
            params.add(watch.sources)
        }
        // The two price bounds are deliberately asymmetric, and this is not a slip.
        //
        // `minPrice` answers "is this the class of thing I mean" -- it is how a fridge is
        // told from a fridge magnet. So it is tested against the HIGHEST price ever recorded
        // for the listing, not today's. Testing today's price silently discards exactly the
        // deal you most wanted: a fridge marked down from 1.1M to 899k drops below an 800k
        // junk floor and vanishes, while a 20k organiser was never near it either way.
        //
        // `maxPrice` is the budget, and a budget is about what you would pay now, so that
        // one does test the current price.
        watch.minPrice?.let {
            where += "hi.top >= ?"
            params += it
        }
        watch.maxPrice?.let {
            where += "po.price <= ?"
            params += it
        }

        val sql = """
            SELECT d.id, d.source, d.title, d.url, d.match_key, d.model_key, po.price, prior.floor AS prior_floor
            FROM deals d
            JOIN LATERAL (
                SELECT price, observed_at FROM price_observations
                WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
            ) po ON TRUE
            LEFT JOIN LATERAL (
                SELECT MIN(price) AS floor FROM price_observations
                WHERE deal_id = d.id
                  AND observed_at >= now() - make_interval(days => ?)
                  AND observed_at < po.observed_at
            ) prior ON TRUE
            -- Dearest this listing has actually been, which is what minPrice is judged
            -- against. Deliberately MAX(price) and NOT list_price: a retailer's "before"
            -- price is a claim, not a fact, and trusting it here let an 8-bottle wine cooler
            -- selling at 529,900 clear a 1,000,000 fridge floor on the strength of a
            -- self-declared 1,399,900 list price. Exito flags ~94% of its catalog as
            -- discounted, so that hole is wide, not theoretical.
            --
            -- The cost is that a listing first seen already on sale is judged on the sale
            -- price. That is the right way to be wrong: it can only ever exclude something,
            -- and one more poll at the normal price fixes it.
            JOIN LATERAL (
                SELECT MAX(price) AS top FROM price_observations WHERE deal_id = d.id
            ) hi ON TRUE
            WHERE ${where.joinToString(" AND ")}
        """.trimIndent()

        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                // The window parameter is first: it sits in the LEFT JOIN LATERAL, which the
                // planner places ahead of every WHERE predicate.
                st.setInt(1, watch.lookbackDays)
                bind(conn, st, params, offset = 1)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WatchCandidate(
                                    dealId = rs.getLong("id"),
                                    source = rs.getString("source"),
                                    title = rs.getString("title"),
                                    url = rs.getString("url"),
                                    price = rs.getBigDecimal("price"),
                                    priorFloor = rs.getBigDecimal("prior_floor"),
                                    // Both tiers, deduped: a listing often carries a GTIN
                                    // key and a model key, and either may be the one that
                                    // matches a rival at another retailer.
                                    keys = listOfNotNull(
                                        rs.getString("match_key"),
                                        rs.getString("model_key"),
                                    ).distinct(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // --- the alert ledger ---

    /**
     * Writes alerts to the ledger and returns only the ones worth announcing.
     *
     * The ledger exists so a rule that keeps being true does not keep being announced. A
     * standing price re-fires `LOWEST_IN_WINDOW` on every cycle forever; what is news is a
     * *new* low. So an alert is returned when it has never been notified, or when it beats
     * the price we last notified about.
     */
    fun recordAlerts(watch: Watch, alerts: List<WatchAlert>): List<PendingNotification> {
        if (alerts.isEmpty()) return emptyList()

        val sql = """
            INSERT INTO watch_matches (watch_id, deal_id, reason, price_at_match, evidence)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (watch_id, deal_id, reason) DO UPDATE SET
                price_at_match = EXCLUDED.price_at_match,
                evidence       = EXCLUDED.evidence,
                matched_at     = now()
            RETURNING id, notified_at, notified_price
        """.trimIndent()

        return ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val pending = mutableListOf<Pair<Long, WatchAlert>>()
                alerts.forEach { alert ->
                    conn.prepareStatement(sql).use { st ->
                        st.setLong(1, watch.id)
                        st.setLong(2, alert.dealId)
                        st.setString(3, alert.reason.name)
                        st.setBigDecimal(4, alert.price)
                        st.setString(5, alert.evidence)
                        st.executeQuery().use { rs ->
                            rs.next()
                            val matchId = rs.getLong("id")
                            val notifiedAt = rs.getTimestamp("notified_at")
                            val notifiedPrice = rs.getBigDecimal("notified_price")
                            val isNews = notifiedAt == null ||
                                (notifiedPrice != null && alert.price < notifiedPrice)
                            if (isNews) pending += matchId to alert
                        }
                    }
                }
                conn.commit()
                // Titles and sources are looked up after the fact so the write stays a tight
                // transaction rather than joining deals inside it.
                hydrate(conn, watch, pending)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun hydrate(
        conn: Connection,
        watch: Watch,
        pending: List<Pair<Long, WatchAlert>>,
    ): List<PendingNotification> {
        if (pending.isEmpty()) return emptyList()
        val byDeal = mutableMapOf<Long, Pair<String, String>>()
        conn.prepareStatement("SELECT id, title, source FROM deals WHERE id = ANY (?)").use { st ->
            st.setArray(1, conn.createArrayOf("bigint", pending.map { it.second.dealId }.toTypedArray()))
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    byDeal[rs.getLong("id")] = rs.getString("title") to rs.getString("source")
                }
            }
        }
        return pending.mapNotNull { (matchId, alert) ->
            val (title, source) = byDeal[alert.dealId] ?: return@mapNotNull null
            PendingNotification(matchId, watch.name, alert, title, source, watch.currency)
        }
    }

    /**
     * Records that a notification went out, which is what suppresses the repeat.
     *
     * Called only after a successful send: leaving `notified_at` null on failure means the
     * next cycle retries rather than silently swallowing the alert.
     */
    fun markNotified(sent: List<PendingNotification>) {
        if (sent.isEmpty()) return
        ds.connection.use { conn ->
            val sql = "UPDATE watch_matches SET notified_at = now(), notified_price = ? WHERE id = ?"
            conn.prepareStatement(sql).use { st ->
                sent.forEach {
                    st.setBigDecimal(1, it.alert.price)
                    st.setLong(2, it.matchId)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    /** Ledger rows for a watch, newest first — the watch detail page. */
    fun matches(watchId: Long, limit: Int = 200): List<WatchMatch> = ds.connection.use { conn ->
        val sql = """
            SELECT * FROM watch_matches WHERE watch_id = ?
            ORDER BY matched_at DESC, id DESC LIMIT ?
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setLong(1, watchId)
            st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toWatchMatch()) } }
        }
    }

    /** Per-watch counters for the list page. */
    fun matchCounts(): Map<Long, Pair<Int, Int>> = ds.connection.use { conn ->
        val sql = """
            SELECT watch_id, COUNT(*) AS total, COUNT(*) FILTER (WHERE notified_at IS NULL) AS unseen
            FROM watch_matches GROUP BY watch_id
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getLong("watch_id"), rs.getInt("total") to rs.getInt("unseen"))
                    }
                }
            }
        }
    }
}

private fun PreparedStatement.bindDraft(conn: Connection, d: WatchDraft) {
    setString(1, d.name)
    setString(2, d.terms)
    setString(3, d.excludeTerms)
    setString(4, d.categoryGroup)
    setArray(5, conn.createArrayOf("text", d.sources.toTypedArray()))
    setBigDecimal(6, d.minPrice)
    setBigDecimal(7, d.maxPrice)
    setString(8, d.currency)
    setInt(9, d.lookbackDays)
    setBigDecimal(10, d.crossSiteMinSpread)
    setBoolean(11, d.enabled)
}

/** Positional binder, dispatching on Kotlin type. Mirrors `DealRepository.bind`. */
private fun bind(conn: Connection, st: PreparedStatement, params: List<Any>, offset: Int = 0) {
    params.forEachIndexed { i, value ->
        val at = i + 1 + offset
        when (value) {
            is String -> st.setString(at, value)
            is BigDecimal -> st.setBigDecimal(at, value)
            is Int -> st.setInt(at, value)
            is Instant -> st.setTimestamp(at, java.sql.Timestamp.from(value))
            is List<*> -> st.setArray(at, conn.createArrayOf("text", value.toTypedArray()))
            else -> st.setObject(at, value)
        }
    }
}

private fun ResultSet.toWatch() = Watch(
    id = getLong("id"),
    name = getString("name"),
    terms = getString("terms"),
    excludeTerms = getString("exclude_terms"),
    categoryGroup = getString("category_group"),
    sources = (getArray("sources")?.array as? Array<*>)?.mapNotNull { it as? String } ?: emptyList(),
    minPrice = getBigDecimal("min_price"),
    maxPrice = getBigDecimal("max_price"),
    currency = getString("currency"),
    lookbackDays = getInt("lookback_days"),
    crossSiteMinSpread = getBigDecimal("cross_site_min_spread"),
    enabled = getBoolean("enabled"),
    createdAt = getTimestamp("created_at").toInstant(),
)

private fun ResultSet.toWatchMatch() = WatchMatch(
    id = getLong("id"),
    watchId = getLong("watch_id"),
    dealId = getLong("deal_id"),
    reason = MatchReason.valueOf(getString("reason")),
    matchedAt = getTimestamp("matched_at").toInstant(),
    priceAtMatch = getBigDecimal("price_at_match"),
    notifiedAt = getTimestamp("notified_at")?.toInstant(),
    notifiedPrice = getBigDecimal("notified_price"),
    evidence = getString("evidence"),
)
