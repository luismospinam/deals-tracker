package com.luis.dealstracker.verify

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifySlotTest {

    private fun lock(name: String) = VerifySlot.lockFileFor(Files.createTempDirectory(name).toFile().path)

    /**
     * 2026-11-11: a long Amazon shipping run held the one in-process slot, and both of the day's Exito
     * login renewals were turned away as "busy" although they use a different Chrome profile.
     */
    @Test
    fun `a run on one profile does not block a run on another`() {
        val amazon = lock("amazon-profile")
        val exito = lock("exito-profile")
        val result = VerifySlot.tryWith(amazon, onBusy = { "amazon busy" }) {
            VerifySlot.tryWith(exito, onBusy = { "exito busy" }) { "both ran" }
        }
        assertEquals("both ran", result)
    }

    @Test
    fun `a second run on the same profile is still turned away, and admitted once the first ends`() {
        val profile = lock("one-profile")
        val nested = VerifySlot.tryWith(profile, onBusy = { "busy" }) {
            VerifySlot.tryWith(profile, onBusy = { "busy" }) { "ran twice" }
        }
        assertEquals("busy", nested)
        assertEquals("ran", VerifySlot.tryWith(profile, onBusy = { "busy" }) { "ran" })
    }
}
