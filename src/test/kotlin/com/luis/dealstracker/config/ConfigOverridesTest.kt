package com.luis.dealstracker.config

import com.luis.dealstracker.web.overrideChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Config tab's overrides over sources.yaml: what they change, and what they refuse. */
class ConfigOverridesTest {

    private val file = AppConfig(
        defaults = Defaults(pollIntervalMinutes = 60, watchDigestTimes = listOf("19:00"), pinnedAlertTimes = listOf("08:00", "19:00")),
        providers = listOf(
            ProviderConfig(id = "exito-grocery", type = "vtex-grocery", listOptions = mapOf("runWindows" to listOf("07:00-07:40", "15:00-15:40"))),
            ProviderConfig(id = "falabella", type = "falabella-next"),
            ProviderConfig(id = "mercadolibre", type = "mercadolibre", enabled = false),
        ),
        db = DbConfig("jdbc:x", "u", "p"),
        mlClientId = null, mlClientSecret = null,
        discordWatchesWebhook = "https://example.invalid/hook",
    )

    @Test
    fun `no overrides is the file`() {
        val out = ConfigOverrides.apply(file, emptyMap())
        assertEquals(file.defaults, out.defaults)
        assertEquals(file.providers, out.providers)
        assertEquals("https://example.invalid/hook", out.discordWatchesWebhook, "secrets are carried over")
    }

    @Test
    fun `overrides change the schedule, the windows and pause a source`() {
        val out = ConfigOverrides.apply(
            file,
            mapOf(
                "pollIntervalMinutes" to "30",
                "watchDigestTimes" to "08:00,20:00",
                "pinnedAlertTimes" to "off",
                "runWindows.exito-grocery" to "06:00-06:30",
                "enabled.falabella" to "false",
            ),
        )
        assertEquals(30, out.defaults.pollIntervalMinutes)
        assertEquals(listOf("08:00", "20:00"), out.defaults.watchDigestTimes)
        assertEquals(emptyList(), out.defaults.pinnedAlertTimes)
        assertEquals(listOf("06:00-06:30"), out.providers.first { it.id == "exito-grocery" }.list("runWindows"))
        assertFalse(out.providers.first { it.id == "falabella" }.enabled)
    }

    @Test
    fun `an override cannot switch on a source the file disables, nor add windows to an hourly one`() {
        val out = ConfigOverrides.apply(file, mapOf("enabled.mercadolibre" to "true", "runWindows.falabella" to "06:00-06:30"))
        assertFalse(out.providers.first { it.id == "mercadolibre" }.enabled)
        assertFalse(out.providers.first { it.id == "falabella" }.listOptions.containsKey("runWindows"))
    }

    /** A bad row (by hand, or from a newer build) must never stop the app booting. */
    @Test
    fun `a value that does not parse is ignored`() {
        val out = ConfigOverrides.apply(file, mapOf("pollIntervalMinutes" to "5", "watchDigestTimes" to "25:00", "askModel" to "gpt"))
        assertEquals(60, out.defaults.pollIntervalMinutes, "below the 15-minute floor")
        assertEquals(listOf("19:00"), out.defaults.watchDigestTimes)
        assertEquals(file.defaults.askModel, out.defaults.askModel)
    }

    @Test
    fun `times and windows are normalised and checked`() {
        assertEquals("08:00,19:30", ConfigOverrides.parseTimes(" 19:30, 8:00 ,08:00").getOrThrow())
        assertEquals("off", ConfigOverrides.parseTimes("OFF").getOrThrow())
        assertTrue(ConfigOverrides.parseTimes("7pm").isFailure)
        assertEquals("07:00-07:40,15:00-15:40", ConfigOverrides.parseWindows("15:00-15:40, 07:00-07:40").getOrThrow())
        assertTrue(ConfigOverrides.parseWindows("07:00-08:00, 07:30-07:50").isFailure, "overlapping windows")
        assertTrue(ConfigOverrides.parseWindows("08:00-07:00").isFailure, "ends before it starts")
    }

    @Test
    fun `a submitted form sets what changed, clears blanks and file values, and refuses bad input`() {
        val changes = overrideChanges(
            file,
            mapOf(
                "pollIntervalMinutes" to "30",
                "watchDigestTimes" to "19:00",
                "pinnedAlertTimes" to "",
                "coShippingPollBatch" to "lots",
                "runWindows.exito-grocery" to "06:00-06:30",
                "cfg.anything" to "x",
            ),
        )
        assertEquals(mapOf("pollIntervalMinutes" to "30", "runWindows.exito-grocery" to "06:00-06:30"), changes.set)
        assertEquals(setOf("watchDigestTimes", "pinnedAlertTimes"), changes.cleared.toSet(), "the file's own value is not frozen into an override")
        assertEquals(setOf("coShippingPollBatch"), changes.errors.keys)
    }
}
