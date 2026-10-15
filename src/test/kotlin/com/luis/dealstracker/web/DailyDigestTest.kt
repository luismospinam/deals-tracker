package com.luis.dealstracker.web

import com.luis.dealstracker.db.DayChange
import com.luis.dealstracker.db.DigestRange
import com.luis.dealstracker.db.GroceryRow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "Since yesterday" banner on Tracked, Groceries and a watch (2026-10-08). */
class DailyDigestTest {

    private fun change(id: Long, yesterday: String, today: String, priorLow: String = yesterday) =
        DayChange(id, BigDecimal(yesterday), BigDecimal(today), BigDecimal(priorLow))

    private fun line(
        id: Long, yesterday: String?, today: String, vararg kinds: DigestKind, title: String = "Item $id", note: String? = null,
    ) = DigestLine(id, title, "COP", yesterday?.let(::BigDecimal), BigDecimal(today), kinds.toSet(), note)

    private fun render(lines: List<DigestLine>, range: DigestRange = DigestRange.YESTERDAY) =
        createHTML().main { digestBanner(lines, range) }

    // --- DayChange ----------------------------------------------------------------------------

    @Test
    fun `a drop is cheaper than the last reading before today`() {
        assertTrue(change(1, "100", "90").isDrop)
        assertFalse(change(1, "100", "100").isDrop)
        assertFalse(change(1, "100", "110").isDrop)
    }

    @Test
    fun `a new low must beat every earlier reading, not just yesterday's`() {
        // Dropped from 100 to 90, but it was 80 last week: a drop, not a new low.
        val c = change(1, "100", "90", priorLow = "80")
        assertTrue(c.isDrop)
        assertFalse(c.isNewLow)
        // Equal to the old floor is not "lower than ever".
        assertFalse(change(1, "100", "80", priorLow = "80").isNewLow)
        assertTrue(change(1, "100", "79", priorLow = "80").isNewLow)
    }

    @Test
    fun `a new low can arrive without a drop against yesterday`() {
        // Yesterday was above the old floor and today is below it: both flags, not just one.
        val c = change(1, "120", "70", priorLow = "80")
        assertTrue(c.isDrop && c.isNewLow)
    }

    // --- digestLines --------------------------------------------------------------------------

    private data class Row(val id: Long, val title: String)

    @Test
    fun `only rows that moved become lines, and only rows that were passed in`() {
        val changes = mapOf(
            1L to change(1, "100", "90"),
            2L to change(2, "50", "40"), // moved, but not on screen (filtered out)
        )
        val rows = listOf(Row(1, "Shown"), Row(3, "Unchanged"))
        val lines = digestLines(changes, rows, idOf = { it.id }, titleOf = { it.title }, currencyOf = { "COP" })
        assertEquals(listOf(1L), lines.map { it.dealId })
    }

    @Test
    fun `an empty selection yields no lines, so a filter that matches nothing hides the banner`() {
        val changes = mapOf(1L to change(1, "100", "90"))
        assertEquals(emptyList(), digestLines(changes, emptyList<Row>(), { it.id }, { it.title }, { "COP" }))
    }

    @Test
    fun `kinds come from the change, plus whatever the caller adds`() {
        val changes = mapOf(1L to change(1, "100", "70", priorLow = "80"))
        val lines = digestLines(
            changes, listOf(Row(1, "A")), { it.id }, { it.title }, { "COP" },
            extraKinds = { _, _ -> setOf(DigestKind.NEW_DEAL) },
        )
        assertEquals(setOf(DigestKind.DROP, DigestKind.NEW_LOW, DigestKind.NEW_DEAL), lines.single().kinds)
    }

    // --- DigestLine ---------------------------------------------------------------------------

    @Test
    fun `drop percent is against yesterday and absent without one`() {
        assertEquals(BigDecimal("25.0"), line(1, "200", "150", DigestKind.DROP).dropPercent)
        assertNull(line(1, null, "150", DigestKind.ALERT).dropPercent)
        // An alert at an unchanged price is not a drop.
        assertNull(line(1, "150", "150", DigestKind.ALERT).dropPercent)
    }

    // --- newlyUnderUsual ----------------------------------------------------------------------

    private val now: Instant = Instant.parse("2026-10-08T12:00:00Z")

