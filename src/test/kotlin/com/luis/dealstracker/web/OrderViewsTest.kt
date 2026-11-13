package com.luis.dealstracker.web

import com.luis.dealstracker.exito.PlacedLine
import com.luis.dealstracker.exito.PlacedOrder
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderViewsTest {
    private fun bd(v: String) = BigDecimal(v)

    private fun line(
        title: String = "Galletas NOEL", qty: Int = 1, paid: String? = "8000", usual: String? = "10000", lowest: String? = "8000",
        multiplier: String = "1", dealId: Long? = 7, grocery: Boolean = true, shelf: String? = null,
    ) = PlacedLine(title, qty, paid?.let(::bd), bd(multiplier), dealId, usual?.let(::bd), lowest?.let(::bd), grocery, shelf?.let(::bd))

    private fun order(id: String, at: String, vararg lines: PlacedLine, total: String = "50000") =
        PlacedOrder(id, Instant.parse(at), bd(total), "COP", "Mastercard", lines.toList())

    private fun render(vararg orders: PlacedOrder) =
        createHTML(prettyPrint = false).main { groceryOrdersPage(orders.toList(), pinnedCount = 3, zone = ZoneOffset.UTC) }

    @Test
    fun `a line is compared with its usual price for the quantity bought`() {
        val l = line(qty = 3, paid = "8000", usual = "10000")

        assertEquals(0, bd("24000").compareTo(l.cost()))
        assertEquals(0, bd("30000").compareTo(l.usualCost()))
        assertEquals(20, l.percentUnder())
    }

    @Test
    fun `fruit sold by the half kilo is compared with half the per-kilo price`() {
        val l = line(paid = "3493", usual = "11625", lowest = "7192", multiplier = "0.5")

        assertEquals(0, bd("5812.50").compareTo(l.usualEach()))
        assertEquals(40, l.percentUnder())
        assertEquals(PaidStanding.LOWEST, l.standing())
    }

    @Test
    fun `standing is lowest, under, same or over, and nothing without a usual price`() {
        assertEquals(PaidStanding.LOWEST, line(paid = "7000", usual = "10000", lowest = "8000").standing(), "a card price under the lowest shelf price")
        assertEquals(PaidStanding.UNDER, line(paid = "9000", usual = "10000", lowest = "8000").standing())
        assertEquals(PaidStanding.SAME, line(paid = "10000", usual = "10000", lowest = "8000").standing())
        assertEquals(PaidStanding.OVER, line(paid = "11000", usual = "10000", lowest = "11000").standing(), "over usual is never 'lowest'")
        assertNull(line(usual = null).standing())
        assertNull(line(paid = null).standing())
    }

    @Test
    fun `savings count only the lines that had a usual price`() {
        val stats = paidStats(
            listOf(
                line(qty = 2, paid = "8000", usual = "10000"),
                line(paid = "11000", usual = "10000", lowest = "9000"),
                line(title = "BOLSA PAPEL", paid = "500", usual = null, lowest = null, dealId = null),
            ),
        )

        assertEquals(3, stats.lines)
        assertEquals(2, stats.compared)
        assertEquals(0, bd("27000").compareTo(stats.paid))
        assertEquals(0, bd("3000").compareTo(stats.saved))
        assertEquals(10, stats.percent)
        assertEquals(1, stats.atLowest)
        assertEquals(1, stats.over)
        assertEquals(0, bd("1000").compareTo(stats.overCost))
    }

    @Test
    fun `a fridge bought on the same account is not a grocery order`() {
        val orders = groceryOrders(
            listOf(
                order("a", "2026-11-08T10:00:00Z", line(title = "Nevecón", grocery = false)),
                order("b", "2026-11-04T10:00:00Z", line(), line(title = "Televisor", grocery = false)),
            ),
        )

        assertEquals(listOf("b"), orders.map { it.orderId })
        assertEquals(listOf("Galletas NOEL"), orders.single().lines.map { it.title })
    }

    @Test
    fun `the page sits in the Groceries strip, with Orders the current view`() {
        val html = render(order("a", "2026-11-04T10:00:00Z", line()))

        assertTrue("""href="/groceries/orders" class="scope on" aria-current="page"""" in html || """class="scope on" href="/groceries/orders"""" in html, html.take(600))
        assertEquals(1, Regex("aria-current=\"page\"").findAll(html).count(), "Deals is not also marked current")
        assertTrue("Pinned" in html && "All items" in html)
    }

    @Test
    fun `each order shows its saving, and the newest is open`() {
        val html = render(
            order("new", "2026-11-11T10:00:00Z", line(qty = 2, paid = "8000", usual = "10000")),
            order("old", "2026-10-02T10:00:00Z", line(paid = "11000", usual = "10000", lowest = "9000")),
        )

        assertTrue("Wed 11 Nov 2026" in html)
        assertTrue("saved $4,000 · 20%" in html)
        assertTrue("$1,000 over usual" in html)
        assertTrue("1 at lowest" in html)
        assertEquals(1, Regex("<details[^>]* open=").findAll(html).count())
        assertTrue("Since 2 Oct 2026" in html && "2 order(s)" in html)
        assertTrue("""<a href="/deal/7">Galletas NOEL</a>""" in html)
        assertTrue("2× " in html)
    }

    @Test
    fun `an order from before the tracker has no saving and is counted as such`() {
        val html = render(
            order("new", "2026-11-11T10:00:00Z", line()),
            order("old", "2025-10-14T10:00:00Z", line(usual = null, lowest = null, dealId = null)),
        )

        assertTrue("no price history" in html)
        assertTrue("1 earlier order(s) have no price history" in html)
        assertTrue("1 order(s)" in html, "the totals cover only the orders that could be compared")
    }

    @Test
    fun `a saving is split into what timing did and what the card did`() {
        val stats = paidStats(
            listOf(
                line(qty = 2, paid = "7000", usual = "10000", shelf = "8000"),
                line(paid = "9000", usual = "10000", shelf = null),
            ),
        )

        assertEquals(0, bd("7000").compareTo(stats.saved))
        assertEquals(0, bd("5000").compareTo(stats.byTiming), "2 x 2000 under usual on the shelf, plus the line with no shelf reading")
        assertEquals(0, bd("2000").compareTo(stats.byCard))
        assertEquals(0, stats.saved.compareTo(stats.byTiming + stats.byCard))
    }

    @Test
    fun `an order names how it was paid and shows the shelf price beside what was paid`() {
        val html = render(order("a", "2026-11-11T10:00:00Z", line(paid = "7000", usual = "10000", shelf = "8000"), line(title = "Arroz", paid = "5000", usual = "5000", shelf = "5000")))

        assertTrue("""<span class="order-pay">Mastercard</span>""" in html)
        assertTrue("Shelf then" in html)
        assertTrue("$2,000 by timing, $1,000 by card" in html)
        assertEquals(1, Regex("order-shelf-sub").findAll(html).count(), "only where the shelf price differs from what was paid")
        assertTrue("shelf $8,000" in html)
    }

    @Test
    fun `the page reads in Spanish`() {
        val html = com.luis.dealstracker.i18n.I18n.with(com.luis.dealstracker.i18n.Lang.ES) {
            render(
                order("new", "2026-11-11T10:00:00Z", line(qty = 2, paid = "8000", usual = "10000")),
                order("old", "2025-10-14T10:00:00Z", line(usual = null, lowest = null, dealId = null)),
            )
        }

        listOf(
            "Pedidos", "Desde el 11 nov 2026", "1 pedido(s)", "ahorraste $4,000 (20%)", "ahorro $4,000 · 20%", "1 en mínimo",
            "sin historial de precios", "Pagaste", "Habitual", "vs habitual", "1 pedido(s) anteriores no tienen historial de precios",
        ).forEach { assertTrue(it in html, "missing: $it") }
        assertFalse("saved" in html || "order(s)" in html || "Lowest" in html, "no English left behind")
    }

    @Test
    fun `no orders is an explanation, not an empty table`() {
        val html = render()

        assertTrue("No orders read yet" in html)
        assertFalse("<table" in html)
        assertTrue("/groceries/orders" in html, "the strip is still there to leave by")
    }
}
