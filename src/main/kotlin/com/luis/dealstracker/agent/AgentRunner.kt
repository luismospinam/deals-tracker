package com.luis.dealstracker.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** What an agent is: its name (also its MCP caller name), its instructions, its task, the tools it may call. */
data class AgentSpec(
    val name: String,
    val systemPrompt: String,
    val prompt: String,
    val tools: List<String>,
    val model: String,
    val effort: String,
    val maxBudgetUsd: Double,
)

/** How a run ended, from the CLI's JSON result. */
data class RunOutcome(val ok: Boolean, val costUsd: Double?, val text: String?, val error: String?)

/**
 * The `claude -p` command for [spec]: the Claude Code login (no API key), only the deals MCP tools.
 *
 * Every isolation flag is deliberate, and one is deliberately missing:
 *  - `--strict-mcp-config` + `--mcp-config`: this server and no other the user has configured;
 *  - `--tools ""`: no built-in tool, so no shell, file or web access -- a product title cannot talk
 *    the model into running anything;
 *  - `--allowedTools`: the MCP tools it may call without a permission prompt (there is no one to answer one);
 *  - `--setting-sources ""`, `--disable-slash-commands`, `--system-prompt`: none of the user's settings,
 *    hooks, skills or Claude Code's own prompt;
 *  - NOT `--safe-mode`, which the Ask panel uses: it turns MCP servers off (measured 2026-11-03: the
 *    agent saw no tools at all). `--bare` would be tighter still but needs an API key.
 */
internal fun claudeCommand(executable: String, spec: AgentSpec, mcpConfig: File): List<String> = buildList {
    add(executable)
    add("-p")
    addAll(listOf("--model", spec.model))
    addAll(listOf("--effort", spec.effort))
    addAll(listOf("--system-prompt", spec.systemPrompt))
    addAll(listOf("--setting-sources", ""))
    add("--disable-slash-commands")
    add("--strict-mcp-config")
    addAll(listOf("--mcp-config", mcpConfig.absolutePath))
    addAll(listOf("--tools", ""))
    addAll(listOf("--allowedTools", spec.tools.joinToString(",") { "mcp__deals__$it" }))
    addAll(listOf("--max-budget-usd", spec.maxBudgetUsd.toString()))
    add("--no-session-persistence")
    addAll(listOf("--output-format", "json"))
}

/** The MCP config a run uses: the server, the token, and which run is calling (for `suggest`). */
internal fun mcpConfigJson(token: String, runId: Long, agent: String, url: String = "http://$MCP_HOST:$MCP_PORT/mcp"): String =
    buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject("deals") {
                put("type", "http")
                put("url", url)
                putJsonObject("headers") {
                    put("Authorization", "Bearer $token")
                    put("X-Agent-Run", runId.toString())
                    put("X-Agent-Name", agent)
                }
            }
        }
    }.toString()

private val json = Json { ignoreUnknownKeys = true }

