package com.luis.dealstracker.web

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.aisleLabel
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.db.ChainRival
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.db.ShelfPrice
import com.luis.dealstracker.db.SyncStatus
import com.luis.dealstracker.ingest.unitPriceLabel
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.HeldCards
import com.luis.dealstracker.model.formatMoney
import io.ktor.http.encodeURLParameter
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.details
import kotlinx.html.summary
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
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.ThScope
import kotlinx.html.caption
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
    return labelStores(rows, stores)
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

/** [rows] with their store and chain set, one row per store: what a search shows (see [splitByStore]). */
internal fun labelStores(rows: List<GroceryRow>, stores: Map<String, GroceryStore>): List<GroceryRow> = rows.map {
    it.copy(store = stores[it.deal.source]?.label ?: storeName(it.deal.source), chain = stores[it.deal.source]?.chain ?: it.deal.source)
}

/**
 * Whether the table shows every store's copy as its own row rather than [mergeStores]' one per chain.
 * A search does: looking for one product, you want to see it at Laureles AND Colombia, even when one
 * is $14 dearer. Browsing (no search) stays merged, so All items does not list most products twice.
 * So does a search of exclusions only (`!aceite`): that is browsing with some rows hidden, not
 * looking for a product.
 */
internal fun splitByStore(controls: GroceryControls): Boolean =
    com.luis.dealstracker.db.SearchQuery.parse(controls.search).include.isNotEmpty()

/** [mergeStores], or one row per store when [split]. */
internal fun storeRows(rows: List<GroceryRow>, stores: Map<String, GroceryStore>, split: Boolean): List<GroceryRow> =
    if (split) labelStores(rows, stores) else mergeStores(rows, stores)

/** One copy per product per chain, the one [mergeStores] would lead with: on the shelf first, then cheapest. */
internal fun onePerChain(rows: List<GroceryRow>): List<GroceryRow> =
    rows.groupBy { Triple(it.chain.ifEmpty { it.deal.source }, it.deal.merchant, it.deal.sourceDealId) }.values
        .map { copies -> copies.sortedWith(compareByDescending<GroceryRow> { it.onShelf }.thenBy { it.deal.price }).first() }

/**
 * Each row's cheapest on-shelf copy at ANOTHER chain, by barcode, as [GroceryRow.rival].
 *
 * [mergeStores] already compares a chain's own stores; this answers the other question -- would this be
 * cheaper at Carulla than at Exito? Matched on the barcode key only, so the two are the same product
 * in the same pack, and in the same currency only. PriceSmart lists no barcodes, so it neither gets
 * nor gives a rival. Ties go to the store listed first in config.
 */
internal fun withRivals(rows: List<GroceryRow>, prices: List<ShelfPrice>, stores: Map<String, GroceryStore>): List<GroceryRow> {
    val order = stores.keys.withIndex().associate { (i, id) -> id to i }
    val chainOf = { source: String -> stores[source]?.chain ?: source }
    val byKey = prices.groupBy { it.matchKey }
    return rows.map { row ->
        val key = row.deal.matchKey ?: return@map row
        val chain = chainOf(row.deal.source)
        val best = byKey[key].orEmpty()
            .filter { chainOf(it.source) != chain && it.currency == row.deal.currency }
            .minWithOrNull(compareBy<ShelfPrice> { it.price }.thenBy { order[it.source] ?: Int.MAX_VALUE })
            ?: return@map row
        row.copy(
            rival = ChainRival(
                stores[best.source]?.label ?: storeName(best.source), chainOf(best.source), best.price, best.currency, best.dealId,
            ),
        )
    }
}

/**
 * Whether [controls] needs a rival on every row in view rather than the page's: sorting by the other
 * chain's price ranks rows that are not on screen yet, and Pinned's shopping plan totals
 * the whole list.
 */
internal fun needsAllRivals(controls: GroceryControls): Boolean =
    controls.sort == GrocerySort.RIVAL || controls.view == GroceryView.PINNED

/**
 * [rows] with a rival on each row [controls] needs one for: the page on screen, or every row in view
 * when [needsAllRivals]. Page-only by default because All items can hold ~15,000 rows -- a 0.3s lookup
 * for rows nobody is looking at.
 */
internal fun withRivalsFor(
    rows: List<GroceryRow>,
    controls: GroceryControls,
    stores: Map<String, GroceryStore>,
    lookup: (keys: Collection<String>) -> List<ShelfPrice>,
): List<GroceryRow> {
    val target = if (needsAllRivals(controls)) groceryInView(rows, controls.view) else groceryPageRows(groceryShown(rows, controls), controls.page)
    val keys = target.mapNotNull { it.deal.matchKey }
    if (keys.isEmpty()) return rows
    val withRival = withRivals(target, lookup(keys), stores).filter { it.rival != null }.associateBy { it.deal.id }
    return rows.map { withRival[it.deal.id] ?: it }
}

/**
 * Where to buy this row today, and at what price: its own store or the other chain's, whichever is
 * cheaper, ties to its own. Off its own shelf, the rival is the only option; with neither, null.
 */
internal fun GroceryRow.bestBuy(): Pair<String, BigDecimal>? {
    val own = if (onShelf) chain to deal.price else null
    val other = rival?.let { it.chain to it.price }
    return listOfNotNull(own, other).minByOrNull { it.second }
}

/** One chain's share of the shopping plan. */
internal data class PlanStop(val chain: String, val items: Int, val total: BigDecimal)

/**
 * Pinned, split by where each item is cheapest today: what one trip per chain would cost.
 *
 * [saving] is what that split saves over buying each on-shelf item at its own row's store; an item off
 * its own shelf saves nothing, since there was no price there to save against. [unavailable] counts
 * items with no shelf price anywhere.
 */
internal data class ShoppingPlan(val stops: List<PlanStop>, val saving: BigDecimal, val unavailable: Int, val currency: String)

