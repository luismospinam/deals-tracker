package com.luis.dealstracker.ingest

import com.luis.dealstracker.db.PendingNotification
import com.luis.dealstracker.db.WatchRepository
import com.luis.dealstracker.model.FxRate
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.formatMoney
import com.luis.dealstracker.notify.Notifier

data class WatchReport(
    val watch: String,
    val candidates: Int,
    val alerts: Int,
    val notified: Int,
) {
    fun summary(): String =
        "[watch:$watch] $candidates candidate(s), $alerts alert(s), $notified notified"
}

/** What a banner needs beyond its text: where a click should go, and what it supersedes. */
typealias NotifyFn = (title: String, message: String, openUrl: String?, group: String?) -> Boolean

/**
 * Evaluates every enabled watch and announces what is genuinely new.
 *
 * Runs after each poll in the same loop as ingest (see `App.kt`), because a watch is only
 * as fresh as the observations it scores. Deliberately never throws out of [runAll]: a
 * broken watch or a failed banner must not stop deals from being fetched, matching how
 * `IngestService.runAll` isolates one provider's failure from the others.
 */
class WatchService(
    private val watches: WatchRepository,
    /**
     * Where this app is reachable, so a banner can link back to the watch that fired. Comes
     * from the running server rather than a constant, since the port is configurable and a
     * second copy may be on another one.
     */
    private val baseUrl: String = "http://localhost:8080",
    private val notifier: NotifyFn = Notifier::notify,
    /** The day's USD→COP rate, read per run so a refreshed TRM applies from the next cycle. */
    private val fx: () -> FxRate = { FxRate(java.math.BigDecimal("3200"), "config") },
) {

    fun runAll(dryRun: Boolean = false): List<WatchReport> =
        watches.enabled().map { watch ->
            runCatching { run(watch, dryRun) }.getOrElse { e ->
                println("[watch:${watch.name}] FAILED: ${e.message}")
                WatchReport(watch.name, 0, 0, 0)
            }
        }

    fun run(watch: Watch, dryRun: Boolean = false): WatchReport {
        val rate = fx()
        val candidates = watches.candidates(watch, rate)
        val alerts = WatchEvaluator.evaluate(watch, candidates, rate)

        if (dryRun) {
            println("[watch:${watch.name}] ${candidates.size} candidate(s); would alert on ${alerts.size}:")
            alerts.forEach { alert ->
                val title = candidates.firstOrNull { it.dealId == alert.dealId }?.title.orEmpty()
                val currency = candidates.firstOrNull { it.dealId == alert.dealId }?.currency ?: watch.currency
                println("    ${alert.reason} ${formatMoney(alert.price, currency)}  ${title.take(64)}")
                println("        ${alert.evidence}")
            }
            return WatchReport(watch.name, candidates.size, alerts.size, 0)
        }

        val pending = watches.recordAlerts(watch, alerts)
        val sent = announce(watch, pending)
        watches.markNotified(sent)

        val report = WatchReport(watch.name, candidates.size, alerts.size, sent.size)
        // Quiet when nothing happened: an hourly loop that logs "0 alerts" forever trains
        // you to stop reading it, the same reason the co-shipping verifier stays silent on
        // an idle cycle.
        if (alerts.isNotEmpty() || sent.isNotEmpty()) println(report.summary())
        return report
    }

    /**
     * One banner per watch, not per deal.
     *
     * A poll can turn over hundreds of rows, and a watch on a broad term can fire on a
     * dozen at once. Per-deal banners would bury the screen and the useful one with it, so
     * the cheapest hit leads and the rest are a count. The watch page carries the detail —
     * macOS notifications cannot link anywhere anyway.
     */
    private fun announce(watch: Watch, pending: List<PendingNotification>): List<PendingNotification> {
        if (pending.isEmpty()) return emptyList()

        // Ranked on the comparable price, so a US$399 TV and a 1.4M-peso one are ordered by what
        // they cost rather than by which number is smaller. Shown in the deal's own currency.
        val best = pending.minByOrNull { it.alert.comparablePrice ?: it.alert.price }!!
        val price = formatMoney(best.alert.price, best.currency) +
            (best.alert.comparablePrice?.let { " ≈ ${formatMoney(it, watch.currency)}" } ?: "")
        val others = pending.size - 1

        val headline = buildString {
            append(price).append(" · ").append(best.source)
            // "undercuts <rival>" rather than "cheaper elsewhere": the comparison only ever
            // spans the sources this app polls, and a Google search for the model code will
            // routinely turn up a retailer we do not. Naming the rival keeps the claim to
            // what was actually measured.
            append(if (best.alert.reason == MatchReason.CROSS_SITE_SPREAD) " · undercuts a rival" else " · new low")
            if (others > 0) append(" (+$others more)")
            append('\n').append(squashRepeats(best.title).take(80))
        }

        // Links to the watch rather than to the retailer: one banner can stand for several
        // hits ("+4 more"), and the watch page is the only place that shows all of them with
        // the evidence for each.
        val url = "$baseUrl/watches/${watch.id}"
        // Grouped per watch, so a watch that keeps finding better prices leaves one current
        // banner rather than a stack of superseded ones.
        val group = "watch-${watch.id}"

        // On failure the ledger stays unnotified, so the next cycle retries rather than
        // losing the alert silently.
        return if (notifier(watch.name, headline, url, group)) pending else emptyList()
    }

    companion object {
        /**
         * Collapses an immediately repeated word, for the notification text only.
         *
         * Exito duplicates model codes in its own titles — "Nevecón MABE Side By Side 480L
         * MSL480LMLPS0 MSL480LMLPS0", "NEVERA ELECTROLUX ... ERB163HB ERB163H" — and a banner
         * has about two lines to work with, so the repeat costs real space. Applied to the
         * banner and nowhere else: the feed and the export show titles exactly as the retailer
         * published them, because that is what you will see on their site.
         */
        internal fun squashRepeats(title: String): String {
            val words = title.split(" ").filter { it.isNotBlank() }
            return words.filterIndexed { i, w -> i == 0 || !w.equals(words[i - 1], ignoreCase = true) }
                .joinToString(" ")
        }
    }
}
