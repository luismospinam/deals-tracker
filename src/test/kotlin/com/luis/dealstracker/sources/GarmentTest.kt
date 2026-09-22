package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.amazon.Garment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Size matching — the part of the Amazon adapter where a bug is invisible.
 *
 * A wrong match resolves to a real ASIN with a real price, so nothing looks broken; the number
 * recorded is simply the price of a garment that does not fit. Every label below is a genuine
 * one, taken from the two product pages captured on 2026-09-17.
 */
class GarmentTest {

    // --- garment inference, which picks WHICH configured size applies ---

    @Test
    fun `garment type is inferred from the search term`() {
        assertEquals(Garment.SHOES, Garment.of("nike men running shoes"))
        assertEquals(Garment.SHOES, Garment.of("adidas men sneakers"))
        assertEquals(Garment.TOPS, Garment.of("nike men hoodie"))
        assertEquals(Garment.TOPS, Garment.of("adidas men t-shirt"))
        assertEquals(Garment.BOTTOMS, Garment.of("nike men pants"))
        assertEquals(Garment.BOTTOMS, Garment.of("adidas men shorts"))
    }

    /**
     * "sweatshirt" is a top and "sweatpants" are bottoms, and the two share a prefix. Getting
     * this backwards would apply the trouser size to a hoodie.
     */
    @Test
    fun `sweatshirt is a top while sweatpants are bottoms`() {
        assertEquals(Garment.TOPS, Garment.of("adidas sweatshirt"))
        assertEquals(Garment.BOTTOMS, Garment.of("adidas sweatpants"))
    }

    @Test
    fun `something with no size dimension is unsized rather than guessed`() {
        assertEquals(Garment.UNSIZED, Garment.of("nike backpack"))
        assertEquals(Garment.UNSIZED, Garment.of("adidas water bottle"))
    }
}
