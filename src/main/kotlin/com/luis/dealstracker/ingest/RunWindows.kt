package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.ProviderConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A daily window a provider may run in, e.g. 07:00-07:40, local time.
 *
 * Replaces fixed `runAtHours` for the grocery crawls (2026-09-25). A fixed hour puts every request on
 * the same :00 every day; a random minute inside a window does not, and a window also tolerates a
 * crawl that starts late without calling it missed.
 */
data class RunWindow(val start: LocalTime, val end: LocalTime) {
    init { require(end.isAfter(start)) { "window must end after it starts: $start-$end" } }

    override fun toString() = "%02d:%02d-%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)

    companion object {
        private val SPEC = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""")

        /** `"07:00-07:40"`, or null when it is not one (a window may not cross midnight). */
        fun parse(spec: String): RunWindow? {
            val m = SPEC.matchEntire(spec) ?: return null
            val (h1, m1, h2, m2) = m.destructured
            return runCatching {
                RunWindow(LocalTime.of(h1.toInt(), m1.toInt()), LocalTime.of(h2.toInt(), m2.toInt()))
            }.getOrNull()
        }
    }
}

/** `listOptions.runWindows`, or null when the provider sets none. Invalid entries are dropped. */
internal fun runWindows(provider: ProviderConfig): List<RunWindow>? =
    provider.list("runWindows").mapNotNull { RunWindow.parse(it) }.sortedBy { it.start }.takeIf { it.isNotEmpty() }

/**
 * The minute within [window] that [providerId] runs on [date].
 *
 * Random-looking but deterministic: seeded by provider, date and window, so a restart mid-window keeps
 * the same time rather than re-rolling it -- which would let a restart push the crawl later and later,
 * or run it twice -- and the Last sync footer can say exactly when the next run is.
 */
internal fun slotIn(window: RunWindow, providerId: String, date: LocalDate): LocalTime {
    val minutes = java.time.Duration.between(window.start, window.end).toMinutes().toInt().coerceAtLeast(1)
    val seed = "$providerId|$date|$window".hashCode().toLong()
    val offset = java.util.Random(seed).nextInt(minutes)
    return window.start.plusMinutes(offset.toLong())
}

/** Every slot for [providerId] on the days around [now], as instants, in order. */
internal fun slotsAround(windows: List<RunWindow>, providerId: String, now: Instant, zone: ZoneId): List<Instant> {
    val today = now.atZone(zone).toLocalDate()
    return (-1L..1L).flatMap { d ->
        val day = today.plusDays(d)
        windows.map { w -> day.atTime(slotIn(w, providerId, day)).atZone(zone).toInstant() }
    }.sorted()
}

/** The next slot after [now]. */
internal fun nextWindowSlot(windows: List<RunWindow>, providerId: String, now: Instant, zone: ZoneId): Instant? =
    slotsAround(windows, providerId, now, zone).firstOrNull { it.isAfter(now) }
