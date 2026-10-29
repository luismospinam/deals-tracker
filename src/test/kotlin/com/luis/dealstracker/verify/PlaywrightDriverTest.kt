package com.luis.dealstracker.verify

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaywrightDriverTest {
    private var saved: String? = null

    @BeforeTest fun save() { saved = System.getProperty(DRIVER_TMPDIR_PROPERTY); System.clearProperty(DRIVER_TMPDIR_PROPERTY) }

    @AfterTest fun restore() {
        if (saved == null) System.clearProperty(DRIVER_TMPDIR_PROPERTY) else System.setProperty(DRIVER_TMPDIR_PROPERTY, saved!!)
    }

    @Test fun `driver is unpacked under the project, not the system temp folder`() {
        val project = Files.createTempDirectory("dt-project").toFile()
        val dir = pinPlaywrightDriverDir(project)
        assertEquals(File(project, "build/playwright-driver").absolutePath, dir.absolutePath)
        assertTrue(dir.isDirectory, "the directory must exist, or Playwright cannot create its temp dir in it")
        assertEquals(dir.absolutePath, System.getProperty(DRIVER_TMPDIR_PROPERTY))
    }

    @Test fun `an explicit property is respected`() {
        System.setProperty(DRIVER_TMPDIR_PROPERTY, "/somewhere/else")
        val dir = pinPlaywrightDriverDir(Files.createTempDirectory("dt-project").toFile())
        assertEquals("/somewhere/else", dir.path)
        assertEquals("/somewhere/else", System.getProperty(DRIVER_TMPDIR_PROPERTY))
    }
}
