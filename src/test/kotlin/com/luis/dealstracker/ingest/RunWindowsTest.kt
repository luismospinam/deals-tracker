package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.ProviderConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunWindowsTest {

    private val zone = ZoneId.of("America/Bogota")
    private val day = LocalDate.of(2026, 9, 26)
    private fun at(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    @Test
    fun `parses windows and rejects bad ones`() {
        assertEquals(RunWindow(LocalTime.of(7, 0), LocalTime.of(7, 40)), RunWindow.parse("07:00-07:40"))
        assertEquals(RunWindow(LocalTime.of(7, 45), LocalTime.of(8, 30)), RunWindow.parse(" 7:45 - 08:30 "))
        assertNull(RunWindow.parse("08:00-07:00"))
        assertNull(RunWindow.parse("7-8"))
        assertNull(RunWindow.parse("25:00-26:00"))
    }

    private val window = RunWindow(LocalTime.of(7, 0), LocalTime.of(7, 40))

    @Test
    fun `the minute is inside the window, and fixed for a given day`() {
        val slot = slotIn(window, "exito-grocery", day)
        assertTrue(!slot.isBefore(window.start) && slot.isBefore(window.end), "$slot")
        // Same inputs, same minute -- a restart does not re-roll it.
        assertEquals(slot, slotIn(window, "exito-grocery", day))
    }

    /** Across a month the minute moves, and it covers the window rather than sticking to one end. */
    @Test
    fun `the minute varies by day`() {
        val minutes = (0L until 30L).map { slotIn(window, "exito-grocery", day.plusDays(it)).minute }.toSet()
        assertTrue(minutes.size > 10, "only $minutes")
        assertTrue(minutes.any { it < 15 } && minutes.any { it > 25 }, "$minutes")
    }

    @Test
    fun `two stores in the same window usually get different minutes`() {
        val differ = (0L until 30L).count { slotIn(window, "a", day.plusDays(it)) != slotIn(window, "b", day.plusDays(it)) }
        assertTrue(differ > 25, "$differ")
    }

    private fun service(last: Instant?) = IngestService(
        repo = throwingRepo(),
        registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
        app = fakeAppConfig(),
        http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
        lastSuccess = { last },
    )

    private val provider = ProviderConfig(
        id = "exito-grocery", type = "vtex-grocery",
        listOptions = mapOf("runWindows" to listOf("07:00-07:40", "10:30-11:10", "17:00-17:40")),
    )

    @Test
    fun `due once the day's minute has passed, and not again until the next window`() {
        val slot = day.atTime(slotIn(window, provider.id, day)).atZone(zone).toInstant()
        val yesterdayEvening = at("00:00").minusSeconds(4 * 3600)
        // Before today's minute: yesterday's evening run still covers it.
        assertFalse(service(yesterdayEvening).isDue(provider, slot.minusSeconds(60), zone))
        assertTrue(service(yesterdayEvening).isDue(provider, slot, zone))
        // Read just after the slot: not due again at 09:00, before the midday window.
        assertFalse(service(slot.plusSeconds(300)).isDue(provider, at("09:00"), zone))
    }

    /** Windows win over a leftover `runAtHours` on the same provider. */
    @Test
    fun `windows take precedence over fixed hours`() {
        val both = provider.copy(listOptions = provider.listOptions + ("runAtHours" to listOf("3")))
        assertNotEquals(null, runWindows(both))
        assertFalse(service(at("06:59")).isDue(both, at("06:59").plusSeconds(60), zone))
    }

    @Test
    fun `the next slot is the first one after now`() {
        val windows = runWindows(provider)!!
        val next = nextWindowSlot(windows, provider.id, at("09:00"), zone)!!.atZone(zone).toLocalTime()
        assertTrue(!next.isBefore(LocalTime.of(10, 30)) && next.isBefore(LocalTime.of(11, 10)), "$next")
    }
}
