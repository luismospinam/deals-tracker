package com.luis.dealstracker.web

import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A finished run that brought back nothing used to read `ok`, which hid Fullhogar's 114 empty runs in a week. */
class RecentRunsTest {

    @Test
    fun `a finished run with no items is a warning, not ok`() {
        assertEquals(RunStatus.EMPTY, runStatus(error = null, finished = true, itemCount = 0))
        assertEquals(RunStatus.OK, runStatus(error = null, finished = true, itemCount = 66))
    }

    /** An error says more than "no items", and a run still going has not brought anything back yet. */
    @Test
    fun `an error or a run in progress outranks the item count`() {
        assertEquals(RunStatus.ERROR, runStatus(error = "HTTP 503", finished = true, itemCount = 0))
        assertEquals(RunStatus.RUNNING, runStatus(error = null, finished = false, itemCount = 0))
    }

    /** A restart mid-run writes no finish time; after an hour that row is interrupted, not "running…" forever. */
    @Test
    fun `an unfinished run over an hour old is interrupted`() {
        val now = Instant.parse("2026-11-02T15:00:00Z")
        assertEquals(RunStatus.RUNNING, runStatus(null, false, 0, now.minusSeconds(9 * 60), now))
        assertEquals(RunStatus.INTERRUPTED, runStatus(null, false, 0, now.minusSeconds(61 * 60), now))
    }

    @Test
    fun `the table marks an empty run in words, not only colour`() {
        val now = Instant.now()
        val html = createHTML().main {
            recentRuns(
                listOf(
                    mapOf("source" to "fullhogar", "startedAt" to now, "finishedAt" to now, "itemCount" to 0, "newCount" to 0, "error" to null),
                    mapOf("source" to "exito", "startedAt" to now, "finishedAt" to now, "itemCount" to 888, "newCount" to 0, "error" to null),
                ),
            )
        }
        val rows = html.substringAfter("<tbody>").split("</tr>")
        assertTrue(rows[0].contains("fullhogar") && rows[0].contains("class=\"warn\"") && rows[0].contains("no items"), rows[0])
        assertTrue(rows[1].contains("exito") && rows[1].contains(">ok<"), rows[1])
        assertFalse(rows[1].contains("warn"), rows[1])
    }
}
