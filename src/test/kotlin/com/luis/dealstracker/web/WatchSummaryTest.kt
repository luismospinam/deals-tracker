package com.luis.dealstracker.web

import com.luis.dealstracker.db.AlertCounts
import com.luis.dealstracker.model.Watch
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The list called alerts "Matches" (131 for Nevera on 2026-11-02, while its own page said 881
 * matching now), and the watch page left out the floor and the exclusions that decide its rows.
 */
class WatchSummaryTest {

    private val watch = Watch(
        id = 1, name = "Nevera", terms = "nevera | nevecon", excludeTerms = "dispensador | organizador", categoryGroup = "Appliances",
        sources = emptyList(), minPrice = BigDecimal("1000000"), maxPrice = null, currency = "COP", lookbackDays = 30,
        crossSiteMinSpread = BigDecimal(15), enabled = true, createdAt = Instant.EPOCH,
    )

    @Test
    fun `the list column is alerts, with this week and the last one`() {
        val list = createHTML().main { watchesPage(listOf(watch), emptyMap(), emptyList(), emptyList()) }
        assertTrue(list.contains(">Alerts<"), "header renamed")
        assertFalse(list.contains("Matches"), "the old label is gone")

        val row = watchRowFragment(watch, AlertCounts(total = 131, unseen = 0, lastWeek = 43, lastAt = Instant.now().minus(2, ChronoUnit.HOURS)))
        assertTrue(row.contains("131"))
        assertTrue(row.contains("43 this week"), row)
        assertTrue(row.contains("last 2h ago"), row)
    }

    @Test
    fun `a watch with no alerts shows a dash, not zeros`() {
        val row = watchRowFragment(watch, null)
        assertTrue(row.contains("—"))
        assertFalse(row.contains("this week"))
    }

    @Test
    fun `the watch page states its floor, exclusions, category and spread`() {
        val html = createHTML().main {
            watchDetail(watch, emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
        }
        val header = html.substringAfter("</h2>").substringBefore("<h3")
        assertTrue(header.contains("from $1,000,000"), header)
        assertTrue(header.contains("dispensador | organizador"), header)
        assertTrue(header.contains("Appliances"), header)
        assertTrue(header.contains("−15% x-site"), header)
    }
}
