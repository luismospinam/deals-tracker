package com.luis.dealstracker.db

import com.luis.dealstracker.model.CardScope
import java.time.Instant
import javax.sql.DataSource

/** One card at one store, as some crawl has seen it (`card_programs`, V35). */
data class CardProgram(
    /** Null for a name this build does not know, written by a newer one. */
    val scope: CardScope?,
    val source: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val readings: Long,
)

/**
 * Every card any crawl has found an offer for. The "My cards" choices come from here rather than from
 * the [CardScope] enum, so the page offers the cards the stores actually run promotions on -- and a card
 * that appears in a new store shows up without a code change.
 */
class CardProgramRepository(private val ds: DataSource) {
    fun all(): List<CardProgram> = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT scope, source, first_seen_at, last_seen_at, readings FROM card_programs ORDER BY scope, source",
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            CardProgram(
                                scope = runCatching { CardScope.valueOf(rs.getString("scope")) }.getOrNull(),
                                source = rs.getString("source"),
                                firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
                                lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                                readings = rs.getLong("readings"),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** One row of the "My cards" list: a card, every store it was seen at, and when last. */
data class CardChoice(val scope: CardScope, val sources: List<String>, val lastSeenAt: Instant, val readings: Long)

/**
 * [programs] grouped per card, most-seen first, for the picker. OTHER (and unknown names) are left out:
 * nobody can say they hold "a card this app cannot name". A [held] card no store has shown is kept, so
 * it can still be unticked.
 */
fun cardChoices(programs: List<CardProgram>, held: Set<CardScope>): List<CardChoice> {
    val seen = programs.filter { it.scope != null && it.scope != CardScope.OTHER }.groupBy { it.scope!! }
        .map { (scope, rows) ->
            CardChoice(scope, rows.sortedByDescending { it.readings }.map { it.source }, rows.maxOf { it.lastSeenAt }, rows.sumOf { it.readings })
        }
    val missing = (held - CardScope.OTHER - seen.map { it.scope }.toSet()).map { CardChoice(it, emptyList(), Instant.EPOCH, 0) }
    return (seen + missing).sortedByDescending { it.readings }
}

/**
 * The ticked boxes as the [SettingsRepository.HELD_CARDS] value: known cards only, never OTHER, in
 * declaration order, so a forged or stale form cannot store a card this build does not know.
 */
fun heldCardsValue(picked: List<String>): String =
    CardScope.entries.filter { it != CardScope.OTHER && it.name in picked }.joinToString(",") { it.name }
