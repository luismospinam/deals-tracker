package com.luis.dealstracker.promo

import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import javax.sql.DataSource

/**
 * A recurring payment promotion (V37): "the first Tuesday of every month, BANCOLMARTES takes US$10 off
 * Amazon orders from US$60 paid with Bancolombia". Applied at checkout, so no crawl ever sees it -- the
 * app's part is to remember the day and say which rows qualify.
 */
data class Promo(
    val id: Long,
    val name: String,
    val code: String?,
    val payment: String?,
    /** The sources a chip goes on, by prefix ("amazon" covers amazon-watches); null for none. */
    val sourcePrefix: String?,
    /** Which [weekday] of the month: 1-5, or -1 for the last. */
    val nth: Int,
    val weekday: DayOfWeek,
    val minAmount: BigDecimal?,
    val discount: BigDecimal,
    val currency: String,
    val notifyAt: LocalTime = LocalTime.of(8, 0),
    val remindDayBefore: Boolean = true,
    val enabled: Boolean = true,
    val notes: String? = null,
) {
    /** The promo's day in [month], or null when the month has no such day (a fifth Tuesday). */
    fun dateIn(month: YearMonth): LocalDate? {
        val first = month.atDay(1)
        if (nth == -1) return first.with(TemporalAdjusters.lastInMonth(weekday))
        val d = first.with(TemporalAdjusters.dayOfWeekInMonth(nth, weekday))
        return d.takeIf { YearMonth.from(it) == month }
    }

    /** The next promo day on or after [from]. */
    fun nextOn(from: LocalDate): LocalDate =
        generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.take(14)
            .mapNotNull { dateIn(it) }.first { !it.isBefore(from) }

    fun isOn(day: LocalDate): Boolean = dateIn(YearMonth.from(day)) == day

    /** Whether a [price] in [currency] on [source] gets the discount: the right store, currency and minimum. */
    fun qualifies(source: String, price: BigDecimal, currency: String): Boolean =
        sourcePrefix != null && source.startsWith(sourcePrefix) && currency == this.currency &&
            (minAmount == null || price >= minAmount)

    fun priceWith(price: BigDecimal): BigDecimal = (price - discount).max(BigDecimal.ZERO)
}

/** "first Tuesday of the month", in the reader's language. */
fun Promo.ruleText(): String {
    val ordinal = when (nth) {
        1 -> tr("first", "primer")
        2 -> tr("second", "segundo")
        3 -> tr("third", "tercer")
        4 -> tr("fourth", "cuarto")
        5 -> tr("fifth", "quinto")
        else -> tr("last", "último")
    }
    val day = weekday.getDisplayName(java.time.format.TextStyle.FULL, if (com.luis.dealstracker.i18n.I18n.lang == Lang.ES) java.util.Locale.forLanguageTag("es") else java.util.Locale.ENGLISH)
    return tr("$ordinal $day of the month", "$ordinal $day del mes")
}

/** "US$10 off from US$60 with Bancolombia, code BANCOLMARTES". */
fun Promo.offerText(): String = buildString {
    append(tr("${formatMoney(discount, currency)} off", "${formatMoney(discount, currency)} de descuento"))
    minAmount?.let { append(tr(" from ${formatMoney(it, currency)}", " desde ${formatMoney(it, currency)}")) }
    payment?.let { append(tr(" paying with $it", " pagando con $it")) }
    code?.let { append(tr(", code $it", ", código $it")) }
}

/** The promos the page renders with: refreshed at boot and on every edit, like `HeldCards`. */
object ActivePromos {
    @Volatile
    var current: List<Promo> = emptyList()

    /** The enabled promos on [day]. */
    fun on(day: LocalDate): List<Promo> = current.filter { it.enabled && it.isOn(day) }
}

/** Today's local date, where every "is it promo day" question is asked. */
fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

