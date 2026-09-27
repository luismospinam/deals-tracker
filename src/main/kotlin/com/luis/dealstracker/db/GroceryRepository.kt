package com.luis.dealstracker.db

import com.luis.dealstracker.ingest.UnitSize
import com.luis.dealstracker.ingest.unitPrice
import com.luis.dealstracker.model.CardOffer
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.Deal
import java.math.BigDecimal
import java.math.RoundingMode
import javax.sql.DataSource

/**
 * A grocery listing and what its own history says about today's price.
 *
 * "Usual" is the MEDIAN of the item's daily closing prices over the [GroceryRepository.WINDOW_DAYS]
 * before today -- not its list price, which is a claim (Exito flags ~94% of its catalogue as
 * discounted), and not its mean, which one promotional day drags down with it. Today is excluded,
 * so a price cannot be compared against itself.
 */
data class GroceryRow(
    val deal: Deal,
    val size: UnitSize?,
    val group: String?,
    val pinnedAt: java.time.Instant?,
    /** Median daily price over the window, or null with no earlier day to go on. */
    val usual: BigDecimal?,
    /** Cheapest daily price over the same window. */
    val low: BigDecimal?,
    /** How many distinct earlier days the window holds. */
    val days: Int,
    /** False when the latest crawl of its store did not return it -- off the shelf at Laureles. */
    val onShelf: Boolean,
    /** The physical store, e.g. "Exito Colombia" -- the provider's `storeLabel`. */
    val store: String = "",
    /**
     * The same product at the chain's other stores, when this row is the cheapest of several.
     * See `mergeStores` in GroceryViews.
     */
    val others: List<GroceryRow> = emptyList(),
    /**
     * Today's card-only price at this store, from the latest observation. Display only: "vs usual",
     * "30d low" and the deal rule stay on the shelf price, so a one-day card promotion never reads as
     * the item getting cheaper -- nobody without that card could pay it.
     */
    val card: CardOffer? = null,
) {
    val unitPrice: BigDecimal? get() = unitPrice(deal.price, size)

    /** Percent below [usual], positive for cheaper; null until there is enough history to say. */
    val belowUsual: BigDecimal?
        get() {
            val u = usual?.takeIf { days >= MIN_DAYS && it.signum() > 0 } ?: return null
            return BigDecimal.ONE.subtract(deal.price.divide(u, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal(100))
                .setScale(1, RoundingMode.HALF_UP)
        }

    val isDeal: Boolean get() = belowUsual?.let { it >= DEAL_PERCENT } ?: false

    companion object {
        /**
         * Three earlier days before a price is judged. Fewer, and a median is one or two readings --
         * a single promotional day would make the item's ordinary price look like a deal against it.
         */
        const val MIN_DAYS = 3

        /** 10% under usual. A shelf price that moves at all moves in steps well above rounding. */
        val DEAL_PERCENT = BigDecimal(10)
    }
}

/** One grocery source's crawl history, for the page's footer. */
data class SyncStatus(
    val source: String,
    val lastStarted: java.time.Instant?,
    val lastFinished: java.time.Instant?,
    val lastItems: Int?,
    val lastError: String?,
    val lastSuccess: java.time.Instant?,
    val lastSuccessItems: Int?,
    val checkedAt: java.time.Instant = java.time.Instant.now(),
) {
    /**
     * A run started recently and not yet finished.
     *
     * Bounded, because an unfinished row is also what a killed process leaves behind: a restart
     * mid-crawl writes no finish time, and unbounded, that store would read "running…" forever. A
     * crawl takes ~6 minutes, so one still open after [STALL_MINUTES] is [interrupted] instead.
     */
    val running: Boolean
        get() = lastStarted != null && lastFinished == null &&
            lastStarted.isAfter(checkedAt.minus(STALL_MINUTES, java.time.temporal.ChronoUnit.MINUTES))

    /** Unfinished and too old to still be going: the app was stopped mid-crawl. */
    val interrupted: Boolean get() = lastStarted != null && lastFinished == null && !running

    /**
     * The latest attempt failed. A zero-item run counts: the first Carulla crawl "succeeded" with 0
     * products because it misread the store's 406s, and that is a failure to anyone reading prices.
     */
    val failed: Boolean
        get() = !running && !interrupted && lastStarted != null && (lastError != null || (lastItems ?: 0) == 0)

    companion object {
        /** Ten times a normal crawl; long enough for a slow night, short enough to notice. */
        const val STALL_MINUTES = 60L
    }
}

/** What the grocery tab asks for. */
data class GroceryFilter(
    val sources: List<String>,
    /** Subcategory labels; empty means all. */
    val groups: List<String> = emptyList(),
    /** Title search, `|` meaning any -- the feed's own syntax. Applied in the All items view. */
    val search: String? = null,
    /** Pinned rows only. */
    val pinnedOnly: Boolean = false,
    /** One product -- every store's copy of the row with this id -- for the htmx swap after a pin toggle. */
    val id: Long? = null,
    val limit: Int = 30_000,
)

class GroceryRepository(private val ds: DataSource) {

    /**
     * Every active grocery row at [GroceryFilter.sources], with its usual price.
     *
     * One query, with the median computed in SQL by a LATERAL join the same way [DealRepository.feed]
     * builds its min/max aggregate. Doing it in Kotlin would mean loading every observation of
     * ~15,000 rows to keep one number per row.
     *
     * Daily, not per observation: the store is crawled twice a day, and a manual `--fetch-once` adds
     * more, so counting readings would weight a day by how often it was read. Each day is its
     * CHEAPEST reading -- the price a shopper could have paid that day. Using the last one would miss
     * a morning-only promotion, and the second crawl exists to catch exactly those.
     *
     * Rows not on the shelf -- missing from their store's latest successful crawl -- are left out
     * unless pinned. `deactivateStale` would retire them anyway, but only after `staleAfterHours`,
     * and an item Laureles stopped stocking this morning should not be offered at yesterday's price.
     */
    fun rows(filter: GroceryFilter): List<GroceryRow> {
        if (filter.sources.isEmpty()) return emptyList()
        val where = mutableListOf("d.source = ANY (?)", "d.is_active OR d.pinned_at IS NOT NULL")
        val params = mutableListOf<Any>(filter.sources)
        if (filter.groups.isNotEmpty()) {
            where += "d.grocery_group = ANY (?)"
            params.add(filter.groups)
        }
        if (filter.pinnedOnly) where += "d.pinned_at IS NOT NULL"
        filter.id?.let {
            where += "(d.merchant, d.source_deal_id) = (SELECT merchant, source_deal_id FROM deals WHERE id = $it)"
        }
        val terms = parseSearchTerms(filter.search)
        if (terms.isNotEmpty()) {
            // Accent-insensitive like the watches (V9), so `cafe` finds `Café`.
            where += terms.joinToString(" OR ", "(", ")") { "(unaccent(d.title) ILIKE unaccent(?) OR d.title % ?)" }
            terms.forEach { params += "%${escapeLike(it)}%"; params += it }
        }

        val sql = """
            WITH shelf AS (
                SELECT source, max(started_at) AS since FROM fetch_runs
                WHERE finished_at IS NOT NULL AND error IS NULL AND item_count > 0
                GROUP BY source
            )
            SELECT d.*, po.price, po.list_price, po.card_price, po.card_percent, po.card_scope,
                   agg.min_price, agg.max_price, agg.n,
                   0.0 AS relevance,
                   hist.usual, hist.low, COALESCE(hist.days, 0) AS days,
                   (s.since IS NULL OR d.last_seen_at >= s.since) AS on_shelf
            FROM deals d
            LEFT JOIN shelf s ON s.source = d.source
            JOIN LATERAL (
                SELECT price, list_price, card_price, card_percent, card_scope FROM price_observations
                WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
            ) po ON TRUE
            JOIN LATERAL (
                SELECT MIN(price) AS min_price, MAX(price) AS max_price, COUNT(*) AS n
                FROM price_observations WHERE deal_id = d.id
            ) agg ON TRUE
            LEFT JOIN LATERAL (
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY price) AS usual,
                       MIN(price) AS low, COUNT(*) AS days
                FROM (
                    SELECT observed_at::date, MIN(price) AS price
                    FROM price_observations
                    WHERE deal_id = d.id
                      AND observed_at >= current_date - $WINDOW_DAYS
                      AND observed_at < current_date
                    GROUP BY 1
                ) daily
            ) hist ON TRUE
            WHERE ${where.joinToString(" AND ") { "($it)" }}
              AND (s.since IS NULL OR d.last_seen_at >= s.since OR d.pinned_at IS NOT NULL)
            ORDER BY d.title, d.id
            LIMIT ${filter.limit}
        """.trimIndent()

        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                params.forEachIndexed { i, p ->
                    when (p) {
                        is List<*> -> st.setArray(i + 1, conn.createArrayOf("text", p.toTypedArray()))
                        else -> st.setString(i + 1, p.toString())
                    }
                }
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val measure = rs.getString("unit_measure")
                            val quantity = rs.getBigDecimal("unit_quantity")
                            add(
                                GroceryRow(
                                    deal = rs.toDeal(),
                                    size = if (measure != null && quantity != null) UnitSize(measure, quantity) else null,
                                    group = rs.getString("grocery_group"),
                                    pinnedAt = rs.getTimestamp("pinned_at")?.toInstant(),
                                    usual = rs.getBigDecimal("usual")?.setScale(2, RoundingMode.HALF_UP),
                                    low = rs.getBigDecimal("low"),
                                    days = rs.getInt("days"),
                                    onShelf = rs.getBoolean("on_shelf"),
                                    card = rs.getBigDecimal("card_price")?.let { cp ->
                                        CardOffer(
                                            price = cp,
                                            percent = rs.getBigDecimal("card_percent") ?: BigDecimal.ZERO,
                                            scope = runCatching { CardScope.valueOf(rs.getString("card_scope")) }
                                                .getOrDefault(CardScope.OTHER),
                                        )
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Subcategory labels with their active row counts, largest first, for the filter tags. */
    fun groups(sources: List<String>): List<Pair<String, Int>> {
        if (sources.isEmpty()) return emptyList()
        return ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT grocery_group, count(DISTINCT merchant || ':' || source_deal_id) FROM deals " +
                    "WHERE source = ANY (?) AND is_active AND grocery_group IS NOT NULL " +
                    "GROUP BY 1 ORDER BY 2 DESC, 1",
            ).use { st ->
                st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) } }
            }
        }
    }

    /**
     * Each source's most recent crawl, finished or not, and its most recent SUCCESSFUL one.
     *
     * Both, because they answer different questions. The latest run says whether the last attempt
     * worked; the latest success says how old the prices on screen are. After a failed 15:00 crawl
     * the page is still showing 07:00's prices, and saying only "failed" would hide that.
     */
    fun syncStatus(sources: List<String>): List<SyncStatus> {
        if (sources.isEmpty()) return emptyList()
        return ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT s.source, l.started_at, l.finished_at, l.item_count, l.error,
                       ok.started_at AS ok_at, ok.item_count AS ok_items
                FROM unnest(?::text[]) AS s(source)
                LEFT JOIN LATERAL (
                    SELECT started_at, finished_at, item_count, error FROM fetch_runs
                    WHERE source = s.source ORDER BY started_at DESC LIMIT 1
                ) l ON TRUE
                LEFT JOIN LATERAL (
                    SELECT started_at, item_count FROM fetch_runs
                    WHERE source = s.source AND finished_at IS NOT NULL AND error IS NULL AND item_count > 0
                    ORDER BY started_at DESC LIMIT 1
                ) ok ON TRUE
                """.trimIndent(),
            ).use { st ->
                st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                SyncStatus(
                                    source = rs.getString("source"),
                                    lastStarted = rs.getTimestamp("started_at")?.toInstant(),
                                    lastFinished = rs.getTimestamp("finished_at")?.toInstant(),
                                    lastItems = rs.getInt("item_count").takeIf { !rs.wasNull() },
                                    lastError = rs.getString("error"),
                                    lastSuccess = rs.getTimestamp("ok_at")?.toInstant(),
                                    lastSuccessItems = rs.getInt("ok_items").takeIf { !rs.wasNull() },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** How many rows are pinned, for the Pinned tab's badge. */
    fun pinnedCount(sources: List<String>): Int {
        if (sources.isEmpty()) return 0
        return ds.connection.use { conn ->
            conn.prepareStatement("SELECT count(DISTINCT merchant || ':' || source_deal_id) FROM deals WHERE source = ANY (?) AND pinned_at IS NOT NULL").use { st ->
                st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
    }

    /**
     * Pins or unpins a product at every store of its chain among [sources], by the row [id] shown.
     *
     * Chain-wide because the tab shows one row per product per chain, at whichever store is cheaper
     * today. Pinning only the store shown would lose the pin the day the other store became cheaper.
     * Returns the ids of the rows changed, empty when [id] does not exist.
     */
    fun setPinned(id: Long, pinned: Boolean, sources: List<String>): List<Long> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE deals d SET pinned_at = ${if (pinned) "COALESCE(d.pinned_at, now())" else "NULL"}
                FROM deals shown
                WHERE shown.id = ?
                  AND d.source_deal_id = shown.source_deal_id
                  AND d.merchant IS NOT DISTINCT FROM shown.merchant
                  AND (d.id = shown.id OR d.source = ANY (?))
                RETURNING d.id
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, id)
                st.setArray(2, conn.createArrayOf("text", sources.toTypedArray()))
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    companion object {
        /** Days of history the usual price is taken over. */
        const val WINDOW_DAYS = 30
    }
}
