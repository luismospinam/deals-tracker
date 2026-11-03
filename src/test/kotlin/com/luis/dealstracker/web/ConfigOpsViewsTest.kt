package com.luis.dealstracker.web

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.DbConfig
import com.luis.dealstracker.config.Defaults
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ops.BackupFile
import com.luis.dealstracker.ops.HealthStatus
import com.luis.dealstracker.ops.SourceHealth
import com.luis.dealstracker.promo.Promo
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Config tab's Backups, source health and promo reminders. */
class ConfigOpsViewsTest {
    private val t = Instant.parse("2026-11-03T20:00:00Z")
    private val file = AppConfig(
        defaults = Defaults(),
        providers = listOf(ProviderConfig("fullhogar", "woocommerce"), ProviderConfig("exito", "vtex")),
        db = DbConfig("jdbc:x", "u", "p"), mlClientId = null, mlClientSecret = null,
    )

    private fun render(
        health: Map<String, SourceHealth> = emptyMap(),
        backup: BackupView? = null,
        promos: List<Promo> = emptyList(),
    ) = createHTML().main {
        configPage(
            ConfigModel(
                file = file, live = file, overrides = emptyMap(), cards = emptyList(), picked = emptySet(),
                unnamedCardReadings = 0, lastRuns = emptyMap(), prunedAt = null, now = t, zone = ZoneOffset.UTC,
                health = health, backup = backup, promos = promos,
            ),
        )
    }

    @Test
    fun `the backups section says where the dumps are and lists them`() {
        val html = render(
            backup = BackupView(
                dir = "/Users/x/deals-tracker/backups/nightly",
                files = listOf(BackupFile("deals-2026-11-03-0300.dump", 58_882_276, t)),
                lastAt = t, lastError = null, failedAt = null, running = false,
            ),
        )
        assertTrue(html.contains("/Users/x/deals-tracker/backups/nightly"))
        assertTrue(html.contains("deals-2026-11-03-0300.dump") && html.contains("58.9 MB"))
        assertTrue(html.contains("action=\"/config/backup\""))
        assertTrue(html.contains("name=\"backupTimes\"") && html.contains("placeholder=\"03:00\""), "its schedule is editable")
    }

    @Test
    fun `a failed backup is shown as an alert`() {
        val html = render(backup = BackupView("/b", emptyList(), null, "docker not found", t, false))
        assertTrue(html.contains("role=\"alert\"") && html.contains("docker not found"))
        assertTrue(html.contains("No backup yet"))
    }

    @Test
    fun `each source shows its health, and why when it is broken`() {
        val html = render(
            health = mapOf(
                "fullhogar" to SourceHealth("fullhogar", HealthStatus.FAILING, 3, 3, null, t, 0, null),
                "exito" to SourceHealth("exito", HealthStatus.OK, 0, 3, t, t, 400, null),
            ),
        )
        assertTrue(html.contains("not working") && html.contains("3 runs in a row returned nothing"), html)
        assertTrue(html.contains("id=\"health\""), "the alert banner's link lands here")
        assertTrue(html.contains("DISCORD_HEALTH_WEBHOOK_URL"))
    }

    @Test
    fun `promo reminders are listed with their next date, and can be added`() {
        val bancol = Promo(
            1, "Bancolombia first Tuesday", "BANCOLMARTES", "Bancolombia", "amazon", 1, DayOfWeek.TUESDAY,
            BigDecimal("60"), BigDecimal("10"), "USD",
        )
        val html = render(promos = listOf(bancol))
        assertTrue(html.contains("first Tuesday of the month"))
        assertTrue(html.contains("2026-11-03"), "today is the first Tuesday")
        assertTrue(html.contains("action=\"/config/promos/1/disable\"") && html.contains("action=\"/config/promos/1/delete\""))
        assertTrue(html.contains("action=\"/config/promos\""))
        assertTrue(html.contains("DISCORD_PROMOS_WEBHOOK_URL"), "its channel is reported with the others")
        assertFalse(render().contains("/config/promos/1/"))
    }
}
