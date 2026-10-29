package com.luis.dealstracker.verify

import com.luis.dealstracker.model.CoShipping
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A row the search stops listing is retired unless its own page vouches for it. "Vouches" is deliberately
 * not "the price matches": prices move, and tile and page prices disagree for about a quarter of rows.
 */
class PageStillSellsTest {
    private val price = BigDecimal("69.68")

    @Test
    fun `a buy-box price and no refusal is enough`() {
        assertTrue(pageStillSells(price, CoShipping.YES))
        assertTrue(pageStillSells(price, CoShipping.UNKNOWN))
    }

    @Test
    fun `no buy-box price means the item is not for sale`() {
        assertFalse(pageStillSells(null, CoShipping.YES))
    }

    @Test
    fun `a refusal to ship to Colombia is not valid even with a price`() {
        assertFalse(pageStillSells(price, CoShipping.NO))
    }

    @Test
    fun `the retirement age comes from the amazon provider's option, defaulting to 48`() {
        assertEquals(72, retireAfterHours(mapOf("staleAfterHours" to "72")))
        assertEquals(48, retireAfterHours(emptyMap()))
        assertEquals(48, retireAfterHours(mapOf("staleAfterHours" to "soon")))
        assertEquals(48, retireAfterHours(mapOf("staleAfterHours" to "0")))
    }
}
