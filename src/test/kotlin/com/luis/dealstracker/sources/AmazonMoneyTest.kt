package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.amazon.AmazonMoney
import com.luis.dealstracker.sources.prestashop.PrestashopSource
import com.luis.dealstracker.sources.shopify.ShopifySource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Amazon's money format, the fourth in this codebase.
 *
 * Every string here was observed on amazon.com on 2026-09-17 with the project's user agent.
 */
class AmazonMoneyTest {

    @Test
    fun `parses a COP price with a space and decimals`() {
        val (amount, currency) = AmazonMoney.parse("COP 234,124.82")!!
        assertEquals(BigDecimal("234124.82"), amount)
        assertEquals("COP", currency)
    }

    @Test
    fun `parses a COP price with no space and no decimals`() {
        // Product pages render it this way where search pages use a space; both are real.
        val (amount, currency) = AmazonMoney.parse("COP117,125")!!
        assertEquals(BigDecimal("117125.00"), amount)
        assertEquals("COP", currency)
    }

    @Test
    fun `parses a dollar price`() {
        val (amount, currency) = AmazonMoney.parse("$1,299.99")!!
        assertEquals(BigDecimal("1299.99"), amount)
        assertEquals("USD", currency)
    }

    @Test
    fun `reads US$ as dollars, not as a bare dollar sign`() {
        val (amount, currency) = AmazonMoney.parse("US$18.99")!!
        assertEquals(BigDecimal("18.99"), amount)
        assertEquals("USD", currency)
    }

    /**
     * `COP` must be tried before `$`, or a store that prints both reports the wrong unit —
     * and the unit is what decides whether a cross-site comparison is meaningful.
     */
    @Test
    fun `a COP marker wins over a dollar sign in the same string`() {
        assertEquals("COP", AmazonMoney.parse("COP $1.234,00")!!.second)
    }

    /**
     * The factor-of-100 trap this project already pins in both directions for Shopify and
     * PrestaShop. Amazon uses BOTH separators, so neither existing parser can be reused —
     * and this test fails loudly if someone ever tries to unify them.
     */
    @Test
    fun `neither existing money parser handles Amazon's format`() {
        val amazon = AmazonMoney.parse("COP 234,124.82")!!.first
        assertEquals(BigDecimal("234124.82"), amazon)

        // PrestaShop strips dots as thousands separators, which would lose the decimal point
        // entirely and inflate the amount 100-fold.
        val prestashop = PrestashopSource.parseCopAmount("COP 234,124.82")
        assertNotEquals(amazon, prestashop, "PrestaShop's parser must not be reused for Amazon")

        // Shopify's parser expects a bare decimal string and rejects grouping and currency.
        assertNull(ShopifySource.amount("COP 234,124.82"), "Shopify's parser cannot read Amazon prices")
    }

    @Test
    fun `a price with no currency marker is rejected rather than assumed`() {
        // On these pages a bare number is far more likely a review count or a size.
        assertNull(AmazonMoney.parse("234,124.82"))
    }

    @Test
    fun `blank and unpriced tiles yield null, never zero`() {
        assertNull(AmazonMoney.parse(null))
        assertNull(AmazonMoney.parse(""))
        assertNull(AmazonMoney.parse("   "))
        assertNull(AmazonMoney.parse("COP 0.00"))
    }
}
