package com.luis.dealstracker.verify

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PauseTest {
    @Test fun `no jitter keeps the fixed pause`() {
        assertEquals(8_000, pauseMillis(8_000, 0))
    }

    @Test fun `every pause stays within base plus or minus jitter`() {
        val random = Random(42)
        repeat(2_000) {
            val p = pauseMillis(8_000, 2_000, random)
            assertTrue(p in 6_000..10_000, "pause $p left the 6-10s band")
        }
    }

    @Test fun `the spread is real and centred on the base`() {
        val random = Random(7)
        val pauses = List(2_000) { pauseMillis(8_000, 2_000, random) }
        assertTrue(pauses.toSet().size > 100, "pauses should vary, not repeat one value")
        assertTrue(pauses.average() in 7_800.0..8_200.0, "mean ${pauses.average()} should sit near the 8s base")
    }

    @Test fun `a pause never drops below a second`() {
        val random = Random(1)
        repeat(500) { assertTrue(pauseMillis(1_500, 5_000, random) >= 1_000) }
    }
}
