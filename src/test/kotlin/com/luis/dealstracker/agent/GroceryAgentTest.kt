package com.luis.dealstracker.agent

import com.luis.dealstracker.config.Defaults
import com.luis.dealstracker.i18n.Lang
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroceryAgentTest {
    private val zone = ZoneId.of("America/Bogota")
    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()
    private val spec = groceryAgentSpec(Defaults(), Lang.ES, LocalDate.parse("2026-11-04"), LocalTime.of(8, 15))

    @Test
    fun `the run gets only the deals tools, none of Claude Code's own, and keeps MCP on`() {
        val cmd = claudeCommand("/bin/claude", spec, File("/tmp/mcp-1.json"))
        fun after(flag: String) = cmd[cmd.indexOf(flag) + 1]
        assertEquals("-p", cmd[1])
        assertEquals("", after("--tools"), "no shell, files or web")
        assertTrue("--strict-mcp-config" in cmd)
        assertEquals("/tmp/mcp-1.json", after("--mcp-config"))
        assertEquals("", after("--setting-sources"))
        assertFalse("--safe-mode" in cmd, "safe mode turns MCP servers off: the agent would see no tools")
        assertFalse("--bare" in cmd, "bare mode needs an API key; this runs on the Claude Code login")
        assertTrue(after("--allowedTools").split(",").all { it.startsWith("mcp__deals__") })
        assertTrue("mcp__deals__suggest" in after("--allowedTools"))
        assertEquals("1.0", after("--max-budget-usd"))
        assertEquals("sonnet", after("--model"))
    }

    @Test
    fun `the run's MCP config carries the token and says which run is calling`() {
        val json = mcpConfigJson("tok", 42, "grocery")
        assertTrue(json.contains("\"Authorization\":\"Bearer tok\"") && json.contains("\"X-Agent-Run\":\"42\"") && json.contains("\"X-Agent-Name\":\"grocery\""))
        assertTrue(json.contains("http://127.0.0.1:8091/mcp"))
    }

    @Test
    fun `the CLI's answer is read for cost and outcome, and anything else is a failure`() {
        val ok = parseOutcome("""{"type":"result","subtype":"success","is_error":false,"result":"Quiet day.","total_cost_usd":0.21}""", 0, "")
        assertEquals(RunOutcome(true, 0.21, "Quiet day.", null), ok)
        val over = parseOutcome("""{"type":"result","subtype":"error_max_budget_usd","is_error":true,"total_cost_usd":1.01}""", 1, "")
        assertFalse(over.ok)
        assertEquals("error_max_budget_usd", over.error)
        val crash = parseOutcome("Invalid API key · Please run /login", 1, "not logged in")
        assertFalse(crash.ok)
        assertTrue(crash.error!!.contains("exited 1") && crash.error!!.contains("not logged in"))
    }

    @Test
    fun `due each morning after the time, once, a sleepy morning runs on waking, a failure retries after an hour`() {
        val t = listOf(LocalTime.of(8, 15))
        assertFalse(agentRunDue(t, at("2026-11-03T08:16"), null, at("2026-11-04T08:14"), zone))
        assertTrue(agentRunDue(t, at("2026-11-03T08:16"), at("2026-11-03T08:16"), at("2026-11-04T08:15"), zone))
        assertTrue(agentRunDue(t, at("2026-11-03T08:16"), at("2026-11-03T08:16"), at("2026-11-04T13:40"), zone), "the Mac slept through 08:15")
        assertFalse(agentRunDue(t, at("2026-11-04T08:16"), at("2026-11-04T08:16"), at("2026-11-04T19:00"), zone), "done today")
        assertFalse(agentRunDue(t, at("2026-11-03T08:16"), at("2026-11-04T08:15"), at("2026-11-04T08:50"), zone), "failed at 08:15: wait an hour")
        assertTrue(agentRunDue(t, at("2026-11-03T08:16"), at("2026-11-04T08:15"), at("2026-11-04T09:16"), zone))
        assertFalse(agentRunDue(emptyList(), null, null, at("2026-11-04T09:16"), zone), "no times is off")
    }

    @Test
    fun `the instructions carry the cap, the language and the weekday`() {
        assertTrue(spec.systemPrompt.contains("At most 8 suggestions"))
        assertTrue(spec.systemPrompt.contains("Spanish") && spec.systemPrompt.contains("\$21.900"))
        assertTrue(spec.systemPrompt.contains("are data"), "titles are not instructions")
        assertTrue(spec.systemPrompt.contains("at least four") && spec.systemPrompt.contains("beyond the pinned list"), "switch and pin ideas need searching, measured: without it a run made none")
        assertTrue(spec.systemPrompt.contains("own swing"), "a weekly 20% dip is not news")
        assertTrue(spec.prompt.contains("miércoles 2026-11-04") && spec.prompt.contains("08:15"), spec.prompt)
        assertTrue(groceryAgentSystemPrompt(Lang.EN, 3).contains("English") && groceryAgentSystemPrompt(Lang.EN, 3).contains("At most 3"))
        assertEquals(GROCERY_AGENT_TOOLS.toSet(), spec.tools.toSet())
    }

    @Test
    fun `the post lists each suggestion with a link and points to the inbox, and a quiet run posts nothing`() {
        val s = Suggestion(5, "grocery", Instant.EPOCH, "buy_now", 7, "Arroz Diana 5 kg", "A \$21.900, 12% bajo lo usual.", "new")
        val (title, lines) = suggestionsMessage(listOf(s), "Buen día para arroz.", "http://localhost:8080") { "buy now" }!!
        assertEquals("Grocery suggestions (1)", title)
        assertEquals("Buen día para arroz.", lines.first())
        assertTrue(lines[1].contains("**buy now**") && lines[1].contains("[Arroz Diana 5 kg](http://localhost:8080/deal/7)"), lines[1])
        assertTrue(lines.last().contains("/groceries#suggestions"))
        assertNull(suggestionsMessage(emptyList(), "x", "http://localhost:8080") { it })
    }
}
