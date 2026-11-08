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
) {
    val running: Boolean get() = runner.running

    fun groceryDue(now: Instant = Instant.now()): Boolean {
        val times = live().defaults.groceryAgentTimes.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        return agentRunDue(times, runs.lastStart(GROCERY_AGENT, okOnly = true), runs.lastStart(GROCERY_AGENT, okOnly = false), now, zone)
    }

    /** The scheduled check. Returns a log line when it ran. */
    fun tick(now: Instant = Instant.now()): String? = if (groceryDue(now)) runGrocery("schedule", now) else null

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
