package com.luis.dealstracker.web

import com.luis.dealstracker.agent.AgentOrder
import com.luis.dealstracker.agent.AgentOrderItem
import com.luis.dealstracker.agent.OrderRun
import com.luis.dealstracker.exito.CartStore
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.Lang
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderAgentViewsTest {
    private val zone = ZoneId.of("America/Bogota")
    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()
    private val now = at("2026-11-17T09:00:00")

    private val items = listOf(
        AgentOrderItem(11, "1", "Banano criollo", BigDecimal(5000), BigDecimal(6000), BigDecimal(3500), "Fruit day: 35% off with the Exito card."),
        AgentOrderItem(12, "2", "Leche entera 1L", BigDecimal(4000), BigDecimal(4400), null, "Bought every Tuesday; 9% under usual."),
    )

    private fun run(
        id: Long = 9, started: String = "2026-11-17T08:46:00", status: String = "ok", summary: String? = "Two things worth it today.",
        order: AgentOrder? = AgentOrder(3, id, at(started), CartStore.LAURELES, "store_card", "cart123"),
        items: List<AgentOrderItem> = this.items, feedback: String? = null, ordered: Boolean = false, error: String? = null,
    ) = OrderRun(id, at(started), status, summary, error, feedback, order, if (order == null) emptyList() else items, ordered)

    private fun html(runs: List<OrderRun>, running: Boolean = false, lang: Lang = Lang.EN) =
        I18n.with(lang) { createHTML().div { orderAgentPanel(OrderPanelModel(runs, running, at("2026-11-18T08:45:00"), now, zone)) } }

    @Test
    fun `today's cart shows the store, the card, the total, each item's reason and the link to open it`() {
        val page = html(listOf(run()))
        assertTrue(page.contains("Today's cart"))
        assertTrue(page.contains("Exito Laureles") && page.contains("Pay with Tarjeta Éxito"))
        assertTrue(page.contains("7,500") || page.contains("7.500"), "the cart's total paid with the card")
        assertTrue(page.contains("Fruit day: 35% off with the Exito card.") && page.contains("Bought every Tuesday"))
        assertTrue(page.contains("href=\"/order-agent/orders/3/open\""), "opens through the tracker, which sets pickup first")
        assertTrue(page.contains("href=\"/deal/11\""))
        assertTrue(page.contains("hx-post=\"/order-agent/runs/9/feedback\""))
    }

    @Test
    fun `a day with no cart says so, and why, and still takes feedback`() {
        val page = html(listOf(run(order = null, summary = "Nothing you buy on Tuesdays is on promotion today.")))
        assertTrue(page.contains("No cart today"))
        assertTrue(page.contains("Nothing you buy on Tuesdays is on promotion today."))
        assertFalse(page.contains("open-cart"))
        assertTrue(page.contains("oa-feedback"))
    }

    @Test
    fun `an ordered cart is marked and no longer offers to open it`() {
        val page = html(listOf(run(ordered = true)))
        assertTrue(page.contains("✓ ordered"))
        assertFalse(page.contains("/order-agent/orders/3/open"))
    }

    @Test
    fun `a failed run says so, and one that failed after adding still shows its cart`() {
        assertTrue(html(listOf(run(order = null, status = "failed", error = "timed out after 15 minutes"))).contains("The run failed: timed out"))
        val partial = html(listOf(run(status = "failed", error = "budget")))
        assertTrue(partial.contains("stopped early") && partial.contains("Banano criollo"))
    }

    @Test
    fun `saved feedback is shown in the box, and earlier runs list their outcome and note`() {
        val runs = listOf(
            run(feedback = "Good call on the fruit"),
            run(id = 8, started = "2026-11-16T08:46:00", feedback = "Never buy soda", ordered = true),
            run(id = 7, started = "2026-11-15T08:46:00", order = null),
        )
        val page = html(runs)
        assertTrue(page.contains(">Good call on the fruit</textarea>"))
        assertTrue(page.contains("Earlier runs (2)"))
        assertTrue(page.contains("“Never buy soda”") && page.contains("· ordered") && page.contains("no cart"))
    }

    @Test
    fun `before the first run it says when that will be, and while running Run now is disabled`() {
        val first = html(emptyList())
        assertTrue(first.contains("First run") && first.contains("08:45"))
        val busy = html(listOf(run()), running = true)
        assertTrue(busy.contains("disabled") && busy.contains("reload in a minute"))
    }

    @Test
    fun `in Spanish`() {
        val page = html(listOf(run()), lang = Lang.ES)
        assertTrue(page.contains("Carrito de hoy") && page.contains("Paga con Tarjeta Éxito") && page.contains("Abrir este carrito en Exito Laureles"))
        assertTrue(page.contains("¿Qué hizo bien o mal?"))
    }

    @Test
    fun `the saving counts what the card took off, never below zero`() {
        assertEquals(BigDecimal(2900), cartSaving(items))
        assertEquals(BigDecimal.ZERO, cartSaving(listOf(AgentOrderItem(1, "1", "x", BigDecimal(5000), BigDecimal(4000), null, "r"))))
    }

    @Test
    fun `the feedback fragment is the form alone, saying it was saved`() {
        val f = I18n.with(Lang.EN) { feedbackFragment(9, "Good") }
        assertTrue(f.startsWith("<form") && f.endsWith("</form>"), f)
        assertTrue(f.contains("Saved. The next run will read it."))
    }

    @Test
    fun `an item added with the cart button says so instead of a reason`() {
        val mine = AgentOrderItem(13, "3", "Limonada de té MR TEA coco", BigDecimal(3400), null, null, "", addedBy = com.luis.dealstracker.agent.OPERATOR_ADDED)
        val page = html(listOf(run(items = items + mine)))
        assertTrue(page.contains("Limonada de té MR TEA coco") && page.contains("added by you"))
        assertEquals(1, Regex("added by you").findAll(page).count(), "only the operator's line")
    }

    @Test
    fun `the agent's summary is shown as plain lines, without its Markdown marks`() {
        val text = "I built the cart.\n\n- **Fruit:** mango and papaya.\n- **Drinks:** Mr Tea.\n## Saving"
        assertEquals("I built the cart.\n\n• Fruit: mango and papaya.\n• Drinks: Mr Tea.\nSaving", plainSummary(text))
    }

    @Test
    fun `the explanation ends with how to pay and what the card saves`() {
        val page = html(listOf(run()))
        assertTrue(page.contains("oa-pay-note"))
        assertTrue(page.contains("Tarjeta Éxito") && (page.contains("1,500 off") || page.contains("1.500 off")), page)
        assertTrue(page.indexOf("oa-pay-note") > page.indexOf("oa-summary"), "after the explanation")
        val es = html(listOf(run()), lang = Lang.ES)
        assertTrue(es.contains("Paga con") && es.contains("le quita"))
        val cash = html(listOf(run(order = AgentOrder(3, 9, at("2026-11-17T08:46:00"), CartStore.LAURELES, "cash", "c"))))
        assertTrue(cash.contains("Pay any way you like"))
        assertEquals(BigDecimal(1500), cardSaving(items))
    }

    @Test
    fun `the panel is a disclosure, open by default, that remembers being closed for this run only`() {
        val page = html(listOf(run()))
        assertTrue(page.contains("<details class=\"order-agent\" id=\"order-agent\" open") && page.contains("data-run=\"9\""), page.take(300))
        assertTrue(page.contains("<summary class=\"oa-head\""))
        assertTrue(page.contains("oa-glance") && page.contains("Exito Laureles · "), "what a closed panel still says")
        assertTrue(page.contains("orderPanelClosedRun"))
        assertFalse(html(emptyList()).contains("oa-glance"), "no cart, nothing to glance at")
    }
}
