package com.luis.dealstracker.web

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.db.SyncStatus
import com.luis.dealstracker.ingest.unitPriceLabel
import com.luis.dealstracker.model.formatMoney
import io.ktor.http.encodeURLParameter
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.MAIN
import kotlinx.html.TBODY
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.stream.createHTML
import java.math.BigDecimal

/** The providers the grocery tab reads: every enabled `vtex-grocery` one. */
internal fun grocerySources(app: AppConfig): List<String> =
    app.enabledProviders.filter { it.type == GROCERY_TYPE }.map { it.id }.sorted()

internal const val GROCERY_TYPE = "vtex-grocery"

/**
 * Each grocery provider's physical store, for the Store tag, and its chain, for [mergeStores].
 *
 * Falls back to the provider id without its `-grocery` suffix, so a provider added without the two
 * options still renders as its own chain rather than merging with something it should not.
 */
internal data class GroceryStore(val label: String, val chain: String)

internal fun groceryStores(app: AppConfig): Map<String, GroceryStore> =
    app.enabledProviders.filter { it.type == GROCERY_TYPE }.associate { p ->
        val fallback = p.id.removeSuffix("-grocery")
        p.id to GroceryStore(
            label = p.options["storeLabel"]?.takeIf { it.isNotBlank() } ?: fallback.replaceFirstChar { it.uppercase() },
            chain = p.options["chain"]?.takeIf { it.isNotBlank() } ?: fallback,
        )
    }

/**
 * One row per product per chain, at the cheapest store stocking it today, with the rest as [GroceryRow.others].
 *
 * The same product at two Exitos has one VTEX product id and two deal rows, one per store. Kept as
 * two rows underneath so each store's history -- and so "vs usual" -- only ever compares a store with
 * itself: a merged history would call the Colombia shelf price a 30% drop from the Laureles one.
 *
 * Off-the-shelf copies lose to on-shelf ones whatever their price, since a price you cannot pay at
 * that store today is not the cheaper option. Pinned-ness is the chain's, not the store's: a product
 * shows as pinned if any of its copies is. Ties go to the store listed first in config.
 */
internal fun mergeStores(rows: List<GroceryRow>, stores: Map<String, GroceryStore>): List<GroceryRow> {
    val order = stores.keys.withIndex().associate { (i, id) -> id to i }
    val labelled = rows.map { it.copy(store = stores[it.deal.source]?.label ?: storeName(it.deal.source)) }
    return labelled
        .groupBy { Triple(stores[it.deal.source]?.chain ?: it.deal.source, it.deal.merchant, it.deal.sourceDealId) }
        .values
        .map { copies ->
            val ranked = copies.sortedWith(
                compareByDescending<GroceryRow> { it.onShelf }
                    .thenBy { it.deal.price }
                    .thenBy { order[it.deal.source] ?: Int.MAX_VALUE },
            )
            val best = ranked.first()
            best.copy(
                others = ranked.drop(1),
                pinnedAt = copies.mapNotNull { it.pinnedAt }.minOrNull(),
            )
        }
}

/**
 * The grocery tab's three screens, as the feed has All deals / Favorites: Deals (the default),
 * everything on the shelf for finding a staple, and the staples pinned so far.
 */
internal enum class GroceryView(val key: String, val label: String) {
    DEALS("deals", "Deals"),
    ALL("all", "All items"),
    PINNED("pinned", "Pinned");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: DEALS
    }
}

internal enum class GrocerySort(val key: String, val label: String) {
    /** Default on Deals: furthest under its own usual price first. */
    BELOW("below", "vs usual"),

    /** Cheapest per 100 g / L / unit first -- how two pack sizes are compared. Unknown sizes last. */
    UNIT("unit", "Unit price"),

    /** The store's own advertised discount, deepest first; rows with none last. */
    STORE("store", "Store %"),

    /**
     * Deepest card-only discount first, measured against the shelf price -- how much the card saves
     * over paying cash today. Rows with no card price last.
     */
    CARD("card", "Card"),
    PRICE("price", "Price"),

