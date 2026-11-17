package com.luis.dealstracker.agent

import com.luis.dealstracker.config.Defaults
import com.luis.dealstracker.exito.CartStore
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.model.CardOffer
import com.luis.dealstracker.model.CardScope
import java.io.File
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderAgentTest {
    private val zone = ZoneId.of("America/Bogota")
    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()
    private fun bd(v: Int) = BigDecimal(v)

    private fun quote(store: CartStore, id: Long, price: Int, usual: Int?, onShelf: Boolean = true, vararg offers: Pair<CardScope, Int>) =
        StoreQuote(store, id, bd(price), usual?.let(::bd), onShelf, offers.map { (c, p) -> CardOffer(bd(p), BigDecimal("20"), c) })

    private fun product(pid: String, title: String, vararg qs: StoreQuote) = ProductQuotes(pid, title, qs.associateBy { it.store })

    private fun purchase(pid: String, at: Instant, title: String = "Banano") =
        Purchase(1L, "exito-grocery", pid, title, 1, bd(1000), "COP", at)

    // --- what counts as today's promotion ---------------------------------------------------------------

    @Test
    fun `a shelf price ten percent under usual is a promotion, nine is not`() {
        assertTrue(quote(CartStore.LAURELES, 1, 9000, 10000).onPromo(emptySet()))
        assertFalse(quote(CartStore.LAURELES, 1, 9100, 10000).onPromo(emptySet()))
        assertEquals(BigDecimal("10.0"), quote(CartStore.LAURELES, 1, 9000, 10000).underUsual)
    }

    @Test
    fun `a card price is a promotion only for a card they hold, and never off the shelf`() {
        val q = quote(CartStore.LAURELES, 1, 10000, 10000, true, CardScope.STORE_CARD to 6500)
        assertTrue(q.onPromo(setOf(CardScope.STORE_CARD)))
        assertFalse(q.onPromo(setOf(CardScope.MASTERCARD)), "a store-card price is not theirs without the store card")
        assertFalse(quote(CartStore.LAURELES, 1, 5000, 10000, onShelf = false).onPromo(emptySet()), "an item off the shelf cannot be bought")
    }

    @Test
    fun `paying with a card takes that card's price, and cash or another card the shelf price`() {
        val q = quote(CartStore.LAURELES, 1, 10000, 11000, true, CardScope.STORE_CARD to 6500, CardScope.MASTERCARD to 8000)
        assertEquals(bd(6500), q.payPrice(CardScope.STORE_CARD))
        assertEquals(bd(8000), q.payPrice(CardScope.MASTERCARD))
        assertEquals(bd(10000), q.payPrice(CardScope.VISA))
        assertEquals(bd(10000), q.payPrice(null))
    }

    // --- one store and one payment per cart -------------------------------------------------------------

    @Test
    fun `basket totals price every store with cash and each card that has an offer there, cheapest first`() {
        val products = listOf(
            product("1", "Banano", quote(CartStore.LAURELES, 11, 5000, 6000, true, CardScope.STORE_CARD to 3500), quote(CartStore.COLOMBIA, 21, 5000, 6000)),
            product("2", "Leche", quote(CartStore.LAURELES, 12, 4000, 4000), quote(CartStore.COLOMBIA, 22, 3500, 4000)),
        )
        val totals = basketTotals(products, setOf(CardScope.STORE_CARD, CardScope.MASTERCARD))
        assertEquals(listOf(CartStore.LAURELES to CardScope.STORE_CARD, CartStore.COLOMBIA to null, CartStore.LAURELES to null), totals.map { it.store to it.payWith })
        assertEquals(bd(7500), totals.first().total)
        assertEquals(bd(10000), totals.first().usual)
        assertTrue(totals.none { it.payWith == CardScope.MASTERCARD }, "no store has a Mastercard offer on these, so it is not a way to pay worth listing")
    }

    @Test
    fun `a store that cannot sell part of the basket ranks after one that sells all of it, whatever the price`() {
        val products = listOf(
            product("1", "Banano", quote(CartStore.LAURELES, 11, 5000, 6000), quote(CartStore.COLOMBIA, 21, 1000, 6000)),
            product("2", "Leche", quote(CartStore.LAURELES, 12, 4000, 4000), quote(CartStore.COLOMBIA, 22, 3500, 4000, onShelf = false)),
        )
        val totals = basketTotals(products, emptySet())
        assertEquals(CartStore.LAURELES, totals.first().store)
        assertEquals(listOf("Leche"), totals.last().missing)
        assertTrue(basketText(totals).contains("colombia\tcash\t1000\t1000\t6000\t5000\t1\tLeche"), basketText(totals))
    }

    @Test
    fun `the shared total compares stores on the items both sell, so a store missing items does not look cheaper`() {
        val products = listOf(
            product("1", "Banano", quote(CartStore.LAURELES, 11, 5000, 6000), quote(CartStore.COLOMBIA, 21, 5000, 6000)),
            product("2", "Queso", quote(CartStore.COLOMBIA, 22, 20000, 20000)), // only Colombia sells it
        )
        val byStore = basketTotals(products, emptySet()).associateBy { it.store }
        assertEquals(bd(5000), byStore.getValue(CartStore.LAURELES).total, "Laureles' total looks cheaper only because it lacks the cheese")
        assertEquals(bd(25000), byStore.getValue(CartStore.COLOMBIA).total)
        assertEquals(byStore.getValue(CartStore.LAURELES).sharedTotal, byStore.getValue(CartStore.COLOMBIA).sharedTotal)
        assertEquals(CartStore.COLOMBIA, basketTotals(products, emptySet()).first().store, "the store that sells all of it ranks first")
    }

    @Test
    fun `the instructions put variety before the Laureles preference when prices are close`() {
        val p = orderAgentSystemPrompt(Lang.EN)
        assertTrue(p.contains("shared_items_total") && p.indexOf("sells more of the wanted items") < p.indexOf("Laureles, which is closer"))
    }

    // --- the weekday habit ------------------------------------------------------------------------------

    @Test
    fun `habits count the days a product was bought on this weekday in the last twelve weeks`() {
        val today = LocalDate.parse("2026-11-17") // a Tuesday
        val ps = listOf(
            purchase("A", at("2026-11-10T20:49:00")), purchase("A", at("2026-11-10T21:00:00")), // one Tuesday, twice
            purchase("A", at("2026-11-03T20:00:00")),
            purchase("B", at("2026-11-13T19:00:00"), "Leche"), // a Friday
            purchase("C", at("2026-08-04T15:00:00"), "Queso"), // a Tuesday, 15 weeks back
            purchase("D", at("2026-11-17T07:00:00"), "Pan"), // today: not a past week
        )
        val h = weekdayHabits(ps, DayOfWeek.TUESDAY, today, zone)
        assertEquals(listOf("A"), h.map { it.productId })
        assertEquals(2, h.single().times)
        assertEquals(LocalDate.parse("2026-11-10"), h.single().last)
    }

    @Test
    fun `an evening order counts on its local day, not the UTC one`() {
        // 21:48 in Bogota on a Tuesday is 02:48 UTC on Wednesday.
        val h = weekdayHabits(listOf(purchase("A", at("2026-11-10T21:48:00"))), DayOfWeek.TUESDAY, LocalDate.parse("2026-11-17"), zone)
        assertEquals(1, h.size)
    }

    // --- what the model reads and sends ----------------------------------------------------------------

    @Test
    fun `a store cell shows price, usual, the held cards' prices and the promotion's last day`() {
        val q = quote(CartStore.LAURELES, 7, 9000, 10000, true, CardScope.STORE_CARD to 6000, CardScope.ADDI to 5000).copy(promoEnds = LocalDate.parse("2026-11-18"))
        assertEquals("#7 9000 usual 10000 (-10.0%) store_card 6000 to 11-18", quoteCell(q, setOf(CardScope.STORE_CARD)))
        assertEquals("#7 off shelf", quoteCell(q.copy(onShelf = false), emptySet()))
        assertEquals("—", quoteCell(null, emptySet()))
        assertEquals("#8 12000 usual 10000 (+20.0%)", quoteCell(quote(CartStore.COLOMBIA, 8, 12000, 10000), emptySet()))
    }

    @Test
    fun `ways to pay round-trip by name, and an unknown one is refused`() {
        assertNull(cardOf("cash").getOrThrow())
        assertNull(cardOf(null).getOrThrow())
        assertEquals(CardScope.STORE_CARD, cardOf("store_card").getOrThrow())
        assertEquals("store_card", cardKey(CardScope.STORE_CARD))
        assertTrue(cardOf("amex").isFailure)
    }

    @Test
    fun `ids arrive however the model writes them`() {
        assertEquals(listOf("123", "456", "789"), parseIds("#123, 456 789;123"))
    }

    @Test
    fun `the cart total counts each line at what it costs paid as the cart is`() {
        val items = listOf(
            AgentOrderItem(1, "1", "Banano", bd(5000), bd(6000), bd(3500), "r"),
            AgentOrderItem(2, "2", "Leche", bd(4000), bd(4000), null, "r"),
        )
        assertEquals(bd(7500), cartTotal(items))
    }

    // --- the run --------------------------------------------------------------------------------------

    @Test
    fun `the order run may fill a cart but not leave suggestions, and only through the deals tools`() {
        val spec = orderAgentSpec(Defaults(), Lang.ES, LocalDate.parse("2026-11-17"), LocalTime.of(8, 45))
        val cmd = claudeCommand("/bin/claude", spec, File("/tmp/mcp-1.json"))
        val allowed = cmd[cmd.indexOf("--allowedTools") + 1].split(",")
        assertTrue(allowed.all { it.startsWith("mcp__deals__") })
        assertTrue("mcp__deals__order_add" in allowed && "mcp__deals__order_begin" in allowed && "mcp__deals__order_context" in allowed)
        assertFalse("mcp__deals__suggest" in allowed, "suggestions are the grocery agent's")
        assertEquals("", cmd[cmd.indexOf("--tools") + 1])
        assertEquals(ORDER_AGENT, spec.name)
        assertTrue(spec.prompt.contains("martes"), spec.prompt)
        assertTrue(spec.systemPrompt.contains("Spanish"))
    }

    @Test
    fun `the scheduled run is due after its time, once a day, and never past noon`() {
        val times = listOf(LocalTime.of(8, 45))
        val yesterday = at("2026-11-16T08:46:00")
        assertFalse(orderRunDue(times, yesterday, yesterday, at("2026-11-17T08:30:00"), zone), "not before its time")
        assertTrue(orderRunDue(times, yesterday, yesterday, at("2026-11-17T08:50:00"), zone))
        assertFalse(orderRunDue(times, null, null, at("2026-11-17T14:00:00"), zone), "even a first run waits for a morning")
        assertFalse(orderRunDue(times, at("2026-11-17T08:46:00"), at("2026-11-17T08:46:00"), at("2026-11-17T10:00:00"), zone), "already ran today")
        assertFalse(orderRunDue(times, at("2026-11-16T08:46:00"), at("2026-11-16T08:46:00"), at("2026-11-17T13:00:00"), zone), "a morning slept through is skipped, not run late")
        assertTrue(orderRunDue(times, at("2026-11-16T08:46:00"), at("2026-11-16T08:46:00"), at("2026-11-17T11:30:00"), zone))
    }

    @Test
    fun `the cart-ready post names the store, total, card and links to Pinned`() {
        val order = AgentOrder(3, 9, Instant.EPOCH, CartStore.COLOMBIA, "store_card", "abc")
        val items = listOf(AgentOrderItem(1, "1", "Banano", bd(5000), bd(6000), bd(3500), "r"))
        val (title, lines) = I18n.with(Lang.EN) { orderReadyMessage(order, items, "Fruit is 35% off with the Exito card.", "https://x.trycloudflare.com") }
        assertTrue(title.contains("Exito Colombia") && title.contains("1 items"), title)
        assertTrue(lines.first().contains("Tarjeta Éxito"), lines.first())
        assertTrue(lines.any { it.contains("Fruit is 35% off") })
        assertTrue(lines.last().contains("https://x.trycloudflare.com/groceries?view=pinned#order-agent"))
    }

    @Test
    fun `the tunnel address is the last one cloudflared logged`() {
        val log = """
            2026-11-13 INF |  https://old-name-here.trycloudflare.com  |
            2026-11-17 INF Registered tunnel connection
            2026-11-17 INF |  https://fresh-words-abc.trycloudflare.com  |
        """.trimIndent()
        assertEquals("https://fresh-words-abc.trycloudflare.com", tunnelUrl(log))
        assertNull(tunnelUrl("no address yet"))
    }

    @Test
    fun `the order agent's tables are readable through sql, and app_settings still is not`() {
        assertTrue("agent_orders" in AGENT_READABLE_TABLES && "agent_order_items" in AGENT_READABLE_TABLES)
        assertFalse("app_settings" in AGENT_READABLE_TABLES)
    }

    @Test
    fun `a run started for one store tells the agent to use it, and a normal run lets it choose`() {
        val forced = orderAgentPrompt(LocalDate.parse("2026-11-17"), LocalTime.of(15, 0), Lang.EN, CartStore.LAURELES)
        assertTrue(forced.contains("Exito Laureles") && forced.contains("store \"laureles\""), forced)
        assertFalse(orderAgentPrompt(LocalDate.parse("2026-11-17"), LocalTime.of(8, 45), Lang.EN).contains("asked for this cart"))
    }

    @Test
    fun `an order placed from an agent cart tells what the operator removed and added, never the service fee`() {
        val items = listOf(
            AgentOrderItem(1, "A", "Mango tarrina", bd(4000), null, null, "r"),
            AgentOrderItem(2, "B", "Kiwi malla", bd(12000), null, null, "r"),
        )
        val ordered = listOf("A" to "Mango tarrina", "C" to "MangoPiña Tarrina 300 gr", "492664" to "Preparación Del Pedido  0000")
        assertEquals(listOf("Kiwi malla") to listOf("MangoPiña Tarrina 300 gr"), orderChanges(items, ordered))
        assertEquals(emptyList<String>() to emptyList<String>(), orderChanges(items.take(1), ordered.take(1)))
    }

    @Test
    fun `Exito's preparation fee is a service line, not a product`() {
        assertTrue(isServiceLine("492664", "x"))
        assertTrue(isServiceLine("1", "Preparación Del Pedido  0000"))
        assertFalse(isServiceLine("868989", "PINA PORCIONADA TARRINA 300 gr"))
        assertEquals(20, RUNS_READ)
    }

    @Test
    fun `every note is sent, oldest first, and only the oldest are cut when they grow too long`() {
        val notes = listOf(
            OrderNote(at("2026-11-24T08:50:00"), null, "never buy soda"),
            OrderNote(at("2026-11-17T09:00:00"), CartStore.LAURELES, "mango is my main\nfruit"),
        )
        assertEquals("2026-11-17 (laureles cart): mango is my main fruit\n2026-11-24 (no cart): never buy soda", notesText(notes, zone))
        val cut = notesText(notes, zone, maxChars = 40)
        assertTrue(cut.startsWith("(1 oldest notes left out") && cut.endsWith("never buy soda"), cut)
        assertEquals("(no notes yet)", notesText(emptyList(), zone))
    }
}