    private fun grocery(price: String, usual: String?, days: Int = 10) = GroceryRow(
        deal = Deal(
            id = 1, source = "exito-grocery", sourceDealId = "x", title = "Leche", url = "https://x", merchant = null, asin = null,
            category = null, imageUrl = null, brand = null, gtin = null, modelCode = null, matchKey = null, modelKey = null,
            currency = "COP", firstSeenAt = now, lastSeenAt = now, isActive = true, state = DealState.NEW,
            coShipping = CoShipping.YES, promoted = false, promotedAt = null, communityScore = null, postedAt = null,
            categoryGroup = "Groceries", coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
            coShippingCost = null, price = BigDecimal(price), listPrice = null, minPriceEver = null, maxPriceEver = null,
            observationCount = 1,
        ),
        size = null, group = "Dairy & eggs", pinnedAt = null, usual = usual?.let(::BigDecimal), low = null, days = days, onShelf = true,
    )

    @Test
    fun `newly under usual needs a deal today that was not one yesterday`() {
        // Usual 1000: 850 is 15% under (a deal); yesterday 1000 was not.
        assertTrue(newlyUnderUsual(grocery("850", "1000"), change(1, "1000", "850")))
        // Already a deal yesterday (880 is 12% under): not news.
        assertFalse(newlyUnderUsual(grocery("850", "1000"), change(1, "880", "850")))
        // Exactly 10% under yesterday still counts as a deal yesterday, so it is not "newly".
        assertFalse(newlyUnderUsual(grocery("800", "1000"), change(1, "900", "800")))
    }

    @Test
    fun `a drop that does not reach 10 percent under usual is not a deal`() {
        assertFalse(newlyUnderUsual(grocery("950", "1000"), change(1, "1000", "950")))
    }

    @Test
    fun `no usual price, or under three earlier days, is never newly a deal`() {
        assertFalse(newlyUnderUsual(grocery("500", null), change(1, "1000", "500")))
        assertFalse(newlyUnderUsual(grocery("500", "1000", days = 2), change(1, "1000", "500")))
    }

    // --- rendering ----------------------------------------------------------------------------

    @Test
    fun `no lines renders no banner, only the range control`() {
        val html = render(emptyList())
        assertFalse("<details" in html, html)
        assertFalse("digest-empty" in html, html)
        assertTrue("digest-range" in html, html)
    }

    @Test
    fun `an empty wider range says so rather than showing nothing`() {
        val html = render(emptyList(), DigestRange.WEEK)
        assertTrue("Nothing cheaper than the past 7 days." in html, html)
    }

