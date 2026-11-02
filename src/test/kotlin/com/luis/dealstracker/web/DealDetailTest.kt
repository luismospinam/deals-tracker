package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.model.PriceObservation
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deal page, where the decision is made, had no actions, no Colombia status, only the Slickdeals
 * link, a chart without axes, and nothing about the same product at other retailers.
 */
class DealDetailTest {

    private fun deal(
        id: Long = 7, source: String = "slickdeals", url: String = "https://slickdeals.net/f/1", asin: String? = "B0GFMGWS8N",
        price: String = "17.55", currency: String = "USD", matchKey: String? = null, modelKey: String? = null,
        coShipping: CoShipping = CoShipping.UNKNOWN, state: DealState = DealState.NEW,
    ) = Deal(
        id = id, source = source, sourceDealId = "s$id", title = "Bedsure Blanket", url = url, merchant = "amazon", asin = asin,
        category = null, imageUrl = null, brand = null, gtin = null, modelCode = null,
        matchKey = matchKey, modelKey = modelKey, currency = currency,
        firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
        state = state, coShipping = coShipping, promoted = false, promotedAt = null,
        communityScore = null, postedAt = null, categoryGroup = null,
        coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null, coShippingCost = null,
        price = BigDecimal(price), listPrice = null, minPriceEver = null, maxPriceEver = null, observationCount = 1,
    )

    private fun obs(at: String, price: String) =
        PriceObservation(dealId = 7, observedAt = Instant.parse(at), price = BigDecimal(price), listPrice = null, currency = "USD")

    private fun render(d: Deal, history: List<PriceObservation> = emptyList(), elsewhere: List<Deal> = emptyList(), feedRow: Boolean = true) =
        createHTML().main { dealDetail(d, history, elsewhere = elsewhere, feedRow = feedRow) }

    @Test
    fun `a Slickdeals row with an ASIN links to Amazon as well as the thread`() {
        assertEquals("https://www.amazon.com/dp/B0GFMGWS8N", amazonUrl(deal()))
        assertNull(amazonUrl(deal(url = "https://www.amazon.com/dp/B0GFMGWS8N")), "already the Amazon page")
        assertNull(amazonUrl(deal(asin = null)))
        val html = render(deal())
        assertTrue(html.contains("open on Amazon ↗"))
        assertTrue(html.contains("open at slickdeals.net ↗"), "named after the link, not the merchant")
    }

    /** The feed's own endpoints, posted from here, reload the page instead of swapping a feed row in. */
    @Test
    fun `a feed row gets favorite, hide and the shipping buttons, wired to reload`() {
        val html = render(deal())
        assertTrue(html.contains("hx-post=\"/deal/7/state?value=FAVORITE\""))
        assertTrue(html.contains("hx-post=\"/deal/7/state?value=DISMISSED\""))
        assertTrue(html.contains("hx-post=\"/deal/7/co-shipping?value=YES\""))
        assertTrue(html.contains("hx-post=\"/deal/7/verify\""))
        assertTrue(html.contains("hx-swap=\"none\""))
        assertTrue(html.contains("location.reload()"))
        assertFalse(html.contains("hx-target=\"#deal-7\""), "no feed row to target here")
    }

    @Test
    fun `a hidden deal offers unhide, and a non-feed row gets no feed buttons`() {
        assertTrue(render(deal(state = DealState.DISMISSED)).contains("value=SEEN"))
        assertFalse(render(deal(), feedRow = false).contains("value=FAVORITE"))
    }

    /** A Colombian retailer's default YES says nothing, so the Colombia line is left off. */
    @Test
    fun `the Colombia line shows only where shipping is in question`() {
        assertTrue(render(deal()).contains("detail-co"))
        assertFalse(render(deal(source = "exito", asin = null, coShipping = CoShipping.YES)).contains("detail-co"))
        assertFalse(
            render(deal(source = "exito", asin = null, coShipping = CoShipping.YES).copy(coShippingSource = "MANUAL")).contains("detail-co"),
            "a Colombian retailer's yes set by hand says nothing either",
        )
    }

    @Test
    fun `the same product elsewhere is compared in the same currency only`() {
        val here = deal(source = "exito", asin = null, price = "2702453", currency = "COP", matchKey = "gtin:07909569417232")
        val olimpica = deal(id = 8, source = "olimpica", asin = null, price = "4093911", currency = "COP", matchKey = "gtin:07909569417232")
        val cheaper = deal(id = 9, source = "easy", asin = null, price = "2500000", currency = "COP", matchKey = "gtin:07909569417232")
        val dollars = deal(id = 10, source = "amazon-watches", price = "699.99", currency = "USD", matchKey = "gtin:07909569417232")
        assertEquals(BigDecimal("51.5"), priceDifference(here, olimpica))
        assertEquals(BigDecimal("-7.5"), priceDifference(here, cheaper))
        assertNull(priceDifference(here, dollars))

        val html = render(here, elsewhere = listOf(cheaper, olimpica, dollars))
        assertTrue(html.contains("Same product elsewhere"))
        assertTrue(html.contains("7.5% cheaper"))
        assertTrue(html.contains("51.5% dearer"))
        assertTrue(html.contains("other currency"))
    }

    @Test
    fun `no identity key means no elsewhere section, and a key with no rivals says so`() {
        assertFalse(render(deal()).contains("Same product elsewhere"))
        assertTrue(render(deal(matchKey = "gtin:1")).contains("No other tracked retailer lists it right now"))
    }

    /** Readings are not evenly spaced, so a fall over one hour must not look as wide as a month. */
    @Test
    fun `chart points sit at their time, not their index`() {
        val h = listOf(obs("2026-10-01T00:00:00Z", "35"), obs("2026-10-31T00:00:00Z", "35"), obs("2026-10-31T01:00:00Z", "17.55"))
        val xs = sparkCoords(h, 640.0, 120.0, 10.0).map { it.first }
        assertEquals(0.0, xs[0])
        assertTrue(xs[1] > 630.0, "30 days of a 30-day-and-an-hour span sit at the right edge, not the middle")
        assertEquals(640.0, xs[2])
    }

    @Test
    fun `the chart labels its high, low and dates`() {
        val h = listOf(obs("2026-10-01T12:00:00Z", "35"), obs("2026-10-31T12:00:00Z", "17.55"))
        val html = render(deal(), history = h)
        val y = html.substringAfter("spark-y").substringBefore("spark-plot")
        assertTrue(y.contains("US$35.00") && y.contains("US$17.55"), y)
        val x = html.substringAfter("spark-x").substringBefore("</div>")
        assertTrue(x.contains("1 Oct") && x.contains("31 Oct"), x)
    }
}
