package com.luis.dealstracker.web

import com.luis.dealstracker.db.DayChange
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.details
import kotlinx.html.li
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.ul
import java.math.BigDecimal
import java.math.RoundingMode

/** Why a line is in the digest. A line can carry several. */
enum class DigestKind { DROP, NEW_LOW, NEW_DEAL, ALERT }

/** One listing in the "since yesterday" banner. */
data class DigestLine(
    val dealId: Long,
    val title: String,
    val currency: String,
    val yesterday: BigDecimal?,
    val today: BigDecimal,
    val kinds: Set<DigestKind>,
    /** Extra context shown after the price, e.g. the watch alert's evidence or the store. */
    val note: String? = null,
) {
    /** Percent below yesterday's price, positive for cheaper; null when there is no yesterday. */
    val dropPercent: BigDecimal?
        get() = yesterday?.takeIf { it.signum() > 0 && today < it }?.let {
            BigDecimal.ONE.subtract(today.divide(it, 6, RoundingMode.HALF_UP))
                .multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
        }
}

/**
 * New lows first, then everything else; largest drop first within each group.
 *
 * A new low is the strongest claim the price log can make, so it leads even when a plain drop is
 * bigger in percent. Lines with no drop (an alert at an unchanged price) go last in their group.
 */
internal val DIGEST_ORDER = compareByDescending<DigestLine> { DigestKind.NEW_LOW in it.kinds }
    .thenByDescending { it.dropPercent ?: BigDecimal.ZERO }
    .thenBy { it.title }

/** Lines shown before "and N more". A digest of 400 rows is a second feed, which it must not become. */
private const val DIGEST_SHOWN = 40

/**
 * Builds the lines for listings that moved down since yesterday.
 *
 * [meta] maps a deal id to its title and currency, so the caller supplies whatever row type it
 * already holds and a change whose listing is not on screen (hidden, dismissed) is skipped rather
 * than announced.
 */
internal fun <T> digestLines(
    changes: Map<Long, DayChange>,
    rows: Collection<T>,
    idOf: (T) -> Long,
    titleOf: (T) -> String,
    currencyOf: (T) -> String,
    noteOf: (T) -> String? = { null },
    extraKinds: (T, DayChange) -> Set<DigestKind> = { _, _ -> emptySet() },
): List<DigestLine> = rows.mapNotNull { row ->
    val ch = changes[idOf(row)] ?: return@mapNotNull null
    val kinds = buildSet {
        if (ch.isDrop) add(DigestKind.DROP)
        if (ch.isNewLow) add(DigestKind.NEW_LOW)
        addAll(extraKinds(row, ch))
    }
    DigestLine(idOf(row), titleOf(row), currencyOf(row), ch.yesterday, ch.today, kinds, noteOf(row))
}

/**
 * The "since yesterday" banner at the top of Tracked, Groceries and a watch.
 *
 * Computed on every load from the price log -- nothing is stored, so there is no job to miss while
 * the Mac sleeps and nothing to go stale: the first load of a new day simply sees a new "today".
 * It renders nothing when nothing moved, which on the first morning after install is the honest
 * answer, instead of an empty box.
 *
 * A native `<details>` like the Ask panel: the summary is the banner, the list is one click away.
 */
internal fun FlowContent.sinceYesterday(lines: List<DigestLine>) {
    if (lines.isEmpty()) return
    val drops = lines.count { DigestKind.DROP in it.kinds }
    val lows = lines.count { DigestKind.NEW_LOW in it.kinds }
    val deals = lines.count { DigestKind.NEW_DEAL in it.kinds }
    val alerts = lines.count { DigestKind.ALERT in it.kinds }

    val parts = buildList {
        if (drops > 0) add(tr("$drops price ${plural(drops, "drop", "drops")}", "$drops ${plural(drops, "bajada", "bajadas")} de precio"))
        if (lows > 0) add(tr("$lows new ${plural(lows, "low", "lows")}", "$lows ${plural(lows, "mínimo nuevo", "mínimos nuevos")}"))
        if (deals > 0) add(tr("$deals newly under usual", "$deals ${plural(deals, "nueva", "nuevas")} bajo lo habitual"))
        if (alerts > 0) add(tr("$alerts ${plural(alerts, "alert", "alerts")} fired today", "$alerts ${plural(alerts, "alerta", "alertas")} hoy"))
    }

    details("digest") {
        summary {
            span("digest-mark") { +"◆" }
            span("digest-title") { +tr("Since yesterday", "Desde ayer") }
            span("digest-counts") { +parts.joinToString(" · ") }
        }
        val shown = lines.sortedWith(DIGEST_ORDER).take(DIGEST_SHOWN)
        ul("digest-list") {
            shown.forEach { l ->
                li {
                    a(href = "/deal/${l.dealId}") { +l.title }
                    span("digest-price") {
                        l.yesterday?.takeIf { it != l.today }?.let { +"${formatMoney(it, l.currency)} → " }
                        +formatMoney(l.today, l.currency)
                    }
                    l.dropPercent?.let { span("digest-pct") { +"−${it.stripTrailingZeros().toPlainString()}%" } }
                    if (DigestKind.NEW_LOW in l.kinds) span("chip") { +tr("new low", "mínimo nuevo") }
                    if (DigestKind.NEW_DEAL in l.kinds) span("chip") { +tr("under usual", "bajo lo habitual") }
                    if (DigestKind.ALERT in l.kinds) span("chip") { +tr("alert", "alerta") }
                    l.note?.let { span("digest-note") { +it } }
                }
            }
        }
        if (lines.size > shown.size) {
            span("digest-more") { +tr("and ${lines.size - shown.size} more", "y ${lines.size - shown.size} más") }
        }
    }
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

/**
 * Whether a grocery item crossed into "deal" territory since yesterday: a deal by the tab's own
 * rule today, and yesterday's price was NOT already 10% under usual.
 *
 * Judged against the same [GroceryRow.usual] today's rule uses, which excludes today, so yesterday's
 * price is measured against the baseline it would have had. A row with no usual price (under three
 * earlier days) is never a deal, so it never appears here either.
 */
internal fun newlyUnderUsual(row: GroceryRow, change: DayChange): Boolean {
    val usual = row.usual ?: return false
    val threshold = BigDecimal.ONE.subtract(GroceryRow.DEAL_PERCENT.divide(BigDecimal(100)))
    return row.isDeal && change.yesterday > usual.multiply(threshold)
}
