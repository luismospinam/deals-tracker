package com.luis.dealstracker.promo

import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.sql.DataSource

/** A pinned or tracked row a promo applies to, for the reminder's list. */
data class PromoItem(val dealId: Long, val title: String, val source: String, val price: BigDecimal, val currency: String)

/** Which reminder is due at [now]: the promo day itself, or the day before. Null when neither. */
internal enum class PromoMoment { DAY_BEFORE, DAY }

internal fun promoMomentDue(p: Promo, now: Instant, zone: ZoneId, alreadySent: String?): Pair<PromoMoment, LocalDate>? {
    if (!p.enabled) return null
    val local = now.atZone(zone)
    if (local.toLocalTime().isBefore(p.notifyAt)) return null
    val today = local.toLocalDate()
    val due = when {
        p.isOn(today) -> PromoMoment.DAY to today
        p.remindDayBefore && p.isOn(today.plusDays(1)) -> PromoMoment.DAY_BEFORE to today.plusDays(1)
        else -> return null
    }
    // Remembered as "<promo date>:<moment>", so a restart neither repeats one nor skips the next.
    return due.takeIf { key(it.second, it.first) != alreadySent }
}

internal fun key(day: LocalDate, moment: PromoMoment) = "$day:${moment.name}"

/** The reminder's title and body lines. [items] are the qualifying pinned and tracked rows, cheapest saving first. */
internal fun promoMessage(p: Promo, moment: PromoMoment, items: List<PromoItem>, baseUrl: String): Pair<String, List<String>> {
    val title = when (moment) {
        PromoMoment.DAY -> tr("Today: ${p.code ?: p.name}", "Hoy: ${p.code ?: p.name}")
        PromoMoment.DAY_BEFORE -> tr("Tomorrow: ${p.code ?: p.name}", "Mañana: ${p.code ?: p.name}")
    }
    val lines = buildList {
        add("${p.name} — ${p.offerText()}.")
        p.notes?.let { add(it) }
        if (items.isEmpty()) {
            if (p.sourcePrefix != null) add(tr("None of your pinned or tracked items qualify right now.", "Ninguno de tus productos fijados o en seguimiento califica ahora."))
        } else {
            add(tr("Your pinned and tracked items that qualify:", "Tus productos fijados y en seguimiento que califican:"))
            items.forEach { i ->
                add("• [${i.title.take(70)}]($baseUrl/deal/${i.dealId}) ${formatMoney(i.price, i.currency)} → **${formatMoney(p.priceWith(i.price), i.currency)}**")
            }
        }
    }
    return title to lines
}

/**
 * Posts the promo reminders when due: a desktop banner and, when a channel is set, Discord. Checked on
 * the 5-minute schedule tick.
 */
class PromoNotifier(
    private val ds: DataSource,
    private val promos: PromoRepository,
    private val settings: SettingsRepository,
    private val baseUrl: String,
    /** (title, body lines). Returns whether it was delivered; an undelivered reminder is retried next tick. */
    private val send: (String, List<String>) -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun runDue(now: Instant = Instant.now()): List<String> = promos.refresh().mapNotNull { p ->
        val sentKey = SettingsRepository.PROMO_SENT_PREFIX + p.id
        val (moment, day) = promoMomentDue(p, now, zone, settings.get(sentKey)) ?: return@mapNotNull null
        val (title, lines) = promoMessage(p, moment, qualifying(p), baseUrl)
        if (!send(title, lines)) return@mapNotNull "[promo] ${p.name}: send failed, retrying next tick"
        settings.set(mapOf(sentKey to key(day, moment)))
        "[promo] ${p.name}: ${moment.name.lowercase()} reminder sent"
    }

    /** The operator's pinned feed rows and tracked items the promo applies to, active, cheapest first. */
    fun qualifying(p: Promo): List<PromoItem> {
        val prefix = p.sourcePrefix ?: return emptyList()
        return ds.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT d.id, d.title, d.source, po.price, d.currency FROM deals d
                  JOIN LATERAL (SELECT price FROM price_observations WHERE deal_id = d.id ORDER BY observed_at DESC LIMIT 1) po ON TRUE
                 WHERE d.is_active AND starts_with(d.source, ?) AND d.currency = ? AND po.price >= ?
                   AND (d.pinned_at IS NOT NULL
                        OR EXISTS (SELECT 1 FROM manual_items m WHERE m.source = d.source AND m.asin = d.asin))
                 ORDER BY po.price LIMIT 25
                """.trimIndent(),
            ).use { st ->
                st.setString(1, prefix)
                st.setString(2, p.currency)
                st.setBigDecimal(3, p.minAmount ?: BigDecimal.ZERO)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(PromoItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5)))
                    }
                }
            }
        }
    }
}
