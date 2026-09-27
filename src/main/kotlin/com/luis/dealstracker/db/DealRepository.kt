package com.luis.dealstracker.db

import com.luis.dealstracker.ingest.ProductIdentity
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.model.PriceObservation
import com.luis.dealstracker.model.RawDeal
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class UpsertResult(val dealId: Long, val isNew: Boolean)

data class FeedFilter(
    val sources: List<String> = emptyList(),
    /**
     * Sources left out unless [sources] names them. The feed uses it to keep out the providers
     * that fetch items whether or not they are discounted — the size tracker and the watches'
     * Amazon search — since the feed's job is ranking offers. See `feedHiddenSources`.
     */
    val excludeSources: List<String> = emptyList(),
    val search: String? = null,
    val minDiscount: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val minPrice: BigDecimal? = null,
    val states: List<DealState> = listOf(DealState.NEW, DealState.SEEN, DealState.FAVORITE),
    val coShipping: List<CoShipping> = emptyList(),
    val minScore: Int? = null,
    val maxAgeDays: Int? = null,
    val categoryGroups: List<String> = emptyList(),
    val onlyPromoted: Boolean = false,
    val onlyLowestEver: Boolean = false,
    val sort: String = "relevance",
    /**
     * Includes deals retired by `deactivateStale`, which every other query excludes.
     *
     * The favorites screen is the only caller, and it needs this because retirement is keyed on
     * `last_seen_at` alone — `deactivateStale` never reads `state`, so a favorited deal whose
     * source stops returning it drops out after 48h exactly like an unfavorited one. A favorite
     * disappearing reads as "it was never saved" rather than "the offer ended", and the second is
     * the useful fact: the row and its full price history are still here.
     *
     * Default false, so no existing query changes behaviour by omission.
     */
    val includeInactive: Boolean = false,
    // High enough that the whole feed renders in one page at current volume (a few
    // hundred deals across four sources). The UI states the total when this does cap
    // the page, so truncation is visible rather than silent. Proper pagination is the
    // fix once volume outgrows this.
    val limit: Int = 1000,
)

/** Most terms anyone would type; guards against a pathological query from a pasted blob. */
private const val MAX_SEARCH_TERMS = 20

/**
 * Splits a search box value on `|` into independent terms, OR'd together by [DealRepository.feed].
 *
 * `"airpods | airtag"` -> `["airpods", "airtag"]`, while a value with no pipe yields a
 * single term and behaves exactly as a plain search did.
 */
fun parseSearchTerms(search: String?): List<String> {
    if (search.isNullOrBlank()) return emptyList()
    return search.split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_SEARCH_TERMS)
}

/**
 * Escapes LIKE metacharacters so a literal `%` or `_` in a search term is matched as
 * typed. Postgres treats backslash as LIKE's default escape character, so no explicit
 * ESCAPE clause is needed.
 */
internal fun escapeLike(term: String): String =
    term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * @param feedRotationHours how often the feed's default ordering reshuffles among comparable
 *   deals; 0 for a pure score ordering. Repository-wide policy rather than a per-query flag,
 *   so the feed cannot accidentally be rendered with rotation on in one place and off in
 *   another. Only the default sort is affected — see `DealScore.rotating`.
 */
