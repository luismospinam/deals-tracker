package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The coupon codes a Slickdeals post names (V42), on the feed row and the deal page. */
class CouponViewsTest {

    private fun deal(codes: List<String> = emptyList(), clip: Boolean = false, asin: String? = "B0EXAMPLE1") = Deal(
        id = 9, source = "slickdeals", sourceDealId = "20263755", title = "ALASDO Storage Ottoman", url = "https://slickdeals.net/f/20263755",
        merchant = "amazon", asin = asin, category = null, imageUrl = null, brand = null, gtin = null, modelCode = null,
        matchKey = null, modelKey = null, currency = "USD", firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
        state = DealState.NEW, coShipping = CoShipping.UNKNOWN, promoted = false, promotedAt = null, communityScore = 12,
        postedAt = null, categoryGroup = null, coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
        coShippingCost = null, price = BigDecimal("13.49"), listPrice = null, minPriceEver = null, maxPriceEver = null,
        observationCount = 1, couponCodes = codes, couponClip = clip,
    )

    @Test
    fun `a feed row shows each code as a button that copies it`() {
        val html = rowFragment(deal(codes = listOf("K8F6E3VU", "SAVE20")))
        assertEquals(2, Regex("class=\"coupon-code\"").findAll(html).count())
        assertTrue(html.contains("data-code=\"K8F6E3VU\""))
        assertTrue(html.contains("<span class=\"coupon-v\">SAVE20</span>"))
        assertTrue(html.contains("type=\"button\""), "a button, so it is reachable by keyboard and never submits a form")
        assertTrue(html.contains("data-copied=\"copied ✓\""))
        assertTrue(html.contains("<span class=\"coupon-act\" aria-live=\"polite\">copy</span>"), "the copy action is spelled out")
        assertTrue(html.contains("aria-label=\"Copy coupon code K8F6E3VU\""))
        // In the price cell, under the price the code unlocks.
        assertTrue(html.indexOf("class=\"col-price\"") < html.indexOf("coupon-code"))
        assertTrue(html.indexOf("coupon-code") < html.indexOf("class=\"col-disc\""))
    }

    @Test
    fun `clip coupon is a note, not a button`() {
        val html = rowFragment(deal(clip = true))
        assertTrue(html.contains("class=\"coupon-clip\""))
        assertTrue(html.contains("✂ clip coupon"))
        assertFalse(html.contains("class=\"coupon-code\""))
    }

    @Test
    fun `a deal that names no coupon shows nothing`() {
        val row = rowFragment(deal())
        assertFalse(row.contains("coupon-"))
        val page = createHTML().main { dealDetail(deal(), emptyList(), feedRow = true) }
        assertFalse(page.contains("detail-coupons"))
    }

    @Test
    fun `the deal page lists them too`() {
        val page = createHTML().main { dealDetail(deal(codes = listOf("USDEAL072"), clip = true), emptyList(), feedRow = true) }
        assertTrue(page.contains("class=\"detail-coupons\""))
        assertTrue(page.contains("data-code=\"USDEAL072\""))
        assertTrue(page.contains("coupon-clip"))
        // The page's own links already open Amazon; a second button beside the code would repeat them.
        assertFalse(page.contains("coupon-amz"))
    }

    @Test
    fun `a coupon row links straight to the product on amazon`() {
        val html = rowFragment(deal(codes = listOf("K8F6E3VU")))
        assertTrue(html.contains("class=\"coupon-amz\""))
        assertTrue(html.contains("href=\"https://www.amazon.com/dp/B0EXAMPLE1\""))
        assertTrue(html.contains("target=\"_blank\""))
        // After the code, in the same line.
        assertTrue(html.indexOf("coupon-code") < html.indexOf("coupon-amz"))
        // A clip coupon is clipped on that same page.
        assertTrue(rowFragment(deal(clip = true)).contains("coupon-amz"))
    }

    @Test
    fun `no amazon button without an asin or without a coupon`() {
        assertFalse(rowFragment(deal(codes = listOf("K8F6E3VU"), asin = null)).contains("coupon-amz"))
        assertTrue(rowFragment(deal(codes = listOf("K8F6E3VU"), asin = null)).contains("coupon-code"))
        assertFalse(rowFragment(deal()).contains("coupon-amz"))
    }

    @Test
    fun `the chip speaks spanish`() {
        val html = com.luis.dealstracker.i18n.I18n.with(com.luis.dealstracker.i18n.Lang.ES) { rowFragment(deal(codes = listOf("K8F6E3VU"), clip = true)) }
        assertTrue(html.contains(">copiar</span>"))
        assertTrue(html.contains("data-copied=\"copiado ✓\""))
        assertTrue(html.contains("✂ activar cupón"))
    }

    @Test
    fun `the copy script is on every page and delegated, so swapped-in rows work`() {
        assertTrue(COUPON_COPY_JS.contains("document.addEventListener('click'"))
        assertTrue(COUPON_COPY_JS.contains("closest('.coupon-code')"))
        assertTrue(COUPON_COPY_JS.contains("navigator.clipboard"))
        assertTrue(COUPON_COPY_JS.contains("'.coupon-act'"), "the label it swaps is the copy action")
    }
}
