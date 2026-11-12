package com.luis.dealstracker.web

import com.luis.dealstracker.db.DayPrice
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.unsafe
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The small price line in a grocery or tracked row: the last [TREND_DAYS] days at a glance, so the
 * shape of the price (a one-day dip, a slow climb, flat for a month) is seen without opening History.
 */
internal const val TREND_DAYS = 30

/**
 * The rows' daily prices for the page being rendered, by deal id. The same thread-local shape as
 * [CurrentPath]: a row is drawn many calls below the route that knows which rows are on screen, and
 * only those rows' histories are loaded.
 */
internal object PriceTrends {
    private val current = ThreadLocal<Map<Long, List<DayPrice>>>()
    fun of(dealId: Long): List<DayPrice> = current.get()?.get(dealId).orEmpty()
    fun <T> with(trends: Map<Long, List<DayPrice>>, block: () -> T): T {
        current.set(trends)
        try { return block() } finally { current.remove() }
    }
}

/**
 * The line's points in a [w] x [h] box, as an SVG `points` list, or null with fewer than two days of
 * history: one price is not a trend.
 *
 * Drawn as steps, not slopes: a price holds until the day it changes, and a diagonal between two
 * readings would show prices nobody charged. The last price runs on to [today]. Days are placed by
 * date, so a gap in the readings is a longer step rather than a squeezed one. A price that never moved
 * is a line through the middle.
 */
internal fun trendPoints(days: List<DayPrice>, today: LocalDate, w: Double = 64.0, h: Double = 20.0, pad: Double = 2.0): String? {
    val sorted = days.filter { !it.day.isAfter(today) }.sortedBy { it.day }
    if (sorted.size < 2) return null
    val first = sorted.first().day
    val span = ChronoUnit.DAYS.between(first, today).coerceAtLeast(1).toDouble()
    val low = sorted.minOf { it.price }
    val high = sorted.maxOf { it.price }
    val range = high.subtract(low).toDouble()
    fun x(day: LocalDate) = pad + ChronoUnit.DAYS.between(first, day) / span * (w - 2 * pad)
    fun y(price: BigDecimal) = if (range == 0.0) h / 2 else pad + (high.subtract(price).toDouble() / range) * (h - 2 * pad)
    // Locale.ROOT: a Spanish locale writes "12,5", which an SVG points list reads as two numbers.
    fun n(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    val points = buildList {
        sorted.forEachIndexed { i, d ->
            if (i > 0) add(x(d.day) to y(sorted[i - 1].price))
            add(x(d.day) to y(d.price))
        }
        add(x(today) to y(sorted.last().price))
    }
    return points.joinToString(" ") { (px, py) -> "${n(px)},${n(py)}" }
}

/** Where today's price ends up on the line: its lowest point, its highest, or in between. */
internal fun trendEnd(days: List<DayPrice>): String {
    val last = days.maxByOrNull { it.day }?.price ?: return "mid"
    val low = days.minOf { it.price }
    val high = days.maxOf { it.price }
    return when {
        low.compareTo(high) == 0 -> "mid"
        last.compareTo(low) == 0 -> "low"
        last.compareTo(high) == 0 -> "high"
        else -> "mid"
    }
}

/**
 * The trend line for [dealId], or nothing when the page loaded no history for it or it has under two
 * days. The picture is described in words for a screen reader and on hover: the range it covers.
 */
internal fun FlowContent.priceTrend(dealId: Long, currency: String, today: LocalDate = LocalDate.now()) {
    val days = PriceTrends.of(dealId)
    val points = trendPoints(days, today) ?: return
    val low = formatMoney(days.minOf { it.price }, currency)
    val high = formatMoney(days.maxOf { it.price }, currency)
    val words = tr("Last $TREND_DAYS days: from $low to $high", "Últimos $TREND_DAYS días: de $low a $high")
    val (endX, endY) = points.substringAfterLast(' ').split(',')
    div("trend trend-${trendEnd(days)}") {
        attributes["title"] = words
        unsafe {
            +"""<svg viewBox="0 0 64 20" width="64" height="20" role="img" aria-label="$words">
                 <polyline fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" points="$points"/>
                 <circle class="trend-now" cx="$endX" cy="$endY" r="2"/>
               </svg>"""
        }
    }
}
