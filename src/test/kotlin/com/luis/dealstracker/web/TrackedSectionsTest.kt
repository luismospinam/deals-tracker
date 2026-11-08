package com.luis.dealstracker.web

import com.luis.dealstracker.db.TrackedSection
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

/**
 * The Tracked tab's two sections: clothes (searched and added) and electronics (added by URL only).
 * The point of the split is that neither leaks into the other, so most of this checks the seams.
 */
class TrackedSectionsTest {

    private fun deal(id: Long, asin: String, title: String) = Deal(
        id = id, source = "amazon", sourceDealId = asin, title = title,
        url = "https://www.amazon.com/dp/$asin", merchant = "amazon", asin = asin,
        category = null, imageUrl = null, brand = "Sony", gtin = null, modelCode = null,
        matchKey = null, modelKey = null, currency = "USD",
        firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
        state = DealState.NEW, coShipping = CoShipping.YES, promoted = false, promotedAt = null,
        communityScore = null, postedAt = null, categoryGroup = null,
        coShippingSource = "AUTO", coShippingEvidence = null, coShippingImportFree = true,
        coShippingCost = null, price = BigDecimal("100.00"), listPrice = null,
        minPriceEver = null, maxPriceEver = null, observationCount = 1,
    )

    private val shoe = deal(1, "B0SHOE0001", "Men's Pegasus 41 Sneaker — 8")
    private val addedShirt = deal(2, "B0SHIRT001", "Men's Park Short Sleeve T Shirt — M")
    private val headphones = deal(3, "B0PHONES01", "Sony WH-1000XM5 Wireless Headphones")
    private val tv = deal(4, "B0TV000001", "LG OLED evo C5 Smart TV — 65 Inches")
    private val electronics = setOf(headphones.asin!!, tv.asin!!)

    @Test
    fun `an unknown or missing section is clothes`() {
        assertEquals(TrackedSection.CLOTHES, TrackedSection.of(null))
        assertEquals(TrackedSection.CLOTHES, TrackedSection.of("furniture"))
        assertEquals(TrackedSection.ELECTRONICS, TrackedSection.of(" Electronics "))
    }

    @Test
    fun `every row is in exactly one section`() {
        val all = listOf(shoe, addedShirt, headphones, tv)
        val clothes = inSection(all, TrackedSection.CLOTHES, electronics)
        val elec = inSection(all, TrackedSection.ELECTRONICS, electronics)
        assertEquals(listOf(shoe, addedShirt), clothes)
        assertEquals(listOf(headphones, tv), elec)
        assertEquals(all.toSet(), (clothes + elec).toSet())
    }

    @Test
    fun `with nothing added to electronics the clothes section is the whole tab, as before`() {
        val all = listOf(shoe, addedShirt)
        assertEquals(all, inSection(all, TrackedSection.CLOTHES, emptySet()))
        assertTrue(inSection(all, TrackedSection.ELECTRONICS, emptySet()).isEmpty())
    }

    private fun render(
        deals: List<Deal>,
        section: TrackedSection,
        counts: Map<TrackedSection, Int> = emptyMap(),
    ) = createHTML(prettyPrint = false).main {
        trackedPage(deals, listOf("amazon"), section = section, sectionCounts = counts)
    }

    @Test
    fun `the switch marks the section on screen and counts only non-empty sections`() {
        val html = render(listOf(shoe), TrackedSection.CLOTHES, mapOf(TrackedSection.CLOTHES to 1, TrackedSection.ELECTRONICS to 0))
        assertTrue(html.contains("""<a href="/tracked" class="scope on" aria-current="page">Clothes<span class="scope-count">1</span></a>"""), html)
        assertTrue(html.contains("""<a href="/tracked?section=electronics" class="scope">Electronics</a>"""), html)
    }

    @Test
    fun `clothes keeps its garment filters and its links carry no section`() {
        val html = render(listOf(shoe), TrackedSection.CLOTHES)
        assertTrue(html.contains("""<span class="typetags-label">Type</span>"""), "garment type tags")
        assertTrue(html.contains(">Size</th>"))
        assertFalse(html.contains("section=clothes"), "default section is not written")
    }

    @Test
    fun `electronics drops the garment filters and keeps every link in the section`() {
        val html = render(listOf(headphones), TrackedSection.ELECTRONICS)
        // No garment types, and no found/added/pinned filter: every electronics row was added by hand.
        assertFalse(html.contains("""<span class="typetags-label">Type</span>"""), "no garment type tags")
        assertFalse(html.contains("""<span class="typetags-label">Origin</span>"""), "no origin tags")
        assertFalse(html.contains("col-size"), "no size or variant column when no row has one")
        assertTrue(html.contains("/tracked?section=electronics&amp;sort=price"), "sort headers stay in the section")
        assertTrue(html.contains("nothing is searched for"))
    }

    @Test
    fun `an electronics row with Amazon's own variation gets a Variant column`() {
        val html = render(listOf(headphones, tv), TrackedSection.ELECTRONICS)
        assertTrue(html.contains(">Variant</th>"), html)
        assertTrue(html.contains("65 Inches"))
    }

    @Test
    fun `the add form is preset to the section on screen`() {
        val html = render(listOf(headphones), TrackedSection.ELECTRONICS)
        assertTrue(html.contains("""<option value="electronics" selected="selected">Electronics</option>"""), html)
        assertTrue(html.contains("""<option value="clothes">Clothes</option>"""))
        assertFalse(html.contains("""<details class="additem" open"""), "closed once there are items")
    }

    @Test
    fun `an empty electronics section opens the add form and says how to fill it`() {
        val html = render(emptyList(), TrackedSection.ELECTRONICS)
        assertTrue(html.contains("""<details class="additem" open"""), html)
        assertTrue(html.contains("Paste an amazon.com product link"))
        assertFalse(html.contains("trackSearches"), "not the clothes searches' setup help")
    }
}
