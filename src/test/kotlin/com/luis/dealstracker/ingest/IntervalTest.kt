package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.ProviderConfig
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `intervalHours` lets the grocery crawl run daily inside the hourly poller. */
class IntervalTest {

    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun service(last: Instant?) = IngestService(
        repo = throwingRepo(),
        registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
        app = fakeAppConfig(),
        http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
        lastSuccess = { last },
    )

    private val daily = ProviderConfig(id = "carulla-grocery", type = "vtex-grocery", options = mapOf("intervalHours" to "24"))

    @Test
    fun `a provider without an interval runs every poll, without asking the database`() {
        // throwingRepo would fail if this consulted fetch_runs.
        val plain = IngestService(
            repo = throwingRepo(),
            registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
            app = fakeAppConfig(),
            http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
            lastSuccess = { error("must not be asked") },
        )
        assertTrue(plain.isDue(ProviderConfig(id = "exito", type = "vtex"), now))
    }

    @Test
    fun `a daily provider waits a day after its last success`() {
        assertFalse(service(now.minus(3, ChronoUnit.HOURS)).isDue(daily, now))
        assertTrue(service(now.minus(25, ChronoUnit.HOURS)).isDue(daily, now))
    }

    /** A crawl that finished a few minutes later yesterday must not slip a whole poll. */
    @Test
    fun `ten minutes of slack`() {
        assertTrue(service(now.minus(24, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES)).isDue(daily, now))
    }

    @Test
    fun `a provider that never succeeded is due`() {
        assertTrue(service(null).isDue(daily, now))
    }

    private val twice = ProviderConfig(
        id = "exito-grocery", type = "vtex-grocery",
        listOptions = mapOf("runAtHours" to listOf("7", "15")),
    )
    private val utc = java.time.ZoneOffset.UTC

    @Test
    fun `fixed hours run once per slot`() {
        val at = { t: String -> Instant.parse("2026-09-24T${t}Z") }
        // Read at 07:02; the 08:00 and 14:59 polls wait, the 15:00 one runs.
        assertFalse(service(at("07:02:00")).isDue(twice, at("08:00:00"), utc))
        assertFalse(service(at("07:02:00")).isDue(twice, at("14:59:00"), utc))
        assertTrue(service(at("07:02:00")).isDue(twice, at("15:00:00"), utc))
        // The 15:00 read covers the evening; the next is 07:00 tomorrow.
        assertFalse(service(at("15:03:00")).isDue(twice, at("23:00:00"), utc))
        assertTrue(service(at("15:03:00")).isDue(twice, Instant.parse("2026-09-25T07:00:00Z"), utc))
    }

    /** Asleep through both slots: one catch-up crawl on waking, not two. */
    @Test
    fun `a missed slot runs once at the next poll`() {
        val last = Instant.parse("2026-09-23T15:02:00Z")
        val waking = Instant.parse("2026-09-24T18:00:00Z")
        assertTrue(service(last).isDue(twice, waking, utc))
        assertFalse(service(waking).isDue(twice, waking.plusSeconds(3600), utc))
    }
}
