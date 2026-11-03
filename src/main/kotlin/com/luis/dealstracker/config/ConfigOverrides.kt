package com.luis.dealstracker.config

import com.luis.dealstracker.ingest.RunWindow

/**
 * The values the Config tab can change, stored as `cfg.*` rows in `app_settings` and laid over
 * `config/sources.yaml`. A missing row means "the file's value", so clearing a field on the page puts
 * the file back in charge rather than freezing today's value into the database.
 *
 * Curated rather than every field of [Defaults]: these are the ones worth turning without an editor --
 * when jobs run, how much each does, which sources poll, and the Ask panel's spend. Pacing that was
 * found empirically against Amazon (delays, wall waits) stays in the file, where its history is.
 */
object ConfigOverrides {

    const val PREFIX = "cfg."

    /** When a change takes hold: on the job's next tick, or only once the app restarts. */
    enum class Applies { NOW, RESTART }

    /** One overridable value: its key under [PREFIX], its parser, and what the file says. */
    data class Field(
        val key: String,
        val applies: Applies,
        /** The value as sources.yaml has it, in the same text form the page shows and accepts. */
        val fileValue: (AppConfig) -> String,
        /** Normalised text, or an error message the page shows beside the field. */
        val parse: (String) -> Result<String>,
    )

    private fun intIn(min: Int, max: Int): (String) -> Result<String> = { raw ->
        raw.trim().toIntOrNull()?.takeIf { it in min..max }?.let { Result.success(it.toString()) }
            ?: Result.failure(IllegalArgumentException("a whole number from $min to $max"))
    }

    private fun decimalIn(min: Double, max: Double): (String) -> Result<String> = { raw ->
        raw.trim().toDoubleOrNull()?.takeIf { it in min..max }?.let { Result.success(it.toString()) }
            ?: Result.failure(IllegalArgumentException("a number from $min to $max"))
    }

