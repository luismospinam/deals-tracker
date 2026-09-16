package com.luis.dealstracker.verify

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cover for the failure that silently stopped verification for ~46 hours.
 *
 * The persistent Chrome profile grows without bound. At 1.5GB across 50,670 files -- 1.3GB of
 * it HTTP cache -- every `page.navigate` timed out at 30s waiting for `load` and the run died
 * with "pipe closed", while plain curl fetched the same URL in 3.3s. The poller kept firing and
 * kept failing, so the log showed activity while nothing was verified.
 *
 * The safety property that matters most is the second test: the whole worth of this profile is
 * a trusted signed-in Amazon session with a Colombian address, and rebuilding that needs a
 * human. Deleting a cache costs seconds; deleting cookies costs an afternoon.
 */
class ProfileCacheTest {

    private val dir: File = Files.createTempDirectory("dt-profile-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun write(relative: String, sizeBytes: Int) {
        val f = File(dir, relative)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(sizeBytes))
    }

    @Test
    fun `clears the cache once it exceeds the limit`() {
        write("Default/Cache/data_0", 2 * 1024 * 1024)
        write("Default/Code Cache/js/index", 2 * 1024 * 1024)

        trimProfileCache(dir.path, maxMb = 1)

        assertFalse(File(dir, "Default/Cache").exists(), "oversized cache should be gone")
        assertFalse(File(dir, "Default/Code Cache").exists(), "code cache should be gone too")
    }

    @Test
    fun `never touches the signed-in session`() {
        write("Default/Cache/data_0", 4 * 1024 * 1024)
        write("Default/Cookies", 45_056)
        write("Default/Login Data", 40_960)
        write("Default/Preferences", 12_269)
        write("Local State", 4_065)

        trimProfileCache(dir.path, maxMb = 1)

        // The cache is what we came for.
        assertFalse(File(dir, "Default/Cache").exists())
        // Everything that constitutes the session must survive, byte for byte.
        assertEquals(45_056, File(dir, "Default/Cookies").length(), "cookies are the session")
        assertEquals(40_960, File(dir, "Default/Login Data").length())
        assertEquals(12_269, File(dir, "Default/Preferences").length())
        assertEquals(4_065, File(dir, "Local State").length())
    }

    @Test
    fun `leaves a cache that is under the limit alone`() {
        write("Default/Cache/data_0", 512 * 1024)

        trimProfileCache(dir.path, maxMb = 400)

        assertTrue(File(dir, "Default/Cache/data_0").exists(), "a small cache is doing its job")
    }

    @Test
    fun `zero disables the trim`() {
        write("Default/Cache/data_0", 8 * 1024 * 1024)

        trimProfileCache(dir.path, maxMb = 0)

        assertTrue(File(dir, "Default/Cache/data_0").exists(), "0 must mean never clear")
    }

    /** A first run has no profile at all; that must not be an error. */
    @Test
    fun `a missing profile is not a failure`() {
        trimProfileCache(File(dir, "does-not-exist").path, maxMb = 400)
    }
}
