package com.luis.dealstracker.ops

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseBackupTest {
    private val bogota = ZoneId.of("America/Bogota")
    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(bogota).toInstant()
    private val three = listOf(LocalTime.of(3, 0))

    @Test
    fun `files are named by local time so they sort by name`() {
        assertEquals("deals-2026-11-03-0300.dump", backupFileName(at("2026-11-03T03:00:12"), bogota))
    }

    @Test
    fun `due once the time has passed since the last good dump, and a slept-through night runs on waking`() {
        assertTrue(backupDue(three, null, at("2026-11-03T01:00"), bogota), "never backed up: due at once")
        assertFalse(backupDue(three, at("2026-11-03T03:01"), at("2026-11-03T15:00"), bogota), "done today")
        assertFalse(backupDue(three, at("2026-11-02T03:01"), at("2026-11-03T02:59"), bogota), "today's time not reached")
        assertTrue(backupDue(three, at("2026-11-02T03:01"), at("2026-11-03T08:40"), bogota), "slept through 03:00: runs on waking")
        assertTrue(backupDue(three, at("2026-10-30T03:01"), at("2026-11-03T08:40"), bogota), "days missed: one dump, not one per day")
        assertFalse(backupDue(emptyList(), null, at("2026-11-03T08:40"), bogota), "no times is off")
    }

    @Test
    fun `retention keeps the newest and never deletes the last one`() {
        val files = (1..5).map { BackupFile("deals-2026-11-0$it-0300.dump", 1, Instant.EPOCH) }.shuffled()
        assertEquals(listOf("deals-2026-11-02-0300.dump", "deals-2026-11-01-0300.dump"), filesToDelete(files, 3).map { it.name })
        assertEquals(4, filesToDelete(files, 0).size, "keep 0 still keeps the newest")
        assertTrue(filesToDelete(files.take(2), 14).isEmpty())
    }

    @Test
    fun `only finished dumps are listed, so a partial or an older manual dump is never deleted`() {
        val dir = Files.createTempDirectory("backups").toFile()
        try {
            listOf("deals-2026-11-02-0300.dump", "deals-2026-11-03-0300.dump", "deals-2026-11-04-0300.dump.partial", "price_history-20261010-1939.dump", "notes.txt")
                .forEach { File(dir, it).writeText("x") }
            assertEquals(listOf("deals-2026-11-03-0300.dump", "deals-2026-11-02-0300.dump"), listBackups(dir).map { it.name })
            assertTrue(listBackups(File(dir, "missing")).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the directory is relative to the project unless absolute`() {
        val project = File("/Users/x/deals-tracker")
        assertEquals(File("/Users/x/deals-tracker/backups/nightly"), backupDir(project, "backups/nightly"))
        assertEquals(File("/Volumes/Backup/deals"), backupDir(project, "/Volumes/Backup/deals"))
        assertEquals(File("/Users/x/elsewhere"), backupDir(project, "../elsewhere"))
    }

    @Test
    fun `sizes read like a person would say them`() {
        assertEquals("58.9 MB", formatBytes(58_882_276))
        assertEquals("1.20 GB", formatBytes(1_200_000_000))
        assertEquals("512 B", formatBytes(512))
    }
}
