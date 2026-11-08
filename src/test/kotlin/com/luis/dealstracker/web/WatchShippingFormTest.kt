package com.luis.dealstracker.web

import io.ktor.http.parametersOf
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchShippingFormTest {
    private fun draft(vararg extra: Pair<String, List<String>>) =
        watchDraftFrom(parametersOf("name" to listOf("Sofá"), "terms" to listOf("sofa"), *extra))!!

    @Test
    fun `a ticked box turns the shipping gate on`() {
        assertTrue(draft("requireCoShipping" to listOf("1")).requireCoShipping)
    }

    @Test
    fun `an unticked box sends nothing and turns it off`() {
        assertFalse(draft().requireCoShipping)
    }

    @Test
    fun `the shipping cap parses, and blank is no cap and negative is clamped to zero`() {
        assertEquals(BigDecimal("40"), draft("maxShippingCost" to listOf("40")).maxShippingCost)
        assertNull(draft("maxShippingCost" to listOf("")).maxShippingCost)
        assertEquals(0, BigDecimal.ZERO.compareTo(draft("maxShippingCost" to listOf("-5")).maxShippingCost))
    }

    @Test
    fun `the smallest screen parses, blank is any size, and out of range is clamped`() {
        assertEquals(55, draft("minScreenInches" to listOf("55")).minScreenInches)
        assertNull(draft("minScreenInches" to listOf("")).minScreenInches)
        assertNull(draft().minScreenInches)
        assertEquals(150, draft("minScreenInches" to listOf("400")).minScreenInches)
        assertEquals(10, draft("minScreenInches" to listOf("2")).minScreenInches)
    }

    @Test
    fun `the floor reads as a size and up`() {
        assertEquals("55″ and up", screenFloorLabel(55))
    }
}
