package com.luis.dealstracker.notify

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.GroceryFilter
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.db.ManualItemRepository
import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.formatMoney
import com.luis.dealstracker.web.collapseVariants
import com.luis.dealstracker.web.groceryStores
import com.luis.dealstracker.web.grocerySources
import com.luis.dealstracker.web.isPinnedOrAdded
import com.luis.dealstracker.web.mergeStores
import com.luis.dealstracker.web.trackedDeals
import com.luis.dealstracker.web.trackingSources
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The twice-daily Discord post of pinned items that are on sale. The rules live in
 * docs/pinned-notifications.md; the constants they name are here and in [GroceryRow].
 */

/**
 * The slot to send for now, or null. A slot is a configured time of day; the latest one already past
 * today is due if it has not been sent. Only the LATEST: a Mac that slept through 08:00 and wakes at
 * 20:00 sends the 19:00 post, not both -- the morning one would only be stale news.
 */
fun dueSlot(now: ZonedDateTime, times: List<LocalTime>, lastSlot: Instant?): Instant? {
    val slot = times.sorted().map { now.toLocalDate().atTime(it).atZone(now.zone) }.lastOrNull { !it.isAfter(now) }
        ?: return null
    return slot.toInstant().takeIf { lastSlot == null || lastSlot.isBefore(it) }
}

/** Parses `"08:00"` entries, dropping malformed ones. */
fun parseSlotTimes(specs: List<String>): List<LocalTime> =
    specs.mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }

/** Pinned groceries on the shelf and at least [GroceryRow.DEAL_PERCENT] under their usual price, deepest first. */
fun pinnedGroceryDeals(rows: List<GroceryRow>): List<GroceryRow> =
    rows.filter { it.pinnedAt != null && it.onShelf && it.isDeal }
        .sortedByDescending { it.belowUsual }

/** Why a tracked item counts as on sale. */
data class TrackedDeal(val deal: Deal, val belowUsual: BigDecimal?, val usual: BigDecimal?, val atLowest: Boolean)

/**
 * Tracked items that are on sale: [GroceryRow.DEAL_PERCENT] or more under their own usual price
 * (same definition as groceries, given [GroceryRow.MIN_DAYS] of history), or at their lowest
 * recorded price after having been higher. [usual] maps deal id to (median, days of history).
 */
fun trackedDealsOnSale(deals: List<Deal>, usual: Map<Long, Pair<BigDecimal, Int>>): List<TrackedDeal> =
    deals.mapNotNull { d ->
        val (median, days) = usual[d.id] ?: (null to 0)
        val below = median?.takeIf { days >= GroceryRow.MIN_DAYS && it.signum() > 0 }?.let {
            BigDecimal(100).subtract(d.price.multiply(BigDecimal(100)).divide(it, 1, RoundingMode.HALF_UP))
        }
        val underUsual = below != null && below >= GroceryRow.DEAL_PERCENT
        val atLowest = d.isAtLowestEver
        if (underUsual || atLowest) TrackedDeal(d, below?.takeIf { underUsual }, median.takeIf { underUsual }, atLowest) else null
    }.sortedWith(compareByDescending<TrackedDeal> { it.belowUsual ?: BigDecimal.ZERO }.thenBy { it.deal.title })

private fun link(title: String, url: String) = "[${title.replace("[", "(").replace("]", ")")}]($url)"

fun groceryLine(row: GroceryRow): String {
    val d = row.deal
    return "${link(d.title, d.url)} — **${formatMoney(d.price, d.currency)}** · −${row.belowUsual?.toPlainString()}% vs usual " +
        "${formatMoney(row.usual!!, d.currency)} · ${row.store}"
}

fun trackedLine(t: TrackedDeal): String {
    val d = t.deal
    val why = buildList {
        if (t.belowUsual != null) add("−${t.belowUsual.toPlainString()}% vs usual ${formatMoney(t.usual!!, d.currency)}")
        if (t.atLowest) add("lowest recorded")
    }.joinToString(" · ")
    return "${link(d.title, d.url)} — **${formatMoney(d.price, d.currency)}** · $why"
}