class PromoRepository(private val ds: DataSource) {
    fun all(): List<Promo> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM promo_reminders ORDER BY id").use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Promo(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                code = rs.getString("code"),
                                payment = rs.getString("payment"),
                                sourcePrefix = rs.getString("source_prefix"),
                                nth = rs.getInt("nth"),
                                weekday = DayOfWeek.of(rs.getInt("weekday")),
                                minAmount = rs.getBigDecimal("min_amount"),
                                discount = rs.getBigDecimal("discount"),
                                currency = rs.getString("currency"),
                                notifyAt = runCatching { LocalTime.parse(rs.getString("notify_at")) }.getOrDefault(LocalTime.of(8, 0)),
                                remindDayBefore = rs.getBoolean("remind_day_before"),
                                enabled = rs.getBoolean("enabled"),
                                notes = rs.getString("notes"),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun insert(p: Promo): Long = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO promo_reminders (name, code, payment, source_prefix, nth, weekday, min_amount, discount, currency,
                                         notify_at, remind_day_before, enabled, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
        ).use { st ->
            st.setString(1, p.name)
            st.setString(2, p.code)
            st.setString(3, p.payment)
            st.setString(4, p.sourcePrefix)
            st.setInt(5, p.nth)
            st.setInt(6, p.weekday.value)
            st.setBigDecimal(7, p.minAmount)
            st.setBigDecimal(8, p.discount)
            st.setString(9, p.currency)
            st.setString(10, "%02d:%02d".format(p.notifyAt.hour, p.notifyAt.minute))
            st.setBoolean(11, p.remindDayBefore)
            st.setBoolean(12, p.enabled)
            st.setString(13, p.notes)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) = update("UPDATE promo_reminders SET enabled = ? WHERE id = ?") { it.setBoolean(1, enabled); it.setLong(2, id) }

    fun delete(id: Long) = update("DELETE FROM promo_reminders WHERE id = ?") { it.setLong(1, id) }

    private fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
        ds.connection.use { conn -> conn.prepareStatement(sql).use { st -> bind(st); st.executeUpdate() } }
    }

    /** Reloads [ActivePromos]. */
    fun refresh(): List<Promo> = all().also { ActivePromos.current = it }
}

/**
 * A promo form as submitted, checked. Errors name the field, so the page can say what to fix. The
 * currency is upper-cased; the code is upper-cased too, as stores print them.
 */
internal fun parsePromoForm(f: Map<String, String>): Result<Promo> {
    fun text(k: String) = f[k]?.trim()?.takeIf { it.isNotEmpty() }
    fun money(k: String) = text(k)?.replace(",", "")?.toBigDecimalOrNull()
    val name = text("name") ?: return Result.failure(IllegalArgumentException("name is required"))
    val nth = text("nth")?.toIntOrNull()?.takeIf { it in 1..5 || it == -1 } ?: return Result.failure(IllegalArgumentException("which week: 1-5 or last"))
    val weekday = text("weekday")?.toIntOrNull()?.takeIf { it in 1..7 } ?: return Result.failure(IllegalArgumentException("pick a day of the week"))
    val discount = money("discount")?.takeIf { it.signum() > 0 } ?: return Result.failure(IllegalArgumentException("discount must be a positive amount"))
    val min = if (text("minAmount") == null) null else money("minAmount")?.takeIf { it.signum() >= 0 }
        ?: return Result.failure(IllegalArgumentException("minimum must be an amount"))
    val currency = text("currency")?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return Result.failure(IllegalArgumentException("currency like USD or COP"))
    val at = text("notifyAt")?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrNull() ?: return Result.failure(IllegalArgumentException("reminder time like 08:00")) }
        ?: LocalTime.of(8, 0)
    return Result.success(
        Promo(
            id = 0, name = name, code = text("code")?.uppercase(), payment = text("payment"),
            sourcePrefix = text("sourcePrefix")?.lowercase(), nth = nth, weekday = DayOfWeek.of(weekday),
            minAmount = min, discount = discount, currency = currency, notifyAt = at,
            remindDayBefore = f["remindDayBefore"] == "on" || f["remindDayBefore"] == "true", notes = text("notes"),
        ),
    )
}
