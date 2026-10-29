package com.luis.dealstracker.ingest

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page read is the price that matches the item; the search tile's often is not (adidas tee B08MSKXYP6:
 * page $30.44, tile $31.96). Before V29 the next hourly tile put its own price back, 222 times out of 316
 * corrections in 14 days.
 */
class PagePriceTest {
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun `a tile that disagrees with the page is dropped`() {
        assertTrue(tileContradictsPage(d("31.96"), "USD", d("30.44"), "USD"))
        assertTrue(tileContradictsPage(d("57.37"), "USD", d("69.68"), "USD"))
    }

    @Test
    fun `a tile within tolerance of the page is kept`() {
        assertFalse(tileContradictsPage(d("30.60"), "USD", d("30.44"), "USD"))
        assertFalse(tileContradictsPage(d("30.44"), "USD", d("30.44"), "USD"))
    }

    @Test
    fun `a row never read from its page has nothing to contradict`() {
        assertFalse(tileContradictsPage(d("31.96"), "USD", null, null))
        assertFalse(tileContradictsPage(d("31.96"), "USD", d("30.44"), null))
    }

    @Test
    fun `a different currency is not compared`() {
        assertFalse(tileContradictsPage(d("120000"), "COP", d("30.44"), "USD"))
    }
}
