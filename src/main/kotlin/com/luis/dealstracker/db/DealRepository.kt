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

/** [rejected]: a product page refuted this row's size; it stays inactive and takes no observations. */
/**
 * A row within a day of its age-based retirement that no page read has vouched for in the last day. Takes
 * two `?`: the provider's retirement age in hours, twice (0 turns the clause off). See `deactivateStale`.
 */
private const val NEARING_RETIREMENT =
    "? > 0 AND d.last_seen_at < now() - make_interval(hours => GREATEST(? - 24, 0)) " +
        "AND (d.page_valid_at IS NULL OR d.page_valid_at < now() - interval '24 hours')"

data class UpsertResult(
    val dealId: Long,
    val isNew: Boolean,
    val rejected: Boolean = false,
    /** What the row's own page last showed (V29), or null when it was never read or the source has none. */
    val pagePrice: BigDecimal? = null,
    val pagePriceCurrency: String? = null,
)

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
    /**
     * Leaves out deals a watch already covers, a disabled one included, so the feed shows what no watch is
     * hunting. The watch's own page is where those belong.
     *
     * Matched the way [WatchRepository.candidates] matches, minus the trigram fuzzy tail: an
     * exact substring on the watch's terms (accent-folded), its exclusions, category, sources,
     * and the price band when the deal is in the watch's currency. The fuzzy tail is left out
     * because here a false match hides a deal from the operator, which is worse than a watch
     * alert that is one row too generous.
     */
    val hideWatched: Boolean = false,
    /**
     * Leaves out deals verified as NOT shipping to Colombia. UNKNOWN and YES stay: an unverified
     * deal is not a refusal, and Amazon items start unverified. Ignored when [coShipping] names
     * values, since choosing "no" by hand is an explicit request to see them.
     */
    val hideNoShip: Boolean = false,
    // High enough that the whole feed renders in one page at current volume (a few
    // hundred deals across four sources). The UI states the total when this does cap
    // the page, so truncation is visible rather than silent. Proper pagination is the
    // fix once volume outgrows this.
    val limit: Int = 1000,
)

/**
 * Gives a query `agg.min_price`, `agg.max_price` and `agg.n` for the deal `d`.
 *
 * Read from the columns V27 keeps on `deals`, never rebuilt from `price_observations`: that scan, run
 * for every row of the feed, was most of its load time. A test pins that this fragment stays off the
 * history table.
 */
internal const val OBSERVATION_SUMMARY_JOIN =
    "JOIN LATERAL (SELECT d.obs_min_price AS min_price, d.obs_max_price AS max_price, d.obs_count AS n) agg ON TRUE"

/**
 * True for a deal some watch covers, enabled or not; used negated by [FeedFilter.hideWatched].
 *
 * Needs `po` and `agg` from the feed query, for the watch's price band. `terms` and
 * `exclude_terms` are split on `|` here because they are stored as the pipe-separated text the
 * search box takes, and LIKE metacharacters are escaped the way [escapeLike] does.
 */
