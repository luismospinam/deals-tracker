package com.luis.dealstracker.web

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.aisleLabel
import com.luis.dealstracker.i18n.tr
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

/** The providers the grocery tab reads: every enabled one of a [GROCERY_TYPES] type. */
internal fun grocerySources(app: AppConfig): List<String> =
    app.enabledProviders.filter { it.type in GROCERY_TYPES }.map { it.id }.sorted()

internal const val GROCERY_TYPE = "vtex-grocery"

/**
 * Every provider type whose rows belong on the grocery tab rather than the feed.
 *
 * A set, not one type, since PriceSmart (2026-09-27) is not VTEX: its adapter differs, but its rows are
 * the same kind of thing -- a store's whole food catalogue, priced at one physical store -- and go
 * through the same tab, merge, usual price and schedule. Lives with the adapters' types in
 * [com.luis.dealstracker.sources.GROCERY_PROVIDER_TYPES] so ingest can read it without the web layer.
 */
internal val GROCERY_TYPES: Set<String> get() = com.luis.dealstracker.sources.GROCERY_PROVIDER_TYPES

/**
 * Each grocery provider's physical store, for the Store tag, and its chain, for [mergeStores].
 *
 * Falls back to the provider id without its `-grocery` suffix, so a provider added without the two
 * options still renders as its own chain rather than merging with something it should not.
 */
internal data class GroceryStore(val label: String, val chain: String)

internal fun groceryStores(app: AppConfig): Map<String, GroceryStore> =
    app.enabledProviders.filter { it.type in GROCERY_TYPES }.associate { p ->
        val fallback = p.id.removeSuffix("-grocery")
        p.id to GroceryStore(
            label = p.options["storeLabel"]?.takeIf { it.isNotBlank() } ?: fallback.replaceFirstChar { it.uppercase() },
            chain = p.options["chain"]?.takeIf { it.isNotBlank() } ?: fallback,
        )
    }

/**
 * The providers to read for [chains], or all of [stores] when none is chosen. An unknown chain matches
 * nothing, so a stale or hand-edited URL shows an empty page with a way back, rather than every store.
 */
internal fun sourcesForChains(stores: Map<String, GroceryStore>, chains: Set<String>): List<String> =
    if (chains.isEmpty()) stores.keys.sorted() else stores.filterValues { it.chain in chains }.keys.sorted()

/**
 * Each chain's name for the store filter: the first word its stores' labels share -- "Exito Laureles"
 * and "Exito Colombia" give "Exito", "PriceSmart Las Américas" gives "PriceSmart".
 */
internal fun chainNames(stores: Map<String, GroceryStore>): Map<String, String> =
    stores.values.groupBy { it.chain }.mapValues { (chain, members) ->
        members.map { it.label.substringBefore(' ') }.distinct().singleOrNull()
            ?: chain.replaceFirstChar { it.uppercase() }
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
internal enum class GroceryView(val key: String, val label: String, private val labelEs: String) {
    DEALS("deals", "Deals", "Ofertas"),
    ALL("all", "All items", "Todo"),
    PINNED("pinned", "Pinned", "Fijados");

    /** [label] in the active language. [label] itself stays English: the Ask context and tests read it. */
    fun display(): String = tr(label, labelEs)

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: DEALS
    }
}

internal enum class GrocerySort(val key: String, val label: String) {
    /** Default on Deals and Pinned: furthest under its own usual price first. */
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
                ?: if (view == GroceryView.ALL) NAME else BELOW
    }
}