    /** Default on All items, where there is no deal to rank by. */
    NAME("name", "Item");

    companion object {
        fun of(value: String?, view: GroceryView) =
            entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) }
                ?: if (view == GroceryView.DEALS) BELOW else NAME
    }
}

/** The page's controls, parsed once so the page and the Ask panel read the same state. */
internal data class GroceryControls(
    val view: GroceryView,
    val groups: Set<String>,
    val search: String?,
    val sort: GrocerySort,
) {
    fun url(
        view: GroceryView = this.view,
        groups: Set<String> = this.groups,
        sort: GrocerySort? = this.sort,
        search: String? = this.search,
    ): String {
        val params = buildList {
            if (view != GroceryView.DEALS) add("view=${view.key}")
            if (groups.isNotEmpty() && view != GroceryView.PINNED) add("group=" + groups.sorted().joinToString(",").encodeURLParameter())
            // Search belongs to All items; carrying it onto Deals would filter a view with no box.
            if (view == GroceryView.ALL) search?.takeIf { it.isNotBlank() }?.let { add("q=" + it.encodeURLParameter()) }
            if (sort != null && sort != GrocerySort.of(null, view)) add("sort=${sort.key}")
        }
        return if (params.isEmpty()) "/groceries" else "/groceries?" + params.joinToString("&")
    }

    companion object {
        fun from(params: io.ktor.http.Parameters): GroceryControls {
            val view = GroceryView.of(params["view"])
            return GroceryControls(
                view = view,
                groups = params["group"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                search = params["q"]?.trim()?.takeIf { it.isNotEmpty() && view == GroceryView.ALL },
                sort = GrocerySort.of(params["sort"], view),
            )
        }
    }
}

/**
 * The rows the main table shows, in its order.
 *
 * Shared with the Ask panel so an answer is about these rows and no others. Pinned rows are not
 * excluded from Deals or All items: a pinned staple that is on a deal belongs in the deal list too.
 *
 * Pinned is the one view that keeps rows off the shelf. A staple Laureles stopped stocking is still
 * on the shopping list, and vanishing would read as "the pin was lost" rather than "out of stock".
 */
internal fun groceryShown(rows: List<GroceryRow>, controls: GroceryControls): List<GroceryRow> {
    val inView = when (controls.view) {
        GroceryView.DEALS -> rows.filter { it.onShelf && it.isDeal }
        GroceryView.ALL -> rows.filter { it.onShelf }
        GroceryView.PINNED -> rows.filter { it.pinnedAt != null }
    }
    return inView.sortedWith(groceryComparator(controls.sort))
}

/** Every ordering puts "unknown" last rather than reading it as zero, as the other tabs do. */
internal fun groceryComparator(sort: GrocerySort): Comparator<GroceryRow> = when (sort) {
    GrocerySort.BELOW -> compareByDescending<GroceryRow> { it.belowUsual ?: BigDecimal("-1000") }
        .thenBy { it.deal.title }
    GrocerySort.UNIT -> compareBy<GroceryRow> { it.unitPrice == null }
        .thenBy { it.size?.measure }
        .thenBy { it.unitPrice }
        .thenBy { it.deal.title }
    GrocerySort.CARD -> compareByDescending<GroceryRow> { r ->
        r.card?.let { BigDecimal.ONE - it.price.divide(r.deal.price, 6, java.math.RoundingMode.HALF_UP) } ?: BigDecimal("-1")
    }.thenBy { it.deal.title }
    GrocerySort.STORE -> compareByDescending<GroceryRow> { it.deal.storeDiscountPercent ?: -1 }
        .thenBy { it.deal.title }
    GrocerySort.PRICE -> compareBy<GroceryRow> { it.deal.price }.thenBy { it.deal.title }
    GrocerySort.NAME -> compareBy<GroceryRow> { it.deal.title.lowercase() }.thenBy { it.deal.source }
}

/**
 * `/groceries`: Exito and Carulla's food aisles at Laureles, judged against each item's own history.
 *
 * A different question from the feed, so a different screen. The feed ranks advertised discounts; a
 * grocery list price is close to meaningless (Exito flags ~94% of its catalogue as discounted), so
 * the only claim this page makes is "cheaper than this item usually is HERE", measured by us.
 *
 * Silent for its first [GroceryRow.MIN_DAYS] days by design, and it says so rather than looking
 * broken -- the same honesty the watches and the Tracked tab keep about thin history.
 */
internal fun MAIN.groceryPage(
    rows: List<GroceryRow>,
    groupCounts: List<Pair<String, Int>>,
    controls: GroceryControls,
    sources: List<String>,
    pinnedCount: Int = 0,
    sync: List<SyncLine> = emptyList(),
    storage: com.luis.dealstracker.db.StorageLine? = null,
) {
    groceryBody(rows, groupCounts, controls, sources, pinnedCount)
    // After the table, whichever early return the body took: "when were these prices read?" matters
    // most on exactly the screens that show nothing, like an empty Deals view or a first run.
    syncFooter(sync)
    storage?.let { st ->
        p("tracked-note") {
            title = "Price readings, distinct promotions and banner configs kept for later analysis"
            +("Database ${"%.0f".format(st.databaseBytes / 1_048_576.0)} MB · " +
                "${String.format("%,d", st.observations)} price readings · " +
                "${String.format("%,d", st.promotions)} promotions · ${String.format("%,d", st.banners)} banner configs")
        }
    }
}

private fun MAIN.groceryBody(
    rows: List<GroceryRow>,
    groupCounts: List<Pair<String, Int>>,
    controls: GroceryControls,
    sources: List<String>,
    pinnedCount: Int,
) {
    p("tracked-intro") {
        +"Exito and Carulla's grocery aisles, priced and stocked at "
        span("strong") { +"Exito Laureles, Exito Colombia and Carulla Laureles" }
        +". Each Exito item is shown at the cheaper of the two Exitos, tagged with which. A deal here means at least ${GroceryRow.DEAL_PERCENT}% under the item's own usual price — the median of its "
        +"daily prices over the last 30 days — never the store's \"before\" price. Read at 07:00 and 15:00."
    }

    if (sources.isEmpty()) {
        div("empty") {
            +"No grocery providers are enabled. Add one with "
            code { +"type: $GROCERY_TYPE" }
            +" in config/sources.yaml."
        }
        return
    }
    // "Never crawled" is read off the aisle counts, not [rows]: an empty Pinned view is a normal state
    // and must still show the tabs, not claim the stores have never been read.
    if (groupCounts.isEmpty()) {
        div("empty") {
            +"Nothing read yet. The first crawl runs on the next poll, or now with "
            code { +"./dt run --args=\"--fetch-once --source=${sources.first()}\"" }
            +"."
        }
        return
    }

    // The same segmented control the feed uses for All deals / Favorites, so the two tabs read alike.
    div("scopes") {
        GroceryView.entries.forEach { v ->
            val on = v == controls.view
            a(href = controls.url(view = v, sort = null), classes = if (on) "scope on" else "scope") {
                if (on) attributes["aria-current"] = "page"
                +v.label
                // Shown only when there are some, as on Favorites: "Pinned 0" reads as a broken counter.
                if (v == GroceryView.PINNED && pinnedCount > 0) span("scope-count") { +"$pinnedCount" }
            }
        }
    }

    if (controls.view == GroceryView.ALL) {
        form(classes = "grocery-search") {
            attributes["method"] = "get"
            attributes["action"] = "/groceries"
            input(type = InputType.hidden, name = "view") { value = GroceryView.ALL.key }
            if (controls.groups.isNotEmpty()) {
                input(type = InputType.hidden, name = "group") { value = controls.groups.sorted().joinToString(",") }
            }
            input(type = InputType.search, name = "q") {
                value = controls.search.orEmpty()
                placeholder = "leche | huevos | café"
                title = "Title search, accent-insensitive. Separate terms with | to match any of them."
            }
            button { +"Search" }
        }
    }

    // Not on Pinned, which is the whole list whatever aisle is picked.
    if (controls.view != GroceryView.PINNED) groupTags(groupCounts, controls)

    val shown = groceryShown(rows, controls)
    val young = rows.count { it.onShelf && it.days < GroceryRow.MIN_DAYS }
    div("tracked-count") {
        +"${shown.size} item(s)"
        if (controls.view == GroceryView.DEALS && young > 0) {
            // On the first days almost every row is young, and an empty Deals view otherwise reads as
            // "nothing is on offer" rather than "not enough history to judge".
            span("tracked-note") {
                +(" · $young of ${rows.count { it.onShelf }} on the shelf have under ${GroceryRow.MIN_DAYS} days of " +
                    "history and cannot be judged yet")
            }
        }
    }

    if (shown.isEmpty()) {
        div("empty") {
            when (controls.view) {
                GroceryView.DEALS -> {
                    +"Nothing is ${GroceryRow.DEAL_PERCENT}% under its usual price right now. "
                    a(href = controls.url(view = GroceryView.ALL, sort = null)) { +"Browse all items" }
                    +" to pin staples in the meantime."
                }
                GroceryView.PINNED -> {
                    +"Nothing pinned yet. Use ☆ pin on "
                    a(href = controls.url(view = GroceryView.ALL, sort = null)) { +"All items" }
                    +" to keep staples here."
                }
                GroceryView.ALL -> +"No items match."
            }
        }
        return
    }

    // Capped for the browser's sake. All items can be ~15,000 rows; the count above is the real total.
    val cap = 1000
    groceryTable(shown.take(cap), controls)
    if (shown.size > cap) {
        p("tracked-note") { +"Showing the first $cap of ${shown.size}. Narrow with a subcategory or a search." }
    }
}

private fun MAIN.groupTags(groupCounts: List<Pair<String, Int>>, controls: GroceryControls) {
    if (groupCounts.isEmpty()) return
    div("typetags") {
        span("typetags-label") { +"Aisle" }
        groupCounts.forEach { (group, n) ->
            val on = group in controls.groups
            val next = if (on) controls.groups - group else controls.groups + group
            a(classes = if (on) "typetag on" else "typetag", href = controls.url(groups = next)) {
                +group
                span("typetag-n") { +n.toString() }
            }
        }
        if (controls.groups.isNotEmpty()) {
            a(classes = "typetag clear", href = controls.url(groups = emptySet())) { +"clear" }
        }
    }
}

private fun FlowContent.groceryTable(rows: List<GroceryRow>, controls: GroceryControls) {
    table("deals tracked grocery") {
        thead {
            tr {
                th(classes = "col-thumb") { attributes["aria-label"] = "image" }
                sortableTh("Item", GrocerySort.NAME, controls, null)
                th(classes = "col-size") { +"Store" }
                // Two sorts in one header: by the shelf price, or by how much a card saves on it today.
                th(classes = "col-price") {
                    title = "Shelf price, what anyone pays. The chip beneath is the card-only price where the store runs one."
                    a(href = controls.url(sort = GrocerySort.PRICE), classes = if (controls.sort == GrocerySort.PRICE) "sorted" else null) {
                        +"Price"
                        if (controls.sort == GrocerySort.PRICE) span("dir") { +" ↓" }
                    }
                    +" "
                    a(href = controls.url(sort = GrocerySort.CARD), classes = if (controls.sort == GrocerySort.CARD) "sorted" else null) {
                        title = "Biggest card saving over the shelf price first"
                        +"· card"
                        if (controls.sort == GrocerySort.CARD) span("dir") { +" ↓" }
                    }
                }
                sortableTh(
                    "Unit price", GrocerySort.UNIT, controls, "col-price",
                    "Per 100 g, per litre or per unit, from the pack size in the listing. Blank when the " +
                        "listing states none — never guessed.",
                )
                th(classes = "col-price") {
                    title = "Median of this item's daily prices at this store over the last 30 days, today excluded"
                    +"Usual"
                }
                sortableTh(
                    "Store %", GrocerySort.STORE, controls, "col-disc",
                    "The store's advertised discount against its own list price, as its page shows it " +
                        "(rounded down). A claim by the store; \"vs usual\" is what we measured.",
                )
                sortableTh(
                    "vs usual", GrocerySort.BELOW, controls, "col-disc",
                    "How far today's price is under the usual one. Blank until ${GroceryRow.MIN_DAYS} " +
                        "earlier days are recorded.",
                )
                th(classes = "col-price") {
                    title = "Cheapest daily price in the last 30 days, today excluded"
                    +"30d low"
                }
                th(classes = "col-chg") {
                    title = "Earlier days of history the usual price rests on"
                    +"Days"
                }
                th(classes = "col-act") { +"" }
            }
        }
        tbody { rows.forEach { groceryRow(it, controls.view) } }
    }
}

private fun kotlinx.html.TR.sortableTh(
    label: String,
    sort: GrocerySort,
    controls: GroceryControls?,
    classes: String?,
    tooltip: String? = null,
) {
    th(classes = classes) {
        tooltip?.let { title = it }
        if (controls == null) {
            +label
        } else {
            a(href = controls.url(sort = sort), classes = if (controls.sort == sort) "sorted" else null) {
                +label
                if (controls.sort == sort) span("dir") { +" ↓" }
            }
        }
    }
}

internal fun TBODY.groceryRow(row: GroceryRow, view: GroceryView = GroceryView.DEALS) {
    val deal = row.deal
    tr(classes = if (row.onShelf) null else "paused") {
        id = "grocery-${deal.id}"
        td(classes = "col-thumb") {
            deal.imageUrl?.let { src ->
                a(href = deal.url) {
                    attributes["target"] = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    img(classes = "thumb", src = src, alt = "") { attributes["loading"] = "lazy" }
                }
            }
        }
        td {
            a(classes = "title", href = deal.url) {
                attributes["target"] = "_blank"
                attributes["rel"] = "noopener noreferrer"
                +deal.title
            }
            div("meta") {
                row.group?.let { span("cat") { +it } }
                deal.brand?.let { span { +it } }
                if (!row.onShelf) span("chip chip-expired") {
                    title = "Not in its store's latest crawl — out of stock at Laureles, or delisted. " +
                        "The price is the last one seen."
                    +"unavailable"
                }
            }
        }
        // Which store the price is from, and what the chain's other store charges. Both shown, because
        // "cheaper at Colombia by 2,660" is a reason to walk to Colombia, and a bare tag is not.
        td(classes = "col-size") {
            span("size-chip") {
                if (row.others.isNotEmpty()) title = "Cheapest of ${row.others.size + 1} stores stocking it"
                +row.store.ifEmpty { storeName(deal.source) }
            }
            row.others.forEach { other ->
                div("store-alt") {
                    title = if (other.onShelf) "Same product at ${other.store}" else "${other.store}: not in its latest crawl"
                    +"${other.store.substringAfterLast(' ')} "
                    if (other.onShelf) +formatMoney(other.deal.price, other.deal.currency) else +"out"
                }
            }
        }
        // The shelf price stays the main number -- what anyone pays. The card price sits under it as
        // a chip, because it is conditional: only a Mastercard (or only the store's own card) gets it.
        td(classes = "col-price") {
            div("price") { +formatMoney(deal.price, deal.currency) }
            row.card?.let { card -> cardChip(card, deal.currency) }
        }
        td(classes = "col-price") {
            val unit = row.unitPrice
            if (unit == null) span("muted") { +"—" } else {
                +formatMoney(unit, deal.currency)
                span("muted") { +unitPriceLabel(row.size) }
            }
        }
        td(classes = "col-price") {
            row.usual?.takeIf { row.days >= GroceryRow.MIN_DAYS }?.let { +formatMoney(it, deal.currency) }
                ?: span("muted") { +"—" }
        }
        td(classes = "col-disc") {
            deal.storeDiscountPercent?.let { span("disc disc-s${discountTier(BigDecimal(it))}") { +"−$it%" } }
                ?: span("muted") { +"—" }
        }
        td(classes = "col-disc") {
            val below = row.belowUsual
            when {
                below == null -> span("muted") { title = "Under ${GroceryRow.MIN_DAYS} days of history"; +"new" }
                below.signum() > 0 -> span(if (row.isDeal) "lowbadge" else "chg chg-down") { +"−${below.toPlainString()}%" }
                below.signum() < 0 -> span("chg chg-up") { +"+${below.negate().toPlainString()}%" }
                else -> span("muted") { +"0%" }
            }
        }
        td(classes = "col-price") {
            row.low?.takeIf { row.days >= 1 }?.let { +formatMoney(it, deal.currency) } ?: span("muted") { +"—" }
        }
        td(classes = "col-chg") { +row.days.toString() }
        td(classes = "col-act") {
            div("actions") {
                a(href = "/deal/${deal.id}") { +"history" }
                val pinned = row.pinnedAt != null
                button(classes = if (pinned) "fav-btn on" else "fav-btn") {
                    attributes["hx-post"] = "/groceries/${deal.id}/${if (pinned) "unpin" else "pin"}?view=${view.key}"
                    attributes["hx-target"] = "#grocery-${deal.id}"
                    attributes["hx-swap"] = "outerHTML"
                    attributes["aria-pressed"] = pinned.toString()
                    title = if (pinned) "Unpin this staple" else "Pin as a staple, listed under Pinned"
                    +if (pinned) "★ pinned" else "☆ pin"
                }
            }
        }
    }
}

/** `carulla-grocery` -> `Carulla`. The provider id carries the store; the tab only needs its name. */
internal fun storeName(source: String): String =
    source.removeSuffix("-grocery").replaceFirstChar { it.uppercase() }

/** One row as a bare `<tr>`, for the htmx swap after a pin toggle. */
internal fun groceryRowFragment(row: GroceryRow, view: GroceryView): String =
    createHTML(prettyPrint = false).table { tbody { groceryRow(row, view) } }
        .substringAfter("<tbody>").substringBeforeLast("</tbody>")

/** One store's line in the sync footer: its crawl history plus when the schedule runs it next. */
internal data class SyncLine(
    val store: String,
    val status: SyncStatus,
    val next: java.time.Instant?,
    /** The schedule, as written for the footer: "07:00-07:40, 10:30-11:10", or "07:00, 15:00". */
    val schedule: String?,
) {
    /** The older constructor shape, for fixed `runAtHours`. */
    constructor(store: String, status: SyncStatus, next: java.time.Instant?, hours: List<Int>?) :
        this(store, status, next, hours?.joinToString(", ") { "%02d:00".format(it) })
}

/**
 * When each store's prices were read, as the page's last word on them.
 *
 * Leads with the last SUCCESSFUL read, since that is how old the prices on screen are. A failed or
 * running crawl is said beside it rather than instead of it: after a failed 15:00 run the page still
 * shows 07:00's prices, and "failed" alone would leave the reader guessing which prices those are.
 *
 * Times are local clock times ("today 07:02") as well as relative ones. A crawl is scheduled on the
 * clock, and "7h ago" makes the reader do arithmetic to tell whether it was the morning run.
 */
private fun MAIN.syncFooter(lines: List<SyncLine>) {
    if (lines.isEmpty()) return
    div("runs grocery-sync") {
        h2 { +"Last sync" }
        table {
            thead {
                tr {
                    th { +"Store" }
                    th { +"Prices read" }
                    th { +"Items" }
                    th { +"Latest run" }
                    th { +"Next" }
                }
            }
            tbody {
                lines.forEach { line ->
                    val st = line.status
                    tr {
                        td { +line.store }
                        td {
                            st.lastSuccess?.let {
                                +clockTime(it)
                                span("muted") { +" · ${relativeAgo(it)}" }
                            } ?: span("muted") { +"never" }
                        }
                        td { +(st.lastSuccessItems?.let { String.format("%,d", it) } ?: "—") }
                        td {
                            when {
                                st.running -> +"running since ${clockTime(st.lastStarted!!)}…"
                                st.interrupted -> span("err") {
                                    title = "Started and never finished — the app was stopped mid-crawl. The next scheduled run retries."
                                    +"interrupted ${clockTime(st.lastStarted!!)}"
                                }
                                st.failed -> span("err") {
                                    title = st.lastError ?: "The run finished with 0 items"
                                    +"failed ${clockTime(st.lastStarted!!)}"
                                    +": ${(st.lastError ?: "0 items read").take(80)}"
                                }
                                st.lastStarted != null -> +"ok"
                                else -> span("muted") { +"—" }
                            }
                        }
                        td {
                            line.next?.let { +clockTime(it) } ?: span("muted") { +"every poll" }
                            line.schedule?.let { sched ->
                                span("muted") { +" · daily at $sched" }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "today 07:02", "yesterday 15:01", or "Sep 23 07:00" -- local time, which is what the schedule uses. */
internal fun clockTime(at: java.time.Instant, now: java.time.Instant = java.time.Instant.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val t = at.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val hm = "%02d:%02d".format(t.hour, t.minute)
    return when (t.toLocalDate()) {
        today -> "today $hm"
        today.minusDays(1) -> "yesterday $hm"
        today.plusDays(1) -> "tomorrow $hm"
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)) + " $hm"
    }
}

/** The footer's lines, in config order, each with the next run the poller will make. */
internal fun grocerySyncLines(
    app: AppConfig,
    statuses: List<SyncStatus>,
    now: java.time.Instant = java.time.Instant.now(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): List<SyncLine> {
    val stores = groceryStores(app)
    val bySource = statuses.associateBy { it.source }
    return app.enabledProviders.filter { it.type == GROCERY_TYPE }.mapNotNull { p ->
        val status = bySource[p.id] ?: return@mapNotNull null
        val windows = com.luis.dealstracker.ingest.runWindows(p)
        val hours = com.luis.dealstracker.ingest.runAtHours(p)
        SyncLine(
            store = stores[p.id]?.label ?: storeName(p.id),
            status = status,
            // Windows win over fixed hours, as they do in IngestService.isDue.
            next = windows?.let { com.luis.dealstracker.ingest.nextWindowSlot(it, p.id, now, zone) }
                ?: hours?.let { com.luis.dealstracker.ingest.nextSlot(it, now, zone) },
            schedule = windows?.joinToString(", ") ?: hours?.joinToString(", ") { "%02d:00".format(it) },
        )
    }
}

/**
 * "Mastercard −20% → $3,280", or "Store card only −30% → $7,000".
 *
 * Store-card-only offers are marked in words and in colour, since most Mastercards will not get them:
 * the store's own card is a Mastercard, but not every Mastercard is the store's card.
 */
private fun FlowContent.cardChip(card: com.luis.dealstracker.model.CardOffer, currency: String) {
    val storeOnly = card.scope == com.luis.dealstracker.model.CardScope.STORE_CARD
    val pct = card.percent.stripTrailingZeros().toPlainString()
    div(if (storeOnly) "card-price store-only" else "card-price") {
        title = when (card.scope) {
            com.luis.dealstracker.model.CardScope.MASTERCARD ->
                "With any Mastercard, including Tarjeta Éxito y Carulla / Carulla Mastercard. " +
                    "$pct% off the price before any shelf promotion — card promotions change daily."
            com.luis.dealstracker.model.CardScope.STORE_CARD ->
                "Only with the store's own card (Tarjeta Éxito y Carulla / Carulla Mastercard), " +
                    "not other Mastercards. Card promotions change daily."
            com.luis.dealstracker.model.CardScope.OTHER ->
                "With a specific payment method this app does not recognise yet. Card promotions change daily."
        }
        +"${if (storeOnly) "Store card only" else card.scope.label} −$pct% → ${formatMoney(card.price, currency)}"
    }
}
