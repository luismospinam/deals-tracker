package com.luis.dealstracker.web

import com.luis.dealstracker.db.DayChange
import com.luis.dealstracker.db.DigestRange
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.li
import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.ul
import java.math.BigDecimal
import java.math.RoundingMode

/** Why a line is in the digest. A line can carry several. */
enum class DigestKind { DROP, NEW_LOW, NEW_DEAL, ALERT }

/** One listing in the digest banner. */
data class DigestLine(
    val dealId: Long,
    val title: String,
    val currency: String,
    /** What [today] is compared against: yesterday's price, or the window's low. Null for an alert with no prior reading. */
    val baseline: BigDecimal?,
    val today: BigDecimal,
    val kinds: Set<DigestKind>,
    /** Extra context shown after the price, e.g. the watch alert's evidence or the store. */
    val note: String? = null,
) {
    /** Percent below the [baseline], positive for cheaper; null when there is none. */
    val dropPercent: BigDecimal?
        get() = baseline?.takeIf { it.signum() > 0 && today < it }?.let {
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
 * Builds the lines for listings that moved down against the chosen range.
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
    DigestLine(idOf(row), titleOf(row), currencyOf(row), ch.baseline, ch.today, kinds, noteOf(row))
}

/** The dropdown label, which doubles as the banner title. */
private fun DigestRange.label() = when (this) {
    DigestRange.YESTERDAY -> tr("Since yesterday", "Desde ayer")
    DigestRange.WEEK -> tr("Below the past 7 days", "Bajo los últimos 7 días")
    DigestRange.FORTNIGHT -> tr("Below the past 14 days", "Bajo los últimos 14 días")
}

/** The segmented control's label; the banner title below says what the choice means. */
private fun DigestRange.shortLabel() = when (this) {
    DigestRange.YESTERDAY -> tr("Yesterday", "Ayer")
    DigestRange.WEEK -> tr("7 days", "7 días")
    DigestRange.FORTNIGHT -> tr("14 days", "14 días")
}

private fun DigestRange.alertsLabel() = when (this) {
    DigestRange.YESTERDAY -> tr("today", "hoy")
    else -> tr("in the past $days days", "en los últimos $days días")
}

/**
 * The digest at the top of Tracked, Groceries and a watch: a banner of what got cheaper, and the
 * dropdown that picks the range it looks back over.
 *
 * Computed on every load from the price log -- nothing is stored, so there is no job to miss while
 * the Mac sleeps and nothing to go stale: the first load of a new day simply sees a new "today".
 * With nothing to list it renders only the range control (so a quiet day can still be widened to a week)
 * and, on a non-default range, a line saying so, instead of an empty box.
 *
 * The list is a native `<details>` like the Ask panel: the summary is the banner, the list is one
 * click away. The range control sits beside it rather than inside the summary, where a click would
 * toggle the panel.
 */
internal fun FlowContent.digestBanner(lines: List<DigestLine>, range: DigestRange) {
    div("digest-bar") {
        if (lines.isEmpty()) {
            if (range != DigestRange.YESTERDAY) {
                span("digest-empty") { +tr("Nothing cheaper than the past ${range.days} days.", "Nada más barato que los últimos ${range.days} días.") }
            }
        } else {
            digestDetails(lines, range)
        }
        // Three options are all visible at once, one click each, like the type tags on Tracked.
        div("digest-range") {
            attributes["role"] = "group"
            attributes["aria-label"] = tr("Compare against", "Comparar con")
            DigestRange.entries.forEach { r ->
                button(type = ButtonType.button, classes = if (r == range) "on" else null) {
                    attributes["aria-pressed"] = (r == range).toString()
                    // Only `range` changes; every other control in the query string is left as it was. It is
                    // `window.URL`, not `URL`: an inline handler resolves names through `document` first, and
                    // `document.URL` is a string, so a bare `new URL(...)` throws and the page does nothing.
                    attributes["onclick"] = "var u=new window.URL(location.href);u.searchParams.set('range','${r.param}');location.href=u.href"
                    +r.shortLabel()
                }
            }
        }
    }
}

private fun FlowContent.digestDetails(lines: List<DigestLine>, range: DigestRange) {
    val drops = lines.count { DigestKind.DROP in it.kinds }
    val lows = lines.count { DigestKind.NEW_LOW in it.kinds }
    val deals = lines.count { DigestKind.NEW_DEAL in it.kinds }
    val alerts = lines.count { DigestKind.ALERT in it.kinds }

    val parts = buildList {
        if (drops > 0) add(tr("$drops price ${plural(drops, "drop", "drops")}", "$drops ${plural(drops, "bajada", "bajadas")} de precio"))
        if (lows > 0) add(tr("$lows new ${plural(lows, "low", "lows")}", "$lows ${plural(lows, "mínimo nuevo", "mínimos nuevos")}"))
        if (deals > 0) add(tr("$deals newly under usual", "$deals ${plural(deals, "nueva", "nuevas")} bajo lo habitual"))
        if (alerts > 0) add(tr("$alerts ${plural(alerts, "alert", "alerts")} fired ${range.alertsLabel()}", "$alerts ${plural(alerts, "alerta", "alertas")} ${range.alertsLabel()}"))
    }

    details("digest") {
        summary {
            span("digest-mark") { +"◆" }
            span("digest-title") { +range.label() }
            span("digest-counts") { +parts.joinToString(" · ") }
        }
        val shown = lines.sortedWith(DIGEST_ORDER).take(DIGEST_SHOWN)
        ul("digest-list") {
            shown.forEach { l ->
                li {
                    a(href = "/deal/${l.dealId}") { +l.title }
                    span("digest-price") {
                        l.baseline?.takeIf { it != l.today }?.let { +"${formatMoney(it, l.currency)} → " }
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
 * Whether a grocery item crossed into "deal" territory over the range: a deal by the tab's own
 * rule today, and the baseline price was NOT already 10% under usual.
 *
 * Judged against the same [GroceryRow.usual] today's rule uses, which excludes today, so yesterday's
 * price is measured against the baseline it would have had. A row with no usual price (under three
 * earlier days) is never a deal, so it never appears here either.
 */
internal fun newlyUnderUsual(row: GroceryRow, change: DayChange): Boolean {
    val usual = row.usual ?: return false
    val threshold = BigDecimal.ONE.subtract(GroceryRow.DEAL_PERCENT.divide(BigDecimal(100)))
    return row.isDeal && change.baseline > usual.multiply(threshold)
}
