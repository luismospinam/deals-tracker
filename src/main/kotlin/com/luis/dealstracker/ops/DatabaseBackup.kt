package com.luis.dealstracker.ops

import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The nightly database dump: the one thing in this app that cannot be rebuilt is the price history,
 * and before this the only copy of it was the Docker volume.
 *
 * `pg_dump` runs inside the Postgres container (`docker exec`), not on the Mac: libpq's client tools
 * are keg-only here and need not match the server's version, while the container's always does. The
 * container also already holds the credentials, so no password is passed on a command line.
 *
 * Custom format (`-Fc`): compressed (954 MB of tables dump to ~59 MB, measured 2026-11-03, in ~5s) and
 * restorable table by table with `pg_restore`. Written to a `.partial` file, checked with
 * `pg_restore --list`, and only then renamed, so a dump cut short by a sleeping Mac or a full disk can
 * never pass for a good one -- nor push a good one out of the [keep] newest.
 */
class DatabaseBackup(
    /** Where the dumps go. Created on first use. */
    val dir: File,
    /** The Postgres container, as docker-compose.yml names it. */
    private val container: String,
    /** How many dumps to keep; older ones are deleted after a good new one lands. */
    private val keep: Int,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Runs one dump. Never throws: a failed backup is reported, and the next tick tries again. */
    fun run(now: Instant = Instant.now()): BackupResult {
        val docker = dockerBinary() ?: return BackupResult.Failed("docker not found (looked in ${DOCKER_PATHS.joinToString()} and PATH)")
        if (!dir.isDirectory && !dir.mkdirs()) return BackupResult.Failed("cannot create ${dir.absolutePath}")
        val name = backupFileName(now, zone)
        val target = File(dir, name)
        val partial = File(dir, "$name.partial")
        return runCatching {
            val dump = exec(
                listOf(docker, "exec", container, "sh", "-c", "pg_dump -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Fc"),
                stdout = partial, timeoutMinutes = 20,
            )
            if (dump.exit != 0) return@runCatching fail(partial, "pg_dump exited ${dump.exit}: ${dump.err.take(300)}")
            if (partial.length() == 0L) return@runCatching fail(partial, "pg_dump wrote nothing")
            // Read back by the same pg_restore that would restore it: a truncated archive fails here.
            val list = exec(listOf(docker, "exec", "-i", container, "pg_restore", "--list"), stdin = partial, timeoutMinutes = 5)
            if (list.exit != 0 || !list.out.contains("price_observations")) {
                return@runCatching fail(partial, "the dump did not read back (pg_restore --list exited ${list.exit}): ${list.err.take(300)}")
            }
            if (!partial.renameTo(target)) return@runCatching fail(partial, "cannot rename ${partial.name}")
            val deleted = filesToDelete(listBackups(dir), keep).onEach { File(dir, it.name).delete() }
            BackupResult.Done(BackupFile(target.name, target.length(), now), deleted.map { it.name })
        }.getOrElse { fail(partial, it.message ?: it::class.simpleName ?: "unknown error") }
    }

    private fun fail(partial: File, why: String): BackupResult.Failed {
        partial.delete()
        return BackupResult.Failed(why)
    }

    private data class Exec(val exit: Int, val out: String, val err: String)

    private fun exec(command: List<String>, stdout: File? = null, stdin: File? = null, timeoutMinutes: Long): Exec {
        val errFile = File.createTempFile("deals-backup", ".err")
        val outFile = stdout ?: File.createTempFile("deals-backup", ".out")
        try {
            val pb = ProcessBuilder(command).redirectOutput(outFile).redirectError(errFile)
            stdin?.let { pb.redirectInput(it) }
            val p = pb.start()
            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                return Exec(-1, "", "timed out after $timeoutMinutes min")
            }
            val out = if (stdout == null) outFile.readText() else ""
            return Exec(p.exitValue(), out, errFile.readText().trim())
        } finally {
            errFile.delete()
            if (stdout == null) outFile.delete()
        }
    }

    companion object {
        /** Docker Desktop's CLI is not on the PATH of a launchd-started process; look where it installs. */
        private val DOCKER_PATHS = listOf("/usr/local/bin/docker", "/opt/homebrew/bin/docker")

        fun dockerBinary(): String? = DOCKER_PATHS.firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(':').map { File(it, "docker") }.firstOrNull { it.canExecute() }?.path
    }
}

sealed interface BackupResult {
    data class Done(val file: BackupFile, val deleted: List<String>) : BackupResult
    data class Failed(val reason: String) : BackupResult
}

data class BackupFile(val name: String, val bytes: Long, val takenAt: Instant)

private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
private val NAME = Regex("""^deals-(\d{4}-\d{2}-\d{2}-\d{4})\.dump$""")

/** "deals-2026-11-03-0300.dump", in local time: sorts by name, and reads as when it was taken. */
internal fun backupFileName(now: Instant, zone: ZoneId): String = "deals-${NAME_TIME.format(now.atZone(zone))}.dump"

/** The finished dumps in [dir], newest first. A `.partial` or any other file is never listed, so never deleted. */
fun listBackups(dir: File): List<BackupFile> =
    dir.listFiles().orEmpty()
        .filter { it.isFile && NAME.matches(it.name) }
        .map { BackupFile(it.name, it.length(), Instant.ofEpochMilli(it.lastModified())) }
        .sortedByDescending { it.name }

/** What to delete so only the [keep] newest remain. Never the newest one, whatever [keep] says. */
internal fun filesToDelete(files: List<BackupFile>, keep: Int): List<BackupFile> =
    files.sortedByDescending { it.name }.drop(keep.coerceAtLeast(1))

/**
 * Whether a backup is due: the latest of [times] ("03:00") that has passed, today or yesterday, came
 * after [lastAt]. A slot slept through runs once at the next tick, not once per missed slot. No times
 * means off.
 */
internal fun backupDue(times: List<LocalTime>, lastAt: Instant?, now: Instant, zone: ZoneId): Boolean {
    if (times.isEmpty()) return false
    val today = now.atZone(zone).toLocalDate()
    val latest = (0L..1L).flatMap { back -> times.map { today.minusDays(back).atTime(it).atZone(zone).toInstant() } }
        .filter { !it.isAfter(now) }.maxOrNull() ?: return lastAt == null
    return lastAt == null || lastAt.isBefore(latest)
}

/** [configured] as a directory: absolute as written, or relative to the project. */
fun backupDir(projectDir: File, configured: String): File =
    File(configured).let { if (it.isAbsolute) it else File(projectDir, configured) }.toPath().normalize().toFile()

/** "59.1 MB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(java.util.Locale.ROOT, bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1e3)
    else -> "$bytes B"
}