internal fun shoppingPlan(rows: List<GroceryRow>): ShoppingPlan? {
    val currency = rows.firstOrNull()?.deal?.currency ?: return null
    val same = rows.filter { it.deal.currency == currency }
    val bought = same.mapNotNull { row -> row.bestBuy()?.let { row to it } }
    val stops = bought.groupBy({ it.second.first }, { it.second.second })
        .map { (chain, prices) -> PlanStop(chain, prices.size, prices.fold(BigDecimal.ZERO, BigDecimal::add)) }
        .sortedWith(compareByDescending<PlanStop> { it.items }.thenBy { it.chain })
    val saving = bought.filter { (row, buy) -> row.onShelf && buy.first != row.chain }
        .fold(BigDecimal.ZERO) { acc, (row, buy) -> acc + (row.deal.price - buy.second) }
    return ShoppingPlan(stops, saving, same.size - bought.size, currency)
}

/**
 * The grocery tab's three screens, as the feed has All deals / Pinned: Deals (the default),
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

    /**
     * Rows with a card price you hold first, furthest under the usual price paying with it, by
     * [belowUsualWithCard]: the store's promotion and the card both count, against a price we measured.
     * Rows with no such card price last -- this is the card sort, not a second "vs usual".
     */
    CARD_USUAL("cardusual", "Card vs usual"),

    /**
     * Only rows with a store discount AND a card price you hold first, by [cardAndStorePercent]: the card
     * price against the store's own list price. Everything else last.
     */
    CARD_STORE("cardstore", "Card + store"),
    PRICE("price", "Price"),

    /**
     * Most cheaper at the other chain first, by [GroceryRow.rivalGap]: the rows worth buying elsewhere.
     * Rows with no rival last.
     */
    RIVAL("rival", "vs other chain"),

    /**
     * Closest to its lowest price ever first, by [overLowest]: what is best bought now. Rows whose price
     * has never been higher -- where "lowest" says nothing -- and rows off the shelf last.
     */
    LOWEST("lowest", "vs lowest"),

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
    /**
     * Pinned only: keep just the items the Exito cart button can add (an Exito Laureles copy on the
     * shelf, see [cartCopy]) -- the shopping list for one cart run. Ignored on the other views.
     */
    val cartable: Boolean = false,
    /** 1-based page of the table. Clamped on use, so a stale bookmark lands on the last page. */
    val page: Int = 1,
    /**
     * Pinned only: just the items whose [bestBuy] is at this chain -- the shopping list for one trip,
     * picked from the shopping plan. Unlike [chains], it goes by where the item is cheapest, not by
     * which store the row is from.
     */
    val buyAt: String? = null,
    /**
     * Pinned only: one list ranked across every aisle, as the page was before the aisles were grouped.
     * Off by default, so Pinned opens grouped by aisle ([grouped]).
     */
    val flat: Boolean = false,
) {
    /** Pinned shows its rows grouped by aisle, each aisle an accordion: see [pinnedAisles]. */
    val grouped: Boolean get() = view == GroceryView.PINNED && !flat

    fun url(
        view: GroceryView = this.view,
        groups: Set<String> = this.groups,
        sort: GrocerySort? = this.sort,
        search: String? = this.search,
        chains: Set<String> = this.chains,
        cartable: Boolean = this.cartable,
        buyAt: String? = this.buyAt,
        flat: Boolean = this.flat,
        // Not carried over: changing the view, filter, search or sort starts again at page 1.
        page: Int = 1,
    ): String {
        val params = buildList {
            if (view != GroceryView.DEALS) add("view=${view.key}")
            // Unlike the aisle, the store filter applies on Pinned too: "my staples at PriceSmart" is a
            // shopping list for one trip.
            if (chains.isNotEmpty()) add("store=" + chains.sorted().joinToString(",").encodeURLParameter())
            if (groups.isNotEmpty() && view != GroceryView.PINNED) add("group=" + groups.sorted().joinToString(",").encodeURLParameter())
            search?.takeIf { it.isNotBlank() }?.let { add("q=" + it.encodeURLParameter()) }
            if (sort != null && sort != GrocerySort.of(null, view)) add("sort=${sort.key}")
            if (cartable && view == GroceryView.PINNED) add("cart=1")
            if (buyAt != null && view == GroceryView.PINNED) add("buy=" + buyAt.encodeURLParameter())
            if (flat && view == GroceryView.PINNED) add("list=flat")
            if (page > 1) add("page=$page")
        }
        return if (params.isEmpty()) "/groceries" else "/groceries?" + params.joinToString("&")
    }

    companion object {
        fun from(params: io.ktor.http.Parameters): GroceryControls {
            val view = GroceryView.of(params["view"])
            return GroceryControls(
                view = view,
                groups = params["group"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                search = params["q"]?.trim()?.takeIf { it.isNotEmpty() },
                sort = GrocerySort.of(params["sort"], view),
                chains = params["store"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                cartable = params["cart"] == "1" && view == GroceryView.PINNED,
                page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                buyAt = params["buy"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && view == GroceryView.PINNED },
                flat = params["list"] == "flat" && view == GroceryView.PINNED,
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
internal fun groceryShown(rows: List<GroceryRow>, controls: GroceryControls): List<GroceryRow> =
    groceryInView(rows, controls.view)
        .filter { controls.view != GroceryView.PINNED || !controls.cartable || cartCopy(it) != null }
        .filter { r -> controls.buyAt == null || r.bestBuy()?.first == controls.buyAt }
        .sortedWith(groceryComparator(controls.sort))

/**
 * Where today's shelf price stands against the item's own history: the row's coloured edge and the
 * chip under its price.
 */
internal enum class PriceStanding(val css: String) {
    /** At the lowest price ever recorded for it, having been higher. */
    LOWEST("stand-lowest"),
    /** Within [NEAR_LOWEST_PERCENT] of that lowest. */
    NEAR("stand-near"),
    /** At least [GroceryRow.DEAL_PERCENT] over its usual price: the mirror of a deal. */
    HIGH("stand-high"),
}

/** "Close to its lowest" is within this many percent of it. */
internal val NEAR_LOWEST_PERCENT: BigDecimal = BigDecimal(5)

/**
 * How far today's shelf price is over the lowest ever recorded, in percent; 0 at the lowest.
 *
 * Null when the lowest says nothing: off the shelf (the price is the last one seen, not today's), no
 * history, or a price that was never higher than it is now -- every such row sits "at its lowest"
 * trivially, the trap [com.luis.dealstracker.model.Deal.isAtLowestEver] documents. The shelf price only,
 * as "vs usual" is: a one-day card price is not the item getting cheaper.
 */
internal fun overLowest(row: GroceryRow): BigDecimal? {
    if (!row.onShelf) return null
    val min = row.deal.minPriceEver?.takeIf { it.signum() > 0 } ?: return null
    val max = row.deal.maxPriceEver ?: return null
    if (max <= row.deal.price) return null
    return row.deal.price.subtract(min).multiply(BigDecimal(100))
        .divide(min, 1, java.math.RoundingMode.HALF_UP).max(BigDecimal.ZERO.setScale(1))
}

/** [row]'s standing today, or null for an ordinary price. Cheap wins over dear when both could be said. */
internal fun priceStanding(row: GroceryRow): PriceStanding? {
    val over = overLowest(row)
    return when {
        over != null && over.signum() == 0 -> PriceStanding.LOWEST
        over != null && over <= NEAR_LOWEST_PERCENT -> PriceStanding.NEAR
        row.onShelf && row.belowUsual?.let { it <= GroceryRow.DEAL_PERCENT.negate() } == true -> PriceStanding.HIGH
        else -> null
    }
}

/** One aisle of the Pinned list: its rows in the table's order. A null [group] is a row with no aisle. */
internal data class PinnedAisle(val group: String?, val rows: List<GroceryRow>) {
    /** How many are a deal today: what says whether the aisle is worth opening. */
    val underUsual: Int get() = rows.count { it.onShelf && it.isDeal }

    /** How many sit at their lowest price ever today. */
    val atLowest: Int get() = rows.count { priceStanding(it) == PriceStanding.LOWEST }
}

/**
 * [shown] split by aisle for Pinned's accordions. The aisle with the most pins comes first -- it is the
 * one most often wanted -- then by name; rows with no aisle go last. Inside an aisle the rows keep the
 * order [shown] has them in, so the page's sort applies within each aisle.
 */
internal fun pinnedAisles(shown: List<GroceryRow>): List<PinnedAisle> =
    shown.groupBy { it.group }
        .map { (group, rows) -> PinnedAisle(group, rows) }
        .sortedWith(
            compareBy<PinnedAisle> { it.group == null }
                .thenByDescending { it.rows.size }
                .thenBy { it.group?.let(::aisleLabel).orEmpty() },
        )

/** The rows a view holds before the page's own filters: what the shopping plan and rival lookup read. */
internal fun groceryInView(rows: List<GroceryRow>, view: GroceryView): List<GroceryRow> = when (view) {
    GroceryView.DEALS -> rows.filter { it.onShelf && it.isDeal }
    GroceryView.ALL -> rows.filter { it.onShelf }
    GroceryView.PINNED -> rows.filter { it.pinnedAt != null }
}

/** The cheapest price a card you hold pays for [row] today, when it beats the shelf price. */
internal fun heldCardPrice(row: GroceryRow, held: Set<CardScope> = HeldCards.current): BigDecimal? =
    com.luis.dealstracker.model.usableOffer(row.cardOffers, held)?.price?.takeIf { it < row.deal.price }

/**
 * Percent under the usual price paying with your best card, or the shelf price without one -- the
 * [GroceryRow.belowUsual] of what you would actually pay. Null until the usual price is known.
 */
internal fun belowUsualWithCard(row: GroceryRow, held: Set<CardScope> = HeldCards.current): BigDecimal? {
    val usual = row.usual?.takeIf { row.days >= GroceryRow.MIN_DAYS && it.signum() > 0 } ?: return null
    val pay = heldCardPrice(row, held) ?: row.deal.price
    return BigDecimal.ONE.subtract(pay.divide(usual, 6, java.math.RoundingMode.HALF_UP))
        .multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)
}

/**
 * The total off the store's list price paying with your best card, for a row with BOTH a store discount
 * and such a card price; null otherwise. Exito and Carulla take the card percentage off the pre-promotion
 * price, so it replaces the shelf promotion rather than stacking on it -- the card price over the list
 * price is the whole saving.
 */
internal fun cardAndStorePercent(row: GroceryRow, held: Set<CardScope> = HeldCards.current): BigDecimal? {
    if (row.deal.storeDiscountPercent == null) return null
    val list = row.deal.listPrice?.takeIf { it > row.deal.price } ?: return null
    val card = heldCardPrice(row, held) ?: return null
    return BigDecimal.ONE.subtract(card.divide(list, 6, java.math.RoundingMode.HALF_UP))
        .multiply(BigDecimal(100)).setScale(1, java.math.RoundingMode.HALF_UP)
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
        // The saving a card the operator holds gives (any card, with none picked): "My cards".
        com.luis.dealstracker.model.usableOffer(r.cardOffers, com.luis.dealstracker.model.HeldCards.current)
            ?.let { BigDecimal.ONE - it.price.divide(r.deal.price, 6, java.math.RoundingMode.HALF_UP) } ?: BigDecimal("-1")
    }.thenBy { it.deal.title }
    GrocerySort.CARD_USUAL -> compareBy<GroceryRow> { heldCardPrice(it) == null }
        .thenByDescending { belowUsualWithCard(it) ?: BigDecimal("-1000") }
        .thenBy { it.deal.title }
    GrocerySort.CARD_STORE -> compareByDescending<GroceryRow> { cardAndStorePercent(it) ?: BigDecimal("-1") }
        .thenBy { it.deal.title }
    GrocerySort.STORE -> compareByDescending<GroceryRow> { it.deal.storeDiscountPercent ?: -1 }
        .thenBy { it.deal.title }
    GrocerySort.PRICE -> compareBy<GroceryRow> { it.deal.price }.thenBy { it.deal.title }
    GrocerySort.RIVAL -> compareBy<GroceryRow> { it.rivalGap == null }.thenBy { it.rivalGap }.thenBy { it.deal.title }
    GrocerySort.LOWEST -> compareBy<GroceryRow> { overLowest(it) == null }
        .thenBy { overLowest(it) }
        .thenBy { it.deal.title }
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
    exitoCartSlot()
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
            ". An item sold at two stores of one chain is shown once, at the cheaper one, tagged with which (a search lists each store); where another chain sells the same barcode, its price sits beneath. A deal here means at least ${GroceryRow.DEAL_PERCENT}% under the item's own usual price — the median of its ",
            ". Un producto que se vende en dos tiendas de la misma cadena aparece una sola vez, en la más barata, marcado con cuál es (una búsqueda muestra cada tienda); si otra cadena vende el mismo código de barras, su precio aparece debajo. Aquí una oferta significa al menos ${GroceryRow.DEAL_PERCENT}% por debajo del precio habitual del propio producto — la mediana de sus ",
        )
        +tr(
            // No clock times here: each store keeps its own schedule in sources.yaml, and the Last
            // sync table below reads it, so it cannot go stale the way "07:00 and 15:00" did.
            "daily prices over the last 30 days — never the store's \"before\" price. Each store is read two or three times a day; Last sync, at the bottom, has the times.",
            "precios diarios de los últimos 30 días — nunca el precio \"antes\" de la tienda. Cada tienda se lee dos o tres veces al día; Última sincronización, al final, tiene las horas.",
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

    // The same segmented control the feed uses for All deals / Pinned, so the two tabs read alike.
    div("scopes") {
        GroceryView.entries.forEach { v ->
            val on = v == controls.view
            a(href = controls.url(view = v, sort = null, search = null), classes = if (on) "scope on" else "scope") {
                if (on) attributes["aria-current"] = "page"
                +v.display()
                // Shown only when there are some, as on the feed: "Pinned 0" reads as a broken counter.
                if (v == GroceryView.PINNED && pinnedCount > 0) span("scope-count") { +"$pinnedCount" }
            }
        }
    }

    // Shopping happens from the shopping list: the cart buttons and the link to the cart they fill live on
    // Pinned only, not on Deals or All items, where an item is being browsed rather than bought.
    if (controls.view == GroceryView.PINNED) {
        openCartSlot()
        cartableToggle(rows.count { it.pinnedAt != null && cartCopy(it) != null }, controls)
    }

    // On Pinned too: with 150+ staples, "where is the milk on my list?" needs a search as much as browsing does.
    searchBox(
        action = "/groceries",
        value = controls.search,
        clearHref = controls.url(search = null),
        placeholder = if (controls.view == GroceryView.PINNED) tr("Search your pinned items", "Buscar en tus fijados") else "leche | huevos | !aceite",
        carry = buildMap {
            put("view", controls.view.key)
            if (controls.groups.isNotEmpty() && controls.view != GroceryView.PINNED) put("group", controls.groups.sorted().joinToString(","))
            if (controls.chains.isNotEmpty()) put("store", controls.chains.sorted().joinToString(","))
            if (controls.cartable) put("cart", "1")
            controls.buyAt?.let { put("buy", it) }
            if (controls.flat) put("list", "flat")
        },
    )

    // Store above aisle: which shop you are going to is the first decision, the aisle the second.
    storeTags(chainCounts, controls)
    val chainLabels = chainCounts.associate { it.first to it.second }
    if (controls.view == GroceryView.PINNED) {
        // A search shows each store's copy; the plan counts each product once, at its chain's best store.
        val planRows = groceryInView(rows, GroceryView.PINNED).let { if (splitByStore(controls)) onePerChain(it) else it }
        shoppingPlan(planRows)?.let { planBlock(it, chainLabels, controls) }
    }
    // Not on Pinned, which is the whole list whatever aisle is picked.
    if (controls.view != GroceryView.PINNED) groupTags(groupCounts, controls)

    val shown = groceryShown(rows, controls)
    if (controls.view == GroceryView.PINNED && shown.isNotEmpty()) listModeTags(controls)
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
            // A filter of this page's own emptied it, not the view: say so, rather than "nothing pinned".
            if (controls.buyAt != null || (controls.view == GroceryView.PINNED && controls.search != null)) {
                +tr("No pinned items match. ", "Ningún fijado coincide. ")
                a(href = controls.url(buyAt = null, search = null)) { +tr("Clear the filter", "Quitar el filtro") }
                return@div
            }
            when (controls.view) {
                GroceryView.DEALS -> {
                    +tr(
                        "Nothing is ${GroceryRow.DEAL_PERCENT}% under its usual price right now. ",
                        "Nada está ${GroceryRow.DEAL_PERCENT}% por debajo de su precio habitual en este momento. ",
                    )
                    a(href = controls.url(view = GroceryView.ALL, sort = null, search = null)) { +tr("Browse all items", "Explora todos los productos") }
                    +tr(" to pin staples in the meantime.", " para fijar tus básicos mientras tanto.")
                }
                GroceryView.PINNED -> if (controls.cartable) {
                    +tr(
                        "None of your pinned items can be added to the Exito cart right now.",
                        "Ninguno de tus productos fijados se puede agregar al carrito de Exito en este momento.",
                    )
                } else {
                    +tr("Nothing pinned yet. Use ☆ pin on ", "Aún no hay nada fijado. Usa ☆ fijar en ")
                    a(href = controls.url(view = GroceryView.ALL, sort = null, search = null)) { +GroceryView.ALL.display() }
                    +tr(" to keep staples here.", " para tener tus básicos aquí.")
                }
                GroceryView.ALL -> +tr("No items match.", "Ningún producto coincide.")
            }
        }
        return
    }

    // Pinned, by aisle: every aisle listed, closed, and opened for the one item wanted. Not paged -- an
    // aisle cut in two by a page break would hide half of it behind "next".
    if (controls.grouped) {
        grocerySortTags(controls)
        aisleAccordions(pinnedAisles(shown), controls)
        return
    }

    // Paged for the browser's sake. All items can be ~15,000 rows; the count above is the real total.
    val pages = groceryPageCount(shown.size)
    val current = controls.page.coerceIn(1, pages)
    pager(controls, current, pages)
    grocerySortTags(controls)
    groceryTable(groceryPageRows(shown, current), controls)
    pager(controls, current, pages)
}

/** Pinned's two layouts: grouped by aisle (the default) or one list ranked across all of them. */
private fun FlowContent.listModeTags(controls: GroceryControls) {
    div("typetags listmode") {
        span("typetags-label") { +tr("List", "Lista") }
        listOf(false to tr("By aisle", "Por pasillo"), true to tr("One list", "Una sola lista")).forEach { (flat, label) ->
            val on = controls.flat == flat
            a(classes = if (on) "typetag on" else "typetag", href = controls.url(flat = flat)) {
                if (on) attributes["aria-current"] = "true"
                +label
            }
        }
    }
}

/**
 * Pinned as one accordion per aisle. Each is a native `<details>`, closed until asked for, whose summary
 * carries what decides whether to open it: how many pins it holds and how many are a deal today.
 *
 * A search opens every aisle it left -- the rows are the answer, and hiding them behind a click would
 * make a search look empty. Those are marked `data-fixed` so that [AISLE_JS] neither closes them from
 * memory nor remembers them as opened by hand.
 */
private fun FlowContent.aisleAccordions(aisles: List<PinnedAisle>, controls: GroceryControls) {
    val searching = controls.search != null
    div("aisles") {
        if (!searching && aisles.size > 1) {
            div("aisles-tools") {
                button(classes = "aisles-all") {
                    type = ButtonType.button
                    attributes["data-open"] = tr("Open all", "Abrir todos")
                    attributes["data-close"] = tr("Close all", "Cerrar todos")
                    +tr("Open all", "Abrir todos")
                }
            }
        }
        aisles.forEach { aisle ->
            details("aisle") {
                attributes["data-aisle"] = aisle.group ?: "none"
                if (searching) {
                    attributes["open"] = "open"
                    attributes["data-fixed"] = "1"
                }
                summary {
                    span("aisle-name") { +(aisle.group?.let(::aisleLabel) ?: tr("No aisle", "Sin pasillo")) }
                    span("aisle-n") {
                        attributes["aria-label"] = tr("${aisle.rows.size} item(s)", "${aisle.rows.size} producto(s)")
                        +aisle.rows.size.toString()
                    }
                    span("aisle-chips") {
                        if (aisle.atLowest > 0) {
                            span("chip stand-lowest-chip aisle-low") {
                                title = tr("At their lowest shelf price ever today", "En su precio en góndola más bajo hoy")
                                span("aisle-low-n") { +aisle.atLowest.toString() }
                                +tr(" at lowest", " en mínimo")
                            }
                        }
                        if (aisle.underUsual > 0) {
                            span("chip chip-drop aisle-under") {
                                title = tr(
                                    "At least ${GroceryRow.DEAL_PERCENT}% under their usual price today",
                                    "Al menos ${GroceryRow.DEAL_PERCENT}% por debajo de su precio habitual hoy",
                                )
                                span("aisle-under-n") { +aisle.underUsual.toString() }
                                +tr(" under usual", " bajo lo habitual")
                            }
                        }
                    }
                }
                groceryTable(aisle.rows, controls)
            }
        }
    }
}

/**
 * Pinned's aisles: remembers which were opened, so a pin, a sort or a reload does not close the list
 * mid-shop; "Open all / Close all"; and after an unpin removes a row, the aisle's counts follow and an
 * emptied aisle goes. Storage can be refused (a private window), so the page works without it.
 */
internal const val AISLE_JS = """
(function () {
  var KEY = 'dt.pinnedAisles';
  function all() { return Array.prototype.slice.call(document.querySelectorAll('details.aisle')); }
  function read() { try { return JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (e) { return []; } }
  function write(a) { try { localStorage.setItem(KEY, JSON.stringify(a)); } catch (e) {} }
  function button() {
    var b = document.querySelector('.aisles-all');
    if (!b) return;
    var open = all().some(function (d) { return d.open; });
    b.textContent = open ? b.dataset.close : b.dataset.open;
    b.setAttribute('aria-expanded', open ? 'true' : 'false');
  }
  function restore() {
    var open = read();
    all().forEach(function (d) { if (!d.dataset.fixed && open.indexOf(d.dataset.aisle) >= 0) d.open = true; });
    button();
  }
  function recount() {
    all().forEach(function (d) {
      var n = d.querySelectorAll('tbody tr').length;
      if (!n) { d.remove(); return; }
      var c = d.querySelector('.aisle-n');
      if (c) c.textContent = n;
      ['under', 'low'].forEach(function (what) {
        var u = d.querySelector('.aisle-' + what);
        if (!u) return;
        var k = d.querySelectorAll('tbody tr[data-' + what + ']').length;
        if (k) u.querySelector('.aisle-' + what + '-n').textContent = k; else u.remove();
      });
    });
  }
  document.addEventListener('toggle', function (e) {
    var d = e.target;
    if (!d.matches || !d.matches('details.aisle')) return;
    if (!d.dataset.fixed) write(all().filter(function (x) { return x.open; }).map(function (x) { return x.dataset.aisle; }));
    button();
  }, true);
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('.aisles-all');
    if (!b) return;
    var open = all().some(function (d) { return d.open; });
    all().forEach(function (d) { d.open = !open; });
  });
  document.addEventListener('htmx:afterSwap', recount);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', restore); else restore();
})();
"""

/** Rows per page of the grocery table. */
internal const val GROCERY_PAGE_SIZE = 500

internal fun groceryPageCount(total: Int): Int = ((total + GROCERY_PAGE_SIZE - 1) / GROCERY_PAGE_SIZE).coerceAtLeast(1)

/** The rows of 1-based [page], clamped into range the way the page itself is. */
internal fun groceryPageRows(shown: List<GroceryRow>, page: Int): List<GroceryRow> {
    val current = page.coerceIn(1, groceryPageCount(shown.size))
    return shown.drop((current - 1) * GROCERY_PAGE_SIZE).take(GROCERY_PAGE_SIZE)
}

/** Above and below the table: 500 rows is a long scroll back up. Nothing for a single page. */
private fun FlowContent.pager(controls: GroceryControls, page: Int, pages: Int) =
    pagerLinks(page, pages) { controls.url(page = it) }

/** First / prev / "page N of M" / next / last, with [urlFor] building each page's link. Nothing for a single page. */
internal fun FlowContent.pagerLinks(page: Int, pages: Int, urlFor: (Int) -> String) {
    if (pages <= 1) return
    div("pager") {
        if (page > 1) {
            a(href = urlFor(1)) { +tr("« first", "« primera") }
            a(href = urlFor(page - 1)) { +tr("‹ prev", "‹ anterior") }
        } else {
            span("off") { +tr("« first", "« primera") }
            span("off") { +tr("‹ prev", "‹ anterior") }
        }
        span("at") { +tr("page $page of $pages", "página $page de $pages") }
        if (page < pages) {
            a(href = urlFor(page + 1)) { +tr("next ›", "siguiente ›") }
            a(href = urlFor(pages)) { +tr("last »", "última »") }
        } else {
            span("off") { +tr("next ›", "siguiente ›") }
            span("off") { +tr("last »", "última »") }
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

/** The phone's sort chips. Once per page, above the table or the aisles, not once per aisle. */
private fun FlowContent.grocerySortTags(controls: GroceryControls) {
    // Same orderings as the column headers, in their left-to-right order. "vs usual" is only
    // meaningful once there is history, but it is offered on every view, as its header is.
    sortTags(
        listOf(
            tr("Item", "Producto") to GrocerySort.NAME, tr("Other chain", "Otra cadena") to GrocerySort.RIVAL,
            tr("Price", "Precio") to GrocerySort.PRICE,
            tr("Card saving", "Ahorro con tarjeta") to GrocerySort.CARD, tr("Unit price", "Precio unitario") to GrocerySort.UNIT,
            tr("Store %", "% tienda") to GrocerySort.STORE, tr("vs usual", "vs habitual") to GrocerySort.BELOW,
            tr("Card + store", "Tarjeta + tienda") to GrocerySort.CARD_STORE,
            tr("Card vs usual", "Tarjeta vs habitual") to GrocerySort.CARD_USUAL,
            tr("vs lowest", "vs mínimo") to GrocerySort.LOWEST,
        ).map { (label, sort) -> SortOption(label, controls.url(sort = sort), controls.sort == sort) },
    )
}

private fun FlowContent.groceryTable(rows: List<GroceryRow>, controls: GroceryControls) {
    table("deals tracked grocery") {
            caption("sr-only") { +tr("Grocery items", "Productos de mercado") }
        thead {
            tr {
                th(scope = ThScope.col, classes = "col-thumb") { attributes["aria-label"] = tr("image", "imagen") }
                sortableTh(tr("Item", "Producto"), GrocerySort.NAME, controls, "col-deal")
                sortableTh(
                    tr("Store", "Tienda"), GrocerySort.RIVAL, controls, "col-size",
                    tr(
                        "The store the price is from, and the same barcode at the other chain beneath. Sorts by how much " +
                            "cheaper the other chain is.",
                        "La tienda del precio, y el mismo código de barras en la otra cadena debajo. Ordena por cuánto " +
                            "más barata es la otra cadena.",
                    ),
                )
                // Two sorts in one header: by the shelf price, or by how much a card saves on it today.
                th(scope = ThScope.col, classes = "col-price") {
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
                th(scope = ThScope.col, classes = "col-price") {
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
                th(scope = ThScope.col, classes = "col-price") {
                    title = tr("Cheapest daily price in the last 30 days, today excluded", "El precio diario más bajo de los últimos 30 días, sin contar hoy")
                    +tr("30d low", "Mín. 30d")
                }
                sortableTh(
                    tr("Lowest ever", "Mín. histórico"), GrocerySort.LOWEST, controls, "col-price",
                    tr(
                        "Cheapest price ever recorded for this item at this store, today included. Old repeated readings are " +
                            "thinned, but every distinct price is kept, so this is exact. Sorts by how close today's price is to it.",
                        "El precio más bajo registrado alguna vez para este producto en esta tienda, incluido hoy. Las lecturas " +
                            "repetidas antiguas se reducen, pero cada precio distinto se conserva, así que es exacto. Ordena por qué tan cerca está el precio de hoy.",
                    ),
                )
                th(scope = ThScope.col, classes = "col-chg") {
                    title = tr("Earlier days of history the usual price rests on", "Días anteriores de historial en los que se basa el precio habitual")
                    +tr("Days", "Días")
                }
                th(scope = ThScope.col, classes = "col-act") { attributes["aria-label"] = tr("actions", "acciones") }
            }
        }
        tbody { rows.forEach { groceryRow(it, controls.view, split = splitByStore(controls), sort = controls.sort) } }
    }
}

/**
 * The words for a row's coloured edge, under its price: colour is never the only cue. Nothing for an
 * ordinary price.
 */
private fun FlowContent.standingChip(row: GroceryRow, standing: PriceStanding?) {
    if (standing == null) return
    val lowest = row.deal.minPriceEver?.let { formatMoney(it, row.deal.currency) }.orEmpty()
    div("stand") {
        span("chip ${standing.css}-chip") {
            when (standing) {
                PriceStanding.LOWEST -> {
                    title = tr("The lowest shelf price ever recorded for this item at this store", "El precio en góndola más bajo registrado para este producto en esta tienda")
                    +tr("lowest ever", "mínimo histórico")
                }
                PriceStanding.NEAR -> {
                    title = tr("Within ${NEAR_LOWEST_PERCENT}% of its lowest shelf price ever, $lowest", "A menos de ${NEAR_LOWEST_PERCENT}% de su precio en góndola más bajo, $lowest")
                    +tr("+${overLowest(row)?.toPlainString()}% over lowest", "+${overLowest(row)?.toPlainString()}% sobre el mínimo")
                }
                PriceStanding.HIGH -> {
                    title = tr("At least ${GroceryRow.DEAL_PERCENT}% over its usual price today", "Al menos ${GroceryRow.DEAL_PERCENT}% sobre su precio habitual hoy")
                    +tr("+${row.belowUsual?.negate()?.toPlainString()}% over usual", "+${row.belowUsual?.negate()?.toPlainString()}% sobre lo habitual")
                }
            }
        }
    }
}

private fun kotlinx.html.TR.sortableTh(
    label: String,
    sort: GrocerySort,
    controls: GroceryControls?,
    classes: String?,
    tooltip: String? = null,
) {
    th(scope = ThScope.col, classes = classes) {
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

internal fun TBODY.groceryRow(
    row: GroceryRow,
    view: GroceryView = GroceryView.DEALS,
    /** A search result, one row per store: the pin toggle must answer with this store's row, not the merged one. */
    split: Boolean = false,
    /** The table's sort: the two card sorts show the number they rank by, which no column holds. */
    sort: GrocerySort? = null,
) {
    val deal = row.deal
    val standing = priceStanding(row)
    // The feed's heat gutter, reused: the row's left edge says cheap or dear before a number is read.
    tr(classes = listOfNotNull(if (row.onShelf) null else "paused", standing?.let { "heat ${it.css}" }).joinToString(" ").ifEmpty { null }) {
        id = "grocery-${deal.id}"
        if (standing == PriceStanding.LOWEST) attributes["data-low"] = "1"
        // Read by AISLE_JS when an unpin removes a row, to keep the aisle's "under usual" count true.
        if (row.onShelf && row.isDeal) attributes["data-under"] = "1"
        td(classes = "col-thumb") {
            deal.imageUrl?.let { src ->
                a(href = deal.url) {
                    thumbLinkHidden()
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
                        "Not in its store's latest crawl, or Exito said it was sold out when you tried to add it — out of stock, or delisted. " +
                            "The price is the last one seen.",
                        "No apareció en la última lectura de su tienda, o Exito dijo que estaba agotado al intentar agregarlo — agotado, o retirado. " +
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
            // A link to that store's own copy (its history and pin), so the other Exito is one tap away.
            row.others.forEach { other ->
                a(href = "/deal/${other.deal.id}", classes = "store-alt") {
                    title = if (other.onShelf) {
                        tr("Same product at ${other.store}: open its price history", "El mismo producto en ${other.store}: ver su historial de precios")
                    } else {
                        tr("${other.store}: not in its latest crawl", "${other.store}: no apareció en su última lectura")
                    }
                    +"${other.store.substringAfterLast(' ')} "
                    if (other.onShelf) +formatMoney(other.deal.price, other.deal.currency) else +tr("out", "agotado")
                }
            }
            row.rival?.let { rivalLine(it, row.rivalGap) }
        }
        // The shelf price stays the main number -- what anyone pays. The card price sits under it as
        // a chip, because it is conditional: only a Mastercard (or only the store's own card) gets it.
        td(classes = "col-price") {
            cardLabel(tr("Price", "Precio"))
            div("price") { +formatMoney(deal.price, deal.currency) }
            standingChip(row, standing)
            cardChips(row.cardOffers, deal.currency)
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
            if (sort == GrocerySort.CARD_STORE) cardAndStorePercent(row)?.let { total ->
                div("combo") {
                    title = tr("Card price against the store's list price", "Precio con tarjeta frente al precio de lista de la tienda")
                    span { +tr("with card", "con tarjeta") }
                    +" "
                    span("v") { +"−${total.toPlainString()}%" }
                }
            }
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
            // Only where the card changes the answer: without a card price it equals the number above.
            if (sort == GrocerySort.CARD_USUAL && heldCardPrice(row) != null) belowUsualWithCard(row)?.let { b ->
                div("combo") {
                    title = tr("Your card's price against the usual price", "El precio con tu tarjeta frente al precio habitual")
                    val v = if (b.signum() >= 0) "−${b.toPlainString()}%" else "+${b.negate().toPlainString()}%"
                    span { +tr("with card", "con tarjeta") }
                    +" "
                    span("v") { +v }
                }
            }
        }
        td(classes = "col-price") {
            cardLabel(tr("30d low", "Mín. 30d"))
            row.low?.takeIf { row.days >= 1 }?.let { +formatMoney(it, deal.currency) } ?: span("muted") { +"—" }
        }
        td(classes = "col-price") {
            cardLabel(tr("Lowest ever", "Mín. histórico"))
            deal.minPriceEver?.let { +formatMoney(it, deal.currency) } ?: span("muted") { +"—" }
        }
        td(classes = "col-chg") {
            cardLabel(tr("Days", "Días"))
            +row.days.toString()
        }
        td(classes = "col-act") {
            // The cart button leads on a row of its own and the pin sits at the far end of the next, so the
            // one that spends money and the one that forgets a staple cannot be hit by the same thumb.
            div("actions grocery-actions") {
                if (view == GroceryView.PINNED) cartButton(row)
                a(href = "/deal/${deal.id}") { +tr("history", "historial") }
                val pinned = row.pinnedAt != null
                button(classes = if (pinned) "fav-btn on" else "fav-btn") {
                    attributes["hx-post"] = "/groceries/${deal.id}/${if (pinned) "unpin" else "pin"}?view=${view.key}" + if (split) "&split=1" else ""
                    attributes["hx-target"] = "#grocery-${deal.id}"
                    attributes["hx-swap"] = "outerHTML"
                    attributes["aria-pressed"] = pinned.toString()
                    // Unpinning drops the staple from the shopping list, and on Pinned the row vanishes with
                    // it; pinning is harmless, so only the removal asks.
                    if (pinned) attributes["hx-confirm"] = tr("Remove this item from Pinned?", "¿Quitar este producto de Fijados?")
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

/**
 * The other chain's price under the store tag, linked to its history. Highlighted only when the other
 * chain is cheaper: that is the case that changes where you shop; dearer is just reassurance.
 */
private fun FlowContent.rivalLine(rival: ChainRival, gap: BigDecimal?) {
    val cheaper = gap != null && gap.signum() < 0
    a(href = "/deal/${rival.dealId}", classes = if (cheaper) "store-rival cheaper" else "store-rival") {
        title = when {
            gap == null -> tr("Same barcode at ${rival.store}", "El mismo código de barras en ${rival.store}")
            cheaper -> tr(
                "Same barcode, ${gap.negate().toPlainString()}% cheaper at ${rival.store} today",
                "El mismo código de barras, ${gap.negate().toPlainString()}% más barato hoy en ${rival.store}",
            )
            gap.signum() == 0 -> tr("Same barcode, same price at ${rival.store}", "El mismo código de barras, el mismo precio en ${rival.store}")
            else -> tr(
                "Same barcode, ${gap.toPlainString()}% dearer at ${rival.store} today",
                "El mismo código de barras, ${gap.toPlainString()}% más caro hoy en ${rival.store}",
            )
        }
        +"${rival.store.substringBefore(' ')} ${formatMoney(rival.price, rival.currency)}"
        gap?.takeIf { it.signum() != 0 }?.let { span("gap") { +(if (cheaper) " −${it.negate().toPlainString()}%" else " +${it.toPlainString()}%") } }
    }
}

/**
 * Pinned's shopping plan: one stop per chain, each the items cheapest there today and their total, and
 * a link that narrows the list to that stop. The saving says why the split is worth the second trip.
 */
private fun FlowContent.planBlock(plan: ShoppingPlan, labels: Map<String, String>, controls: GroceryControls) {
    if (plan.stops.isEmpty()) return
    div("shop-plan") {
        span("shop-plan-h") { +tr("Cheapest split", "Reparto más barato") }
        plan.stops.forEach { stop ->
            val on = controls.buyAt == stop.chain
            a(href = controls.url(buyAt = if (on) null else stop.chain), classes = if (on) "plan-stop on" else "plan-stop") {
                if (on) attributes["aria-current"] = "true"
                title = if (on) tr("Show every pinned item again", "Mostrar de nuevo todos los fijados")
                else tr("Only the items cheapest at this chain today", "Solo los productos más baratos hoy en esta cadena")
                span("plan-chain") { +(labels[stop.chain] ?: stop.chain.replaceFirstChar { it.uppercase() }) }
                +" "
                +tr("${stop.items} item(s)", "${stop.items} producto(s)")
                +" · "
                span("plan-total") { +formatMoney(stop.total, plan.currency) }
            }
        }
        if (plan.saving.signum() > 0) {
            span("plan-note") {
                title = tr(
                    "Against buying each item at the store its row shows, where it is on the shelf",
                    "Frente a comprar cada producto en la tienda de su fila, donde está en góndola",
                )
                +tr("saves ${formatMoney(plan.saving, plan.currency)}", "ahorra ${formatMoney(plan.saving, plan.currency)}")
            }
        }
        if (plan.unavailable > 0) {
            span("plan-note muted") { +tr("${plan.unavailable} not on any shelf", "${plan.unavailable} sin existencias en ninguna") }
        }
    }
}

/**
 * "Only items I can add to the cart" on Pinned. The count is of rows that would remain, so ticking it
 * changes the table by exactly the number it promised.
 */
private fun FlowContent.cartableToggle(count: Int, controls: GroceryControls) {
    div("noship-toggle") {
        label {
            input(type = InputType.checkBox) {
                checked = controls.cartable
                attributes["onchange"] =
                    "location.href=this.checked?'${controls.url(cartable = true)}':'${controls.url(cartable = false)}'"
            }
            +tr(" only items I can add to the Exito cart", " solo productos que puedo agregar al carrito de Exito")
            span("typetag-n") { +count.toString() }
        }
    }
}

/** `carulla-grocery` -> `Carulla`. The provider id carries the store; the tab only needs its name. */
internal fun storeName(source: String): String =
    source.removeSuffix("-grocery").replaceFirstChar { it.uppercase() }

/** Marks a pin toggle posted from a deal's own page: it answers with the button, not a table row. */
internal const val DETAIL_VIEW = "detail"

/**
 * The ☆ pin / ★ pinned toggle on a grocery item's detail page. Same endpoint as the Groceries tab, which
 * pins the product at every store, but it swaps itself rather than a table row.
 */
internal fun kotlinx.html.FlowContent.groceryDetailPinButton(id: Long, pinned: Boolean) {
    button(classes = if (pinned) "fav-btn on" else "fav-btn") {
        attributes["hx-post"] = "/groceries/$id/${if (pinned) "unpin" else "pin"}?view=$DETAIL_VIEW"
        attributes["hx-target"] = "this"
        attributes["hx-swap"] = "outerHTML"
        attributes["aria-pressed"] = pinned.toString()
        if (pinned) attributes["hx-confirm"] = tr("Remove this item from Pinned?", "¿Quitar este producto de Fijados?")
        title = if (pinned) {
            tr("Unpin this staple", "Quitar este básico de fijados")
        } else {
            tr("Pin as a staple, listed under Pinned", "Fijar como básico, aparece en Fijados")
        }
        +if (pinned) tr("★ pinned", "★ fijado") else tr("☆ pin", "☆ fijar")
    }
}

internal fun groceryDetailPinFragment(id: Long, pinned: Boolean): String =
    createHTML(prettyPrint = false).div { groceryDetailPinButton(id, pinned) }
        .removePrefix("<div>").removeSuffix("</div>")

/** One row as a bare `<tr>`, for the htmx swap after a pin toggle. */
internal fun groceryRowFragment(row: GroceryRow, view: GroceryView, split: Boolean = false, sort: GrocerySort? = null): String =
    createHTML(prettyPrint = false).table { tbody { groceryRow(row, view, split, sort) } }
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
            caption("sr-only") { +tr("Last sync", "Última sincronización") }
            thead {
                tr {
                    th(scope = ThScope.col) { +tr("Store", "Tienda") }
                    th(scope = ThScope.col) { +tr("Prices read", "Precios leídos") }
                    th(scope = ThScope.col) { +tr("Items", "Productos") }
                    th(scope = ThScope.col) { +tr("Latest run", "Última ejecución") }
                    th(scope = ThScope.col) { +tr("Next", "Siguiente") }
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