    private fun oneOf(vararg options: String): (String) -> Result<String> = { raw ->
        raw.trim().lowercase().takeIf { it in options }?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("one of ${options.joinToString(", ")}"))
    }

    private val TIME = Regex("""^(\d{1,2}):(\d{2})$""")

    /**
     * "08:00, 19:00" -> "08:00,19:00", sorted and deduplicated. "off" is the empty list, which for the
     * watch digest restores a banner per cycle and for the pinned posts sends none.
     */
    internal fun parseTimes(raw: String): Result<String> {
        val text = raw.trim()
        if (text.equals("off", ignoreCase = true)) return Result.success("off")
        val parts = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("times like 08:00, 19:00, or off"))
        val times = parts.map { p ->
            val m = TIME.matchEntire(p) ?: return Result.failure(IllegalArgumentException("\"$p\" is not a time like 08:00"))
            val (h, min) = m.destructured
            if (h.toInt() !in 0..23 || min.toInt() !in 0..59) return Result.failure(IllegalArgumentException("\"$p\" is not a time of day"))
            "%02d:%02d".format(h.toInt(), min.toInt())
        }
        return Result.success(times.distinct().sorted().joinToString(","))
    }

    /** "07:00-07:40, 15:00-15:40": each a valid window, none overlapping, in order. */
    internal fun parseWindows(raw: String): Result<String> {
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("windows like 07:00-07:40, 15:00-15:40"))
        val windows = parts.map { p ->
            RunWindow.parse(p) ?: return Result.failure(IllegalArgumentException("\"$p\" is not a window like 07:00-07:40"))
        }.sortedBy { it.start }
        windows.zipWithNext().firstOrNull { (a, b) -> b.start < a.end }?.let { (a, b) ->
            return Result.failure(IllegalArgumentException("$a and $b overlap"))
        }
        return Result.success(windows.joinToString(","))
    }

    private fun timesText(times: List<String>) = times.ifEmpty { listOf("off") }.joinToString(",")

    /** The global fields, in the order the page shows them. Provider fields are built per provider. */
    val fields: List<Field> = listOf(
        Field("pollIntervalMinutes", Applies.NOW, { it.defaults.pollIntervalMinutes.toString() }, intIn(15, 720)),
        Field("coShippingEveryNPolls", Applies.NOW, { it.defaults.coShippingEveryNPolls.toString() }, intIn(0, 24)),
        Field("coShippingPollBatch", Applies.NOW, { it.defaults.coShippingPollBatch.toString() }, intIn(0, 200)),
        Field("watchDigestTimes", Applies.NOW, { timesText(it.defaults.watchDigestTimes) }, ::parseTimes),
        Field("pinnedAlertTimes", Applies.NOW, { timesText(it.defaults.pinnedAlertTimes) }, ::parseTimes),
        Field("pruneHistoryAfterDays", Applies.NOW, { it.defaults.pruneHistoryAfterDays.toString() }, intIn(0, 365)),
        Field("pruneHistoryEveryDays", Applies.NOW, { it.defaults.pruneHistoryEveryDays.toString() }, intIn(1, 60)),
        Field("backupTimes", Applies.NOW, { timesText(it.defaults.backupTimes) }, ::parseTimes),
        Field("backupKeep", Applies.NOW, { it.defaults.backupKeep.toString() }, intIn(1, 90)),
        Field("healthFailAfterRuns", Applies.NOW, { it.defaults.healthFailAfterRuns.toString() }, intIn(2, 24)),
        Field("askModel", Applies.RESTART, { it.defaults.askModel }, oneOf("haiku", "sonnet", "opus")),
        Field("askEffort", Applies.RESTART, { it.defaults.askEffort }, oneOf("low", "medium", "high")),
        Field("askMaxBudgetUsd", Applies.RESTART, { it.defaults.askMaxBudgetUsd.toString() }, decimalIn(0.01, 5.0)),
    )

    /** `cfg.enabled.<id>`: pauses a source the file enables. One the file disables stays off -- see BLOCKERS.md. */
    fun enabledKey(providerId: String) = "enabled.$providerId"

    /** `cfg.runWindows.<id>`: a scheduled crawl's daily windows. Only for providers the file gives windows. */
    fun windowsKey(providerId: String) = "runWindows.$providerId"

    /**
     * [base] with [overrides] applied. [overrides] are keyed without [PREFIX]. A value that no longer
     * parses -- written by a newer build, or by hand -- is ignored rather than fatal, so a bad row can
     * never stop the app from booting.
     */
    fun apply(base: AppConfig, overrides: Map<String, String>): AppConfig {
        fun valid(key: String, parse: (String) -> Result<String>): String? = overrides[key]?.let { parse(it).getOrNull() }
        fun times(key: String): List<String>? = valid(key, ::parseTimes)?.let { if (it == "off") emptyList() else it.split(',') }
        val byKey = fields.associateBy { it.key }
        fun v(key: String) = valid(key, byKey.getValue(key).parse)

        val d = base.defaults
        val defaults = d.copy(
            pollIntervalMinutes = v("pollIntervalMinutes")?.toLong() ?: d.pollIntervalMinutes,
            coShippingEveryNPolls = v("coShippingEveryNPolls")?.toInt() ?: d.coShippingEveryNPolls,
            coShippingPollBatch = v("coShippingPollBatch")?.toInt() ?: d.coShippingPollBatch,
            watchDigestTimes = times("watchDigestTimes") ?: d.watchDigestTimes,
            pinnedAlertTimes = times("pinnedAlertTimes") ?: d.pinnedAlertTimes,
            pruneHistoryAfterDays = v("pruneHistoryAfterDays")?.toInt() ?: d.pruneHistoryAfterDays,
            pruneHistoryEveryDays = v("pruneHistoryEveryDays")?.toInt() ?: d.pruneHistoryEveryDays,
            backupTimes = times("backupTimes") ?: d.backupTimes,
            backupKeep = v("backupKeep")?.toInt() ?: d.backupKeep,
            healthFailAfterRuns = v("healthFailAfterRuns")?.toInt() ?: d.healthFailAfterRuns,
            askModel = v("askModel") ?: d.askModel,
            askEffort = v("askEffort") ?: d.askEffort,
            askMaxBudgetUsd = v("askMaxBudgetUsd")?.toDouble() ?: d.askMaxBudgetUsd,
        )
        val providers = base.providers.map { p ->
            var out = p
            // Pausing only: an override cannot switch on a source the file turned off.
            if (p.enabled && overrides[enabledKey(p.id)] == "false") out = out.copy(enabled = false)
            if (p.listOptions.containsKey("runWindows")) {
                valid(windowsKey(p.id), ::parseWindows)?.let { w ->
                    out = out.copy(listOptions = out.listOptions + ("runWindows" to w.split(',')))
                }
            }
            out
        }
        return base.copy(defaults = defaults, providers = providers)
    }
}

/**
 * The configuration in force: `sources.yaml` with the Config tab's overrides. Set at boot and again on
 * every save; the jobs read it on each tick instead of holding the boot-time copy, which is what lets a
 * schedule change apply without a restart.
 */
object LiveConfig {
    @Volatile
    private var value: AppConfig? = null

    /** The file as loaded, before any override: what "reset" goes back to and what the page shows as default. */
    @Volatile
    var file: AppConfig? = null
        private set

    fun set(file: AppConfig, overrides: Map<String, String>) {
        this.file = file
        value = ConfigOverrides.apply(file, overrides)
    }

    /** The live config, or [fallback] where nothing set it (a test, a one-off CLI run). */
    fun currentOr(fallback: AppConfig): AppConfig = value ?: fallback
}