/** Reads the CLI's `--output-format json` answer. Anything else (a crash, a login prompt) is a failure. */
internal fun parseOutcome(stdout: String, exitCode: Int, stderrTail: String): RunOutcome {
    val obj = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: return RunOutcome(false, null, null, "claude exited $exitCode" + stderrTail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
    fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
    val cost = (obj["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull
    val isError = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull == true || str("subtype").let { it != null && it != "success" }
    val text = str("result")
    return if (isError) RunOutcome(false, cost, text, text?.takeIf { it.isNotBlank() }?.take(300) ?: str("subtype") ?: "the run reported an error")
    else RunOutcome(true, cost, text, null)
}

/** One row of `agent_runs`. */
data class AgentRun(
    val id: Long, val agent: String, val trigger: String, val startedAt: Instant, val finishedAt: Instant?,
    val status: String, val costUsd: BigDecimal?, val error: String?, val summary: String?, val suggestions: Int = 0,
)

class AgentRunRepository(private val ds: DataSource) {
    fun start(agent: String, trigger: String): Long = ds.connection.use { conn ->
        conn.prepareStatement("INSERT INTO agent_runs (agent, trigger) VALUES (?, ?) RETURNING id").use { st ->
            st.setString(1, agent); st.setString(2, trigger)
            st.executeQuery().use { it.next(); it.getLong(1) }
        }
    }

    fun finish(id: Long, o: RunOutcome) = ds.connection.use { conn ->
        conn.prepareStatement("UPDATE agent_runs SET finished_at = now(), status = ?, cost_usd = ?, error = ?, summary = ? WHERE id = ?").use { st ->
            st.setString(1, if (o.ok) "ok" else "failed")
            if (o.costUsd != null) st.setBigDecimal(2, BigDecimal.valueOf(o.costUsd)) else st.setNull(2, java.sql.Types.NUMERIC)
            st.setString(3, o.error); st.setString(4, o.text?.take(4000)); st.setLong(5, id)
            st.executeUpdate()
        }
    }

    /** A run the app was stopped in the middle of stays "running" forever otherwise. */
    fun failAbandoned() = ds.connection.use { conn ->
        conn.createStatement().use { it.executeUpdate("UPDATE agent_runs SET status = 'failed', finished_at = now(), error = 'the app stopped during the run' WHERE status = 'running'") }
    }

    /** When [agent] last started a run, or a successful one with [okOnly]. */
    fun lastStart(agent: String, okOnly: Boolean): Instant? = ds.connection.use { conn ->
        conn.prepareStatement("SELECT max(started_at) FROM agent_runs WHERE agent = ?" + if (okOnly) " AND status = 'ok'" else "").use { st ->
            st.setString(1, agent)
            st.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
        }
    }

    fun recent(agent: String, limit: Int = 8): List<AgentRun> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT r.*, (SELECT count(*) FROM suggestions s WHERE s.run_id = r.id) AS n
              FROM agent_runs r WHERE r.agent = ? ORDER BY r.started_at DESC LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, agent); st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AgentRun(
                                rs.getLong("id"), rs.getString("agent"), rs.getString("trigger"), rs.getTimestamp("started_at").toInstant(),
                                rs.getTimestamp("finished_at")?.toInstant(), rs.getString("status"), rs.getBigDecimal("cost_usd"),
                                rs.getString("error"), rs.getString("summary"), rs.getInt("n"),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun suggestionsOf(runId: Long): List<Suggestion> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT id, agent, created_at, kind, deal_id, title, reason, status, note FROM suggestions WHERE run_id = ? ORDER BY id").use { st ->
            st.setLong(1, runId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Suggestion(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getLong(5).takeIf { !rs.wasNull() }, rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)))
                    }
                }
            }
        }
    }
}

/**
 * Runs an agent once: records the run, writes its MCP config (token inside, owner-only, deleted after),
 * runs `claude -p`, and records how it ended. One run at a time across all agents.
 *
 * It runs in [workDir], outside the repo, so Claude Code finds no CLAUDE.md there (the repo's would
 * brief the agent on developing this app) and files nothing under this repo's session history.
 */
class AgentRunner(
    private val executable: String,
    private val workDir: File,
    private val token: () -> String,
    private val runs: AgentRunRepository,
    private val timeoutMinutes: Long = 15,
) {
    private val busy = AtomicBoolean(false)
    val running: Boolean get() = busy.get()

    /** Blocking. Null when another run is in progress. */
    fun run(spec: AgentSpec, trigger: String): Pair<Long, RunOutcome>? {
        if (!busy.compareAndSet(false, true)) return null
        try {
            val id = runs.start(spec.name, trigger)
            val outcome = try {
                execute(spec, id)
            } catch (e: Exception) {
                RunOutcome(false, null, null, "${e::class.simpleName}: ${e.message?.take(200)}")
            }
            runs.finish(id, outcome)
            return id to outcome
        } finally {
            busy.set(false)
        }
    }

    private fun execute(spec: AgentSpec, runId: Long): RunOutcome {
        workDir.mkdirs()
        val config = File(workDir, "mcp-$runId.json")
        config.writeText(mcpConfigJson(token(), runId, spec.name))
        runCatching { Files.setPosixFilePermissions(config.toPath(), PosixFilePermissions.fromString("rw-------")) }
        try {
            val process = ProcessBuilder(claudeCommand(executable, spec, config)).directory(workDir).start()
            // The task goes over stdin, never argv, like the Ask panel's question.
            process.outputStream.bufferedWriter().use { it.write(spec.prompt) }
            val stderr = StringBuilder()
            val errThread = Thread { process.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } } }
                .apply { isDaemon = true; start() }
            val stdout = StringBuilder()
            val outThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return RunOutcome(false, null, null, "timed out after $timeoutMinutes minutes")
            }
            outThread.join(2_000); errThread.join(1_000)
            val tail = synchronized(stderr) { stderr.toString() }.trim().lines().takeLast(3).joinToString(" ")
            return parseOutcome(stdout.toString(), process.exitValue(), tail)
        } finally {
            config.delete()
        }
    }
}
