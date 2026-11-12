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
import com.luis.dealstracker.web.itemName
import com.luis.dealstracker.web.sizeOf
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
 * The Discord posts of pinned items that are on sale: groceries twice a day as one list, tracked items
 * as soon as each goes on sale, one card apiece. The rules live in
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

/**
 * One pinned grocery as a Discord card. The picture sits beside the text, not under it: the post is a
 * list of a few dozen items twice a day, and full-width pictures would bury it.
 */
fun groceryEmbed(row: GroceryRow): DiscordEmbed {
    val d = row.deal
    return DiscordEmbed(
        title = d.title,
        url = d.url,
        description = "**${formatMoney(d.price, d.currency)}** · −${row.belowUsual?.toPlainString()}% vs usual ${formatMoney(row.usual!!, d.currency)}",
        imageUrl = d.imageUrl?.takeIf { it.startsWith("http") },
        footer = row.store,
        smallImage = true,
    )
}

fun trackedLine(t: TrackedDeal): String {
    val d = t.deal
    return "${link(d.title, d.url)} — **${formatMoney(d.price, d.currency)}** · ${trackedWhy(t)}"
}

/** The words after the price: why this item counts as on sale. */
fun trackedWhy(t: TrackedDeal): String = buildList {
    if (t.belowUsual != null) add("−${t.belowUsual.toPlainString()}% vs usual ${formatMoney(t.usual!!, t.deal.currency)}")
    if (t.atLowest) add("lowest recorded")
}.joinToString(" · ")

/** One tracked item as a Discord card: its name linking to the store, the price and why, and its picture. */
fun trackedEmbed(t: TrackedDeal): DiscordEmbed {
    val d = t.deal
    return DiscordEmbed(
        title = itemName(d.title),
        url = d.url,
        description = "**${formatMoney(d.price, d.currency)}** · ${trackedWhy(t)}",
        imageUrl = d.imageUrl?.takeIf { it.startsWith("http") },
        footer = listOfNotNull(sizeOf(d.title)?.let { "Size $it" }, d.brand, d.source).joinToString("  •  ").ifEmpty { null },
    )
}

/** A Slickdeals post about an item on the tracked list: the post, and the tracked row it is about. */
data class TrackedPost(val post: Deal, val tracked: Deal?)

/** How old a post may be and still be news. Older ones were there before the item was tracked, or before this existed. */
val POST_MAX_AGE: java.time.Duration = java.time.Duration.ofDays(7)

/**
 * The [posts] (active Slickdeals rows) that are about a tracked item: same ASIN as a row in
 * [tracked], and posted within [POST_MAX_AGE]. Slickdeals reads the ASIN off the Amazon link in the
 * post, so the match is exact -- no title guessing. Newest first.
 */
fun trackedPosts(posts: List<Deal>, tracked: List<Deal>, now: Instant): List<TrackedPost> {
    val byAsin = tracked.filter { it.asin != null }.associateBy { it.asin!! }
    return posts.filter { p -> p.asin != null && p.asin in byAsin && (p.postedAt ?: p.firstSeenAt).plus(POST_MAX_AGE).isAfter(now) }
        .sortedByDescending { it.postedAt ?: it.firstSeenAt }
        .map { TrackedPost(it, byAsin[it.asin]) }
}

/** A Slickdeals post as a Discord card: the post's own title and link, its price against the tracked one. */
fun postEmbed(p: TrackedPost): DiscordEmbed {
    val d = p.post
    val against = p.tracked?.takeIf { it.currency == d.currency }?.let { t ->
        when {
            d.price < t.price -> " · tracked at ${formatMoney(t.price, t.currency)} on Amazon, so ${formatMoney(t.price.subtract(d.price), d.currency)} less here"
            else -> " · tracked at ${formatMoney(t.price, t.currency)} on Amazon"
        }
    }.orEmpty()
    return DiscordEmbed(
        title = d.title,
        url = d.url,
        description = "**${formatMoney(d.price, d.currency)}** on Slickdeals$against",
        imageUrl = (d.imageUrl ?: p.tracked?.imageUrl)?.takeIf { it.startsWith("http") },
        footer = listOfNotNull("Slickdeals post", d.communityScore?.takeIf { it > 0 }?.let { "+$it votes" }, p.tracked?.let { "tracked: ${itemName(it.title)}".take(80) })
            .joinToString("  •  "),
        color = SLICKDEALS_BLUE,
    )
}

/** Told apart from an on-sale card (green) at a glance. */
const val SLICKDEALS_BLUE = 0x3498DB

/** What Discord was last told about a tracked item: the price, and when. */
data class SentAlert(val price: BigDecimal, val sentAt: Instant)

