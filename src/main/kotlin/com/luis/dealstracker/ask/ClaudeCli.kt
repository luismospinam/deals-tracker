package com.luis.dealstracker.ask

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs one question through the local `claude` CLI and streams the answer back.
 *
 * ## Why the CLI rather than the API
 *
 * There is no API key on this machine, only the Claude Code login. `claude -p` runs a single turn
 * non-interactively on that login, so the panel costs plan usage rather than a separate bill — and
 * it is the only route available at all.
 *
 * ## Why every flag is here
 *
 * Each one was measured or is a guard, not decoration:
 *
 *  - **`--safe-mode` + `--system-prompt`** replace Claude Code's own prompt, CLAUDE.md, memory,
 *    skills and plugins. The default prompt alone was 8.5K tokens on a one-word answer — more than
 *    the 139 rows of a real watch. With both, the fixed overhead is ~350 tokens.
 *  - **`--tools ""`** removes every tool. The model reads the rows it is given and nothing else: it
 *    cannot run a command, read a file or fetch a page, so a product title cannot talk it into
 *    doing any of those.
 *  - **`--max-budget-usd`** is the runaway guard. A typical answer is 2-4 cents.
 *  - **`--session-id` / `--resume`** make a follow-up cheap: the rows are already in the session,
 *    so a second question sends only itself. The id is chosen here rather than read back from the
 *    output, so the route can hand it to the browser before the first word has arrived.
 *
 * It runs in [workDir], an empty directory under `build/`, so the session files it leaves behind
 * are filed under that directory in `~/.claude/projects` rather than mixed into this repo's own
 * Claude Code history.
 */
class ClaudeCli(
    private val executable: String,
    private val workDir: File,
    private val model: String,
    private val effort: String,
    private val maxBudgetUsd: Double,
    private val systemPrompt: String,
    private val timeoutSeconds: Long = 180,
) {
    data class Outcome(
        val costUsd: Double?,
        val durationMs: Long,
        /** Null on success. Worded for the panel, since that is where it is shown. */
        val error: String?,
        /** Whether any answer text reached [run]'s callback — an error after text is partial. */
        val streamedText: Boolean,
    )

    /**
     * Blocking. Calls [onText] with each fragment of the answer as it is generated.
     *
     * [resume] continues [sessionId]; otherwise a new session is created under that id.
     */
    fun run(prompt: String, sessionId: String, resume: Boolean, onText: (String) -> Unit): Outcome {
        require(UUID_RE.matches(sessionId)) { "not a session id: $sessionId" }
        workDir.mkdirs()
        val started = System.currentTimeMillis()

        val cmd = buildList {
            add(executable)
            add("-p")
            addAll(listOf("--model", model))
            addAll(listOf("--effort", effort))
            addAll(listOf("--tools", ""))
            add("--safe-mode")
            addAll(listOf("--system-prompt", systemPrompt))
            addAll(listOf("--output-format", "stream-json"))
            // Both required for token-level streaming: without partial messages the answer arrives
            // in one piece at the end, and stream-json output refuses to run without --verbose.
            add("--include-partial-messages")
            add("--verbose")
            addAll(listOf("--max-budget-usd", maxBudgetUsd.toString()))
            if (resume) addAll(listOf("--resume", sessionId)) else addAll(listOf("--session-id", sessionId))
        }

        val process = try {
            ProcessBuilder(cmd)
                .directory(workDir)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        } catch (e: Exception) {
            return Outcome(null, 0, "could not start `$executable`: ${e.message}", streamedText = false)
        }

        // The question goes over stdin, never argv: a title or a question containing quotes or a
        // leading dash must not be able to become a flag.
        process.outputStream.bufferedWriter().use { it.write(prompt) }

        // Drained concurrently, or a chatty stderr fills its pipe and the process blocks forever.
        val stderr = StringBuilder()
        val errThread = Thread {
            process.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
        }.apply { isDaemon = true; start() }

        // A hung CLI must not hold the HTTP request open indefinitely.
        val watchdog = Thread {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
        }.apply { isDaemon = true; start() }

        var streamed = false
        var cost: Double? = null
        var resultError: String? = null
        var resultText: String? = null

        process.inputStream.bufferedReader().forEachLine { line ->
            val event = parse(line) ?: return@forEachLine
            when (event.str("type")) {
                "stream_event" -> {
                    val inner = event["event"] as? JsonObject ?: return@forEachLine
                    if (inner.str("type") != "content_block_delta") return@forEachLine
                    val delta = inner["delta"] as? JsonObject ?: return@forEachLine
                    // Thinking deltas are skipped: they are not the answer, and at `low` effort
                    // they are usually empty anyway.
                    if (delta.str("type") == "text_delta") {
                        val text = delta.str("text").orEmpty()
                        if (text.isNotEmpty()) {
                            streamed = true
                            onText(text)
                        }
                    }
                }
                "result" -> {
                    cost = event["total_cost_usd"]?.jsonPrimitive?.doubleOrNull
                    resultText = event.str("result")
                    val isError = event["is_error"]?.jsonPrimitive?.booleanOrNull == true
                    if (isError || event.str("subtype") != "success") {
                        resultError = resultText?.takeIf { it.isNotBlank() }
                            ?: event.str("subtype")
                            ?: "the CLI reported an error"
                    }
                }
            }
        }

        process.waitFor()
        errThread.join(1_000)
        watchdog.interrupt()
        val elapsed = System.currentTimeMillis() - started

        // An answer that never streamed but did complete (older CLI, or a one-shot reply) is still
        // an answer: surface the final text rather than an empty bubble.
        if (!streamed && resultError == null && !resultText.isNullOrBlank()) {
            onText(resultText!!)
            streamed = true
        }

        val error = resultError ?: when {
            process.exitValue() != 0 && !streamed -> {
                val tail = synchronized(stderr) { stderr.toString() }.trim().lines().takeLast(3).joinToString(" ")
                if (elapsed >= timeoutSeconds * 1000) "timed out after ${timeoutSeconds}s"
                else "claude exited ${process.exitValue()}" + if (tail.isNotBlank()) ": $tail" else ""
            }
            else -> null
        }
        return Outcome(cost, elapsed, error, streamed)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        private fun parse(line: String): JsonObject? =
            if (line.isBlank()) null else runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()

        private fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * Where `claude` lives. Configured path first; then PATH; then the installer's default.
         *
         * The last fallback is the one that matters in practice: launchd starts the app with a bare
         * PATH that does not include `~/.local/bin`, so an app that found `claude` fine from a
         * terminal would fail every question once it ran in the background.
         */
        fun resolveExecutable(configured: String): String {
            if (configured.isNotBlank()) return configured
            val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "claude") }
                .firstOrNull { it.canExecute() }
            if (onPath != null) return onPath.absolutePath
            val installed = File(System.getProperty("user.home"), ".local/bin/claude")
            return if (installed.canExecute()) installed.absolutePath else "claude"
        }
    }
}
