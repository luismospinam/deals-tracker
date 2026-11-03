package com.luis.dealstracker.notify

import com.luis.dealstracker.db.PendingNotification
import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.db.WatchRepository
import com.luis.dealstracker.i18n.AppLanguage
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.ingest.NotifyFn
import com.luis.dealstracker.ingest.WatchService
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.formatMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * Biggest gap first; an alert with no recorded gap (older new-low rows, see V31) after every known one.
 * Ties go to the cheaper price, then to the oldest alert, so the order is stable between runs.
 */
fun rankDigest(items: List<PendingNotification>): List<PendingNotification> =
    items.sortedWith(
        compareByDescending<PendingNotification> { it.alert.gapPercent ?: BigDecimal("-1") }
            .thenBy { it.alert.comparablePrice ?: it.alert.price }
            .thenBy { it.matchId },
    )

/**
 * The one banner a digest sends: a count in the title, the biggest gap in the body. A banner has about
 * three lines, so the rest are a count, and the click opens /watches, where all of them are listed.
 */
fun digestBanner(items: List<PendingNotification>, lang: Lang): Pair<String, String>? {
    val ranked = rankDigest(items).ifEmpty { return null }
    val watches = ranked.map { it.watchId }.distinct().size
    val title = lang.tr(
        "${ranked.size} watch alert${if (ranked.size == 1) "" else "s"}" + if (watches > 1) " across $watches watches" else "",
        "${ranked.size} alerta${if (ranked.size == 1) "" else "s"} de búsquedas" + if (watches > 1) " en $watches búsquedas" else "",
    )
    val top = ranked.first()
    val gap = top.alert.gapPercent?.let { "−${it.stripTrailingZeros().toPlainString()}% " }.orEmpty()
    val why = when (top.alert.reason) {
        MatchReason.CROSS_SITE_SPREAD -> lang.tr("under a rival", "bajo un rival")
        MatchReason.CARD_LOW -> cardWhy(top.alert, lang, "under the floor", "bajo el mínimo")
        MatchReason.LOWEST_IN_WINDOW -> lang.tr("under the floor", "bajo el mínimo")
    }
    val message = buildString {
        append(top.watchName).append(": ").append(gap).append(why)
        append(" · ").append(formatMoney(top.alert.price, top.currency)).append(" · ").append(top.source)
        append('\n').append(WatchService.squashRepeats(top.title).take(70))
        if (ranked.size > 1) append('\n').append(lang.tr("+${ranked.size - 1} more", "+${ranked.size - 1} más"))
    }
    return title to message
}

/**
 * A CARD_LOW alert's reason, naming the card: its price is only real to someone holding it, so the
 * line must never read like a cash low.
 */
private fun cardWhy(alert: com.luis.dealstracker.model.WatchAlert, lang: Lang, en: String, es: String): String {
    val card = alert.card?.label ?: lang.tr("card", "tarjeta")
    return lang.tr("$en with $card", "$es con $card")
}

/**
 * The digest's Discord post: every alert, one line each, biggest gap first, each linked to the retailer.
 * A post has room the banner does not, so nothing is left as "+N more" unless Discord's limit cuts it.
 */
fun digestDiscordLines(items: List<PendingNotification>, lang: Lang): List<String> =
    rankDigest(items).map { p ->
        val gap = p.alert.gapPercent?.let { "**−${it.stripTrailingZeros().toPlainString()}%**" } ?: "**—**"
        val title = WatchService.squashRepeats(p.title).take(80).replace("[", "(").replace("]", ")")
        val item = p.url?.let { "[$title]($it)" } ?: title
        val why = when (p.alert.reason) {
            MatchReason.CROSS_SITE_SPREAD -> lang.tr("under a rival", "bajo un rival")
            MatchReason.CARD_LOW -> cardWhy(p.alert, lang, "new low", "nuevo mínimo")
            MatchReason.LOWEST_IN_WINDOW -> lang.tr("new low", "nuevo mínimo")
        }
        "$gap ${p.watchName} · $item — ${formatMoney(p.alert.price, p.currency)} ${lang.tr("at", "en")} ${p.source} ($why)"
    }

/**
 * The daily watch-alert digest: one banner at each of `defaults.watchDigestTimes`, in place of a banner
 * per watch per hourly cycle. The hourly check still records every alert, and the watch page shows
 * them at once; only the interruption is batched. Slots are remembered in `app_settings`, as the pinned
 * posts' are, so a restart neither repeats nor skips one.
 */
class WatchDigest(
    private val watches: WatchRepository,
    private val settings: SettingsRepository,
    times: List<String>,
    private val baseUrl: String,
    private val notifier: NotifyFn = Notifier::notify,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** `DISCORD_WATCHES_WEBHOOK_URL`; null keeps the digest on the desktop only. */
    private val discord: DiscordWebhook? = null,
) {
    private val slots = parseSlotTimes(times)

    /** One scheduler pass: sends the digest if a slot is due. Null when nothing was due. */
    fun runDue(now: Instant = Instant.now()): String? {
        val slot = dueSlot(now.atZone(zone), slots, settings.get(SettingsRepository.WATCH_DIGEST_SLOT)?.let(Instant::parse))
            ?: return null
        val result = send()
        // Nothing waiting is handled; a failed banner is not, so the next tick retries.
        if (result != SendResult.FAILED) settings.set(mapOf(SettingsRepository.WATCH_DIGEST_SLOT to slot.toString()))
        return "[watch-digest] ${result.describe}"
    }

    /** `--watch-digest`: sends now, ignoring the slots. [dryRun] prints the banner instead. */
    fun runNow(dryRun: Boolean): String {
        if (!dryRun) return "[watch-digest] ${send().describe}"
        val pending = waiting()
        val banner = digestBanner(pending, AppLanguage.current) ?: return "[watch-digest] nothing waiting"
        val ranked = rankDigest(pending)
        return "[watch-digest] would send:\n  ${banner.first}\n  " + banner.second.replace("\n", "\n  ") +
            "\n  ranked:\n" + ranked.joinToString("\n") {
                "    ${it.alert.gapPercent?.let { g -> "$g%" } ?: "—"}  ${it.watchName}  ${formatMoney(it.alert.price, it.currency)}  ${it.source}  ${it.title.take(60)}"
            }
    }

    private fun waiting(): List<PendingNotification> = watches.digestRows().filter { it.waiting }.map { it.pending }

    private enum class SendResult(val describe: String) { NOTHING("nothing waiting"), SENT("sent"), FAILED("send failed") }

    private fun send(): SendResult {
        val pending = waiting()
        val lang = AppLanguage.current
        val (title, message) = digestBanner(pending, lang) ?: return SendResult.NOTHING
        val desktop = notifier(title, message, "$baseUrl/watches#digest", "watch-digest")
        val posted = discord?.send(
            title = title,
            description = fitDescription(digestDiscordLines(pending, lang)),
            footer = "${java.time.LocalDate.now(zone)}  •  ${pending.size} alert(s)",
        ) ?: false
        // Delivered if either channel took it: the phone post alone is still the alert being seen, and
        // marking it only on both would announce it again tomorrow. Neither, and the next tick retries.
        if (!desktop && !posted) return SendResult.FAILED
        watches.markNotified(pending)
        return SendResult.SENT
    }
}
