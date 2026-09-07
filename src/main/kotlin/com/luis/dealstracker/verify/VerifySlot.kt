package com.luis.dealstracker.verify

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Semaphore

/**
 * Admits one Amazon verification at a time — across threads *and* across processes.
 *
 * Chrome takes an exclusive lock on its `--user-data-dir`, so two verifications sharing
 * `.amazon-profile` cannot both run: the second Chrome hands off to the first instead of
 * starting a process Playwright can drive, and the launch fails. There are three callers
 * that can collide:
 *
 *  - the every-Nth-poll job inside the running app
 *  - the per-deal Verify button in the UI (same JVM as the poller — hence the semaphore)
 *  - `./dt verify` from a terminal (a separate JVM — hence the file lock)
 *
 * Relying on Chrome's own failure was not enough. It surfaces as an opaque Playwright
 * error about a closed target, which says nothing about the actual cause; and by the time
 * it fires the caller has already committed to a run. A dedicated lock lets each caller
 * find out cheaply and say something true instead.
 *
 * The lock is advisory between cooperating callers, not security. Anything that launches
 * Chrome against the profile without taking it will still collide.
 */
object VerifySlot {
    /**
     * A file lock is held per-JVM, not per-thread: a second `tryLock` on a region this
     * same JVM already holds raises [OverlappingFileLockException] rather than reporting
     * "busy". So in-process contention has to be settled before the file lock is reached.
     */
    private val inProcess = Semaphore(1)

    /**
     * Runs [block] if no other verification holds the slot, otherwise returns [onBusy].
     *
     * Both paths return normally — being busy is an expected outcome here, not a failure,
     * and the UI needs to render it as a message on the row.
     */
    fun <T> tryWith(lockFile: File, onBusy: () -> T, block: () -> T): T {
        if (!inProcess.tryAcquire()) return onBusy()
        try {
            lockFile.parentFile?.mkdirs()
            // "rw" because a shared lock on a read-only channel is not exclusive, and this
            // has to exclude other writers rather than merely coexist with them.
            RandomAccessFile(lockFile, "rw").use { raf ->
                val lock: FileLock? =
                    try {
                        raf.channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        // Another thread here already holds it. The semaphore should have
                        // caught that, so this is a belt-and-braces path, not the norm.
                        null
                    }
                if (lock == null) return onBusy()
                return try {
                    block()
                } finally {
                    // Released explicitly rather than left to channel close, so the window
                    // where a stale lock blocks the next caller stays as short as possible.
                    lock.release()
                }
            }
        } finally {
            inProcess.release()
        }
    }

    /** Lock file for a given Chrome profile directory — co-located with what it protects. */
    fun lockFileFor(profileDir: String): File = File(File(profileDir), ".dt-verify.lock")
}