/** What a section would post: its description, or null when nothing is on sale (and nothing is sent). */
data class Section(val title: String, val lines: List<String>)

class PinnedAlerts(
    private val app: AppConfig,
    private val repo: DealRepository,
    private val groceries: GroceryRepository,
    private val manualItems: ManualItemRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val times = parseSlotTimes(app.defaults.pinnedAlertTimes)

    fun grocerySection(): Section {
        val rows = groceries.rows(GroceryFilter(grocerySources(app), pinnedOnly = true))
        val deals = pinnedGroceryDeals(mergeStores(rows, groceryStores(app)))
        return Section("Pinned groceries on sale", deals.map(::groceryLine))
    }

    fun trackedSection(): Section {
        val added = manualItems.trackedAsins()
        val pinned = collapseVariants(trackedDeals(repo, trackingSources(app)), added).rows
            .filter { it.coShipping != CoShipping.NO && isPinnedOrAdded(it, added) }
        val onSale = trackedDealsOnSale(pinned, groceries.usualPrices(pinned.map { it.id }))
        return Section("Pinned tracked items on sale", onSale.map(::trackedLine))
    }

    /** One pass of the scheduler: send each section whose slot is due. Returns what it did, for the log. */
    fun runDue(now: Instant = Instant.now()): List<String> {
        val at = now.atZone(zone)
        return listOf(
            Triple("groceries", app.discordGroceriesWebhook, SettingsRepository.PINNED_ALERT_GROCERIES_SLOT) to ::grocerySection,
            Triple("tracked", app.discordTrackedWebhook, SettingsRepository.PINNED_ALERT_TRACKED_SLOT) to ::trackedSection,
        ).mapNotNull { (meta, build) ->
            val (name, webhook, key) = meta
            // No webhook, no slot recorded: configuring one later should send the slot that is current then.
            if (webhook.isNullOrBlank()) return@mapNotNull null
            val slot = dueSlot(at, times, settings.get(key)?.let { Instant.parse(it) }) ?: return@mapNotNull null
            val section = build()
            val sent = if (section.lines.isEmpty()) true else deliver(DiscordWebhook(webhook), section, at)
            // An empty section counts as handled (nothing to say), a failed send does not (next tick retries).
            if (sent) settings.set(mapOf(key to slot.toString()))
            "[pinned-alerts] $name: ${if (section.lines.isEmpty()) "nothing on sale" else if (sent) "sent ${section.lines.size}" else "send failed"}"
        }
    }

    /** `--notify-pinned`: build both sections now, ignoring slots. [dryRun] prints instead of sending. */
    fun runNow(dryRun: Boolean): List<String> {
        val at = ZonedDateTime.now(zone)
        return listOf(
            Triple("groceries", app.discordGroceriesWebhook, ::grocerySection),
            Triple("tracked", app.discordTrackedWebhook, ::trackedSection),
        ).map { (name, webhook, build) ->
            val section = build()
            when {
                section.lines.isEmpty() -> "$name: nothing on sale, nothing sent"
                dryRun -> "$name: would send ${section.lines.size}:\n" + section.lines.joinToString("\n") { "  $it" }
                webhook.isNullOrBlank() -> "$name: no webhook configured (see .env.example)"
                else -> "$name: " + if (deliver(DiscordWebhook(webhook), section, at)) "sent ${section.lines.size}" else "send failed"
            }
        }
    }

    private fun deliver(webhook: DiscordWebhook, section: Section, at: ZonedDateTime): Boolean =
        webhook.send(
            title = section.title,
            description = fitDescription(section.lines),
            footer = "${at.toLocalDate()}  •  ${section.lines.size} item(s)",
        )
}