/** How long an announced item is remembered after it goes off sale, so a hovering price is not re-announced. */
val ALERT_MEMORY: java.time.Duration = java.time.Duration.ofHours(24)

/**
 * The items on sale that Discord has not heard about: never announced, or cheaper now than when they
 * were. An item still on sale at the announced price, or dearer, says nothing new.
 */
fun newTrackedAlerts(onSale: List<TrackedDeal>, sent: Map<Long, SentAlert>): List<TrackedDeal> =
    onSale.filter { t -> sent[t.deal.id]?.let { t.deal.price < it.price } ?: true }

/**
 * The announcements to forget: items no longer on sale, announced more than [ALERT_MEMORY] ago. Once
 * forgotten, the item's next sale is announced again.
 */
fun forgottenAlerts(onSaleIds: Set<Long>, sent: Map<Long, SentAlert>, now: Instant): Set<Long> =
    sent.filter { (id, alert) -> id !in onSaleIds && alert.sentAt.plus(ALERT_MEMORY).isBefore(now) }.keys

class PinnedAlerts(
    private val app: AppConfig,
    private val repo: DealRepository,
    private val groceries: GroceryRepository,
    private val manualItems: ManualItemRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The post times in force, read per check so the Config tab can move them without a restart. */
    private val timesFn: () -> List<String> = { app.defaults.pinnedAlertTimes },
    /** What Discord was already told about tracked items; null (the one-off CLI) announces nothing on its own. */
    private val trackedAlerts: TrackedAlertRepository? = null,
) {
    private val times get() = parseSlotTimes(timesFn())

    /** Pinned groceries on sale now, deepest first, each at its chain's cheaper store. */
    fun groceriesOnSale(): List<GroceryRow> {
        val rows = groceries.rows(GroceryFilter(grocerySources(app), pinnedOnly = true))
        return pinnedGroceryDeals(mergeStores(rows, groceryStores(app)))
    }

    /** Pinned or added tracked items (clothes and electronics alike) that are on sale now, deepest first. */
    fun trackedOnSale(): List<TrackedDeal> {
        val added = manualItems.trackedAsins()
        val pinned = collapseVariants(trackedDeals(repo, trackingSources(app)), added).rows
            .filter { it.coShipping != CoShipping.NO && isPinnedOrAdded(it, added) }
        return trackedDealsOnSale(pinned, groceries.usualPrices(pinned.map { it.id }))
    }

    /** One pass of the scheduler: the grocery post when its slot is due, tracked items as they go on sale. */
    fun runDue(now: Instant = Instant.now()): List<String> = listOfNotNull(groceriesDue(now), trackedNew(now))

    /** Groceries keep the fixed times of day: one list of everything pinned and on sale. */
    private fun groceriesDue(now: Instant): String? {
        val webhook = app.discordGroceriesWebhook
        val key = SettingsRepository.PINNED_ALERT_GROCERIES_SLOT
        // No webhook, no slot recorded: configuring one later should send the slot that is current then.
        if (webhook.isNullOrBlank()) return null
        val at = now.atZone(zone)
        val slot = dueSlot(at, times, settings.get(key)?.let { Instant.parse(it) }) ?: return null
        val rows = groceriesOnSale()
        val sent = if (rows.isEmpty()) true else deliver(DiscordWebhook(webhook), rows, at)
        // An empty list counts as handled (nothing to say), a failed send does not (next tick retries).
        if (sent) settings.set(mapOf(key to slot.toString()))
        return "[pinned-alerts] groceries: ${if (rows.isEmpty()) "nothing on sale" else if (sent) "sent ${rows.size}" else "send failed"}"
    }

    /**
     * Tracked items are announced when they are first seen on sale, not at a time of day: one card per
     * item, with its picture. Null when there was nothing new, which is nearly every tick.
     */
    private fun trackedNew(now: Instant): String? {
        val webhook = app.discordTrackedWebhook
        val store = trackedAlerts
        if (webhook.isNullOrBlank() || store == null) return null
        val onSale = trackedOnSale()
        val posts = trackedPostsNow(now)
        val sent = store.all()
        store.forget(forgottenAlerts((onSale.map { it.deal.id } + posts.map { it.post.id }).toSet(), sent, now))
        val fresh = newTrackedAlerts(onSale, sent)
        // A post is news once, and again only if it is edited to a lower price -- the same rule, on the post's own row.
        val freshPosts = posts.filter { p -> sent[p.post.id]?.let { p.post.price < it.price } ?: true }
        if (fresh.isEmpty() && freshPosts.isEmpty()) return null
        // Recorded only when Discord took them, so a failed send is retried on the next tick.
        val ok = DiscordWebhook(webhook).sendEmbeds(fresh.map(::trackedEmbed) + freshPosts.map(::postEmbed))
        if (ok) store.record(fresh.associate { it.deal.id to it.deal.price } + freshPosts.associate { it.post.id to it.post.price }, now)
        return "[pinned-alerts] tracked: " +
            if (ok) "sent ${fresh.size} on sale, ${freshPosts.size} Slickdeals post(s)" else "send failed"
    }

    /**
     * Slickdeals posts about pinned or added tracked items. The tracked list is Amazon's price; a post
     * can be the same item cheaper somewhere else, or with a coupon Amazon's page does not show.
     */
    fun trackedPostsNow(now: Instant = Instant.now()): List<TrackedPost> {
        val sources = app.enabledProviders.filter { "slickdeals" in it.type }.map { it.id }
        if (sources.isEmpty()) return emptyList()
        val added = manualItems.trackedAsins()
        val mine = trackedDeals(repo, trackingSources(app)).filter { isPinnedOrAdded(it, added) }
        if (mine.isEmpty()) return emptyList()
        val posts = repo.feed(com.luis.dealstracker.db.FeedFilter(sources = sources, sort = "price", limit = Int.MAX_VALUE))
        return trackedPosts(posts, mine, now)
    }

    /** `--notify-pinned`: post what is on sale now, ignoring times and what was already announced. [dryRun] prints instead. */
    fun runNow(dryRun: Boolean): List<String> {
        val at = ZonedDateTime.now(zone)
        val rows = groceriesOnSale()
        val groceries = when {
            rows.isEmpty() -> "groceries: nothing on sale, nothing sent"
            dryRun -> "groceries: would send ${rows.size}:\n" + rows.joinToString("\n") { "  ${groceryLine(it)}" }
            app.discordGroceriesWebhook.isNullOrBlank() -> "groceries: no webhook configured (see .env.example)"
            else -> "groceries: " + if (deliver(DiscordWebhook(app.discordGroceriesWebhook), rows, at)) "sent ${rows.size}" else "send failed"
        }
        val onSale = trackedOnSale()
        val tracked = when {
            onSale.isEmpty() -> "tracked: nothing on sale, nothing sent"
            dryRun -> "tracked: would send ${onSale.size}:\n" + onSale.joinToString("\n") { "  ${trackedLine(it)}" }
            app.discordTrackedWebhook.isNullOrBlank() -> "tracked: no webhook configured (see .env.example)"
            else -> "tracked: " + if (DiscordWebhook(app.discordTrackedWebhook).sendEmbeds(onSale.map(::trackedEmbed))) "sent ${onSale.size}" else "send failed"
        }
        val posts = trackedPostsNow()
        val slick = when {
            posts.isEmpty() -> "slickdeals: no recent post about a tracked item"
            dryRun -> "slickdeals: would send ${posts.size}:\n" + posts.joinToString("\n") { "  ${it.post.title} — ${formatMoney(it.post.price, it.post.currency)}" }
            app.discordTrackedWebhook.isNullOrBlank() -> "slickdeals: no webhook configured (see .env.example)"
            else -> "slickdeals: " + if (DiscordWebhook(app.discordTrackedWebhook).sendEmbeds(posts.map(::postEmbed))) "sent ${posts.size}" else "send failed"
        }
        return listOf(groceries, tracked, slick)
    }

    /** One card per grocery, under a line saying what the batch is. */
    private fun deliver(webhook: DiscordWebhook, rows: List<GroceryRow>, at: ZonedDateTime): Boolean =
        webhook.sendEmbeds(
            rows.map(::groceryEmbed),
            heading = "**Pinned groceries on sale** · ${at.toLocalDate()} · ${rows.size} item(s)",
        )
}

/** The tracked items Discord was told about (`tracked_alerts`, V44). */
class TrackedAlertRepository(private val ds: javax.sql.DataSource) {
    fun all(): Map<Long, SentAlert> = ds.connection.use { conn ->
        conn.prepareStatement("SELECT deal_id, price, sent_at FROM tracked_alerts").use { st ->
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), SentAlert(rs.getBigDecimal(2), rs.getTimestamp(3).toInstant())) }
            }
        }
    }

    fun record(prices: Map<Long, BigDecimal>, at: Instant) {
        if (prices.isEmpty()) return
        ds.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO tracked_alerts (deal_id, price, sent_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT (deal_id) DO UPDATE SET price = EXCLUDED.price, sent_at = EXCLUDED.sent_at",
            ).use { st ->
                prices.forEach { (id, price) ->
                    st.setLong(1, id); st.setBigDecimal(2, price); st.setTimestamp(3, java.sql.Timestamp.from(at)); st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    fun forget(ids: Set<Long>) {
        if (ids.isEmpty()) return
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM tracked_alerts WHERE deal_id = ANY (?)").use { st ->
                st.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
                st.executeUpdate()
            }
        }
    }
}
