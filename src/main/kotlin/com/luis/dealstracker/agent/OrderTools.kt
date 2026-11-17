package com.luis.dealstracker.agent

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.GroceryFilter
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.exito.CartResult
import com.luis.dealstracker.exito.CartStore
import com.luis.dealstracker.exito.ExitoCart
import com.luis.dealstracker.exito.cookieExpired
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.HeldCards
import com.luis.dealstracker.web.groceryStores
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.sql.DataSource

/** Ids as the model sends them: "123, 456" or "123 456", deal ids or Exito product ids. */
internal fun parseIds(raw: String): List<String> =
    raw.split(',', ' ', ';', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()

/** The cart's running total, each line at what it costs paid as the cart is paid. */
internal fun cartTotal(items: List<AgentOrderItem>): BigDecimal =
    items.fold(BigDecimal.ZERO) { s, i -> s + (i.cardPrice ?: i.price ?: BigDecimal.ZERO) }

/**
 * The order agent's tools: what to judge today's cart by, and the two that fill it. The cart is filled
 * through [ExitoCart.addTo] on a cart of its own, never the 🛒 button's.
 */
/** How many of the order agent's own past runs a run reads, with their notes and outcomes. */
internal const val RUNS_READ = 20

class OrderTools(
    private val ds: DataSource,
    private val groceries: GroceryRepository,
    private val orders: OrderRepository,
    private val cart: ExitoCart,
    private val app: () -> AppConfig,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val held: () -> Set<CardScope> = { HeldCards.current },
    private val now: () -> Instant = Instant::now,
) {
    /** Held cards that Exito takes. With none picked on the Config tab, every Exito card counts. */
    private fun exitoCards(): Set<CardScope> = held().intersect(EXITO_CARDS).ifEmpty { EXITO_CARDS }

    /** Each Exito's grocery provider, from config. */
    private fun storeSources(): Map<CartStore, String> {
        val grocery = groceryStores(app()).keys
        return CartStore.entries.mapNotNull { s -> s.sources.firstOrNull { it in grocery }?.let { s to it } }.toMap()
    }

    /** [productIds] priced at both Exitos this morning. */
    internal fun quotes(productIds: Collection<String>): List<ProductQuotes> {
        val bySource = storeSources()
        val storeOf = bySource.entries.associate { (store, source) -> source to store }
        val ids = orders.copies(productIds, bySource.values)
        if (ids.isEmpty()) return emptyList()
        val rows = groceries.rows(GroceryFilter(bySource.values.toList(), ids = ids))
        val ends = orders.promoEnds(ids)
        return rows.groupBy { it.deal.sourceDealId }.map { (pid, copies) ->
            ProductQuotes(
                pid, copies.first().deal.title,
                copies.mapNotNull { r ->
                    storeOf[r.deal.source]?.let { s -> s to StoreQuote(s, r.deal.id, r.deal.price, r.usual, r.onShelf, r.cardOffers, ends[r.deal.id]) }
                }.toMap(),
            )
        }
    }

    /** The model may name a product by a deal id from any tool, or by its Exito product id. */
    private fun resolve(raw: List<String>): List<String> {
        val numeric = raw.mapNotNull { it.toLongOrNull() }
        val asDeals = orders.productIds(numeric)
        return raw.map { r -> r.toLongOrNull()?.let { asDeals[it] } ?: r }.distinct()
    }

    private fun purchases(days: Int): List<Purchase> = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT deal_id, source, product_id, title, quantity, unit_price, currency, bought_at FROM ($PURCHASES_AND_ORDERS) p " +
                "WHERE bought_at >= now() - make_interval(days => ?) ORDER BY bought_at",
        ).use { st ->
            st.setInt(1, days)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Purchase(
                                rs.getLong(1).takeIf { !rs.wasNull() }, rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getBigDecimal(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun requireOrderRun(caller: McpCaller): Long {
        if (caller.agent != ORDER_AGENT || caller.runId == null) throw ToolError("only the order agent's scheduled or Run now runs can fill a cart")
        return caller.runId
    }

    fun tools(): List<McpTool> = listOf(
        McpTool(
            "order_context",
            "Start here. Today's date and the cards held, the order agent's recent runs with the operator's feedback and whether each cart was ordered, " +
                "what was bought on this weekday in past weeks, and today's candidates (bought on this weekday, or bought before or pinned and on promotion today) " +
                "priced at both Exitos. A store cell reads `#deal_id price usual U (-x%) card price ... to MM-DD`: -x% is under usual, card prices are only for held cards, " +
                "`to` is the last day of the store's promotion.",
            emptyList(),
        ) { _, caller -> context(caller) },
        McpTool(
            "exito_prices",
            "Products priced this morning at Exito Laureles and Exito Colombia, in order_context's columns. For substitutes found with grocery_search.",
            listOf(Param("ids", "string", "Deal ids or Exito product ids, comma-separated", required = true)),
        ) { a, _ ->
            val ps = quotes(resolve(parseIds(a.require("ids"))))
            val cards = exitoCards()
            buildString {
                appendLine(CANDIDATE_HEADER)
                ps.forEach { appendLine(candidateLine(it, "asked", null, null, cards)) }
                if (ps.isEmpty()) appendLine("(none of these is sold at either Exito)")
            }
        },
        McpTool(
            "basket_totals",
            "What a basket costs at each Exito, in cash and with each held card that has offers there, with the saving against usual prices and the items a store cannot sell today. " +
                "One cart is one store and one payment: use this to choose both before order_begin.",
            listOf(Param("ids", "string", "The products you mean to add: deal ids or Exito product ids, comma-separated", required = true)),
        ) { a, _ -> basketText(basketTotals(quotes(resolve(parseIds(a.require("ids")))), exitoCards())) },
        McpTool(
            "order_begin",
            "Start today's cart at one Exito, paid one way. Once per run, before any order_add. Do not call it on a day with nothing worth buying.",
            listOf(
                Param("store", "string", "Where it is picked up", required = true, enum = CartStore.entries.map { it.key }),
                Param("pay_with", "string", "The card the whole cart is paid with, or cash", required = true, enum = listOf("cash") + EXITO_CARDS.map { cardKey(it) }),
            ),
            writes = true,
        ) { a, caller -> begin(requireOrderRun(caller), a.require("store"), a.require("pay_with")) },
        McpTool(
            "order_add",
            "Put one unit of a product in today's cart, at the store order_begin chose. Answers with the cart so far, or why Exito would not add it.",
            listOf(
                Param("id", "string", "Deal id or Exito product id", required = true),
                Param("reason", "string", "One sentence with the numbers: price today, usual, the promotion or card, what it replaces", required = true),
            ),
            writes = true,
        ) { a, caller -> add(requireOrderRun(caller), a.require("id"), a.require("reason")) },
    )

    private fun context(caller: McpCaller): String {
        val today = LocalDate.now(zone)
        val cards = exitoCards()
        val bought = purchases(180)
        val habits = weekdayHabits(bought, today.dayOfWeek, today, zone).associateBy { it.productId }
        val lastBought = bought.groupBy { it.productId }.mapValues { (_, ps) -> ps.maxOf { it.at }.atZone(zone).toLocalDate() }
        val pinned = groceries.rows(GroceryFilter(storeSources().values.toList(), pinnedOnly = true)).map { it.deal.sourceDealId }.toSet()
        val products = quotes(habits.keys + lastBought.keys + pinned).filterNot { isServiceLine(it.productId, it.title) }
        val listed = products.mapNotNull { p ->
            val because = when {
                p.productId in habits -> "bought on this weekday"
                !p.onPromo(cards) -> null
                p.productId in lastBought -> "bought before, promo today"
                else -> "pinned, promo today"
            } ?: return@mapNotNull null
            Triple(p, because, habits[p.productId])
        }.sortedWith(
            compareByDescending<Triple<ProductQuotes, String, WeekdayHabit?>> { it.third?.times ?: 0 }
                .thenByDescending { t -> t.first.quotes.values.mapNotNull { it.underUsual }.maxOrNull() ?: BigDecimal.ZERO },
        )
        val dayName = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return buildString {
            appendLine("Today: $dayName $today. Cards held that Exito takes: ${cards.joinToString { cardKey(it) }}. They pay before noon.")
            appendLine()
            appendLine("Your recent runs (newest first):")
            val runs = orders.recentRuns(limit = RUNS_READ, days = 90).filter { it.runId != caller.runId }
            if (runs.isEmpty()) appendLine("  (none yet: this is the first run)")
            runs.forEach { appendLine("  " + runLine(it)) }
            appendLine()
            // Every note ever saved, not just the recent runs': a lasting preference must not fade after 20 runs.
            appendLine("All the operator's notes, oldest first (a newer note overrides an older one where they disagree):")
            appendLine(notesText(orders.allNotes(), zone))
            appendLine()
            appendLine("Candidates (${listed.size}; bought on ${dayName}s counts the last 12 weeks):")
            appendLine(CANDIDATE_HEADER)
            listed.take(150).forEach { (p, because, habit) -> appendLine(candidateLine(p, because, habit, lastBought[p.productId], cards)) }
            if (listed.size > 150) appendLine("… 150 of ${listed.size} shown")
            if (listed.isEmpty()) appendLine("(none: nothing bought on this weekday, and nothing bought or pinned is on promotion at Exito today)")
        }
    }

    private fun runLine(r: OrderRun): String {
        val day = r.startedAt.atZone(zone).toLocalDate()
        val what = when {
            r.status != "ok" -> "failed: ${r.error?.take(120)}"
            r.order == null || r.items.isEmpty() -> "no cart: ${r.summary?.replace('\n', ' ')?.take(200).orEmpty()}"
            else -> "cart at ${r.order.store.key}, pay ${r.order.payWith}, ${r.items.size} items (${r.items.joinToString("; ") { it.title.take(40) + if (it.byOperator) " [added by operator]" else "" }}), " +
                if (!r.ordered) "not ordered"
                else orderChanges(r.items, r.orderedLines).let { (removed, added) ->
                    "ORDERED" +
                        (if (removed.isNotEmpty()) "; the operator removed before paying: ${removed.joinToString("; ") { it.take(40) }}" else "") +
                        (if (added.isNotEmpty()) "; the operator added on Exito's site: ${added.joinToString("; ") { it.take(40) }}" else "") +
                        (if (removed.isEmpty() && added.isEmpty() && r.orderedLines.isNotEmpty()) "; bought as proposed" else "")
                }
        }
        val note = r.feedback?.let { " | operator feedback: \"${it.replace('\n', ' ')}\"" } ?: " | no feedback"
        return "$day ${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}: $what$note"
    }

    private fun begin(runId: Long, storeKey: String, payKey: String): String {
        val store = CartStore.entries.firstOrNull { it.key.equals(storeKey.trim(), ignoreCase = true) }
            ?: throw ToolError("store must be one of ${CartStore.entries.joinToString { it.key }}")
        val card = cardOf(payKey).getOrElse { throw ToolError("pay_with must be cash or one of ${exitoCards().joinToString { cardKey(it) }}") }
        if (card != null && card !in exitoCards()) throw ToolError("they do not hold $payKey; use cash or one of ${exitoCards().joinToString { cardKey(it) }}")
        val token = cart.cookie()
        if (token == null || cookieExpired(token, now())) {
            throw ToolError("The Exito login is missing or expired, so no cart can be filled today. Do not call order_add; say so in your reply.")
        }
        val existing = orders.forRun(runId)
        if (existing != null && (existing.store != store || existing.payWith != cardKey(card))) {
            throw ToolError("this run's cart is already at ${existing.store.key}, paid with ${existing.payWith}; one cart per run")
        }
        val o = existing ?: orders.begin(runId, store, cardKey(card))
        return "Cart started at ${o.store.label}, paid with ${o.payWith}. Add each product with order_add."
    }

    private fun add(runId: Long, rawId: String, reason: String): String {
        val order = orders.forRun(runId) ?: throw ToolError("call order_begin first")
        if (reason.length < 15) throw ToolError("the reason must say why, with the numbers")
        val pid = resolve(parseIds(rawId)).singleOrNull() ?: throw ToolError("give one id")
        val already = orders.items(order.id)
        if (already.any { it.productId == pid }) throw ToolError("already in the cart: one of each product")
        val product = quotes(listOf(pid)).firstOrNull() ?: throw ToolError("no Exito sells product $rawId")
        val q = product.quotes[order.store] ?: throw ToolError("${order.store.label} does not sell ${product.title}")
        if (!q.onShelf) throw ToolError("${product.title} is off the shelf at ${order.store.label} today; pick a substitute or skip it")
        val card = cardOf(order.payWith).getOrNull()
        val result = runBlocking {
            cart.addTo(
                order.store,
                saved = orders.forRun(runId)?.cartId,
                others = cart.manualCartIds(),
                adopt = { orders.setCart(order.id, it) },
                productId = pid,
            )
        }
        return when (result) {
            is CartResult.Added -> {
                val item = AgentOrderItem(q.dealId, pid, result.title ?: product.title, q.price, q.usual, q.payPrice(card).takeIf { it < q.price }, reason.trim())
                orders.addItem(order.id, item)
                val all = already + item
                "Added ${item.title}. Cart: ${all.size} items, ${money(cartTotal(all))} paid with ${order.payWith}."
            }
            CartResult.SoldOut -> {
                groceries.markSoldOut(q.dealId)
                throw ToolError("${product.title} is sold out at ${order.store.label} right now; pick a substitute or skip it")
            }
            is CartResult.Refused -> throw ToolError("Exito did not add it: ${result.reason.ifBlank { "no reason given (out of stock?)" }}")
            CartResult.NotConnected, CartResult.Expired ->
                throw ToolError("The Exito login expired; stop adding and say so in your reply.")
            is CartResult.Failed -> throw ToolError("could not reach Exito (${result.reason}); you may try this product once more")
        }
    }

    private fun money(v: BigDecimal) = v.setScale(0, RoundingMode.HALF_UP).toPlainString()
}
