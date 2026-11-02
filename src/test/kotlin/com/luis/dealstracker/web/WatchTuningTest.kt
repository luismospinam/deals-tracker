package com.luis.dealstracker.web

import com.luis.dealstracker.db.DigestRow
import com.luis.dealstracker.db.PendingNotification
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchAlert
import com.luis.dealstracker.model.WatchCandidate
import com.luis.dealstracker.model.withExcludedTerm
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tuning a watch from the wrong row (hide, exclude word), and the digest table the banner opens. */
class WatchTuningTest {

    private val watch = Watch(
        id = 1, name = "Nevera", terms = "nevera | refrigerador", excludeTerms = "dispensador", categoryGroup = "Appliances",
        sources = emptyList(), minPrice = BigDecimal("1000000"), maxPrice = null, currency = "COP", lookbackDays = 30,
        crossSiteMinSpread = BigDecimal(15), enabled = true, createdAt = Instant.EPOCH,
    )

    @Test
    fun `a word is appended once, whatever its case or accents`() {
        assertEquals("dispensador | bebidas", withExcludedTerm("dispensador", " bebidas "))
        assertEquals("dispensador | Cojín", withExcludedTerm("dispensador | Cojín", "cojin"), "already there")
        assertEquals("mini", withExcludedTerm(null, "mini"))
    }

    @Test
    fun `a blank word or one with a pipe is refused`() {
        assertNull(withExcludedTerm("a", "  "))
        assertNull(withExcludedTerm("a", "b | c"))
    }

    @Test
    fun `every matching row offers hide and exclude word`() {
        val cooler = WatchCandidate(
            dealId = 105450, source = "exito", title = "Refrigerador De Bebidas Hailang Mini", url = "https://exito.example/1",
            price = BigDecimal("826990"), currency = "COP", priorFloor = null,
        )
        val html = createHTML().main { watchDetail(watch, listOf(cooler), emptyList(), emptyMap(), emptyList(), emptyList()) }
        assertTrue(html.contains("hx-post=\"/watches/1/hide/105450\""), "hide is one click")
        assertTrue(html.contains("action=\"/watches/1/exclude\""))
        assertFalse(html.contains("Hidden from this watch"), "no hidden list when nothing is hidden")
    }

    @Test
    fun `hidden listings are listed with a way back`() {
        val html = createHTML().main {
            watchDetail(watch, emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), hidden = listOf(105450L to "Refrigerador De Bebidas"))
        }
        assertTrue(html.contains("Hidden from this watch (1)"))
        assertTrue(html.contains("action=\"/watches/1/unhide/105450\""))
    }

    private fun row(id: Long, gap: String?, waiting: Boolean) = DigestRow(
        PendingNotification(
            matchId = id, watchName = "Nevera",
            alert = WatchAlert(id, MatchReason.CROSS_SITE_SPREAD, BigDecimal("2587455"), "16.0% cheaper than carulla", gapPercent = gap?.let(::BigDecimal)),
            title = "Nevecon $id", source = "exito", currency = "COP", watchId = 1,
        ),
        notifiedAt = if (waiting) null else Instant.now().minusSeconds(7200),
        waiting = waiting,
    )

    @Test
    fun `the digest table ranks by gap and says what is waiting`() {
        val html = createHTML().main {
            watchesPage(listOf(watch), emptyMap(), emptyList(), emptyList(), listOf(row(1, "16.0", false), row(2, "34.0", true)), listOf("19:00"))
        }
        val table = html.substringAfter("id=\"digest\"")
        assertTrue(table.indexOf("Nevecon 2") < table.indexOf("Nevecon 1"), "34% before 16%")
        assertTrue(table.contains("waiting · 19:00"))
        assertTrue(table.contains("sent 2h ago"))
        assertTrue(html.contains("one desktop notification at 19:00"))
    }

    /** A busy day must not push the watches themselves off the screen. */
    @Test
    fun `the digest shows the top ten and links to the rest`() {
        val rows = (1..14L).map { row(it, "$it.0", waiting = false) }
        val top = createHTML().main { watchesPage(listOf(watch), emptyMap(), emptyList(), emptyList(), rows, listOf("19:00")) }
        assertEquals(WATCH_DIGEST_SHOWN, top.substringAfter("id=\"digest\"").substringBefore("</tbody>").split("<tr").size - 2)
        assertTrue(top.contains("href=\"/watches?digest=all#digest\""))
        assertFalse(top.contains("Nevecon 1<"), "the smallest gap is below the cut")
        val all = createHTML().main { watchesPage(listOf(watch), emptyMap(), emptyList(), emptyList(), rows, listOf("19:00"), digestAll = true) }
        assertTrue(all.contains("Nevecon 1<"))
        assertFalse(all.contains("show all"))
    }

    @Test
    fun `without digest times there is no digest section`() {
        val html = createHTML().main { watchesPage(listOf(watch), emptyMap(), emptyList(), emptyList()) }
        assertFalse(html.contains("id=\"digest\""))
        assertTrue(html.contains("Alerts land as a desktop notification."))
    }
}
