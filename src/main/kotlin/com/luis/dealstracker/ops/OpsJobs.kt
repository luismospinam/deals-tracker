package com.luis.dealstracker.ops

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.i18n.AppLanguage
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.notify.parseSlotTimes
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two jobs that watch the app itself rather than the stores: the nightly dump, and the source
 * health check. Both alert through [alert] (a desktop banner, and Discord when a channel is set), both
 * remember their state in `app_settings` so a restart neither repeats nor forgets an alert, and both
 * read the live config each tick so the Config tab applies at once.
 */
class OpsJobs(
    private val projectDir: File,
    private val repo: DealRepository,
    private val settings: SettingsRepository,
    private val live: () -> AppConfig,
    /** (title, body). Must not throw. */
    private val alert: (String, String) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** When this process started: a run left unfinished before it was cut short by the restart, not hung. */
    private val bootedAt: Instant = Instant.now(),
) {
    private val backingUp = AtomicBoolean(false)

    fun backupDir(): File = backupDir(projectDir, live().defaults.backupDir)

    /** Runs the dump when its time has passed since the last good one. A log line when it ran, else null. */
    fun backupIfDue(now: Instant = Instant.now()): String? {
        val times = parseSlotTimes(live().defaults.backupTimes)
        val last = settings.get(SettingsRepository.BACKUP_LAST_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (!backupDue(times, last, now, zone)) return null
        // A failure is retried, but not every 5-minute tick: an hour apart, so a dead Docker is not
        // hammered nor alerted about 12 times an hour.
        val failedAt = settings.get(SettingsRepository.BACKUP_FAILED_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (failedAt != null && Duration.between(failedAt, now) < Duration.ofHours(1)) return null
        return backupNow(now)
    }

    /** One dump now, whatever the schedule. Null when one is already running. */
    fun backupNow(now: Instant = Instant.now()): String? {
        if (!backingUp.compareAndSet(false, true)) return null
        try {
            val d = live().defaults
            return when (val r = DatabaseBackup(backupDir(), d.backupContainer, d.backupKeep, zone).run(now)) {
                is BackupResult.Done -> {
                    val hadFailed = settings.get(SettingsRepository.BACKUP_LAST_ERROR) != null
                    settings.set(
                        mapOf(
                            SettingsRepository.BACKUP_LAST_AT to now.toString(),
                            SettingsRepository.BACKUP_LAST_FILE to r.file.name,
                        ),
                    )
                    settings.delete(listOf(SettingsRepository.BACKUP_LAST_ERROR, SettingsRepository.BACKUP_FAILED_AT))
                    if (hadFailed) {
                        inAppLanguage { alert(tr("Database backup working again", "El respaldo de la base de datos volvió a funcionar"), "${r.file.name} · ${formatBytes(r.file.bytes)}") }
                    }
                    "[backup] ${r.file.name} ${formatBytes(r.file.bytes)} in ${backupDir()}" +
                        if (r.deleted.isEmpty()) "" else "; removed ${r.deleted.joinToString()}"
                }
                is BackupResult.Failed -> {
                    // Alerted once per failure streak: the first failure says so, the retries stay quiet.
                    val first = settings.get(SettingsRepository.BACKUP_LAST_ERROR) == null
                    settings.set(
                        mapOf(
                            SettingsRepository.BACKUP_LAST_ERROR to r.reason.take(500),
                            SettingsRepository.BACKUP_FAILED_AT to now.toString(),
                        ),
                    )
                    if (first) {
                        inAppLanguage {
                            alert(
                                tr("Database backup failed", "Falló el respaldo de la base de datos"),
                                tr("${r.reason.take(300)}. Retried hourly; see /config.", "${r.reason.take(300)}. Se reintenta cada hora; mira /config."),
                            )
                        }
                    }
                    "[backup] failed: ${r.reason}"
                }
            }
        } finally {
            backingUp.set(false)
        }
    }

    val backupRunning: Boolean get() = backingUp.get()

    /** Every configured source's health now. */
    fun health(now: Instant = Instant.now()): List<SourceHealth> {
        val app = live()
        val file = com.luis.dealstracker.config.LiveConfig.file ?: app
        val runs = repo.runsPerSource()
        val alive = repo.lastFinishedRun()
        val poll = Duration.ofMinutes(app.defaults.pollIntervalMinutes)
        return file.providers.map { p ->
            val livep = app.providers.firstOrNull { it.id == p.id } ?: p
            assessSource(p, livep, runs[p.id].orEmpty(), poll, app.defaults.healthFailAfterRuns, alive, now, bootedAt)
        }
    }

    /** Alerts on sources that just broke or recovered. A log line when anything changed, else null. */
    fun checkHealth(now: Instant = Instant.now()): String? {
        val health = health(now)
        val before = settings.get(SettingsRepository.HEALTH_ALERTED).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val changes = healthChanges(health, before)
        if (changes.broke.isEmpty() && changes.recovered.isEmpty() && changes.alerted == before) return null
        inAppLanguage {
            if (changes.broke.isNotEmpty()) {
                val title = if (changes.broke.size == 1) tr("Source not working: ${changes.broke[0].id}", "Fuente sin funcionar: ${changes.broke[0].id}")
                else tr("${changes.broke.size} sources not working", "${changes.broke.size} fuentes sin funcionar")
                alert(title, changes.broke.joinToString("\n") { brokeLine(it, ::tr) { at -> com.luis.dealstracker.web.relativeAgo(at) } })
            }
            if (changes.recovered.isNotEmpty()) {
                alert(tr("Working again", "Funcionando de nuevo"), changes.recovered.joinToString(", "))
            }
        }
        settings.set(mapOf(SettingsRepository.HEALTH_ALERTED to changes.alerted.sorted().joinToString(",")))
        return "[health] broke: ${changes.broke.joinToString { it.id }.ifEmpty { "-" }}; recovered: ${changes.recovered.joinToString().ifEmpty { "-" }}"
    }

    private fun <T> inAppLanguage(block: () -> T): T = I18n.with(AppLanguage.current) { block() }
}