class DealRepository(
    private val ds: DataSource,
    private val feedRotationHours: Int = 3,
) {

    private fun <T> tx(block: (Connection) -> T): T =
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

    /**
     * Inserts or refreshes a deal. Idempotent on (source, source_deal_id), so
     * re-running a fetch never duplicates rows.
     *
     * Deliberately does NOT touch `state` or `co_shipping` on conflict: those are
     * Luis's decisions, and a later poll must not reset a dismissal or wipe out a
     * manual Colombia-shipping verdict.
     */
    fun upsertDeal(
        conn: Connection,
        source: String,
        raw: RawDeal,
        coShippingDefault: CoShipping,
        categoryGroup: String?,
    ): UpsertResult {
        // Derived here rather than in each adapter, so an adapter only has to publish what
        // the source gave it (brand, gtin, model) and every current and future source gets
        // cross-site identity for free.
        //
        // A published model is still run through modelCode rather than trusted verbatim, so
        // both routes canonicalise identically. Homecenter sends "RS23T5B00S9/CO" while a
        // title elsewhere yields "RS23T5B00S9"; without the shared pass those two would be
        // different keys for one fridge.
        val modelCode = raw.model?.let { ProductIdentity.modelCode(it, raw.brand) }
            ?: ProductIdentity.modelCode(raw.title, raw.brand)
        val matchKey = ProductIdentity.matchKey(raw.gtin, raw.brand, modelCode)
        // Computed even when a GTIN was found, so a barcode-publishing retailer can still be
        // compared against one that publishes none. See V10__model_key.
        val modelKey = ProductIdentity.modelKey(raw.brand, modelCode)

        val sql = """
            INSERT INTO deals (source, source_deal_id, title, url, merchant, asin, category,
                               image_url, currency, co_shipping, community_score, posted_at,
                               category_group, gtin, model_code, match_key, model_key, brand,
                               is_active, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, now(), now())
            ON CONFLICT (source, source_deal_id) DO UPDATE SET
                title      = EXCLUDED.title,
                url        = EXCLUDED.url,
                merchant   = COALESCE(EXCLUDED.merchant, deals.merchant),
                asin       = COALESCE(EXCLUDED.asin, deals.asin),
                category   = COALESCE(EXCLUDED.category, deals.category),
                image_url  = COALESCE(EXCLUDED.image_url, deals.image_url),
                -- Votes accrue over time, so refresh rather than keep the first value.
                community_score = COALESCE(EXCLUDED.community_score, deals.community_score),
                posted_at  = COALESCE(EXCLUDED.posted_at, deals.posted_at),
                -- Recomputed each poll so editing categories.yaml takes effect on refresh.
                category_group = COALESCE(EXCLUDED.category_group, deals.category_group),
                gtin       = COALESCE(EXCLUDED.gtin, deals.gtin),
                -- COALESCE in this direction gives both behaviours that matter: a newly
                -- derived key overwrites (so a retailer fixing a typo'd model code fixes
                -- the clustering too), while a poll that simply did not publish a code
                -- leaves the existing key alone. Plain assignment would drop a good key to
                -- NULL whenever a title got less informative, silently un-clustering a
                -- product; plain COALESCE-on-null-only would pin the first guess forever.
                model_code = COALESCE(EXCLUDED.model_code, deals.model_code),
                match_key  = COALESCE(EXCLUDED.match_key, deals.match_key),
                model_key  = COALESCE(EXCLUDED.model_key, deals.model_key),
                -- Same direction as model_code above: a source that publishes a brand overwrites
                -- (including correcting the folded form V12's backfill recovered from model_key),
                -- while a poll that publishes none leaves the stored value alone.
                brand      = COALESCE(EXCLUDED.brand, deals.brand),
                is_active  = TRUE,
                last_seen_at = now()
            RETURNING id, (first_seen_at = last_seen_at) AS is_new
        """.trimIndent()

        conn.prepareStatement(sql).use { st ->
            st.setString(1, source)
            st.setString(2, raw.externalId)
            st.setString(3, raw.title)
            st.setString(4, raw.url)
            st.setString(5, raw.merchant)
            st.setString(6, raw.asin)
            st.setString(7, raw.category)
            st.setString(8, raw.imageUrl)
            st.setString(9, raw.currency)
            st.setString(10, coShippingDefault.name)
            raw.communityScore?.let { st.setInt(11, it) } ?: st.setNull(11, java.sql.Types.INTEGER)
            st.setTimestamp(12, raw.postedAt?.let { Timestamp.from(it) })
            st.setString(13, categoryGroup)
            st.setString(14, raw.gtin)
            st.setString(15, modelCode)
            st.setString(16, matchKey)
            st.setString(17, modelKey)
            // Trimmed and blank-to-null: a tile occasionally yields an empty brand element, and
            // "" would show up in the dropdown as a nameless option.
            st.setString(18, raw.brand?.trim()?.takeIf { it.isNotEmpty() })
            st.executeQuery().use { rs ->
                rs.next()
                return UpsertResult(rs.getLong("id"), rs.getBoolean("is_new"))
            }
        }
    }

    /**
     * Cheapest price ever recorded for a deal, or null if it has no history yet.
     *
     * Must be read BEFORE inserting the current observation — otherwise the new
     * price is included in its own minimum and a genuine drop can never be
     * detected.
     */
    fun minPriceEver(conn: Connection, dealId: Long): BigDecimal? =
        conn.prepareStatement("SELECT MIN(price) FROM price_observations WHERE deal_id = ?").use { st ->
            st.setLong(1, dealId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBigDecimal(1) else null }
        }

    fun recordObservation(conn: Connection, dealId: Long, raw: RawDeal) {
        conn.prepareStatement(
            "INSERT INTO price_observations (deal_id, price, list_price, currency) VALUES (?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, dealId)
            st.setBigDecimal(2, raw.price)
            st.setBigDecimal(3, raw.listPrice)
            st.setString(4, raw.currency)
            st.executeUpdate()
        }
    }

    fun markPromoted(conn: Connection, dealId: Long) {
        conn.prepareStatement(
            "UPDATE deals SET promoted = TRUE, promoted_at = now() WHERE id = ? AND NOT promoted",
        ).use { st ->
            st.setLong(1, dealId)
            st.executeUpdate()
        }
    }

    /**
     * ASINs for [source] whose price was recorded within the last [withinMinutes].
     *
     * Asked by the manual refresh so it can skip an item a search already fetched this cycle.
     * Reading it back from the database rather than threading a set of ASINs out through
     * `IngestReport` keeps the two paths from drifting: the question is "was an observation written
     * for this item just now?", and `price_observations` is the only thing that can answer it.
     *
     * Without the skip, an item that is BOTH manually tracked and returned by a search gets two
     * observations per poll seconds apart -- which reads as a price oscillating between two values
     * when nothing changed, and "lowest ever" is computed from that series.
     */
    fun recentlyObservedAsins(source: String, withinMinutes: Int = 30): Set<String> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT DISTINCT d.asin
                FROM deals d
                JOIN price_observations po ON po.deal_id = d.id
                WHERE d.source = ?
                  AND d.asin IS NOT NULL
                  AND po.observed_at >= now() - make_interval(mins => ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, source)
                st.setInt(2, withinMinutes)
                st.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("asin")) }
                }
            }
        }

    /**
     * Retires deals this source has not returned for [staleAfterHours].
     *
     * Deliberately age-based rather than "absent from the latest fetch". A single poll
     * is NOT an authoritative snapshot: Slickdeals' paginated search returns a window
     * that shifts between requests, and VTEX category walks are capped at a few pages.
     * Retiring anything merely absent from one fetch made deals flap between active and
     * inactive on every cycle.
     *
     * Deals that reappear are reactivated by the upsert, and history is never deleted —
     * only visibility changes.
     */
    fun deactivateStale(conn: Connection, source: String, staleAfterHours: Int): Int =
        conn.prepareStatement(
            "UPDATE deals SET is_active = FALSE " +
                "WHERE source = ? AND is_active AND last_seen_at < now() - make_interval(hours => ?)",
        ).use { st ->
            st.setString(1, source)
            st.setInt(2, staleAfterHours)
            st.executeUpdate()
        }

    fun withTransaction(block: (Connection) -> Unit) = tx(block)

    /** Every stored deal's raw category and title, for the `--recategorize` backfill. */
    fun allForCategorization(): List<Triple<Long, String?, String>> =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT id, category, title FROM deals ORDER BY id").use { st ->
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(Triple(rs.getLong("id"), rs.getString("category"), rs.getString("title")))
                        }
                    }
                }
            }
        }

    fun setCategoryGroups(assignments: List<Pair<Long, String>>): Int {
        if (assignments.isEmpty()) return 0
        return tx { conn ->
            conn.prepareStatement("UPDATE deals SET category_group = ? WHERE id = ?").use { st ->
                assignments.forEach { (id, group) ->
                    st.setString(1, group)
                    st.setLong(2, id)
                    st.addBatch()
                }
                st.executeBatch().sum()
            }
        }
    }

    fun categoryGroupCounts(): List<Pair<String, Int>> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT coalesce(category_group,'(unset)') AS g, count(*) FROM deals " +
                    "WHERE is_active GROUP BY 1 ORDER BY 2 DESC",
            ).use { st ->
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
                }
            }
        }

    /** Titles currently stored for a source, used by the `--purge-excluded` preview. */
    fun titlesFor(source: String): List<Pair<Long, String>> =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT id, title FROM deals WHERE source = ? ORDER BY id").use { st ->
                st.setString(1, source)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getLong("id") to rs.getString("title")) }
                }
            }
        }

    /**
     * Deals a source published before the cutoff, plus (optionally) rows we could never
     * date. Used by `--purge-old`.
     *
     * Undatable rows matter here: anything ingested before posted_at existed has NULL,
     * and once page depth is reduced those rows are never re-fetched, so they would
     * linger indefinitely.
     */
    fun staleByPostedAt(source: String, maxAgeDays: Int, includeUndated: Boolean): List<Pair<Long, String>> {
        val undated = if (includeUndated) "OR posted_at IS NULL" else ""
        return ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, title FROM deals WHERE source = ? " +
                    "AND (posted_at < now() - make_interval(days => ?) $undated) ORDER BY posted_at NULLS LAST",
            ).use { st ->
                st.setString(1, source)
                st.setInt(2, maxAgeDays)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getLong("id") to rs.getString("title")) }
                }
            }
        }
    }

    /**
     * Permanently removes deals by id. `price_observations` rows go with them via
     * ON DELETE CASCADE — intentional, since an excluded deal's history has no value.
     */
    fun deleteDeals(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM deals WHERE id = ANY (?)").use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.executeUpdate()
            }
        }
    }

    // ---- reads for the web UI ----

    /** Feed page plus the unfiltered total, so a truncated page is never shown as a total. */
    data class FeedPage(val deals: List<Deal>, val totalMatching: Int) {
        val truncated: Boolean get() = totalMatching > deals.size
    }

    fun feedPage(filter: FeedFilter): FeedPage =
        FeedPage(feed(filter), countMatching(filter))

    /** Built once and shared by [feed] and [countMatching] so the two cannot disagree. */
    private data class Criteria(val where: String, val params: List<Any>)

    private fun criteria(filter: FeedFilter): Criteria {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()

        // Applied unless a caller opts out, which only the favorites screen does -- see
        // FeedFilter.includeInactive. Kept first so the common query's plan is unchanged.
        if (!filter.includeInactive) where += "d.is_active"

        // These are bound as a single SQL array parameter, so the list must be added
        // as one element -- `params += list` would splat it into separate params.
        if (filter.states.isNotEmpty()) {
            where += "d.state = ANY (?)"
            params.add(filter.states.map { it.name })
        }
        if (filter.sources.isNotEmpty()) {
            where += "d.source = ANY (?)"
            params.add(filter.sources)
        } else if (filter.excludeSources.isNotEmpty()) {
            // Only when no source is picked: choosing one by name is an explicit request to see it.
            where += "d.source <> ALL (?)"
            params.add(filter.excludeSources)
        }
        if (filter.coShipping.isNotEmpty()) {
            where += "d.co_shipping = ANY (?)"
            params.add(filter.coShipping.map { it.name })
        }
        // "airpods | airtag | ssd" matches any of the terms. Pipe is used because it
        // reads as alternation; a query with no pipe behaves exactly as before.
        val terms = parseSearchTerms(filter.search)
        if (terms.isNotEmpty()) {
            // Trigram similarity backed by deals_title_trgm_idx; ILIKE keeps exact
            // substring matches working for short terms where trigrams are weak.
            where += terms.joinToString(" OR ", prefix = "(", postfix = ")") {
                "(d.title ILIKE ? OR d.title % ?)"
            }
            terms.forEach { term ->
                params += "%${escapeLike(term)}%"
                params += term
            }
        }
        if (filter.minPrice != null) {
            where += "po.price >= ?"
            params += filter.minPrice
        }
        if (filter.maxPrice != null) {
            where += "po.price <= ?"
            params += filter.maxPrice
        }
        if (filter.minDiscount != null) {
            where += "(po.list_price IS NOT NULL AND po.list_price > 0 AND (1 - po.price / po.list_price) * 100 >= ?)"
            params += filter.minDiscount
        }
        if (filter.minScore != null) {
            // NULL scores are excluded rather than treated as 0: VTEX and ML publish no
            // score at all, which is a different fact from a downvoted deal.
            where += "(d.community_score IS NOT NULL AND d.community_score >= ?)"
            params += filter.minScore
        }
        if (filter.categoryGroups.isNotEmpty()) {
            where += "d.category_group = ANY (?)"
            params.add(filter.categoryGroups)
        }
        if (filter.maxAgeDays != null) {
            // Rows with no posted_at are kept: VTEX and ML publish no posting date, so
            // excluding NULL would hide every Colombian deal.
            where += "(d.posted_at IS NULL OR d.posted_at >= now() - make_interval(days => ?))"
            params += filter.maxAgeDays
        }
        if (filter.onlyPromoted) where += "d.promoted"
        if (filter.onlyLowestEver) where += "po.price <= agg.min_price"

        // `ifEmpty` matters now that is_active is conditional: every clause above is optional, so a
        // filter with nothing set at all would otherwise emit a bare `WHERE` and fail to parse.
        return Criteria(where.ifEmpty { listOf("TRUE") }.joinToString(" AND "), params)
    }

    fun countMatching(filter: FeedFilter): Int {
        val c = criteria(filter)
        val sql = """
            SELECT COUNT(*)
            FROM deals d
            JOIN LATERAL (
                SELECT price, list_price FROM price_observations
                WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
            ) po ON TRUE
            JOIN LATERAL (
                SELECT MIN(price) AS min_price, MAX(price) AS max_price, COUNT(*) AS n
                FROM price_observations WHERE deal_id = d.id
            ) agg ON TRUE
            WHERE ${c.where}
        """.trimIndent()
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                bind(conn, st, c.params)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    fun feed(filter: FeedFilter): List<Deal> {
        val c = criteria(filter)
        val where = c.where
        val params = c.params

        // "How recent is this deal", used by both `newest` and the relevance default.
        //
        // COALESCE rather than first_seen_at alone, because first_seen_at records when *we*
        // ingested a deal, while the Seen column shows the source's own posting date when it
        // has one. A single Slickdeals poll returns items posted anywhere from minutes to
        // hours earlier and stamps them all with the same first_seen_at, so ordering by our
        // ingest time produced a feed that was correctly sorted and looked shuffled -- the
        // column the user reads and the column the query sorted on were different clocks.
        //
        // The fallback matters: VTEX publishes no posting date at all, so posted_at alone
        // with NULLS LAST would dump every Exito and Carulla deal below every Slickdeals one.
        val recency = "COALESCE(d.posted_at, d.first_seen_at) DESC"

        val sortKey = when (filter.sort) {
            "newest" -> recency
            "score" -> "d.community_score DESC NULLS LAST"
            "price" -> "po.price ASC"
            "drop" -> "(agg.min_price - po.price) DESC"
            "discount" -> "CASE WHEN po.list_price > 0 THEN (1 - po.price / po.list_price) ELSE 0 END DESC"
            // Default: the composed relevance score. This used to lead with `d.promoted DESC`
            // followed by recency and then discount -- but `promoted` is true for 81% of active
            // deals, so the leading key sorted almost nothing and the feed was effectively
            // ordered by recency alone. Worse, nothing in that ordering knew about the shipping
            // fee, so items costing more to ship than to buy ranked among the best deals on the
            // page. See DealScore for the weights and the evidence behind each.
            //
            // The *only* place rotation is applied, and only to the ordering: the score shown in
            // the Rank column stays plain `DealScore.SQL`, so a reshuffle can never be mistaken
            // for the number having changed.
            else -> "${DealScore.rotating(feedRotationHours)} DESC"
        }

        // Every sort key above has ties, and some are enormous: a whole poll batch shares a
        // first_seen_at, and hundreds of deals share a null discount or a zero score. Postgres
        // guarantees no order within a tie, so without a unique final key the same query can
        // return the same rows in a different order on each request -- which is what makes a
        // working sort look broken. id is unique and monotonic, so it settles every tie.
        val orderBy = "$sortKey, d.id DESC"

        val sql = """
            SELECT d.*, po.price, po.list_price, agg.min_price, agg.max_price, agg.n,
                   ${DealScore.SQL} AS relevance
            FROM deals d
            JOIN LATERAL (
                SELECT price, list_price FROM price_observations
                WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
            ) po ON TRUE
            JOIN LATERAL (
                SELECT MIN(price) AS min_price, MAX(price) AS max_price, COUNT(*) AS n
                FROM price_observations WHERE deal_id = d.id
            ) agg ON TRUE
            WHERE $where
            ORDER BY $orderBy
            LIMIT ${filter.limit}
        """.trimIndent()

        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                bind(conn, st, params)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDeal()) } }
            }
        }
    }

    fun findById(id: Long): Deal? = ds.connection.use { conn ->
        conn.prepareStatement(dealSelect("d.id = ?")).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toDeal() else null }
        }
    }

    /**
     * Several deals in one query, returned in the order of [ids].
     *
     * For callers that already hold a list of ids from somewhere else — a watch's candidates — and
     * need the full rows. Calling [findById] per id would be one round trip per row, which on a
     * watch with 140 candidates is noticeable. Ids that no longer exist are simply absent.
     */
    fun findByIds(ids: List<Long>): List<Deal> {
        if (ids.isEmpty()) return emptyList()
        val byId = ds.connection.use { conn ->
            conn.prepareStatement(dealSelect("d.id = ANY(?)")).use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDeal()) } }
            }
        }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** The row shape [toDeal] reads, for single- and multi-id lookups. */
    private fun dealSelect(where: String) = """
        SELECT d.*, po.price, po.list_price, agg.min_price, agg.max_price, agg.n,
               ${DealScore.SQL} AS relevance
        FROM deals d
        JOIN LATERAL (
            SELECT price, list_price FROM price_observations
            WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
        ) po ON TRUE
        JOIN LATERAL (
            SELECT MIN(price) AS min_price, MAX(price) AS max_price, COUNT(*) AS n
            FROM price_observations WHERE deal_id = d.id
        ) agg ON TRUE
        WHERE $where
    """.trimIndent()

    fun priceHistory(dealId: Long): List<PriceObservation> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT deal_id, observed_at, price, list_price, currency FROM price_observations " +
                    "WHERE deal_id = ? ORDER BY observed_at ASC",
            ).use { st ->
                st.setLong(1, dealId)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PriceObservation(
                                    dealId = rs.getLong("deal_id"),
                                    observedAt = rs.getTimestamp("observed_at").toInstant(),
                                    price = rs.getBigDecimal("price"),
                                    listPrice = rs.getBigDecimal("list_price"),
                                    currency = rs.getString("currency"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun updateState(id: Long, state: DealState): Boolean =
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE deals SET state = ? WHERE id = ?").use { st ->
                st.setString(1, state.name)
                st.setLong(2, id)
                st.executeUpdate() > 0
            }
        }

    /**
     * Deals still needing an automated Colombia shipping check, **best first**.
     *
     * Excludes anything already decided, anything decided MANUALLY (human effort must
     * survive automation), and anything whose backoff window has not elapsed. Backoff is
     * exponential in the attempt count: 2^attempts hours, capped. Without it a row that
     * cannot be read would be retried on every cycle indefinitely.
     *
     * ## Why the ordering is the important part
     *
     * This queue is permanently oversubscribed and always will be. The automatic run takes
     * `coShippingPollBatch` deals every Nth poll — 20 an hour, so ~480 a day at full uptime,
     * though measured uptime is nearer 14h than 24h — against a source that brings tens of new
     * Amazon deals an hour, and a browser session tolerates only 40-55 loads before Amazon
     * walls it. Raising the batch has not emptied it and is not expected to: at 15 an hour the
     * backlog still stood at 1,179 (2026-09-23). The backlog cannot be drained, so *which*
     * deals get the budget is the whole decision.
     *
     * It used to be ordered `co_shipping_checked_at ASC NULLS FIRST, id ASC` — sound for a
     * queue with mixed retry state, but measured against the live database **all 223 waiting
     * deals had `co_shipping_checked_at IS NULL`**. Every row tied on the leading key, so the
     * ordering fell through to `id ASC`: the arbitrary sequence they happened to be ingested
     * in. The result was 196 verdicts produced of which 177 landed on deals that had already
     * retired — 90% of the effort expiring before the feed could use it.
     *
     * So the primary key is now [DealScore], the same ranking the feed itself uses. The old
     * keys are kept as tiebreakers: among equally attractive deals the longest-waiting still
     * goes first, and `id` still settles the rest so the order is never unstable.
     *
     * The score here is necessarily a *prior* — an unchecked deal has no
     * `co_shipping_cost` yet, so the freight term is zero and the ranking rests on discount,
     * observed drop, community score and age. That is exactly the information available for
     * deciding whether a page load is worth spending.
     *
     * [prioritySources] come ahead of everything, and DealScore then orders within each group.
     * Tracked rows score low by construction -- no community votes, often no list price -- so on
     * score alone they lost nearly every slot to Slickdeals, despite being the rows whose landed
     * cost matters most. Empty means no priority, which is the plain score order.
     */
    fun coShippingCandidates(
        limit: Int,
        baseBackoffHours: Int = 6,
        maxBackoffHours: Int = 168,
        prioritySources: List<String> = emptyList(),
    ): List<Triple<Long, String, String>> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT d.id, d.asin, d.title FROM deals d
                JOIN LATERAL (
                    SELECT price, list_price FROM price_observations
                    WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
                ) po ON TRUE
                JOIN LATERAL (
                    SELECT MAX(price) AS max_price FROM price_observations WHERE deal_id = d.id
                ) agg ON TRUE
                 WHERE d.is_active
                   AND d.asin IS NOT NULL
                   AND d.co_shipping = 'UNKNOWN'
                   AND coalesce(d.co_shipping_source, '') <> 'MANUAL'
                   AND (
                        d.co_shipping_checked_at IS NULL
                     OR d.co_shipping_checked_at < now() - make_interval(
                            hours => LEAST(? * POWER(2, d.co_shipping_attempts)::int, ?))
                   )
                 ORDER BY (d.source = ANY(?)) DESC,
                          ${DealScore.SQL} DESC,
                          d.co_shipping_checked_at ASC NULLS FIRST,
                          d.id ASC
                 LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.setInt(1, baseBackoffHours)
                st.setInt(2, maxBackoffHours)
                st.setArray(3, conn.createArrayOf("text", prioritySources.toTypedArray()))
                st.setInt(4, limit)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(Triple(rs.getLong("id"), rs.getString("asin"), rs.getString("title")))
                        }
                    }
                }
            }
        }

    /**
     * One specific deal to verify, for the UI's per-deal Verify button.
     *
     * Deliberately ignores the backoff window that [coShippingCandidates] applies: backoff
     * exists to stop the *automatic* runs from hammering a deal that keeps coming back
     * unclear, but an explicit click is a human asking for this one now. It still refuses a
     * MANUAL verdict, since that is the operator's own answer and outranks any automated one.
     *
     * Returns null when the deal is gone, inactive, already decided, manual, or has no ASIN —
     * the caller turns that into a message rather than a check.
     */
    fun coShippingCandidate(dealId: Long): Triple<Long, String, String>? =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, asin, title FROM deals
                 WHERE id = ?
                   AND is_active
                   AND asin IS NOT NULL
                   AND asin <> ''
                   AND co_shipping = 'UNKNOWN'
                   AND coalesce(co_shipping_source, '') <> 'MANUAL'
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, dealId)
                st.executeQuery().use { rs ->
                    if (rs.next()) {
                        Triple(rs.getLong("id"), rs.getString("asin"), rs.getString("title"))
                    } else {
                        null
                    }
                }
            }
        }

    /** Active UNKNOWN deals with no ASIN — reported rather than silently skipped. */
    fun coShippingUncheckableCount(): Int =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM deals WHERE is_active AND co_shipping = 'UNKNOWN' AND asin IS NULL",
            ).use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        }

    /**
     * Records an automated verdict.
     *
     * Refuses to touch a row whose verdict is MANUAL — enforced in the WHERE clause rather
     * than by the caller remembering, because silently clobbering hand-verified results
     * would be the worst failure mode here. Returns false when the row was protected.
     *
     * An inconclusive result still updates checked_at and increments attempts, which is
     * precisely what stops the same unreadable deal being retried every cycle.
     */
    fun recordCoShippingCheck(
        id: Long,
        verdict: CoShipping,
        unclear: Boolean,
        evidence: String,
        importFree: Boolean?,
        shippingCost: java.math.BigDecimal?,
    ): Boolean =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE deals
                   SET co_shipping = CASE WHEN ? THEN co_shipping ELSE ? END,
                       co_shipping_source = CASE WHEN ? THEN co_shipping_source ELSE 'AUTO' END,
                       co_shipping_checked_at = now(),
                       co_shipping_attempts = CASE WHEN ? THEN co_shipping_attempts + 1 ELSE 0 END,
                       co_shipping_evidence = ?,
                       co_shipping_import_free = ?,
                       co_shipping_cost = ?
                 WHERE id = ?
                   AND coalesce(co_shipping_source, '') <> 'MANUAL'
                """.trimIndent(),
            ).use { st ->
                st.setBoolean(1, unclear)
                st.setString(2, verdict.name)
                st.setBoolean(3, unclear)
                st.setBoolean(4, unclear)
                st.setString(5, evidence.take(300))
                importFree?.let { st.setBoolean(6, it) } ?: st.setNull(6, java.sql.Types.BOOLEAN)
                st.setBigDecimal(7, shippingCost)
                st.setLong(8, id)
                st.executeUpdate() > 0
            }
        }

    fun coShippingStats(): Map<String, Int> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT co_shipping,
                       coalesce(co_shipping_source, 'NONE') AS src,
                       count(*)
                  FROM deals WHERE is_active
                 GROUP BY 1, 2 ORDER BY 1, 2
                """.trimIndent(),
            ).use { st ->
                st.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put("${rs.getString(1)}/${rs.getString(2)}", rs.getInt(3)) }
                }
            }
        }

    fun updateCoShipping(id: Long, value: CoShipping): Boolean =
        ds.connection.use { conn ->
            // Marked MANUAL: this path is the UI's ships/no buttons, i.e. a human decision,
            // which coShippingCandidates and recordCoShippingCheck both refuse to override.
            conn.prepareStatement(
                "UPDATE deals SET co_shipping = ?, co_shipping_source = 'MANUAL', " +
                    "co_shipping_checked_at = now(), co_shipping_attempts = 0 WHERE id = ?",
            ).use { st ->
                st.setString(1, value.name)
                st.setLong(2, id)
                st.executeUpdate() > 0
            }
        }

    fun knownSources(): List<String> =
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT DISTINCT source FROM deals ORDER BY source").use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    // ---- fetch run bookkeeping ----

    fun startRun(source: String): Long =
        ds.connection.use { conn ->
            conn.prepareStatement("INSERT INTO fetch_runs (source) VALUES (?) RETURNING id").use { st ->
                st.setString(1, source)
                st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    fun finishRun(runId: Long, itemCount: Int, newCount: Int, error: String?) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE fetch_runs SET finished_at = now(), item_count = ?, new_count = ?, error = ? WHERE id = ?",
            ).use { st ->
                st.setInt(1, itemCount)
                st.setInt(2, newCount)
                st.setString(3, error)
                st.setLong(4, runId)
                st.executeUpdate()
            }
        }
    }

    fun recentRuns(limit: Int = 20): List<Map<String, Any?>> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT source, started_at, finished_at, item_count, new_count, error " +
                    "FROM fetch_runs ORDER BY started_at DESC LIMIT ?",
            ).use { st ->
                st.setInt(1, limit)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                mapOf(
                                    "source" to rs.getString("source"),
                                    "startedAt" to rs.getTimestamp("started_at")?.toInstant(),
                                    "finishedAt" to rs.getTimestamp("finished_at")?.toInstant(),
                                    "itemCount" to rs.getInt("item_count"),
                                    "newCount" to rs.getInt("new_count"),
                                    "error" to rs.getString("error"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun bind(conn: Connection, st: java.sql.PreparedStatement, params: List<Any>) {
        params.forEachIndexed { i, p ->
            val idx = i + 1
            when (p) {
                is String -> st.setString(idx, p)
                is BigDecimal -> st.setBigDecimal(idx, p)
                is Int -> st.setInt(idx, p)
                is Instant -> st.setTimestamp(idx, Timestamp.from(p))
                is List<*> -> st.setArray(idx, conn.createArrayOf("text", p.toTypedArray()))
                else -> st.setObject(idx, p)
            }
        }
    }

    private fun ResultSet.toDeal() = Deal(
        id = getLong("id"),
        source = getString("source"),
        sourceDealId = getString("source_deal_id"),
        title = getString("title"),
        url = getString("url"),
        merchant = getString("merchant"),
        asin = getString("asin"),
        category = getString("category"),
        imageUrl = getString("image_url"),
        currency = getString("currency"),
        firstSeenAt = getTimestamp("first_seen_at").toInstant(),
        lastSeenAt = getTimestamp("last_seen_at").toInstant(),
        isActive = getBoolean("is_active"),
        state = DealState.valueOf(getString("state")),
        coShipping = CoShipping.valueOf(getString("co_shipping")),
        promoted = getBoolean("promoted"),
        promotedAt = getTimestamp("promoted_at")?.toInstant(),
        communityScore = getInt("community_score").takeIf { !wasNull() },
        postedAt = getTimestamp("posted_at")?.toInstant(),
        categoryGroup = getString("category_group"),
        brand = getString("brand"),
        gtin = getString("gtin"),
        modelCode = getString("model_code"),
        matchKey = getString("match_key"),
        modelKey = getString("model_key"),
        coShippingSource = getString("co_shipping_source"),
        coShippingEvidence = getString("co_shipping_evidence"),
        coShippingImportFree = getBoolean("co_shipping_import_free").takeIf { !wasNull() },
        coShippingCost = getBigDecimal("co_shipping_cost"),
        price = getBigDecimal("price"),
        listPrice = getBigDecimal("list_price"),
        minPriceEver = getBigDecimal("min_price"),
        maxPriceEver = getBigDecimal("max_price"),
        observationCount = getInt("n"),
        relevance = getDouble("relevance"),
    )
}
