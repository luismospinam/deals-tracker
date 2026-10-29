package com.luis.dealstracker.verify

import java.io.File

internal const val DRIVER_TMPDIR_PROPERTY = "playwright.driver.tmpdir"

/**
 * Pins where Playwright for Java unpacks its Node driver, away from the system temp folder.
 *
 * The driver is unpacked once per JVM and every later browser launch reuses that copy. macOS's
 * periodic temp cleanup deletes files untouched for ~3 days, so on a long-running app the unpacked
 * `package/cli.js` vanished (2026-10-24) while the process kept pointing at it: every verify,
 * cleanup and browser fetch then failed with "Failed to read message from driver, pipe closed"
 * until a restart. A folder under the project is not cleaned.
 *
 * An explicit `-Dplaywright.driver.tmpdir=...` wins. Returns the directory in effect.
 */
internal fun pinPlaywrightDriverDir(projectDir: File): File {
    System.getProperty(DRIVER_TMPDIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val dir = File(projectDir, "build/playwright-driver").also { it.mkdirs() }
    System.setProperty(DRIVER_TMPDIR_PROPERTY, dir.absolutePath)
    return dir
}
