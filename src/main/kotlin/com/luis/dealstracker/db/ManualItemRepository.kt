package com.luis.dealstracker.db

import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

/**
 * One item tracked because it was asked for by URL.
 *
 * [brand] and [productType] are operator OVERRIDES and normally null: the product page publishes a
 * brand, a breadcrumb category and the ASIN's own size, so they are only needed where the page does
 * not say or says something unhelpful.
 */
data class ManualItem(
    val id: Long,
    val url: String,
    val asin: String,
    val source: String,
    val brand: String?,
    val productType: String?,
    val addedAt: Instant,
    val lastFetchAt: Instant?,
    val lastFetchError: String?,
)

/**
 * The manual tracking list: intent, not observations.
 *
 * `deals` and `price_observations` remain the only record of what was seen. This table says which
 * URLs to keep fetching, and is joined to those by `(source, asin)` — so a manual item's price
 * history is indistinguishable from a searched one's and the tracked tab needs no special case.
 *
 * Why this route is the more trustworthy of the two: Amazon's size FILTER returns a representative
 * of the product family rather than strictly the matching child, so a searched row can carry the
 * wrong variant at the wrong price. A pasted URL names an exact ASIN, so it cannot.
 */
class ManualItemRepository(private val ds: DataSource) {

    /**
     * Adds a URL to the list, or returns the existing row if that ASIN is already tracked.
     *
     * Idempotent on ASIN rather than on URL: the same item has many URL forms (with and without a
     * slug, with `?th=1&psc=1`, with a `ref=` payload), and treating those as different items would
     * double the request cost for one thing. The URL of the FIRST add is kept, since that is what
     * the operator saw.
     *
     * Returns the row and whether it was newly created, so the caller can say "already tracking
     * this" rather than silently doing nothing.
     */
    fun add(url: String, asin: String, brand: String?, productType: String?): Pair<ManualItem, Boolean> =
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO manual_items (url, asin, brand, product_type)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (asin) DO UPDATE SET
                    -- Re-adding with an override is how you CORRECT one, so these refresh. COALESCE
                    -- in this direction means re-adding without an override leaves the existing one
                    -- alone rather than clearing it.
                    brand        = COALESCE(EXCLUDED.brand, manual_items.brand),
                    product_type = COALESCE(EXCLUDED.product_type, manual_items.product_type)
                RETURNING id, url, asin, source, brand, product_type, added_at,
                          last_fetch_at, last_fetch_error, (added_at = now()) AS is_new
                """.trimIndent(),
            ).use { st ->
                st.setString(1, url)
                st.setString(2, asin)
                st.setString(3, brand)
                st.setString(4, productType)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.toManualItem() to rs.getBoolean("is_new")
                }
            }
        }

    /** Oldest-checked first, nulls first, so a repeatedly failing item cannot starve the others. */
    fun all(): List<ManualItem> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT id, url, asin, source, brand, product_type, added_at, last_fetch_at, last_fetch_error
            FROM manual_items ORDER BY last_fetch_at ASC NULLS FIRST, id ASC
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toManualItem()) }
            }
        }
    }

    /**
     * Records the outcome of a refresh.
     *
     * The error is stored rather than only logged, because a manual item that has silently stopped
     * refreshing looks identical in the UI to one whose price genuinely has not moved — and those
     * are very different facts. Success clears it, so a stale message never outlives its cause.
     */
    fun recordFetch(id: Long, error: String?) = ds.connection.use { conn ->
        conn.prepareStatement(
            "UPDATE manual_items SET last_fetch_at = now(), last_fetch_error = ? WHERE id = ?",
        ).use { st ->
            st.setString(1, error)
            st.setLong(2, id)
            st.executeUpdate()
        }
    }

    /**
     * Stops refreshing a URL.
     *
     * Deliberately does NOT touch `deals` or `price_observations`: the recorded history is worth
     * keeping even once you stop watching, and deleting the deal row would only see it re-inserted
     * by the next search anyway. Hiding the row from the tab is a separate action.
     */
    fun remove(asin: String): Boolean = ds.connection.use { conn ->
        conn.prepareStatement("DELETE FROM manual_items WHERE asin = ?").use { st ->
            st.setString(1, asin)
            st.executeUpdate() > 0
        }
    }

    private fun ResultSet.toManualItem() = ManualItem(
        id = getLong("id"),
        url = getString("url"),
        asin = getString("asin"),
        source = getString("source"),
        brand = getString("brand"),
        productType = getString("product_type"),
        addedAt = getTimestamp("added_at").toInstant(),
        lastFetchAt = getTimestamp("last_fetch_at")?.toInstant(),
        lastFetchError = getString("last_fetch_error"),
    )
}
