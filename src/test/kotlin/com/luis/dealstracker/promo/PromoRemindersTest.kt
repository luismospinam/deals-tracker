package com.luis.dealstracker.promo

import com.luis.dealstracker.web.promoBanner
import com.luis.dealstracker.web.promoChips
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromoRemindersTest {
    private val bancol = Promo(
        id = 1, name = "Bancolombia first Tuesday", code = "BANCOLMARTES", payment = "Bancolombia", sourcePrefix = "amazon",
        nth = 1, weekday = DayOfWeek.TUESDAY, minAmount = BigDecimal("60"), discount = BigDecimal("10"), currency = "USD",
    )
    private val zone = ZoneId.of("America/Bogota")
    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test
    fun `first Tuesday of each month`() {
        assertEquals(LocalDate.parse("2026-11-03"), bancol.dateIn(YearMonth.of(2026, 11)))
        assertEquals(LocalDate.parse("2026-12-01"), bancol.dateIn(YearMonth.of(2026, 12)))
        assertEquals(LocalDate.parse("2026-12-01"), bancol.nextOn(LocalDate.parse("2026-11-04")))
        assertEquals(LocalDate.parse("2026-11-03"), bancol.nextOn(LocalDate.parse("2026-11-03")))
        assertTrue(bancol.isOn(LocalDate.parse("2026-11-03")))
        assertFalse(bancol.isOn(LocalDate.parse("2026-11-10")))
    }

    @Test
    fun `last weekday, and a fifth one that some months lack`() {
        val last = bancol.copy(nth = -1, weekday = DayOfWeek.FRIDAY)
        assertEquals(LocalDate.parse("2026-11-27"), last.dateIn(YearMonth.of(2026, 11)))
        val fifth = bancol.copy(nth = 5)
        assertNull(fifth.dateIn(YearMonth.of(2026, 11)), "November 2026 has four Tuesdays")
        assertEquals(LocalDate.parse("2026-12-29"), fifth.nextOn(LocalDate.parse("2026-11-04")))
    }

    @Test
    fun `qualifies on the store, the currency and the minimum`() {
        assertTrue(bancol.qualifies("amazon-watches", BigDecimal("60.00"), "USD"))
        assertFalse(bancol.qualifies("amazon", BigDecimal("59.99"), "USD"))
        assertFalse(bancol.qualifies("exito", BigDecimal("600"), "USD"))
        assertFalse(bancol.qualifies("amazon", BigDecimal("600000"), "COP"))
        assertEquals(BigDecimal("89.99"), bancol.priceWith(BigDecimal("99.99")))
    }

    @Test
    fun `reminders go out the day before and on the day, once each, after the set time`() {
        assertNull(promoMomentDue(bancol, at("2026-11-03T07:59"), zone, null), "before 08:00")
        assertEquals(PromoMoment.DAY to LocalDate.parse("2026-11-03"), promoMomentDue(bancol, at("2026-11-03T08:00"), zone, null))
        assertNull(promoMomentDue(bancol, at("2026-11-03T15:00"), zone, "2026-11-03:DAY"), "already sent")
        assertEquals(PromoMoment.DAY_BEFORE to LocalDate.parse("2026-12-01"), promoMomentDue(bancol, at("2026-11-30T09:00"), zone, "2026-11-03:DAY"))
        assertNull(promoMomentDue(bancol.copy(remindDayBefore = false), at("2026-11-30T09:00"), zone, null))
        assertNull(promoMomentDue(bancol.copy(enabled = false), at("2026-11-03T09:00"), zone, null))
        assertNull(promoMomentDue(bancol, at("2026-11-10T09:00"), zone, null), "an ordinary Tuesday")
    }

    @Test
    fun `the post names the code and lists qualifying items with the price after it`() {
        val (title, lines) = promoMessage(
            bancol, PromoMoment.DAY,
            listOf(PromoItem(7, "Kindle Paperwhite", "amazon", BigDecimal("159.99"), "USD")), "http://localhost:8080",
        )
        assertEquals("Today: BANCOLMARTES", title)
        assertEquals("Bancolombia first Tuesday — US\$10.00 off from US\$60.00 paying with Bancolombia, code BANCOLMARTES.", lines[0])
        assertTrue(lines.last().contains("[Kindle Paperwhite](http://localhost:8080/deal/7)") && lines.last().contains("**US\$149.99**"), lines.last())
    }

    @Test
    fun `the form is checked field by field`() {
        val ok = parsePromoForm(
            mapOf("name" to "Bancol", "code" to "bancolmartes", "nth" to "1", "weekday" to "2", "discount" to "10",
                "minAmount" to "60", "currency" to "usd", "notifyAt" to "8:30", "remindDayBefore" to "on", "sourcePrefix" to "Amazon"),
        ).getOrThrow()
        assertEquals("BANCOLMARTES", ok.code)
        assertEquals("USD", ok.currency)
        assertEquals("amazon", ok.sourcePrefix)
        assertEquals(LocalTime.of(8, 30), ok.notifyAt)
        assertTrue(ok.remindDayBefore)
        assertTrue(parsePromoForm(mapOf("name" to "x", "nth" to "1", "weekday" to "2", "discount" to "0", "currency" to "USD")).isFailure)
        assertTrue(parsePromoForm(mapOf("name" to "x", "nth" to "6", "weekday" to "2", "discount" to "5", "currency" to "USD")).isFailure)
        assertTrue(parsePromoForm(mapOf("nth" to "1", "weekday" to "2", "discount" to "5", "currency" to "USD")).isFailure)
    }

    @Test
    fun `rows show the price after the code on the day and the day before, and the strip shows on every page`() {
        val tue = LocalDate.parse("2026-11-03")
        fun chip(day: LocalDate, price: String, source: String = "amazon") =
            createHTML().div { promoChips(source, BigDecimal(price), "USD", day, listOf(bancol)) }
        assertTrue(chip(tue, "99.99").contains("BANCOLMARTES today → <span class=\"promo-amt\">US\$89.99"), chip(tue, "99.99"))
        assertTrue(chip(tue.minusDays(1), "99.99").contains("BANCOLMARTES tomorrow"))
        assertFalse(chip(tue, "59.99").contains("promo-price"))
        assertFalse(chip(tue, "99.99", "exito").contains("promo-price"))
        assertFalse(chip(tue.plusDays(1), "99.99").contains("promo-price"))

        val strip = createHTML().div { promoBanner(listOf(bancol), tue) }
        assertTrue(strip.contains("Today") && strip.contains("code BANCOLMARTES"), strip)
        assertFalse(createHTML().div { promoBanner(listOf(bancol), tue.plusDays(2)) }.contains("promo-banner"))
    }

    @Test
    fun `the strip can be hidden for two hours, and the promo day itself shows again`() {
        val tue = LocalDate.parse("2026-11-03")
        val strip = createHTML().div { promoBanner(listOf(bancol), tue) }
        assertTrue(strip.contains("class=\"promo-dismiss\"") && strip.contains("promoDismiss()"), strip)
        assertTrue(strip.contains("data-key=\"2026-11-03:1d\""), strip)
        assertTrue(strip.contains("localStorage") && strip.contains("7200000"), "snooze lasts 2h")
        val eve = createHTML().div { promoBanner(listOf(bancol), tue.minusDays(1)) }
        assertTrue(eve.contains("data-key=\"2026-11-02:1b\""), "the day-before strip has its own key, so hiding it does not hide the day")
    }
}
