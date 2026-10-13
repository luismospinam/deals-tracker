package com.luis.dealstracker.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservationSummaryTest {

    @Test
    fun `the summary join reads the deal's own columns and never the history table`() {
        assertFalse("price_observations" in OBSERVATION_SUMMARY_JOIN, OBSERVATION_SUMMARY_JOIN)
        listOf("obs_min_price AS min_price", "obs_max_price AS max_price", "obs_count AS n").forEach {
            assertTrue(it in OBSERVATION_SUMMARY_JOIN, "$it missing from $OBSERVATION_SUMMARY_JOIN")
        }
        // Callers refer to it as `agg`, and to the deal as `d`.
        assertTrue(OBSERVATION_SUMMARY_JOIN.endsWith("agg ON TRUE"))
    }

    @Test
    fun `the migration backfills, then keeps the columns current with a trigger on insert`() {
        val sql = File("src/main/resources/db/migration/V27__observation_summary.sql").readText()
        listOf("obs_min_price", "obs_max_price", "obs_count").forEach { col ->
            assertTrue("ADD COLUMN $col" in sql.replace(Regex("\\s+"), " ").replace("ADD COLUMN  ", "ADD COLUMN "), "no column $col")
        }
        assertTrue("AFTER INSERT ON price_observations" in sql)
        assertTrue("LEAST(" in sql && "GREATEST(" in sql, "trigger must widen min and max incrementally")
        assertEquals(1, Regex("CREATE TRIGGER").findAll(sql).count())
    }

    @Test
    fun `the watched filter asks whether a watch matches before it evaluates that watch's exclusions`() {
        val sql = WATCHED_DEAL_CLAUSE
        val case = sql.indexOf("CASE WHEN")
        val then = sql.indexOf(") THEN (")
        val inclusion = sql.indexOf("w.terms")
        val exclusion = sql.indexOf("w.exclude_terms")
        assertTrue(case in 0 until inclusion, "inclusion terms must sit inside the CASE condition")
        assertTrue(inclusion < then && then < exclusion, "exclusions belong in the THEN branch, after the match")
        assertTrue(sql.trimEnd().endsWith("ELSE FALSE END\n)"), "a deal that matches no watch must not be hidden")
    }
}

class UsualPriceCacheTest {

    private val migration get() = java.io.File("src/main/resources/db/migration/V28__usual_price_cache.sql").readText()

    @Test
    fun `the migration adds the cache columns and fills them for grocery rows`() {
        val sql = migration
        listOf("usual_price", "usual_low", "usual_days", "usual_as_of").forEach { assertTrue(it in sql, it) }
        assertTrue("grocery_group IS NOT NULL" in sql, "only grocery rows have a usual price")
        // The window must match the table's definition: the days BEFORE today, today excluded.
        assertTrue("observed_at < current_date" in sql && "current_date - 30" in sql)
    }

    @Test
    fun `the digest finds the all-history minimum only for listings that fell`() {
        val src = java.io.File("src/main/kotlin/com/luis/dealstracker/db/DailyChanges.kt").readText()
        assertTrue("AND t.price < p.price" in src, "minimum must be gated on a fall")
        assertTrue("WHERE t.price < p.price" in src)
        assertFalse("DISTINCT ON" in src, "the all-history DISTINCT ON scan is what made this slow")
    }
}