    @Test
    fun `the control offers every range and marks the current one pressed`() {
        val html = render(emptyList(), DigestRange.FORTNIGHT)
        assertEquals(DigestRange.entries.size, Regex("<button").findAll(html).count(), html)
        assertTrue(Regex("""class="on"[^>]*aria-pressed="true"|aria-pressed="true"[^>]*class="on"""").containsMatchIn(html), html)
        assertEquals(1, Regex("""aria-pressed="true"""").findAll(html).count(), html)
    }

    @Test
    fun `each button sets its own range through window URL, because document URL shadows a bare URL`() {
        val html = render(emptyList())
        DigestRange.entries.forEach { assertTrue("set(&#39;range&#39;,&#39;${it.param}&#39;)" in html || "set('range','${it.param}')" in html, html) }
        assertTrue("new window.URL(" in html, html)
        assertFalse(Regex("""new URL\(""").containsMatchIn(html), html)
    }

    @Test
    fun `the banner is titled by the range`() {
        val lines = listOf(line(1, "100", "90", DigestKind.DROP))
        assertTrue("Since yesterday</span>" in render(lines), render(lines))
        assertTrue("Below the past 7 days</span>" in render(lines, DigestRange.WEEK), render(lines, DigestRange.WEEK))
    }

    @Test
    fun `alert counts name the window they cover`() {
        val lines = listOf(line(1, null, "90", DigestKind.ALERT))
        assertTrue("1 alert fired in the past 14 days" in render(lines, DigestRange.FORTNIGHT), render(lines, DigestRange.FORTNIGHT))
    }

    @Test
    fun `range parameter parsing falls back to yesterday`() {
        assertEquals(DigestRange.WEEK, DigestRange.of("7"))
        assertEquals(DigestRange.FORTNIGHT, DigestRange.of("14"))
        assertEquals(DigestRange.YESTERDAY, DigestRange.of("1"))
        assertEquals(DigestRange.YESTERDAY, DigestRange.of(null))
        assertEquals(DigestRange.YESTERDAY, DigestRange.of("365"))
        assertEquals(DigestRange.YESTERDAY, DigestRange.of("abc"))
    }

    @Test
    fun `the summary counts each kind and pluralises`() {
        val html = render(listOf(line(1, "100", "90", DigestKind.DROP, DigestKind.NEW_LOW)))
        assertTrue("1 price drop · 1 new low" in html, html)
        val many = render(
            listOf(
                line(1, "100", "90", DigestKind.DROP),
                line(2, "100", "90", DigestKind.DROP, DigestKind.NEW_DEAL),
                line(3, null, "90", DigestKind.ALERT),
            ),
        )
        assertTrue("2 price drops" in many, many)
        assertTrue("1 newly under usual" in many, many)
        assertTrue("1 alert fired today" in many, many)
    }

    @Test
    fun `a category with no members is left out of the summary`() {
        val html = render(listOf(line(1, "100", "90", DigestKind.DROP)))
        assertFalse("new low" in html.substringAfter("digest-counts").substringBefore("</span>"), html)
        assertFalse("alert" in html.substringAfter("digest-counts").substringBefore("</span>"), html)
    }

    @Test
    fun `each line links to the deal page and shows old then new price`() {
        val html = render(listOf(line(42, "10000", "7500", DigestKind.DROP, title = "Arroz 1 kg")))
        assertTrue("""href="/deal/42"""" in html, html)
        assertTrue("Arroz 1 kg" in html)
        assertTrue("$10,000 → $7,500" in html, html)
        assertTrue("−25%" in html, html)
    }

    @Test
    fun `an alert with no yesterday shows only today's price`() {
        val html = render(listOf(line(1, null, "7500", DigestKind.ALERT, note = "beat the 30d floor")))
        assertFalse("→" in html, html)
        assertTrue("$7,500" in html)
        assertTrue("beat the 30d floor" in html)
    }

    @Test
    fun `the biggest drop is listed first`() {
        val html = render(
            listOf(
                line(1, "100", "95", DigestKind.DROP, title = "Small"),
                line(2, "100", "50", DigestKind.DROP, title = "Big"),
            ),
        )
        assertTrue(html.indexOf("Big") < html.indexOf("Small"), html)
    }

    @Test
    fun `new lows come first even when a plain drop is bigger`() {
        val html = render(
            listOf(
                line(1, "100", "40", DigestKind.DROP, title = "BigPlainDrop"),
                line(2, "100", "95", DigestKind.DROP, DigestKind.NEW_LOW, title = "SmallNewLow"),
                line(3, "100", "90", DigestKind.DROP, DigestKind.NEW_LOW, title = "MidNewLow"),
                line(4, null, "50", DigestKind.ALERT, title = "AlertOnly"),
            ),
        )
        val order = listOf("MidNewLow", "SmallNewLow", "BigPlainDrop", "AlertOnly").map { html.indexOf(it) }
        assertEquals(order.sorted(), order, html)
        assertTrue(order.all { it >= 0 })
    }

    @Test
    fun `a new low survives the cap even when many plain drops are bigger`() {
        val lines = (1L..45L).map { line(it, "100", "10", DigestKind.DROP, title = "Plain $it") } +
            line(99, "100", "99", DigestKind.DROP, DigestKind.NEW_LOW, title = "TheLow")
        assertTrue("TheLow" in render(lines))
    }

    @Test
    fun `a long digest is capped and says how many are left`() {
        val lines = (1L..45L).map { line(it, "100", "90", DigestKind.DROP, title = "Item $it") }
        val html = render(lines)
        assertEquals(40, Regex("""href="/deal/""").findAll(html).count())
        assertTrue("and 5 more" in html, html)
        assertTrue("45 price drops" in html, html)
    }

    @Test
    fun `titles are escaped, not inserted as markup`() {
        val html = render(listOf(line(1, "100", "90", DigestKind.DROP, title = "<script>x</script>")))
        assertFalse("<script>x" in html, html)
    }
}
