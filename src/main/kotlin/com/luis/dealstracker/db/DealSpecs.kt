package com.luis.dealstracker.db

import com.luis.dealstracker.model.RawDeal
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

/** What a retailer says a product is, as stored in `deal_specs` (V26). */
data class DealSpecs(val specs: Map<String, String>, val description: String?)

/**
 * Persistence for [DealSpecs], kept apart from [DealRepository] so the price path stays as it is.
 *
 * Specifications change rarely and are read only when an Ask question needs them, so writes are
 * skipped when the hash is unchanged -- an hourly poll of 600 sofas costs no spec writes at all
 * after the first.
 */
object DealSpecStore {
    private val json = Json { encodeDefaults = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Stores what [raw] published, inside the ingest transaction. Does nothing for a row with neither
     * specs nor a description, so sources that publish none never touch the table.
     */
    fun upsert(conn: Connection, dealId: Long, raw: RawDeal) {
        if (raw.specs.isEmpty() && raw.description.isNullOrBlank()) return
        val specsJson = json.encodeToString(mapSerializer, raw.specs.toSortedMap())
        val hash = sha256(specsJson + "\u0000" + raw.description.orEmpty())
        conn.prepareStatement(
            """
            INSERT INTO deal_specs (deal_id, specs, description, spec_hash)
            VALUES (?, ?::jsonb, ?, ?)
            ON CONFLICT (deal_id) DO UPDATE
               SET specs = EXCLUDED.specs, description = EXCLUDED.description,
                   spec_hash = EXCLUDED.spec_hash, fetched_at = now()
             WHERE deal_specs.spec_hash <> EXCLUDED.spec_hash
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, dealId)
            st.setString(2, specsJson)
            st.setString(3, raw.description)
            st.setString(4, hash)
            st.executeUpdate()
        }
    }

    /**
     * Stores what a product page said, outside any ingest transaction. An empty result is written too,
     * as a "looked, found nothing" marker: [pendingPages] skips it until [recheckDays] pass, so a page
     * with no table is not fetched again every cycle.
     */
    fun record(ds: DataSource, dealId: Long, specs: DealSpecs) {
        val specsJson = json.encodeToString(mapSerializer, specs.specs.toSortedMap())
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO deal_specs (deal_id, specs, description, spec_hash)
                VALUES (?, ?::jsonb, ?, ?)
                ON CONFLICT (deal_id) DO UPDATE
                   SET specs = EXCLUDED.specs, description = EXCLUDED.description,
                       spec_hash = EXCLUDED.spec_hash, fetched_at = now()
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, dealId)
                st.setString(2, specsJson)
                st.setString(3, specs.description)
                st.setString(4, sha256(specsJson + "\u0000" + specs.description.orEmpty()))
                st.executeUpdate()
            }
        }
    }

    /**
     * Gives [dealId] the specs another deal with the same page URL already has, and says whether it did.
     *
     * Colour and size variants are separate deals that share one product page, so the page need only be
     * read once. It is also required, not just thrifty: the HTTP client sends conditional requests, so a
     * second fetch of a URL it has already seen answers 304 with no body, and that deal would stay
     * pending forever.
     */
    fun copyFromSameUrl(ds: DataSource, dealId: Long, url: String): Boolean = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO deal_specs (deal_id, specs, description, spec_hash)
            SELECT ?, s.specs, s.description, s.spec_hash
              FROM deal_specs s JOIN deals d ON d.id = s.deal_id
             WHERE d.url = ? AND d.id <> ? AND (s.specs <> '{}'::jsonb OR s.description IS NOT NULL)
             LIMIT 1
            ON CONFLICT (deal_id) DO NOTHING
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, dealId)
            st.setString(2, url)
            st.setLong(3, dealId)
            st.executeUpdate() > 0
        }
    }

    /** One product page to read: the deal and where its page is. */
    data class PageTarget(val dealId: Long, val url: String)

    /**
     * Products whose specs live only on their own page and that an enabled watch is hunting.
     *
     * Restricted to watch matches on purpose: Falabella and Homecenter list tens of thousands of
     * products, each page is ~1 MB, and nothing outside a watch is ever asked about. A product matches
     * when one of an enabled watch's terms is in its title and none of that watch's exclusions are --
     * the same test the watch page applies, so a toilet seat is not fetched for a toilet watch.
     * Newest watch first, so a watch just added is filled before the old ones.
     *
     * Never-fetched rows come first; a row marked empty comes back after [recheckDays].
     */
    fun pendingPages(ds: DataSource, sources: List<String>, limit: Int, watchId: Long? = null, recheckDays: Int = 7): List<PageTarget> {
        if (sources.isEmpty() || limit <= 0) return emptyList()
        return ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT d.id, d.url
                  FROM deals d
                  LEFT JOIN deal_specs s ON s.deal_id = d.id
                  JOIN LATERAL (
                        SELECT max(w.created_at) AS newest
                          FROM watches w
                         WHERE w.enabled
                           AND (?::bigint IS NULL OR w.id = ?)
                           AND EXISTS (SELECT 1 FROM unnest(string_to_array(w.terms, '|')) t
                                        WHERE btrim(t) <> '' AND unaccent(d.title) ILIKE unaccent('%' || btrim(t) || '%'))
                           AND NOT EXISTS (SELECT 1 FROM unnest(string_to_array(coalesce(w.exclude_terms, ''), '|')) x
                                            WHERE btrim(x) <> '' AND unaccent(d.title) ILIKE unaccent('%' || btrim(x) || '%'))
                       ) m ON m.newest IS NOT NULL
                 WHERE d.is_active
                   AND d.source = ANY (?)
                   AND d.url <> ''
                   AND (s.deal_id IS NULL
                        OR (s.specs = '{}'::jsonb AND s.description IS NULL
                            AND s.fetched_at < now() - make_interval(days => ?)))
                 ORDER BY (s.deal_id IS NULL) DESC, m.newest DESC, d.last_seen_at DESC
                 LIMIT ?
                """.trimIndent(),
            ).use { st ->
                if (watchId == null) { st.setNull(1, java.sql.Types.BIGINT); st.setNull(2, java.sql.Types.BIGINT) }
                else { st.setLong(1, watchId); st.setLong(2, watchId) }
                st.setArray(3, conn.createArrayOf("text", sources.toTypedArray()))
                st.setInt(4, recheckDays)
                st.setInt(5, limit)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(PageTarget(rs.getLong(1), rs.getString(2))) } }
            }
        }
    }

    /** Specs for these deals, keyed by id. A deal with none stored is simply absent. */
    fun load(ds: DataSource, ids: Collection<Long>): Map<Long, DealSpecs> {
        if (ids.isEmpty()) return emptyMap()
        return ds.connection.use { conn ->
            conn.prepareStatement("SELECT deal_id, specs::text, description FROM deal_specs WHERE deal_id = ANY (?)").use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(
                                rs.getLong(1),
                                DealSpecs(json.decodeFromString(mapSerializer, rs.getString(2)), rs.getString(3)),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Which of these deals have anything stored, so a prompt can say where specs are worth asking for. */
    fun idsWithSpecs(ds: DataSource, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return ds.connection.use { conn ->
            conn.prepareStatement("SELECT deal_id FROM deal_specs WHERE deal_id = ANY (?) AND (specs <> '{}'::jsonb OR description IS NOT NULL)").use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
