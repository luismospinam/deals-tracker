package com.luis.dealstracker.web

import io.ktor.http.Parameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchSortTest {

    private fun params(vararg pairs: Pair<String, String>): Parameters =
        Parameters.build { pairs.forEach { (k, v) -> append(k, v) } }

    @Test
    fun `defaults are cheapest-first for products and newest-first for alerts`() {
        val empty = Parameters.Empty
        assertEquals(WatchSort("price", desc = false), watchSortFrom(empty, WatchSort.MATCHING_PARAM))
        assertEquals(WatchSort("when", desc = true), watchSortFrom(empty, WatchSort.ALERTS_PARAM))
    }

    @Test
    fun `parses a column and an explicit direction`() {
        assertEquals(
            WatchSort("source", desc = false),
            watchSortFrom(params("msort" to "source"), WatchSort.MATCHING_PARAM),
        )
        assertEquals(
            WatchSort("price", desc = true),
            watchSortFrom(params("msort" to "price:desc"), WatchSort.MATCHING_PARAM),
        )
    }

    /**
     * A stale bookmark, or a column renamed in the view, must still render the page. Falling
     * back beats a 400 on a read-only listing.
     */
    @Test
    fun `an unknown or blank column falls back to the default`() {
        assertEquals(
            WatchSort.MATCHING_DEFAULT,
            watchSortFrom(params("msort" to "nonsense"), WatchSort.MATCHING_PARAM),
        )
        assertEquals(
            WatchSort.MATCHING_DEFAULT,
            watchSortFrom(params("msort" to ""), WatchSort.MATCHING_PARAM),
        )
        // "when" belongs to the alerts table, so it is not valid for the product table.
        assertEquals(
            WatchSort.MATCHING_DEFAULT,
            watchSortFrom(params("msort" to "when"), WatchSort.MATCHING_PARAM),
        )
    }

    /** The two tables must not read each other's parameter. */
    @Test
    fun `each table reads only its own parameter`() {
        val both = params("msort" to "source", "asort" to "price:desc")
        assertEquals(WatchSort("source", false), watchSortFrom(both, WatchSort.MATCHING_PARAM))
        assertEquals(WatchSort("price", true), watchSortFrom(both, WatchSort.ALERTS_PARAM))

        // Setting only one leaves the other at its own default rather than borrowing.
        val onlyAlerts = params("asort" to "deal")
        assertEquals(WatchSort.MATCHING_DEFAULT, watchSortFrom(onlyAlerts, WatchSort.MATCHING_PARAM))
    }

    @Test
    fun `clicking the active column flips direction, another column starts fresh`() {
        val current = WatchSort("price", desc = false)
        assertEquals(WatchSort("price", desc = true), current.toggledFor("price", defaultDesc = false))
        // A different column takes that column's own default, not the current direction.
        assertEquals(WatchSort("source", desc = false), current.toggledFor("source", defaultDesc = false))
        // "When" defaults to newest-first, so a first click on it should be descending.
        assertEquals(WatchSort("when", desc = true), current.toggledFor("when", defaultDesc = true))
    }

    @Test
    fun `round-trips through its url form`() {
        assertEquals("price", WatchSort("price", desc = false).asParam())
        assertEquals("price:desc", WatchSort("price", desc = true).asParam())
        listOf(WatchSort("source", false), WatchSort("floor", true)).forEach { sort ->
            assertEquals(
                sort,
                watchSortFrom(params("msort" to sort.asParam()), WatchSort.MATCHING_PARAM),
            )
        }
    }

    @Test
    fun `isOn identifies the active column only`() {
        val sort = WatchSort("floor", desc = true)
        assertTrue(sort.isOn("floor"))
        assertFalse(sort.isOn("price"))
    }

    // --- paging ---

    @Test
    fun `page defaults to the first and never goes below it`() {
        assertEquals(1, watchPageFrom(Parameters.Empty))
        assertEquals(1, watchPageFrom(params("mpage" to "1")))
        // A hand-edited or stale URL must not produce a negative offset.
        assertEquals(1, watchPageFrom(params("mpage" to "0")))
        assertEquals(1, watchPageFrom(params("mpage" to "-5")))
        assertEquals(1, watchPageFrom(params("mpage" to "abc")))
        assertEquals(1, watchPageFrom(params("mpage" to "")))
    }

    @Test
    fun `reads a page number`() {
        assertEquals(7, watchPageFrom(params("mpage" to "7")))
    }

    /**
     * The page belongs to the product table only. Sorting the alerts must not move it, which is
     * why they are separate parameters rather than one shared "page".
     */
    @Test
    fun `page is independent of either sort`() {
        val all = params("msort" to "source", "asort" to "price:desc", "mpage" to "3")
        assertEquals(3, watchPageFrom(all))
        assertEquals(WatchSort("source", false), watchSortFrom(all, WatchSort.MATCHING_PARAM))
        assertEquals(WatchSort("price", true), watchSortFrom(all, WatchSort.ALERTS_PARAM))
    }
}

class WatchRowStyleTest {

    private fun watch(enabled: Boolean) = com.luis.dealstracker.model.Watch(
        id = 9, name = "Televisor", terms = "televisor", excludeTerms = null, categoryGroup = null,
        sources = emptyList(), minPrice = null, maxPrice = null, currency = "COP", lookbackDays = 30,
        crossSiteMinSpread = java.math.BigDecimal("15"), enabled = enabled,
        createdAt = java.time.Instant.parse("2026-09-01T00:00:00Z"),
    )

    /** The pause button swaps in this fragment, so the class must travel with it or the look lags. */
    @Test
    fun `a paused watch's row is marked, an active one is not`() {
        assertTrue(watchRowFragment(watch(enabled = false), null).startsWith("""<tr class="paused""""))
        assertFalse(watchRowFragment(watch(enabled = true), null).contains("paused"))
    }
}