/** The page's controls, parsed once so the page and the Ask panel read the same state. */
internal data class GroceryControls(
    val view: GroceryView,
    val groups: Set<String>,
    val search: String?,
    val sort: GrocerySort,
    /**
     * Chains to show -- `exito`, `carulla`, `pricesmart` -- or empty for all. By CHAIN, not provider:
     * the tab already shows one row per product per chain at its cheaper store, so "Exito" means both
     * Exitos, and the row keeps the other Exito's price beneath it.
     */
    val chains: Set<String> = emptySet(),
) {
    fun url(
        view: GroceryView = this.view,
        groups: Set<String> = this.groups,
        sort: GrocerySort? = this.sort,
        search: String? = this.search,
        chains: Set<String> = this.chains,
    ): String {
        val params = buildList {
            if (view != GroceryView.DEALS) add("view=${view.key}")
            // Unlike the aisle, the store filter applies on Pinned too: "my staples at PriceSmart" is a
            // shopping list for one trip.
            if (chains.isNotEmpty()) add("store=" + chains.sorted().joinToString(",").encodeURLParameter())
            if (groups.isNotEmpty() && view != GroceryView.PINNED) add("group=" + groups.sorted().joinToString(",").encodeURLParameter())
            // Pinned has no box, so carrying a search onto it would filter a view with nothing to clear it.
            if (view != GroceryView.PINNED) search?.takeIf { it.isNotBlank() }?.let { add("q=" + it.encodeURLParameter()) }
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
                search = params["q"]?.trim()?.takeIf { it.isNotEmpty() && view != GroceryView.PINNED },
                sort = GrocerySort.of(params["sort"], view),
                chains = params["store"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
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
    /** Chain id -> (display name, products on the shelf), for the store filter. */
    chainCounts: List<Triple<String, String, Int>> = emptyList(),
) {
    groceryBody(rows, groupCounts, controls, sources, pinnedCount, chainCounts)
    // After the table, whichever early return the body took: "when were these prices read?" matters
    // most on exactly the screens that show nothing, like an empty Deals view or a first run.
    syncFooter(sync)
    storage?.let { st ->
        p("tracked-note") {
            title = tr(
                "Price readings, distinct promotions and banner configs kept for later analysis",
                "Lecturas de precio, promociones distintas y configuraciones de banners guardadas para análisis",
            )
            val mb = "%.0f".format(st.databaseBytes / 1_048_576.0)
            val obs = String.format("%,d", st.observations)
            val promos = String.format("%,d", st.promotions)
            val banners = String.format("%,d", st.banners)
            +tr(
                "Database $mb MB · $obs price readings · $promos promotions · $banners banner configs",
                "Base de datos $mb MB · $obs lecturas de precio · $promos promociones · $banners configuraciones de banners",
            )
        }
    }
}

private fun MAIN.groceryBody(
    rows: List<GroceryRow>,
    groupCounts: List<Pair<String, Int>>,
    controls: GroceryControls,
    sources: List<String>,
    pinnedCount: Int,
    chainCounts: List<Triple<String, String, Int>>,
) {
    p("tracked-intro") {
        +tr(
            "Exito, Carulla and PriceSmart's grocery aisles, priced and stocked at ",
            "Los pasillos de mercado de Exito, Carulla y PriceSmart, con precio y existencias de ",
        )
        span("strong") {
            +tr(
                "Exito Laureles and Colombia, Carulla Laureles, and PriceSmart Las Américas and El Poblado",
                "Exito Laureles y Colombia, Carulla Laureles, y PriceSmart Las Américas y El Poblado",
            )
        }
        +tr(
            ". An item sold at two stores of one chain is shown once, at the cheaper one, tagged with which. A deal here means at least ${GroceryRow.DEAL_PERCENT}% under the item's own usual price — the median of its ",
            ". Un producto que se vende en dos tiendas de la misma cadena aparece una sola vez, en la más barata, marcado con cuál es. Aquí una oferta significa al menos ${GroceryRow.DEAL_PERCENT}% por debajo del precio habitual del propio producto — la mediana de sus ",
        )
        +tr(
            "daily prices over the last 30 days — never the store's \"before\" price. Read at 07:00 and 15:00.",
            "precios diarios de los últimos 30 días — nunca el precio \"antes\" de la tienda. Se lee a las 07:00 y 15:00.",
        )
    }

    if (sources.isEmpty()) {
        div("empty") {
            +tr("No grocery providers are enabled. Add one with ", "No hay proveedores de mercado activos. Agrega uno con ")
            code { +"type: $GROCERY_TYPE" }
            +tr(" in config/sources.yaml.", " en config/sources.yaml.")
        }
        return
    }
    // "Never crawled" is read off the aisle counts, not [rows]: an empty Pinned view is a normal state
    // and must still show the tabs, not claim the stores have never been read.
    if (groupCounts.isEmpty()) {
        div("empty") {
            +tr(
                "Nothing read yet. The first crawl runs on the next poll, or now with ",
                "Aún no se ha leído nada. La primera lectura corre en la próxima consulta, o ahora con ",
            )
            code { +"./dt run --args=\"--fetch-once --source=${sources.first()}\"" }
            +"."
        }
        return
    }

    // The same segmented control the feed uses for All deals / Favorites, so the two tabs read alike.
    div("scopes") {
        GroceryView.entries.forEach { v ->
            val on = v == controls.view
            a(href = controls.url(view = v, sort = null, search = null), classes = if (on) "scope on" else "scope") {
                if (on) attributes["aria-current"] = "page"
                +v.display()
                // Shown only when there are some, as on Favorites: "Pinned 0" reads as a broken counter.
                if (v == GroceryView.PINNED && pinnedCount > 0) span("scope-count") { +"$pinnedCount" }
            }
        }
    }

    if (controls.view != GroceryView.PINNED) {
        searchBox(
            action = "/groceries",
            value = controls.search,
            carry = buildMap {
                put("view", controls.view.key)
                if (controls.groups.isNotEmpty()) put("group", controls.groups.sorted().joinToString(","))
                if (controls.chains.isNotEmpty()) put("store", controls.chains.sorted().joinToString(","))
            },
        )
    }

    // Store above aisle: which shop you are going to is the first decision, the aisle the second.
    storeTags(chainCounts, controls)
    // Not on Pinned, which is the whole list whatever aisle is picked.
    if (controls.view != GroceryView.PINNED) groupTags(groupCounts, controls)

    val shown = groceryShown(rows, controls)
    val young = rows.count { it.onShelf && it.days < GroceryRow.MIN_DAYS }
    div("tracked-count") {
        +tr("${shown.size} item(s)", "${shown.size} producto(s)")
        if (controls.view == GroceryView.DEALS && young > 0) {
            // On the first days almost every row is young, and an empty Deals view otherwise reads as
            // "nothing is on offer" rather than "not enough history to judge".
            span("tracked-note") {
                val onShelf = rows.count { it.onShelf }
                +tr(
                    " · $young of $onShelf on the shelf have under ${GroceryRow.MIN_DAYS} days of " +
                        "history and cannot be judged yet",
                    " · $young de $onShelf en góndola tienen menos de ${GroceryRow.MIN_DAYS} días de " +
                        "historial y aún no se pueden evaluar",
                )
            }
        }
    }

    if (shown.isEmpty()) {
        div("empty") {
            when (controls.view) {
                GroceryView.DEALS -> {
                    +tr(
                        "Nothing is ${GroceryRow.DEAL_PERCENT}% under its usual price right now. ",
                        "Nada está ${GroceryRow.DEAL_PERCENT}% por debajo de su precio habitual en este momento. ",
                    )
                    a(href = controls.url(view = GroceryView.ALL, sort = null, search = null)) { +tr("Browse all items", "Explora todos los productos") }
                    +tr(" to pin staples in the meantime.", " para fijar tus básicos mientras tanto.")
                }
                GroceryView.PINNED -> {
                    +tr("Nothing pinned yet. Use ☆ pin on ", "Aún no hay nada fijado. Usa ☆ fijar en ")
                    a(href = controls.url(view = GroceryView.ALL, sort = null, search = null)) { +GroceryView.ALL.display() }
                    +tr(" to keep staples here.", " para tener tus básicos aquí.")
                }
                GroceryView.ALL -> +tr("No items match.", "Ningún producto coincide.")
            }
        }
        return
    }

    // Capped for the browser's sake. All items can be ~15,000 rows; the count above is the real total.
    val cap = 1000
    groceryTable(shown.take(cap), controls)
    if (shown.size > cap) {
        p("tracked-note") {
            +tr(
                "Showing the first $cap of ${shown.size}. Narrow with a subcategory or a search.",
                "Mostrando los primeros $cap de ${shown.size}. Acota con una subcategoría o una búsqueda.",
            )
        }
    }
}

/**
 * Exito / Carulla / PriceSmart tags, the same controls as the aisle row. Hidden with only one chain,
 * where a filter could not change anything.
 */
private fun MAIN.storeTags(chainCounts: List<Triple<String, String, Int>>, controls: GroceryControls) {
    if (chainCounts.size < 2) return
    div("typetags") {
        span("typetags-label") { +tr("Store", "Tienda") }
        chainCounts.forEach { (chain, name, n) ->
            val on = chain in controls.chains
            val next = if (on) controls.chains - chain else controls.chains + chain
            a(classes = if (on) "typetag on" else "typetag", href = controls.url(chains = next)) {
                +name
                span("typetag-n") { +String.format("%,d", n) }
            }
        }
        if (controls.chains.isNotEmpty()) {
            a(classes = "typetag clear", href = controls.url(chains = emptySet())) { +tr("clear", "quitar") }
        }
    }
}

private fun MAIN.groupTags(groupCounts: List<Pair<String, Int>>, controls: GroceryControls) {
    if (groupCounts.isEmpty()) return
    div("typetags") {
        span("typetags-label") { +tr("Aisle", "Pasillo") }
        groupCounts.forEach { (group, n) ->
            val on = group in controls.groups
            val next = if (on) controls.groups - group else controls.groups + group
            a(classes = if (on) "typetag on" else "typetag", href = controls.url(groups = next)) {
                +aisleLabel(group)
                span("typetag-n") { +n.toString() }
            }
        }
        if (controls.groups.isNotEmpty()) {
            a(classes = "typetag clear", href = controls.url(groups = emptySet())) { +tr("clear", "quitar") }
        }
    }
}

private fun FlowContent.groceryTable(rows: List<GroceryRow>, controls: GroceryControls) {
    // Same orderings as the column headers, in their left-to-right order. "vs usual" is only
    // meaningful once there is history, but it is offered on every view, as its header is.
    sortTags(
        listOf(
            tr("Item", "Producto") to GrocerySort.NAME, tr("Price", "Precio") to GrocerySort.PRICE,
            tr("Card saving", "Ahorro con tarjeta") to GrocerySort.CARD, tr("Unit price", "Precio unitario") to GrocerySort.UNIT,
            tr("Store %", "% tienda") to GrocerySort.STORE, tr("vs usual", "vs habitual") to GrocerySort.BELOW,
        ).map { (label, sort) -> SortOption(label, controls.url(sort = sort), controls.sort == sort) },
    )
    table("deals tracked grocery") {
        thead {
            tr {
                th(classes = "col-thumb") { attributes["aria-label"] = tr("image", "imagen") }
                sortableTh(tr("Item", "Producto"), GrocerySort.NAME, controls, "col-deal")
                th(classes = "col-size") { +tr("Store", "Tienda") }
                // Two sorts in one header: by the shelf price, or by how much a card saves on it today.
                th(classes = "col-price") {
                    title = tr(
                        "Shelf price, what anyone pays. The chip beneath is the card-only price where the store runs one.",
                        "Precio en góndola, lo que paga cualquiera. La etiqueta debajo es el precio solo con tarjeta, cuando la tienda tiene uno.",
                    )
                    a(href = controls.url(sort = GrocerySort.PRICE), classes = if (controls.sort == GrocerySort.PRICE) "sorted" else null) {
                        +tr("Price", "Precio")
                        if (controls.sort == GrocerySort.PRICE) span("dir") { +" ↓" }
                    }
                    +" "
                    a(href = controls.url(sort = GrocerySort.CARD), classes = if (controls.sort == GrocerySort.CARD) "sorted" else null) {
                        title = tr("Biggest card saving over the shelf price first", "Primero el mayor ahorro con tarjeta sobre el precio en góndola")
                        +tr("· card", "· tarjeta")
                        if (controls.sort == GrocerySort.CARD) span("dir") { +" ↓" }
                    }
                }
                sortableTh(
                    tr("Unit price", "Precio unitario"), GrocerySort.UNIT, controls, "col-price",
                    tr(
                        "Per 100 g, per litre or per unit, from the pack size in the listing. Blank when the " +
                            "listing states none — never guessed.",
                        "Por 100 g, por litro o por unidad, según el tamaño del empaque en la publicación. Vacío cuando " +
                            "la publicación no lo indica — nunca se adivina.",
                    ),
                )
                th(classes = "col-price") {
                    title = tr(
                        "Median of this item's daily prices at this store over the last 30 days, today excluded",
                        "Mediana de los precios diarios de este producto en esta tienda en los últimos 30 días, sin contar hoy",
                    )
                    +tr("Usual", "Habitual")
                }
                sortableTh(
                    tr("Store %", "% tienda"), GrocerySort.STORE, controls, "col-disc",
                    tr(
                        "The store's advertised discount against its own list price, as its page shows it " +
                            "(rounded down). A claim by the store; \"vs usual\" is what we measured.",
                        "El descuento que anuncia la tienda frente a su propio precio de lista, como lo muestra su página " +
                            "(redondeado hacia abajo). Es lo que afirma la tienda; \"vs habitual\" es lo que medimos.",
                    ),
                )
                sortableTh(
                    tr("vs usual", "vs habitual"), GrocerySort.BELOW, controls, "col-disc",
                    tr(
                        "How far today's price is under the usual one. Blank until ${GroceryRow.MIN_DAYS} " +
                            "earlier days are recorded.",
                        "Cuánto está el precio de hoy por debajo del habitual. Vacío hasta tener ${GroceryRow.MIN_DAYS} " +
                            "días anteriores registrados.",
                    ),
                )
                th(classes = "col-price") {
                    title = tr("Cheapest daily price in the last 30 days, today excluded", "El precio diario más bajo de los últimos 30 días, sin contar hoy")
                    +tr("30d low", "Mín. 30d")
                }
                th(classes = "col-chg") {
                    title = tr("Earlier days of history the usual price rests on", "Días anteriores de historial en los que se basa el precio habitual")
                    +tr("Days", "Días")
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
        td(classes = "col-deal") {
            a(classes = "title", href = deal.url) {
                attributes["target"] = "_blank"
                attributes["rel"] = "noopener noreferrer"
                +deal.title
            }
            div("meta") {
                row.group?.let { span("cat") { +aisleLabel(it) } }
                deal.brand?.let { span { +it } }
                if (!row.onShelf) span("chip chip-expired") {
                    title = tr(
                        "Not in its store's latest crawl — out of stock at Laureles, or delisted. " +
                            "The price is the last one seen.",
                        "No apareció en la última lectura de su tienda — agotado en Laureles, o retirado. " +
                            "El precio es el último visto.",
                    )
                    +tr("unavailable", "no disponible")
                }
            }
        }
        // Which store the price is from, and what the chain's other store charges. Both shown, because
        // "cheaper at Colombia by 2,660" is a reason to walk to Colombia, and a bare tag is not.
        td(classes = "col-size") {
            cardLabel(tr("Store", "Tienda"))
            span("size-chip") {
                if (row.others.isNotEmpty()) {
                    title = tr(
                        "Cheapest of ${row.others.size + 1} stores stocking it",
                        "La más barata de ${row.others.size + 1} tiendas que lo tienen",
                    )
                }
                +row.store.ifEmpty { storeName(deal.source) }
            }
            row.others.forEach { other ->
                div("store-alt") {
                    title = if (other.onShelf) {
                        tr("Same product at ${other.store}", "El mismo producto en ${other.store}")
                    } else {
                        tr("${other.store}: not in its latest crawl", "${other.store}: no apareció en su última lectura")
                    }
                    +"${other.store.substringAfterLast(' ')} "
                    if (other.onShelf) +formatMoney(other.deal.price, other.deal.currency) else +tr("out", "agotado")
                }
            }
        }
        // The shelf price stays the main number -- what anyone pays. The card price sits under it as
        // a chip, because it is conditional: only a Mastercard (or only the store's own card) gets it.
        td(classes = "col-price") {
            cardLabel(tr("Price", "Precio"))
            div("price") { +formatMoney(deal.price, deal.currency) }
            row.card?.let { card -> cardChip(card, deal.currency) }
        }
        td(classes = "col-price") {
            cardLabel(tr("Unit price", "Precio unitario"))
            val unit = row.unitPrice
            if (unit == null) span("muted") { +"—" } else {
                +formatMoney(unit, deal.currency)
                span("muted") { +unitPriceLabel(row.size) }
            }
        }
        td(classes = "col-price") {
            cardLabel(tr("Usual", "Habitual"))
            row.usual?.takeIf { row.days >= GroceryRow.MIN_DAYS }?.let { +formatMoney(it, deal.currency) }
                ?: span("muted") { +"—" }
        }
        td(classes = "col-disc") {
            cardLabel(tr("Store %", "% tienda"))
            deal.storeDiscountPercent?.let { span("disc disc-s${discountTier(BigDecimal(it))}") { +"−$it%" } }
                ?: span("muted") { +"—" }
        }
        td(classes = "col-disc") {
            cardLabel(tr("vs usual", "vs habitual"))
            val below = row.belowUsual
            when {
                below == null -> span("muted") {
                    title = tr("Under ${GroceryRow.MIN_DAYS} days of history", "Menos de ${GroceryRow.MIN_DAYS} días de historial")
                    +tr("new", "nuevo")
                }
                below.signum() > 0 -> span(if (row.isDeal) "lowbadge" else "chg chg-down") { +"−${below.toPlainString()}%" }
                below.signum() < 0 -> span("chg chg-up") { +"+${below.negate().toPlainString()}%" }
                else -> span("muted") { +"0%" }
            }
        }
        td(classes = "col-price") {
            cardLabel(tr("30d low", "Mín. 30d"))
            row.low?.takeIf { row.days >= 1 }?.let { +formatMoney(it, deal.currency) } ?: span("muted") { +"—" }
        }
        td(classes = "col-chg") {
            cardLabel(tr("Days", "Días"))
            +row.days.toString()
        }
        td(classes = "col-act") {
            div("actions") {
                a(href = "/deal/${deal.id}") { +tr("history", "historial") }
                val pinned = row.pinnedAt != null
                button(classes = if (pinned) "fav-btn on" else "fav-btn") {
                    attributes["hx-post"] = "/groceries/${deal.id}/${if (pinned) "unpin" else "pin"}?view=${view.key}"
                    attributes["hx-target"] = "#grocery-${deal.id}"
                    attributes["hx-swap"] = "outerHTML"
                    attributes["aria-pressed"] = pinned.toString()
                    title = if (pinned) {
                        tr("Unpin this staple", "Quitar este básico de fijados")
                    } else {
                        tr("Pin as a staple, listed under Pinned", "Fijar como básico, aparece en Fijados")
                    }
                    +if (pinned) tr("★ pinned", "★ fijado") else tr("☆ pin", "☆ fijar")
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
        h2 { +tr("Last sync", "Última sincronización") }
        table {
            thead {
                tr {
                    th { +tr("Store", "Tienda") }
                    th { +tr("Prices read", "Precios leídos") }
                    th { +tr("Items", "Productos") }
                    th { +tr("Latest run", "Última ejecución") }
                    th { +tr("Next", "Siguiente") }
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
                            } ?: span("muted") { +tr("never", "nunca") }
                        }
                        td { +(st.lastSuccessItems?.let { String.format("%,d", it) } ?: "—") }
                        td {
                            when {
                                st.running -> +tr(
                                    "running since ${clockTime(st.lastStarted!!)}…",
                                    "en curso desde ${clockTime(st.lastStarted!!)}…",
                                )
                                st.interrupted -> span("err") {
                                    title = tr(
                                        "Started and never finished — the app was stopped mid-crawl. The next scheduled run retries.",
                                        "Empezó y nunca terminó — la app se detuvo a mitad de la lectura. La próxima ejecución programada lo reintenta.",
                                    )
                                    +tr("interrupted ${clockTime(st.lastStarted!!)}", "interrumpida ${clockTime(st.lastStarted!!)}")
                                }
                                // The crawler's own error text stays as it was logged: it is a diagnostic, and
                                // a translated one could no longer be searched for in logs/app.log.
                                st.failed -> span("err") {
                                    title = st.lastError ?: tr("The run finished with 0 items", "La ejecución terminó con 0 productos")
                                    +tr("failed ${clockTime(st.lastStarted!!)}", "falló ${clockTime(st.lastStarted!!)}")
                                    +": ${(st.lastError ?: tr("0 items read", "0 productos leídos")).take(80)}"
                                }
                                st.lastStarted != null -> +tr("ok", "ok")
                                else -> span("muted") { +"—" }
                            }
                        }
                        td {
                            line.next?.let { +clockTime(it) } ?: span("muted") { +tr("every poll", "en cada consulta") }
                            line.schedule?.let { sched ->
                                span("muted") { +tr(" · daily at $sched", " · a diario a las $sched") }
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
        today -> tr("today $hm", "hoy $hm")
        today.minusDays(1) -> tr("yesterday $hm", "ayer $hm")
        today.plusDays(1) -> tr("tomorrow $hm", "mañana $hm")
        // "23 sept" in Spanish: day before month, as Colombia writes dates.
        else -> if (I18n.lang == Lang.ES) {
            t.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag("es-CO"))) + " $hm"
        } else {
            t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)) + " $hm"
        }
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
    return app.enabledProviders.filter { it.type in GROCERY_TYPES }.mapNotNull { p ->
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

// `cardChip` lives in Views.kt: the Groceries tab and each watch's "Matching now" table both
// render it, and two copies would let the wording drift between them.
