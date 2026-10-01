package com.luis.dealstracker.web

import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchCandidate
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Matching now" narrowed to one retailer by a dropdown, replacing the source sort (2026-09-28). */
class WatchSourceFilterTest {

    private val watch = Watch(
        id = 5, name = "Freidora de aire", terms = "freidora de aire", excludeTerms = null, categoryGroup = "Appliances",
        sources = emptyList(), minPrice = BigDecimal("120000"), maxPrice = null, currency = "COP", lookbackDays = 30,
        crossSiteMinSpread = BigDecimal(12), enabled = true, createdAt = Instant.EPOCH,
    )

    private fun candidate(id: Long, source: String, price: String) = WatchCandidate(
        dealId = id, source = source, title = "Freidora $id", url = "https://$source.example/$id",
        price = BigDecimal(price), currency = "COP", priorFloor = null,
    )

    private val candidates = listOf(
        candidate(1, "falabella", "156900"),
        candidate(2, "homecenter", "178900"),
        candidate(3, "falabella", "199900"),
        candidate(4, "exito", "188200"),
        candidate(5, "falabella", "259900"),
    )

    @Test
    fun `filtering keeps only the chosen source, and null keeps all`() {
        assertEquals(listOf(1L, 3L, 5L), filterBySource(candidates, "falabella").map { it.dealId })
        assertEquals(5, filterBySource(candidates, null).size)
        assertEquals(emptyList(), filterBySource(candidates, "olimpica"))
    }

    private fun render(source: String?, sort: WatchSort = WatchSort.MATCHING_DEFAULT) = createHTML().main {
        watchDetail(watch, candidates, emptyList(), emptyMap(), emptyList(), emptyList(),
            matchingSort = sort, matchingSource = source)
    }

    @Test
    fun `the dropdown lists every source with its count, most listings first`() {
        val html = render(null)
        val select = html.substringAfter("<select").substringBefore("</select>")
        val options = Regex(">([^<]+)</option>").findAll(select).map { it.groupValues[1] }.toList()
        assertEquals(listOf("all sources (5)", "falabella (3)", "exito (1)", "homecenter (1)"), options)
    }

    @Test
    fun `a chosen source narrows the table and keeps every source in the dropdown`() {
        val html = render("falabella")
        val table = html.substringAfter("watchmatches").substringBefore("</table>")
        assertTrue("Freidora 1" in table && "Freidora 3" in table && "Freidora 5" in table)
        assertFalse("Freidora 2" in table || "Freidora 4" in table)
        assertTrue("From falabella only" in html)
        val select = html.substringAfter("<select").substringBefore("</select>")
        assertTrue("exito (1)" in select && "homecenter (1)" in select, "other sources stay selectable")
        assertTrue(Regex("<option[^>]*selected[^>]*>falabella \\(3\\)").containsMatchIn(select), select)
    }

    /** Sorting must not drop the filter, or clicking a header would silently show every retailer again. */
    @Test
    fun `sort links keep the source filter`() {
        val html = render("falabella")
        val headers = html.substringAfter("watchmatches").substringAfter("<thead>").substringBefore("</thead>")
        val hrefs = Regex("href=\"([^\"]+)\"").findAll(headers).map { it.groupValues[1] }.toList()
        assertTrue(hrefs.isNotEmpty())
        hrefs.forEach { assertTrue("msource=falabella" in it, it) }
        assertFalse("msort=source" in html, "source is no longer a sortable column")
    }

    @Test
    fun `a source with nothing left says so, with a way back`() {
        val html = render("olimpica")
        assertTrue("Nothing from olimpica matches right now" in html)
        assertTrue("olimpica (0)" in html.substringAfter("<select").substringBefore("</select>"))
    }
}
