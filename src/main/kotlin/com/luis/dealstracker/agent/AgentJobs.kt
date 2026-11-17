package com.luis.dealstracker.agent

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.web.suggestionKindLabel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The grocery agent on its schedule (checked on the 5-minute tick) and on the Config tab's Run now.
 * After a good run, its suggestions go out as a desktop banner and a Discord post; a scheduled run that
 * fails alerts once, and is retried hourly.
 */
class AgentJobs(
    private val runner: AgentRunner,
    private val runs: AgentRunRepository,
    private val live: () -> AppConfig,
    private val lang: () -> com.luis.dealstracker.i18n.Lang,
    /** (title, body lines) to the desktop and Discord. */
    private val notify: (String, List<String>) -> Unit,
    private val baseUrl: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The order agent's carts; null leaves it off (a test, or a copy without the MCP server). */
    private val orders: OrderRepository? = null,
    /** (title, body lines) for a cart that is ready, which opens Pinned rather than the suggestions. */
    private val notifyOrder: (String, List<String>) -> Unit = notify,
    /** Where a phone reaches Pinned (the tunnel), when known; [baseUrl] otherwise. */
    private val publicUrl: () -> String? = { null },
    /** Run before each order run: refreshes the account's Exito orders, so last night's order is seen this morning. */
    private val beforeOrderRun: () -> Unit = {},
) {
    val running: Boolean get() = runner.running

    fun groceryDue(now: Instant = Instant.now()): Boolean {
        val times = live().defaults.groceryAgentTimes.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        return agentRunDue(times, runs.lastStart(GROCERY_AGENT, okOnly = true), runs.lastStart(GROCERY_AGENT, okOnly = false), now, zone)
    }

    fun orderDue(now: Instant = Instant.now()): Boolean {
        if (orders == null) return false
        val times = live().defaults.orderAgentTimes.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        return orderRunDue(times, runs.lastStart(ORDER_AGENT, okOnly = true), runs.lastStart(ORDER_AGENT, okOnly = false), now, zone)
    }

    /** The scheduled check, both agents in turn (they share one runner). Returns a log line when one ran. */
    fun tick(now: Instant = Instant.now()): String? = listOfNotNull(
        if (groceryDue(now)) runGrocery("schedule", now) else null,
        if (orderDue(now)) runOrder("schedule", now) else null,
    ).joinToString("\n").ifEmpty { null }

    /** Blocking; one run at a time. Fills today's cart, or explains why there is none. */
    fun runOrder(trigger: String, now: Instant = Instant.now(), store: com.luis.dealstracker.exito.CartStore? = null): String {
        val repo = orders ?: return "[agent] order: not set up"
        if (runner.running) return "[agent] order: another run is in progress"
        runCatching(beforeOrderRun).onFailure { println("[agent] order: could not refresh Exito orders first: ${it.message}") }
        val wasFailing = runs.recent(ORDER_AGENT, 1).firstOrNull()?.status == "failed"
        val local = now.atZone(zone)
        val l = lang()
        val spec = orderAgentSpec(live().defaults, l, local.toLocalDate(), local.toLocalTime(), store)
        val (id, outcome) = runner.run(spec, trigger) ?: return "[agent] order: another run is in progress"
        return I18n.with(l) {
            val order = repo.forRun(id)
            val items = order?.let { repo.items(it.id) }.orEmpty()
            if (order != null && items.isNotEmpty()) {
                val (title, lines) = orderReadyMessage(order, items, outcome.text, publicUrl() ?: baseUrl)
                notifyOrder(title, lines)
            } else if (!outcome.ok && !wasFailing && trigger == "schedule") {
                notify(tr("Order agent failed", "Falló el agente de pedidos"), listOf(outcome.error ?: "", tr("It retries hourly. See Groceries → Pinned.", "Reintenta cada hora. Mira Mercado → Fijados.")))
            }
            val cost = outcome.costUsd?.let { "%.3f".format(it) } ?: "?"
            if (outcome.ok) "[agent] order run $id: ${if (items.isEmpty()) "no cart" else "${items.size} item(s) at ${order!!.store.label}"}, US$$cost"
            else "[agent] order run $id failed: ${outcome.error}" + if (items.isNotEmpty()) " (after adding ${items.size} item(s))" else ""
        }
    }

    /** Blocking; one run at a time. */
    fun runGrocery(trigger: String, now: Instant = Instant.now()): String {
        val wasFailing = runs.recent(GROCERY_AGENT, 1).firstOrNull()?.status == "failed"
        val local = now.atZone(zone)
        val l = lang()
        val spec = groceryAgentSpec(live().defaults, l, local.toLocalDate(), local.toLocalTime())
        val (id, outcome) = runner.run(spec, trigger) ?: return "[agent] grocery: another run is in progress"
        return I18n.with(l) {
            if (outcome.ok) {
                val made = runs.suggestionsOf(id)
                suggestionsMessage(made, outcome.text, baseUrl, ::suggestionKindLabel)?.let { (title, lines) -> notify(title, lines) }
                "[agent] grocery run $id: ${made.size} suggestion(s), US$${outcome.costUsd?.let { "%.3f".format(it) } ?: "?"}"
            } else {
                // Once per breakage: a run that keeps failing hourly does not post every hour.
                if (!wasFailing && trigger == "schedule") {
                    notify(tr("Grocery agent failed", "Falló el agente de mercado"), listOf(outcome.error ?: "", tr("It retries hourly. See Config → Agents.", "Reintenta cada hora. Mira Configuración → Agentes.")))
                }
                "[agent] grocery run $id failed: ${outcome.error}"
            }
        }
    }
}
