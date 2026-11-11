package com.luis.dealstracker.sources

import com.luis.dealstracker.model.Coupons
import com.luis.dealstracker.sources.slickdeals.SlickdealsSource
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coupon codes from the write-up (V42). The fixture is the real 2026-08-23 frontpage feed, whose 24
 * items hold codes marked up by Slickdeals, codes in plain prose, an expired one, and two "clip the
 * coupon" Amazon deals -- every case the parser has to tell apart.
 */
class SlickdealsCouponTest {

    private val source = SlickdealsSource()
    private val feed = source.parse(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/slickdeals-frontpage.xml")).bufferedReader().readText(),
    )

    private fun item(titlePart: String) = feed.single { titlePart in it.deal.title }.deal
    private fun coupons(html: String): Coupons = source.extractCoupons(Jsoup.parse(html))

    @Test
    fun `a code slickdeals marked up is read`() {
        assertEquals(listOf("K8F6E3VU"), item("LISEN Ultra Slim").coupons?.codes)
        assertEquals(listOf("USDEAL072"), item("ECOFLOW Delta 3").coupons?.codes)
    }

    @Test
    fun `a code named only in prose is read too`() {
        // "Use $40 off ... Coupon at checkout: AFFUS130", no marker.
        assertEquals("AFFUS130", item("DJI Avata 360").coupons?.codes?.first())
        assertEquals(listOf("USDEAL020"), item("HIFIMAN Edition XS").coupons?.codes)
    }

    /** "w/ code VSUSDEAL6 ... Expired Code: USDEAL08": the dead one must not be offered. */
    @Test
    fun `an expired code is dropped and the live one kept`() {
        assertEquals(listOf("VSUSDEAL6"), item("Baseus VD1 Pro").coupons?.codes)
    }

    /** Marked twice and written twice in one write-up, and a "Features" heading after it. */
    @Test
    fun `a repeated code is listed once and a heading is not a code`() {
        assertEquals(listOf("CUI5I9Y5"), item("Beelicious").coupons?.codes)
    }

    @Test
    fun `clip the coupon is a flag, not a code`() {
        val tums = item("TUMS").coupons!!
        assertTrue(tums.clip)
        assertEquals(emptyList(), tums.codes)
        assertTrue(item("ROG Harpe Mini").coupons!!.clip)
    }

    /** Read, and names nothing: an empty result, so a stored code from an earlier write-up is cleared. */
    @Test
    fun `a write-up with no coupon is read as empty, not unknown`() {
        assertTrue(feed.all { it.deal.coupons != null }, "every Slickdeals write-up is read")
        // Checked by hand against the write-ups: these seven name no code and no clip coupon.
        val plain = feed.filter { it.deal.coupons!!.isEmpty }.map { it.deal.title.substringBefore(' ', "").ifEmpty { it.deal.title } }
        assertEquals(listOf("101.4-Oz", "403-Pc", "Insignia", "The", "Ryobi", "46mm", "Seiko"), plain)
        // And the other sixteen each name something: 14 codes, 2 clips.
        assertEquals(14, feed.count { it.deal.coupons!!.codes.isNotEmpty() })
    }

    @Test
    fun `prose after the word code is not taken for one`() {
        assertEquals(emptyList(), coupons("<p>No code needed. Price drops in cart.</p>").codes)
        assertEquals(emptyList(), coupons("<p>Promo code: FREE shipping on orders over \$35</p>").codes)
        assertEquals(emptyList(), coupons("<p>Use code at checkout for 10% off</p>").codes)
        assertEquals(listOf("SAVE20"), coupons("<p>Use promo code: SAVE20 at checkout</p>").codes)
    }

    @Test
    fun `a marked code keeps its case and a long list is cut to three`() {
        val marked = "<span data-role='couponCode'><span class='code'><strong>save10now</strong></span></span>"
        assertEquals(listOf("save10now"), coupons(marked).codes)
        val many = coupons("<p>code AAAA1 or code BBBB2 or code CCCC3 or code DDDD4</p>")
        assertEquals(listOf("AAAA1", "BBBB2", "CCCC3"), many.codes)
    }

    @Test
    fun `clipping needs the word coupon nearby`() {
        assertTrue(coupons("<p>\$14 after you clip the 20% off coupon on the product page.</p>").clip)
        assertTrue(coupons("<p>\$33.99 with clipped coupon</p>").clip)
        assertFalse(coupons("<p>Comes with a belt clip. Coupon codes do not apply.</p>").clip)
    }
}
