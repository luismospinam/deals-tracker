package com.luis.dealstracker.ops

import com.luis.dealstracker.config.ProviderConfig
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceHealthTest {
    private val now = Instant.parse("2026-11-03T20:00:00Z")
    private val hourly = ProviderConfig("fullhogar", "woocommerce")
    private val grocery = ProviderConfig("exito-grocery", "vtex-grocery", listOptions = mapOf("runWindows" to listOf("07:00-07:40", "10:30-11:10", "17:00-17:40")))
    private val poll = Duration.ofMinutes(60)

    /** Runs an hour apart, newest first: '.' good, '0' empty, 'E' error, '?' unfinished. */
    private fun runs(pattern: String, every: Duration = Duration.ofHours(1), newest: Instant = now.minusSeconds(600)) =
        pattern.mapIndexed { i, c ->
            val start = newest.minus(every.multipliedBy(i.toLong()))
            when (c) {
                '.' -> RunRow(start, start.plusSeconds(60), 40, null)
                '0' -> RunRow(start, start.plusSeconds(60), 0, null)
                'E' -> RunRow(start, start.plusSeconds(60), 0, "HTTP 403")
                else -> RunRow(start, null, 0, null)
            }
        }

    private fun assess(p: ProviderConfig, r: List<RunRow>, alive: Instant? = now.minusSeconds(300), live: ProviderConfig = p, bootedAt: Instant = Instant.EPOCH) =
        assessSource(p, live, r, poll, failAfter = 3, pollerAliveAt = alive, now = now, bootedAt = bootedAt)

    @Test
    fun `one empty run is routine, three in a row is broken`() {
        assertEquals(HealthStatus.OK, assess(hourly, runs("...0..")).status)
        assertEquals(HealthStatus.DEGRADED, assess(hourly, runs("0.....")).status)
        assertEquals(HealthStatus.DEGRADED, assess(hourly, runs("00....")).status)
        val broken = assess(hourly, runs("000..."))
        assertEquals(HealthStatus.FAILING, broken.status)
        assertEquals(3, broken.badStreak)
        assertEquals(now.minusSeconds(600).minus(Duration.ofHours(3)).plusSeconds(60), broken.lastOk)
    }

    @Test
    fun `errors count like empty runs, and the reason is kept`() {
        val h = assess(hourly, runs("EE0."))
        assertEquals(HealthStatus.FAILING, h.status)
        assertEquals("HTTP 403", h.lastError)
    }

    @Test
    fun `a scheduled crawl is broken after two empty runs`() {
        assertEquals(HealthStatus.FAILING, assess(grocery, runs("00..", Duration.ofHours(8))).status)
        assertEquals(Duration.ofHours(8), expectedEvery(grocery, poll))
        assertEquals(poll, expectedEvery(hourly, poll))
    }

    @Test
    fun `a run cut short by a restart is skipped, one stuck since this boot is hung`() {
        // Started 4h ago, before this boot an hour ago: the restart cut it short, so it is not hung.
        val cut = listOf(RunRow(now.minus(Duration.ofHours(4)), null, 0, null)) + runs("...", newest = now.minus(Duration.ofHours(5)))
        val restarted = assess(hourly, cut, bootedAt = now.minus(Duration.ofHours(1)))
        assertNull(restarted.hungSince)
        assertTrue(restarted.status != HealthStatus.FAILING)
        val stuck = listOf(RunRow(now.minus(Duration.ofHours(4)), null, 0, null)) + runs("...", newest = now.minus(Duration.ofHours(5)))
        val h = assess(hourly, stuck, bootedAt = now.minus(Duration.ofHours(6)))
        assertEquals(HealthStatus.FAILING, h.status)
        assertEquals(now.minus(Duration.ofHours(4)), h.hungSince)
    }

    @Test
    fun `overdue only while the poller is alive, so a Mac asleep blames no source`() {
        val old = runs("...", newest = now.minus(Duration.ofHours(5)))
        assertEquals(HealthStatus.OVERDUE, assess(hourly, old).status)
        assertEquals(HealthStatus.OK, assess(hourly, old, alive = now.minus(Duration.ofHours(5))).status)
        assertEquals(HealthStatus.OK, assess(grocery, runs("..", newest = now.minus(Duration.ofHours(10)))).status, "8h schedule: 17h before overdue")
    }

    @Test
    fun `paused, off and never-run sources are not alerted`() {
        assertEquals(HealthStatus.PAUSED, assess(hourly, runs("000"), live = hourly.copy(enabled = false)).status)
        assertEquals(HealthStatus.OFF, assess(hourly.copy(enabled = false), runs("000")).status)
        assertEquals(HealthStatus.NEW, assess(hourly, emptyList()).status)
    }

    @Test
    fun `an alert goes out once per breakage, and once on recovery`() {
        fun h(id: String, s: HealthStatus) = SourceHealth(id, s, 3, 3, null, null, 0, null)
        val first = healthChanges(listOf(h("amazon", HealthStatus.FAILING), h("exito", HealthStatus.OK)), emptySet())
        assertEquals(listOf("amazon"), first.broke.map { it.id })
        assertEquals(setOf("amazon"), first.alerted)

        val again = healthChanges(listOf(h("amazon", HealthStatus.FAILING)), first.alerted)
        assertTrue(again.broke.isEmpty(), "still broken: no second alert")

        val back = healthChanges(listOf(h("amazon", HealthStatus.DEGRADED)), first.alerted)
        assertEquals(listOf("amazon"), back.recovered)
        assertTrue(back.alerted.isEmpty())

        val paused = healthChanges(listOf(h("amazon", HealthStatus.PAUSED)), first.alerted)
        assertTrue(paused.recovered.isEmpty() && paused.alerted.isEmpty(), "pausing it is not a recovery")
    }

    @Test
    fun `the alert line says why`() {
        val tr = { en: String, _: String -> en }
        val ago = { _: Instant -> "2h ago" }
        val empty = SourceHealth("fullhogar", HealthStatus.FAILING, 3, 3, Instant.EPOCH, null, 0, null)
        assertEquals("fullhogar: 3 runs in a row returned nothing (last good run 2h ago)", brokeLine(empty, tr, ago))
        val never = empty.copy(status = HealthStatus.OVERDUE, lastOk = null)
        assertEquals("fullhogar: not run on schedule (no good run on record)", brokeLine(never, tr, ago))
        assertNull(empty.hungSince)
    }
}
