package com.luis.dealstracker.agent

import java.time.Instant
import javax.sql.DataSource

/** One agent suggestion, as the inbox shows it. */
data class Suggestion(
    val id: Long,
    val agent: String,
    val createdAt: Instant,
    val kind: String,
    val dealId: Long?,
    val title: String,
    val reason: String,
    val status: String,
    val note: String? = null,
)

/** What answering a suggestion does: [PIN] also pins the row, which is what a `pin` suggestion asks. */
enum class SuggestionAnswer(val status: String) { ACCEPT("accepted"), PIN("accepted"), DISMISS("dismissed") }

class SuggestionRepository(private val ds: DataSource) {
    /** The open ones, newest first. Older than [maxAgeDays] are expired first: a week-old "buy now" is stale. */
    fun open(maxAgeDays: Int = 7, limit: Int = 50): List<Suggestion> = ds.connection.use { conn ->
        conn.prepareStatement(
            "UPDATE suggestions SET status = 'expired' WHERE status = 'new' AND created_at < now() - make_interval(days => ?)",
        ).use { it.setInt(1, maxAgeDays); it.executeUpdate() }
        conn.prepareStatement(
            "SELECT id, agent, created_at, kind, deal_id, title, reason, status, note FROM suggestions WHERE status = 'new' ORDER BY created_at DESC, id DESC LIMIT ?",
        ).use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Suggestion(
                                rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4),
                                rs.getLong(5).takeIf { !rs.wasNull() }, rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Records the answer and returns the suggestion's deal, or null when it was not open. */
    fun answer(id: Long, answer: SuggestionAnswer, note: String?): Long? = ds.connection.use { conn ->
        conn.prepareStatement(
            "UPDATE suggestions SET status = ?, answered_at = now(), note = ? WHERE id = ? AND status = 'new' RETURNING deal_id",
        ).use { st ->
            st.setString(1, answer.status)
            st.setString(2, note?.trim()?.take(300)?.takeIf { it.isNotEmpty() })
            st.setLong(3, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null }
        }
    }
}
