package com.luis.dealstracker.ops

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.runAtHours
import com.luis.dealstracker.ingest.runWindows
import java.time.Duration
import java.time.Instant

/** One `fetch_runs` row, as the health check reads it. */
data class RunRow(
    val startedAt: Instant,
    val finishedAt: Instant?,
    val items: Int,
    val error: String?,
) {
    /** A finished run that brought nothing back, or failed: a store that blocked us usually looks like this. */
    val bad: Boolean get() = finishedAt != null && (error != null || items == 0)
}

enum class HealthStatus(val unhealthy: Boolean) {
    OK(false),
    /** The last run came back empty or failed, but not enough in a row to call the source broken. */
    DEGRADED(false),
    /** The last [SourceHealth.threshold] runs all came back empty or failed, or one has hung. */
    FAILING(true),
    /** The poller is running but this source has not been tried for well past its schedule. */
    OVERDUE(true),
    PAUSED(false),
    /** Off in sources.yaml. */
    OFF(false),
    /** Enabled, never run: a source added since the last poll. */
    NEW(false),
}

data class SourceHealth(
    val id: String,
    val status: HealthStatus,
    /** How many of the latest finished runs in a row came back empty or failed. */
    val badStreak: Int,
    val threshold: Int,
    val lastOk: Instant?,
    val lastTried: Instant?,
    val lastItems: Int?,
    val lastError: String?,
    /** A run that started this long ago and never finished, when that is why it is failing. */
    val hungSince: Instant? = null,
)

/**
 * How often [provider] is meant to run: the poll interval, or for a scheduled crawl the gap between its
 * daily windows (three windows -> every 8h).
 */
internal fun expectedEvery(provider: ProviderConfig, pollInterval: Duration): Duration {
    runWindows(provider)?.takeIf { it.isNotEmpty() }?.let { return Duration.ofHours(24).dividedBy(it.size.toLong()) }
    runAtHours(provider)?.takeIf { it.isNotEmpty() }?.let { return Duration.ofHours(24).dividedBy(it.size.toLong()) }
    val hours = provider.int("intervalHours", 0)
    return if (hours > 0) Duration.ofHours(hours.toLong()) else pollInterval
}

/** A run older than this that never finished counts as hung, not as one cut short by a restart. */
internal val HUNG_AFTER: Duration = Duration.ofHours(3)

/**
 * The health of one source from its latest runs, newest first.
 *
 * Bad means finished with an error or with zero items. Zero is the signal that matters here: the
 * adapters catch their own failures, so a blocked or changed store shows up as an empty run, not an
 * error -- `fetch_runs` holds one error in its whole history (2026-08-24), and dozens of empty runs.
 * One empty run is routine (Amazon returns nothing for a search now and then), so it takes
 * [failAfter] in a row; a scheduled crawl runs two or three times a day, so two in a row there
 * (a day of missing shelf prices) is already worth hearing about.
 *
 * Unfinished runs are skipped -- almost all are the app restarted mid-crawl -- except the newest
 * one, when it started after [bootedAt] and is older than [HUNG_AFTER]: a crawl stuck that long
 * blocks every source behind it, while one started before this boot was cut short by the restart.
 *
 * OVERDUE needs [pollerAliveAt] recent: when the whole app was off, every source is late and none of
 * them is to blame.
 */
internal fun assessSource(
    provider: ProviderConfig,
    live: ProviderConfig,
    runs: List<RunRow>,
    pollInterval: Duration,
    failAfter: Int,
    pollerAliveAt: Instant?,
    now: Instant,
    bootedAt: Instant = Instant.EPOCH,
): SourceHealth {
    val scheduled = runWindows(provider) != null || runAtHours(provider) != null
    val threshold = if (scheduled) minOf(2, failAfter) else failAfter
    val finished = runs.filter { it.finishedAt != null }
    val streak = finished.takeWhile { it.bad }.size
    val last = finished.firstOrNull()
    val lastOk = finished.firstOrNull { !it.bad }?.finishedAt
    val newest = runs.firstOrNull()
    val hung = newest?.takeIf { it.finishedAt == null && it.startedAt.isAfter(bootedAt) && Duration.between(it.startedAt, now) > HUNG_AFTER }
    fun health(status: HealthStatus) = SourceHealth(
        provider.id, status, streak, threshold, lastOk, newest?.startedAt, last?.items, last?.error, hung?.startedAt,
    )
    if (!provider.enabled) return health(HealthStatus.OFF)
    if (!live.enabled) return health(HealthStatus.PAUSED)
    if (newest == null) return health(HealthStatus.NEW)
    if (hung != null || streak >= threshold) return health(HealthStatus.FAILING)
    val alive = pollerAliveAt != null && Duration.between(pollerAliveAt, now) < Duration.ofHours(2)
    val overdueAfter = expectedEvery(provider, pollInterval).multipliedBy(2).plusHours(1)
    if (alive && Duration.between(newest.startedAt, now) > overdueAfter) return health(HealthStatus.OVERDUE)
    return health(if (streak > 0) HealthStatus.DEGRADED else HealthStatus.OK)
}

/** Which sources just turned unhealthy and which just recovered, given the ones already alerted. */
internal data class HealthChanges(val broke: List<SourceHealth>, val recovered: List<String>, val alerted: Set<String>)

internal fun healthChanges(health: List<SourceHealth>, alreadyAlerted: Set<String>): HealthChanges {
    val unhealthy = health.filter { it.status.unhealthy }
    val broke = unhealthy.filter { it.id !in alreadyAlerted }
    // A source paused or switched off while alerted leaves the set quietly: nobody needs "recovered" for it.
    val recovered = health.filter { it.id in alreadyAlerted && !it.status.unhealthy && it.status !in QUIET }.map { it.id }
    return HealthChanges(broke, recovered, unhealthy.map { it.id }.toSet())
}

private val QUIET = setOf(HealthStatus.PAUSED, HealthStatus.OFF)

/** One line per broken source, for the banner and the Discord post. English and Spanish via [tr]. */
internal fun brokeLine(h: SourceHealth, tr: (String, String) -> String, ago: (Instant) -> String): String {
    val lastOk = h.lastOk?.let { tr("last good run ${ago(it)}", "última corrida buena ${ago(it)}") } ?: tr("no good run on record", "sin corridas buenas registradas")
    val why = when {
        h.status == HealthStatus.OVERDUE -> tr("not run on schedule", "no ha corrido según su horario")
        h.hungSince != null -> tr("a run started ${ago(h.hungSince)} has not finished", "una corrida iniciada ${ago(h.hungSince)} no ha terminado")
        h.lastError != null -> tr("${h.badStreak} failed runs in a row: ${h.lastError.take(160)}", "${h.badStreak} corridas fallidas seguidas: ${h.lastError.take(160)}")
        else -> tr("${h.badStreak} runs in a row returned nothing", "${h.badStreak} corridas seguidas sin resultados")
    }
    return "${h.id}: $why ($lastOk)"
}