internal const val WATCHED_DEAL_CLAUSE = """NOT EXISTS (
    SELECT 1 FROM watches w
    WHERE (w.category_group IS NULL OR w.category_group = '' OR w.category_group = d.category_group)
      AND (cardinality(w.sources) = 0 OR d.source = ANY (w.sources))
      -- CASE, not AND: it fixes the order of evaluation. Left as AND, the planner ran every watch's
      -- exclusion terms (an accent-stripping ILIKE each) against every deal BEFORE asking whether the
      -- deal matched the watch at all -- ~20,000 of them per page load, half the feed's wait.
      AND CASE WHEN (
          EXISTS (SELECT 1 FROM watch_sourced_deals ws WHERE ws.watch_id = w.id AND ws.deal_id = d.id)
          OR EXISTS (
              SELECT 1 FROM unnest(string_to_array(w.terms, '|')) AS t(term)
              WHERE btrim(t.term) <> ''
                AND unaccent(d.title) ILIKE unaccent(
                    '%' || replace(replace(replace(btrim(t.term), '\', '\\'), '%', '\%'), '_', '\_') || '%')
          )
      ) THEN (
          NOT EXISTS (
              SELECT 1 FROM unnest(string_to_array(COALESCE(w.exclude_terms, ''), '|')) AS x(term)
              WHERE btrim(x.term) <> ''
                AND unaccent(d.title) ILIKE unaccent(
                    '%' || replace(replace(replace(btrim(x.term), '\', '\\'), '%', '\%'), '_', '\_') || '%')
          )
          AND (w.currency <> d.currency OR (
              (w.min_price IS NULL OR agg.max_price >= w.min_price)
              AND (w.max_price IS NULL OR po.price <= w.max_price)))
      ) ELSE FALSE END
)"""

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
 * @param feedRotationHours non-zero reshuffles the feed's default ordering on every load; 0 for a
 *   pure score ordering. Repository-wide policy rather than a per-query flag,
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
                               unit_measure, unit_quantity, grocery_group,
                               is_active, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, now(), now())
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
                -- Pack size is a fact of the listing, so a size the store now publishes replaces an
                -- older reading, and a poll that yields none keeps what was known.
                unit_measure  = COALESCE(EXCLUDED.unit_measure, deals.unit_measure),
                unit_quantity = COALESCE(EXCLUDED.unit_quantity, deals.unit_quantity),
                grocery_group = COALESCE(EXCLUDED.grocery_group, deals.grocery_group),
                -- A row whose own page refuted its size (V23) stays retired: the next search returns
                -- the same ASIN, and without this it would be reactivated every poll.
                is_active  = NOT deals.size_rejected,
                last_seen_at = now()
            RETURNING id, (first_seen_at = last_seen_at) AS is_new, size_rejected, page_price, page_price_currency
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
            // Both or neither: a quantity without its unit is meaningless, and the CHECKs in V16
            // would reject half a pair only after the whole transaction had been built.
            val sized = raw.unitMeasure != null && raw.unitQuantity != null
            st.setString(19, raw.unitMeasure.takeIf { sized })
            st.setBigDecimal(20, raw.unitQuantity.takeIf { sized })
            st.setString(21, raw.groceryGroup)
            st.executeQuery().use { rs ->
                rs.next()
                return UpsertResult(
                    rs.getLong("id"), rs.getBoolean("is_new"), rs.getBoolean("size_rejected"),
                    rs.getBigDecimal("page_price"), rs.getString("page_price_currency"),
                )
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

    /**
     * Records one price reading, and returns its id so [linkPromotions] can attach what was live on it.
     */
    fun recordObservation(conn: Connection, dealId: Long, raw: RawDeal): Long =
        conn.prepareStatement(
            "INSERT INTO price_observations (deal_id, price, list_price, currency, card_price, card_percent, card_scope, stock_qty) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        ).use { st ->
            st.setLong(1, dealId)
            st.setBigDecimal(2, raw.price)
            st.setBigDecimal(3, raw.listPrice)
            st.setString(4, raw.currency)
            // Per observation, because card promotions change daily -- see V17__card_price.
            st.setBigDecimal(5, raw.cardOffer?.price)
            st.setBigDecimal(6, raw.cardOffer?.percent)
            st.setString(7, raw.cardOffer?.scope?.name)
            raw.stockQty?.let { st.setInt(8, it.coerceAtLeast(0)) } ?: st.setNull(8, java.sql.Types.INTEGER)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

    /**
     * Upserts [raw]'s promotions for [source] and links each to [observationId]. See `V18__promotions`.
     *
     * [cache] maps a promotion name to its id for the length of one ingest, so a crawl touching 7,000
     * rows upserts each of its ~100 distinct names once rather than once per row. The upsert refreshes
     * `last_seen_at`, which is what dates a promotion's end.
     */
    fun linkPromotions(
        conn: Connection,
        source: String,
        observationId: Long,
        raw: RawDeal,
        cache: MutableMap<String, Long>,
    ) {
        if (raw.promotions.isEmpty()) return
        val ids = raw.promotions.map { tag ->
            cache.getOrPut(tag.name) {
                conn.prepareStatement(
                    """
                    INSERT INTO promotions (source, name, kind, percent, card_ids, pay_qty, take_qty, valid_from, valid_to)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source, name) DO UPDATE SET last_seen_at = now()
                    RETURNING id
                    """.trimIndent(),
                ).use { st ->
                    st.setString(1, source)
                    st.setString(2, tag.name)
                    st.setString(3, tag.kind.name)
                    st.setBigDecimal(4, tag.percent)
                    st.setString(5, tag.cardIds)
                    tag.payQty?.let { st.setInt(6, it) } ?: st.setNull(6, java.sql.Types.INTEGER)
                    tag.takeQty?.let { st.setInt(7, it) } ?: st.setNull(7, java.sql.Types.INTEGER)
                    st.setObject(8, tag.validFrom)
                    st.setObject(9, tag.validTo)
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }.distinct()
        conn.prepareStatement(
            "INSERT INTO observation_promotions (observation_id, promotion_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        ).use { st ->
            ids.forEach { id ->
                st.setLong(1, observationId)
                st.setLong(2, id)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    /**
     * Records the homepage banner configs seen on one crawl. A config already known has its span and
     * count extended rather than a row added -- see `site_banners` in V18.
     */
    fun recordBanners(host: String, banners: List<com.luis.dealstracker.sources.vtex.SiteBanner>) {
        if (banners.isEmpty()) return
        tx { conn ->
            conn.prepareStatement(
                """
                INSERT INTO site_banners (host, text, shown, stated_from, stated_to, link) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (host, text) DO UPDATE SET
                    last_seen_at = now(),
                    seen_count   = site_banners.seen_count + 1,
                    -- The store edits a config's dates and visibility in place; keep the latest.
                    shown        = EXCLUDED.shown,
                    stated_from  = EXCLUDED.stated_from,
                    stated_to    = EXCLUDED.stated_to,
                    link         = EXCLUDED.link
                """.trimIndent(),
            ).use { st ->
                banners.distinctBy { it.text }.forEach { b ->
                    st.setString(1, host)
                    st.setString(2, b.text)
                    b.shown?.let { st.setBoolean(3, it) } ?: st.setNull(3, java.sql.Types.BOOLEAN)
                    st.setObject(4, b.statedFrom)
                    st.setObject(5, b.statedTo)
                    st.setString(6, b.link)
                    st.addBatch()
                }
                st.executeBatch()
            }
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
    fun deactivateStale(
        conn: Connection,
        source: String,
        staleAfterHours: Int,
        spareConfirmed: Boolean = false,
        maxUnseenDays: Int = 14,
    ): Int =
        conn.prepareStatement(
            """
            UPDATE deals SET is_active = FALSE
             WHERE source = ? AND is_active AND last_seen_at < now() - make_interval(hours => ?)
               -- A row its own page vouched for within the window is not gone, only unlisted; one whose page
               -- could not be read yet (a failed attempt waiting to retry) is not decided either. The hard cap
               -- keeps either from holding a row forever.
               AND NOT (? AND last_seen_at >= now() - make_interval(days => ?)
                        AND (page_valid_at >= now() - make_interval(hours => ?) OR page_attempts > 0))
            """.trimIndent(),
        ).use { st ->
            st.setString(1, source)
            st.setInt(2, staleAfterHours)
            st.setBoolean(3, spareConfirmed)
            st.setInt(4, maxUnseenDays)
            st.setInt(5, staleAfterHours)
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

    /**
     * The page and the total it was cut from, in ONE query: `COUNT(*) OVER ()` is computed over every
     * matching row before LIMIT. Two queries meant the watched-deal filter, the dearest part of the
     * feed, ran twice per page load. An empty page has no rows to carry the total, and then nothing
     * matched, so the total is 0.
     */
    fun feedPage(filter: FeedFilter): FeedPage {
        val (deals, total) = feedWithTotal(filter)
        return FeedPage(deals, total)
    }

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
        } else if (filter.hideNoShip) {
            where += "d.co_shipping <> 'NO'"
        }
        // "airpods | airtag | !refurbished": any inclusion, no exclusion -- see [SearchQuery].
        // Trigram similarity (deals_title_trgm_idx) backs the fuzzy half of inclusion.
        val (searchClauses, searchParams) = SearchQuery.parse(filter.search).toSql()
        where += searchClauses
        params.addAll(searchParams)
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
        if (filter.hideWatched) where += WATCHED_DEAL_CLAUSE
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
            ${OBSERVATION_SUMMARY_JOIN}
            WHERE ${c.where}
        """.trimIndent()
        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                bind(conn, st, c.params)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    fun feed(filter: FeedFilter): List<Deal> = feedWithTotal(filter).first

    private fun feedWithTotal(filter: FeedFilter): Pair<List<Deal>, Int> {
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
            else -> "${DealScore.DOWNVOTED_LAST} DESC, ${DealScore.rotating(feedRotationHours > 0)} DESC"
        }

        // Every sort key above has ties, and some are enormous: a whole poll batch shares a
        // first_seen_at, and hundreds of deals share a null discount or a zero score. Postgres
        // guarantees no order within a tie, so without a unique final key the same query can
        // return the same rows in a different order on each request -- which is what makes a
        // working sort look broken. id is unique and monotonic, so it settles every tie.
        val orderBy = "$sortKey, d.id DESC"

        val sql = """
            SELECT d.*, po.price, po.list_price, agg.min_price, agg.max_price, agg.n,
                   ${DealScore.SQL} AS relevance,
                   COUNT(*) OVER () AS total_matching
            FROM deals d
            JOIN LATERAL (
                SELECT price, list_price FROM price_observations
                WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1
            ) po ON TRUE
            ${OBSERVATION_SUMMARY_JOIN}
            WHERE $where
            ORDER BY $orderBy
            LIMIT ${filter.limit}
        """.trimIndent()

        return ds.connection.use { conn ->
            conn.prepareStatement(sql).use { st ->
                bind(conn, st, params)
                st.executeQuery().use { rs ->
                    var total = 0
                    val deals = buildList {
                        while (rs.next()) {
                            if (isEmpty()) total = rs.getInt("total_matching")
                            add(rs.toDeal())
                        }
                    }
                    deals to total
                }
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
        ${OBSERVATION_SUMMARY_JOIN}
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

    /**
     * Each deal's price *changes* over the last [days] days, oldest first, at most [perDeal] of the
     * most recent. A run of identical observations collapses to its first, so an hourly poll of a
     * price that never moved is one point, not 2,000 — which is what makes a trend affordable to
     * put in a prompt. The first point is the price at the window's start (or the first sighting).
     */
    fun priceChanges(ids: List<Long>, days: Int, perDeal: Int): Map<Long, List<Pair<java.time.LocalDate, BigDecimal>>> {
        if (ids.isEmpty()) return emptyMap()
        return ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT deal_id, observed_at::date AS day, price FROM (
                    SELECT deal_id, observed_at, price,
                           LAG(price) OVER (PARTITION BY deal_id ORDER BY observed_at) AS prev
                    FROM price_observations
                    WHERE deal_id = ANY(?) AND observed_at > now() - make_interval(days => ?)
                ) o
                WHERE prev IS NULL OR prev <> price
                ORDER BY deal_id, observed_at
                """.trimIndent(),
            ).use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.setInt(2, days)
                st.executeQuery().use { rs ->
                    val out = LinkedHashMap<Long, MutableList<Pair<java.time.LocalDate, BigDecimal>>>()
                    while (rs.next()) {
                        out.getOrPut(rs.getLong("deal_id")) { mutableListOf() }
                            .add(rs.getDate("day").toLocalDate() to rs.getBigDecimal("price"))
                    }
                    out.mapValues { (_, v) -> v.takeLast(perDeal) }
                }
            }
        }
    }

    /**
     * Pins or unpins the given rows, returning how many changed. Several ids because the tracked tab
     * folds same-product variants into one row (see `collapseVariants`) and the pin must cover the
     * group, or the next poll could surface an unpinned sibling in its place. Keeps the first pin time.
     */
    fun setPinned(ids: List<Long>, pinned: Boolean): Int =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE deals SET pinned_at = ${if (pinned) "COALESCE(pinned_at, now())" else "NULL"} WHERE id = ANY (?)",
            ).use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.executeUpdate()
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
        recheckYesDays: Int = 0,
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
                   AND coalesce(d.co_shipping_source, '') <> 'MANUAL'
                   AND (
                        (d.co_shipping = 'UNKNOWN' AND (
                            d.co_shipping_checked_at IS NULL
                         OR d.co_shipping_checked_at < now() - make_interval(
                                hours => LEAST(? * POWER(2, d.co_shipping_attempts)::int, ?))))
                     -- A settled YES carries a fee quoted at the item's price, which Amazon changes.
                     OR (? > 0 AND d.co_shipping = 'YES'
                         AND d.co_shipping_checked_at < now() - make_interval(days => ?))
                   )
                 ORDER BY (d.co_shipping = 'UNKNOWN') DESC,
                          EXISTS (
                              -- A listing a shipping-gated watch is waiting on is held out of its
                              -- alerts until this verdict lands, so it jumps the whole queue.
                              SELECT 1 FROM watches w
                               WHERE w.enabled AND w.require_co_shipping
                                 AND (d.id IN (SELECT deal_id FROM watch_sourced_deals s WHERE s.watch_id = w.id)
                                      OR EXISTS (SELECT 1 FROM unnest(string_to_array(w.terms, '|')) t
                                                  WHERE btrim(t) <> ''
                                                    AND unaccent(d.title) ILIKE unaccent('%' || btrim(t) || '%')))
                          ) DESC,
                          (d.source = ANY(?)) DESC,
                          ${DealScore.SQL} DESC,
                          d.co_shipping_checked_at ASC NULLS FIRST,
                          d.id ASC
                 LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.setInt(1, baseBackoffHours)
                st.setInt(2, maxBackoffHours)
                st.setInt(3, recheckYesDays)
                st.setInt(4, recheckYesDays)
                st.setArray(5, conn.createArrayOf("text", prioritySources.toTypedArray()))
                st.setInt(6, limit)
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

    /**
     * Rows found by search whose size no product page has confirmed yet.
     *
     * [sources] are the Tracked tab's providers: only those read size from a search tile. Items the
     * operator added by URL are excluded -- their size already came from the ASIN's own page, and
     * rejecting something explicitly asked for would be wrong. Never-visited rows come first, newest first, so
     * a row is checked soon after it appears, which is when a wrong size does the most harm; then, when
     * [recheckDays] is positive, rows last confirmed longer ago than that, oldest first, which keeps a page-true
     * price on every row. Failed reads back off
     * like the shipping check's (`2^attempts` hours, capped at a week).
     */
    fun pageCheckCandidates(
        limit: Int,
        sources: List<String>,
        recheckDays: Int = 0,
        baseBackoffHours: Int = 6,
        maxBackoffHours: Int = 168,
        retireAfterHours: Int = 0,
    ): List<Triple<Long, String, String>> =
        if (sources.isEmpty()) emptyList() else ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT d.id, d.asin, d.title FROM deals d
                 WHERE d.is_active
                   AND NOT d.size_rejected
                   AND d.asin IS NOT NULL
                   AND d.source = ANY(?)
                   AND (d.page_checked_at IS NULL
                        OR (? > 0 AND d.page_checked_at < now() - make_interval(days => ?))
                        -- Within a day of retirement and not vouched for by its page lately: ask the page.
                        OR ($NEARING_RETIREMENT))
                   AND NOT EXISTS (SELECT 1 FROM manual_items m WHERE m.asin = d.asin)
                   AND (
                        d.page_attempted_at IS NULL
                     OR d.page_attempted_at < now() - make_interval(
                            hours => LEAST(? * POWER(2, d.page_attempts)::int, ?))
                   )
                 ORDER BY ($NEARING_RETIREMENT) DESC,
                          (d.page_checked_at IS NULL) DESC, d.page_checked_at ASC NULLS FIRST,
                          d.first_seen_at DESC, d.id DESC
                 LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
                st.setInt(2, recheckDays)
                st.setInt(3, recheckDays)
                st.setInt(4, retireAfterHours)
                st.setInt(5, retireAfterHours)
                st.setInt(6, baseBackoffHours)
                st.setInt(7, maxBackoffHours)
                st.setInt(8, retireAfterHours)
                st.setInt(9, retireAfterHours)
                st.setInt(10, limit)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Triple(rs.getLong("id"), rs.getString("asin"), rs.getString("title"))) }
                }
            }
        }

    /**
     * Starts tracking [raw], the size-correct sibling of a row retired for the wrong size.
     *
     * Everything is written in one transaction -- the row, its first observation, and the fact that a
     * page has already confirmed its size -- so it never exists half-made: a row with no observation
     * would show on Tracked with no price. The category group is copied from the row it replaces
     * (same product family) because the verifier has no categoriser; the source is copied too. A sibling that already exists
     * is refreshed, and one already retired stays retired (see `upsertDeal`).
     *
     * @return the new row's id, or null when that row is retired and nothing was written.
     */
    fun addSibling(raw: RawDeal, replacesDealId: Long, pageSize: String?): Long? {
        var id: Long? = null
        tx { conn ->
            val (source, group) = conn.prepareStatement("SELECT source, category_group FROM deals WHERE id = ?").use { st ->
                st.setLong(1, replacesDealId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else return@tx }
            }
            val upsert = upsertDeal(conn, source, raw, CoShipping.UNKNOWN, group)
            if (upsert.rejected) return@tx
            recordObservation(conn, upsert.dealId, raw)
            conn.prepareStatement(
                "UPDATE deals SET page_checked_at = now(), page_size = ?, page_attempts = 0 WHERE id = ?",
            ).use { st -> st.setString(1, pageSize); st.setLong(2, upsert.dealId); st.executeUpdate() }
            id = upsert.dealId
        }
        return id
    }

    /**
     * Retired wrong-size rows whose family has not been searched for the right size yet, or searched
     * without luck more than [retryDays] ago and retired no more than [windowDays] ago. The window ends
     * the retries: a size a family has not had in a month is not coming back soon.
     */
    fun siblingRecoveryCandidates(
        limit: Int,
        sources: List<String>,
        retryDays: Int = 7,
        windowDays: Int = 28,
    ): List<Triple<Long, String, String>> =
        if (sources.isEmpty()) emptyList() else ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT d.id, d.asin, d.title FROM deals d
                 WHERE d.size_rejected
                   AND d.size_reject_reason LIKE 'page says%'
                   AND d.asin IS NOT NULL
                   AND d.source = ANY(?)
                   AND (d.sibling_checked_at IS NULL
                        OR (NOT coalesce(d.sibling_added, FALSE)
                            AND d.sibling_checked_at < now() - make_interval(days => ?)
                            AND d.page_checked_at > now() - make_interval(days => ?)))
                 ORDER BY d.sibling_checked_at ASC NULLS FIRST, d.id DESC
                 LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
                st.setInt(2, retryDays)
                st.setInt(3, windowDays)
                st.setInt(4, limit)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Triple(rs.getLong("id"), rs.getString("asin"), rs.getString("title"))) }
                }
            }
        }

    /** Records that a retired row's family was searched for its size, and whether a replacement started. */
    fun recordSiblingAttempt(id: Long, added: Boolean) {
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE deals SET sibling_checked_at = now(), sibling_added = ? WHERE id = ?")
                .use { st -> st.setBoolean(1, added); st.setLong(2, id); st.executeUpdate() }
        }
    }

    /** A visit that read nothing usable: counts toward backoff, changes nothing else. */
    fun recordPageAttempt(id: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE deals SET page_attempts = page_attempts + 1, page_attempted_at = now() WHERE id = ?",
            ).use { st -> st.setLong(1, id); st.executeUpdate() }
        }
    }

    /**
     * Records what the product page said about a row's size, and corrects its price.
     *
     * [rejectReason] non-null retires the row for good (`size_rejected`, see V23). Otherwise the row
     * is marked visited. A page price that differs from the latest observation is appended as a new
     * observation rather than edited in: history stays an honest record of what was read when, and
     * the tile reading that preceded it remains visible as the disagreement it was. The list price is
     * carried over from that observation, since the buy box does not state one and a null would erase
     * the discount the row displays. Returns whether a price was appended.
     */
    fun recordPageCheck(
        id: Long,
        pageSize: String?,
        rejectReason: String?,
        pagePrice: java.math.BigDecimal?,
        pageCurrency: String?,
        valid: Boolean = false,
    ): Boolean =
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    UPDATE deals
                       SET page_checked_at = now(), page_size = ?, page_attempts = 0, page_attempted_at = now(),
                           size_rejected = ?, size_reject_reason = ?,
                           is_active = is_active AND NOT ?,
                           page_price = CASE WHEN ? THEN page_price ELSE ? END,
                           page_price_currency = CASE WHEN ? THEN page_price_currency ELSE ? END,
                           page_valid_at = CASE WHEN ? THEN now() ELSE page_valid_at END
                     WHERE id = ?
                    """.trimIndent(),
                ).use { st ->
                    st.setString(1, pageSize)
                    st.setBoolean(2, rejectReason != null)
                    st.setString(3, rejectReason?.take(300))
                    st.setBoolean(4, rejectReason != null)
                    // Kept as it was when this read produced no usable price, so a bad load cannot erase it.
                    val noPrice = rejectReason != null || pagePrice == null || pageCurrency == null
                    st.setBoolean(5, noPrice)
                    st.setBigDecimal(6, pagePrice)
                    st.setBoolean(7, noPrice)
                    st.setString(8, pageCurrency)
                    st.setBoolean(9, valid && rejectReason == null)
                    st.setLong(10, id)
                    st.executeUpdate()
                }
                var appended = false
                if (rejectReason == null && pagePrice != null && pageCurrency != null) {
                    appended = conn.prepareStatement(
                        """
                        INSERT INTO price_observations (deal_id, price, list_price, currency)
                        SELECT deal_id, ?, list_price, currency FROM (
                            SELECT deal_id, price, list_price, currency FROM price_observations
                             WHERE deal_id = ? ORDER BY observed_at DESC LIMIT 1
                        ) last
                        WHERE last.currency = ? AND last.price <> ?
                        """.trimIndent(),
                    ).use { st ->
                        st.setBigDecimal(1, pagePrice)
                        st.setLong(2, id)
                        st.setString(3, pageCurrency)
                        st.setBigDecimal(4, pagePrice)
                        st.executeUpdate() > 0
                    }
                }
                conn.commit()
                appended
            } catch (e: Exception) {
                conn.rollback()
                throw e
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
                       co_shipping_evidence = CASE WHEN ? THEN co_shipping_evidence ELSE ? END,
                       co_shipping_import_free = CASE WHEN ? THEN co_shipping_import_free ELSE ? END,
                       co_shipping_cost = CASE WHEN ? THEN co_shipping_cost ELSE ? END
                 WHERE id = ?
                   AND coalesce(co_shipping_source, '') <> 'MANUAL'
                """.trimIndent(),
            ).use { st ->
                st.setBoolean(1, unclear)
                st.setString(2, verdict.name)
                st.setBoolean(3, unclear)
                st.setBoolean(4, unclear)
                st.setBoolean(5, unclear)
                st.setString(6, evidence.take(300))
                st.setBoolean(7, unclear)
                importFree?.let { st.setBoolean(8, it) } ?: st.setNull(8, java.sql.Types.BOOLEAN)
                st.setBoolean(9, unclear)
                st.setBigDecimal(10, shippingCost)
                st.setLong(11, id)
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

    /**
     * When [source] last FINISHED a fetch without error, or null if it never has. Asked by the
     * scheduler, so a run is judged by when it completed.
     *
     * `finished_at`, not `started_at`: a catch-up crawl that started at 06:43 and, slowed by a
     * sleeping Mac, finished at 07:51 had read the store past the 07:00 slot -- keyed on its start it
     * counted as "before 07:00" and the same store was crawled again at 07:57.
     */
    fun lastSuccessfulRun(source: String): Instant? =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT max(finished_at) FROM fetch_runs " +
                    "WHERE source = ? AND finished_at IS NOT NULL AND error IS NULL AND item_count > 0",
            ).use { st ->
                st.setString(1, source)
                st.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
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
}

/**
 * One row of the shared deal select -- `d.*` plus the latest observation (`price`, `list_price`),
 * the history aggregate (`min_price`, `max_price`, `n`) and `relevance`.
 *
 * File-level rather than private to [DealRepository] so the grocery query, which selects the same
 * columns and more, builds its [Deal] the same way instead of a second mapping that could drift.
 */
internal fun ResultSet.toDeal() = Deal(
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
    pinnedAt = getTimestamp("pinned_at")?.toInstant(),
)
