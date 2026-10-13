package com.luis.dealstracker.ask

import kotlinx.coroutines.sync.Semaphore
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Ask panel's conversations, and the gate in front of the CLI.
 *
 * ## Conversations live in memory
 *
 * A conversation is a `claude` session id plus the screen it is about. Nothing is written to
 * Postgres: an answer is advice about a moment's prices, worth nothing a day later, and a restart
 * losing the thread costs one re-asked question. The CLI keeps its own transcript on disk anyway.
 *
 * ## A follow-up must be about the same screen
 *
 * A session holds the rows it was first given. If the operator changes a filter and asks again,
 * resuming would answer about the *old* rows while the page shows new ones — so a follow-up whose
 * [screenKey] differs starts a fresh session instead. That costs one full prompt, which is the
 * price of the answer matching the page.
 */
class AskService(
    private val cli: ClaudeCli,
    val maxRows: Int,
    /** Concurrent CLI processes. Each is a Node process and a slice of the plan's rate limit. */
    maxConcurrent: Int = 2,
    /** Rows (from the top of the screen's order) that get a price history. 0 turns it off. */
    val historyRows: Int = 100,
) {
    data class Conversation(val sessionId: String, val screenKey: String, val lastUsed: Instant, val turns: Int)

    private val conversations = ConcurrentHashMap<String, Conversation>()
    val gate = Semaphore(maxConcurrent)

    /**
     * Resolves which session a question belongs to.
     *
     * Returns the session id and whether it is a resume. An unknown, expired or other-screen id
     * yields a new session — never an error, because the panel's only sensible reaction to any of
     * those is "start over", and it can do that silently.
     */
    fun sessionFor(conversationId: String?, screenKey: String): Pair<String, Boolean> {
        evictStale()
        val existing = conversationId
            ?.takeIf { ClaudeCli.UUID_RE.matches(it) }
            ?.let { conversations[it] }
            ?.takeIf { it.screenKey == screenKey && it.turns < MAX_TURNS }
        return if (existing != null) existing.sessionId to true else UUID.randomUUID().toString() to false
    }

    fun record(sessionId: String, screenKey: String) {
        conversations.compute(sessionId) { _, prev ->
            Conversation(sessionId, screenKey, Instant.now(), (prev?.turns ?: 0) + 1)
        }
    }

    /** Forgets a session whose turn failed, so the next question starts clean rather than resuming a broken one. */
    fun forget(sessionId: String) {
        conversations.remove(sessionId)
    }

    fun run(prompt: String, sessionId: String, resume: Boolean, onText: (String) -> Unit): ClaudeCli.Outcome =
        cli.run(prompt, sessionId, resume, onText)

    private fun evictStale() {
        val cutoff = Instant.now().minus(IDLE)
        conversations.values.removeIf { it.lastUsed.isBefore(cutoff) }
    }

    companion object {
        /** Prices move hourly; a thread idle this long is about a screen that no longer exists. */
        private val IDLE: Duration = Duration.ofHours(2)

        /**
         * Resumed turns replay the whole transcript, so the cost of a follow-up climbs with the
         * conversation's length. Past this a fresh session — one row table plus one question — is
         * cheaper than resuming, so the panel starts one.
         */
        const val MAX_TURNS = 8
    }
}
