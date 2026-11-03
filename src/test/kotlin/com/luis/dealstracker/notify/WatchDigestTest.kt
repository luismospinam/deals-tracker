package com.luis.dealstracker.notify

import com.luis.dealstracker.db.PendingNotification
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.WatchAlert
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One banner a day for every watch's alerts, biggest gap first. Sent per watch per hourly cycle,
 * Televisor and Nevera fired 98 between them in the week to 2026-11-02.
 */
class WatchDigestTest {

    private fun item(id: Long, watchId: Long, gap: String?, price: String = "1000000", reason: MatchReason = MatchReason.CROSS_SITE_SPREAD) =
        PendingNotification(
            matchId = id, watchName = if (watchId == 1L) "Nevera" else "Televisor",
            alert = WatchAlert(id, reason, BigDecimal(price), "evidence", gapPercent = gap?.let(::BigDecimal)),
            title = "Item $id", source = "exito", currency = "COP", watchId = watchId,
        )

    @Test
    fun `biggest gap leads, unknown gaps go last, ties to the cheaper`() {
        val ranked = rankDigest(
            listOf(item(1, 1, "16.0"), item(2, 2, null), item(3, 1, "47.7"), item(4, 2, "16.0", price = "900000")),
        )
        assertEquals(listOf(3L, 4L, 1L, 2L), ranked.map { it.matchId })
    }

    @Test
    fun `the banner counts everything and shows the biggest gap`() {
        val (title, message) = digestBanner(listOf(item(1, 1, "16.0"), item(3, 1, "47.7"), item(2, 2, "20.5")), Lang.EN)!!
        assertEquals("3 watch alerts across 2 watches", title)
        assertTrue(message.startsWith("Nevera: −47.7% under a rival · \$1,000,000 · exito"), message)
        assertTrue(message.contains("Item 3"))
        assertTrue(message.endsWith("+2 more"), message)
    }

    @Test
    fun `one alert reads singular, a new low says so, and Spanish is Spanish`() {
        val (title, message) = digestBanner(listOf(item(1, 1, "12.0", reason = MatchReason.LOWEST_IN_WINDOW)), Lang.EN)!!
        assertEquals("1 watch alert", title)
        assertTrue(message.contains("−12% under the floor"), message)
        assertTrue(!message.contains("more"))
        assertEquals("1 alerta de búsquedas", digestBanner(listOf(item(1, 1, "12.0")), Lang.ES)!!.first)
    }

    /** A card low is only real to someone holding the card, so every line names it. */
    @Test
    fun `a card low names its card in the banner and the Discord post`() {
        val card = item(1, 1, "6.4", price = "2179900", reason = MatchReason.CARD_LOW).let {
            it.copy(alert = it.alert.copy(card = com.luis.dealstracker.model.CardScope.CMR))
        }
        assertTrue(digestBanner(listOf(card), Lang.EN)!!.second.contains("−6.4% under the floor with CMR"))
        assertTrue(digestDiscordLines(listOf(card), Lang.EN).single().endsWith("(new low with CMR)"), digestDiscordLines(listOf(card), Lang.EN).single())
        assertTrue(digestDiscordLines(listOf(card), Lang.ES).single().endsWith("(nuevo mínimo con CMR)"))
    }

    @Test
    fun `nothing waiting means no banner`() {
        assertNull(digestBanner(emptyList(), Lang.EN))
    }

    /** The slot rule the digest shares with the pinned posts: 19:00 is due from 19:00 until it is sent. */
    @Test
    fun `the evening slot is due once`() {
        val zone = ZoneId.of("America/Bogota")
        val times = listOf(LocalTime.of(19, 0))
        val before = Instant.parse("2026-11-02T23:30:00Z").atZone(zone) // 18:30 local
        val after = Instant.parse("2026-11-03T00:05:00Z").atZone(zone)  // 19:05 local
        assertNull(dueSlot(before, times, null))
        val slot = dueSlot(after, times, null)!!
        assertNull(dueSlot(after, times, slot), "sent once")
    }

    /** The post lists every alert, ranked, each linked to the retailer; brackets in a title cannot break the link. */
    @Test
    fun `the Discord post lists every alert with its link`() {
        val withUrl = item(3, 1, "47.7").copy(title = "Nevera [Inverter] 565L", url = "https://falabella.example/1")
        val lines = digestDiscordLines(listOf(item(1, 2, "16.0"), withUrl), Lang.EN)
        assertEquals(2, lines.size)
        assertEquals(
            "**−47.7%** Nevera · [Nevera (Inverter) 565L](https://falabella.example/1) — \$1,000,000 at exito (under a rival)",
            lines[0],
        )
        assertTrue(lines[1].startsWith("**−16%** Televisor · Item 1 —"), lines[1])
    }
}
