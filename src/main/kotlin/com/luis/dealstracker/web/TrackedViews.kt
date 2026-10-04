package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.formatMoney
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.id
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.img
import kotlinx.html.InputType
import kotlinx.html.details
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.summary
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import com.luis.dealstracker.i18n.categoryLabel
import com.luis.dealstracker.i18n.tr
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Product types a tracked row can be filtered to, derived from its title rather than a column.
 *
 * `deals` has no product-type field, but it does not need one here: the adapter already appends
 * the size, and a size label identifies the type unambiguously — a numeric size is footwear, a
 * letter size is a garment, and which garment is readable from the title's own nouns. Deriving it
 * beats a migration for a filter that is purely a view concern.
 *
 * Order is the display order of the tags.
 */
internal enum class ProductType(val label: String, internal val nouns: List<String>) {
    SHOES(
        "shoes",
        listOf(
            "shoe", "sneaker", "trainer", "cleat", "boot", "sandal", "slide", "runner",
            "metcon", "samba", "air max", "air force",
        ),
    ),
    HOODIES(
        "hoodies",
        // `outerwear`, `jacket`, `windbreaker`, `track top` and `half-zip` all live here: they are
        // upper-body layers, and a live run filed "Essentials Warm-Up 3-Stripes Athletic Outerwear"
        // under `other` for want of any of them.
        //
        // `fleece` is deliberately ABSENT. Like `jersey` it is a FABRIC — "Club Fleece Shorts" and
        // "Essentials Fleece Pants" are both real tracked products — and a fleece top nearly always
        // says hoodie, pullover or sweatshirt as well.
        //
        // `crewneck` is ABSENT for the same reason: it is a NECKLINE, and tees have one too. It put
        // "Men's Strike Short Sleeve Crewneck T-Shirt" under a `hoodies` filter, because HOODIES is
        // declared before SHIRTS and wins a tie. A crewneck sweatshirt still says `sweatshirt`.
        listOf(
            "hoodie", "pullover", "sweatshirt", "outerwear", "jacket",
            "windbreaker", "half-zip", "quarter-zip", "track top", "vest",
        ),
    ),
    // PANTS before SHIRTS, and both before the fabric words. Two live misclassifications pulled in
    // opposite directions and neither ordering alone fixed both, so the NOUNS are precise instead:
    //
    //  - "Single Jersey Tapered Open Hem Pants" -> was SHIRTS, because `jersey` is a fabric here.
    //  - "Jersey Short Sleeve T-Shirt"          -> was PANTS, because `short` matched "Short Sleeve".
    //
    // `shorts` is therefore always plural (a garment is; "short sleeve" is not), and `bottoms`,
    // `tracksuit` and `cargo` were added after a run filed "Tracksuit Bottoms" and "Woven Cargo"
    // under `other`.
    // Before PANTS: a boxer brief is a "Boxer Brief Underwear" and some are described as
    // "trunks", neither of which should be filed as trousers. Its own tag rather than folded into
    // pants, because "show me boxers" and "show me trousers" are different questions even though
    // they share a configured size.
    BOXERS("boxers", listOf("boxer", "brief", "trunk", "underwear")),
    PANTS(
        "pants",
        listOf(
            "pant", "jogger", "sweatpant", "shorts", "trouser", "tight", "legging",
            "bottoms", "tracksuit", "cargo", "chino",
        ),
    ),
    SHIRTS(
        "shirts",
        // `short-sleeve`, `long-sleeve`, `hydroguard` and `rash guard` are here because a live run
        // put "Men's Short-Sleeve Hydroguard" in `other`: it is plainly a top, and a sleeve length
        // is only ever said of one. Hyphenated, so it cannot collide with `shorts`.
        listOf(
            "shirt", "tee", "t-shirt", "polo", "tank", "jersey", "short-sleeve",
            "long-sleeve", "hydroguard", "rash guard", "base layer",
        ),
    ),
    OTHER("other", emptyList());

    /** The tag's text. [label] stays English because it is also the `type=` URL value. */
    val display: String
        get() = when (this) {
            SHOES -> tr("shoes", "zapatos")
            HOODIES -> tr("hoodies", "buzos")
            BOXERS -> tr("boxers", "bóxers")
            PANTS -> tr("pants", "pantalones")
            SHIRTS -> tr("shirts", "camisetas")
            OTHER -> tr("other", "otros")
        }

    companion object {
        /**
         * Classifies a row for the type tags.
         *
         * ## Why the size decides an ambiguous title
         *
         * Amazon titles routinely name two garments. "Mens Squadra 25 **Jersey** Soccer **Cleats**"
         * is a real tracked product, and reading its nouns alone gives both SHOES and SHIRTS — it
         * appeared under a `shoes` filter while being a size M top.
         *
         * The size resolves it, and does so from Amazon's own guarantee rather than a guess: this
         * adapter only stores an item because Amazon's size filter returned it, so a numeric size
         * means footwear and a letter size means a garment. So when the nouns disagree ACROSS that
         * divide, the size wins and the losing candidate is discarded.
         *
         * Nouns still decide everything the size cannot: a letter size says "a garment", not which.
         */
        fun of(title: String): ProductType {
            val t = title.lowercase()
            val matched = entries.filter { type -> type.nouns.any { t.contains(it) } }
            if (matched.isEmpty()) return fromSizeAlone(title)

            val footwear = sizeOf(title)?.firstOrNull()?.isDigit()
            // Only intervene when the size actually contradicts the nouns; otherwise declaration
            // order decides, which is what the noun lists are ordered for.
            return when (footwear) {
                true -> matched.firstOrNull { it == SHOES } ?: matched.first()
                false -> matched.firstOrNull { it != SHOES } ?: matched.first()
                null -> matched.first()
            }
        }

        /**
         * A title with no recognisable garment noun at all — "Men's Slim", "Men's Modern".
         *
         * Amazon truncates aggressively in search tiles, and those two are real stored titles. The
         * size still says whether it is footwear, so that much is reported rather than discarded;
         * a letter size only says "a garment", which is not one of the buckets, so it stays OTHER.
         */
        private fun fromSizeAlone(title: String): ProductType =
            if (sizeOf(title)?.firstOrNull()?.isDigit() == true) SHOES else OTHER
    }
}

/**
 * How a tracked row got here: found by a search, or added by URL.
 *
 * Worth distinguishing rather than a cosmetic label, because the two are not equally trustworthy.
 * Amazon's size FILTER returns a representative of the product family, not strictly the matching
 * child — a size-8 search returned the 8.5 ASIN at $130.26 while size 8 sold for $107.41 — so a
 * searched row's size is Amazon's claim. A pasted URL names an exact ASIN, so its size comes from
 * that ASIN's own variation entry and its price is the price of the thing actually wanted.
 *
 * So "added by hand" marks the rows whose numbers can be relied on, which is the reason to surface
 * it at all.
 */
internal enum class TrackedOrigin(val label: String) {
    MANUAL("added"),
    SEARCH("found");

    /** The tag's text. [label] stays English because it is also the `origin=` URL value. */
    val display: String
        get() = when (this) {
            MANUAL -> tr("added", "agregados")
            SEARCH -> tr("found", "encontrados")
        }

    companion object {
        fun of(value: String?): TrackedOrigin? =
            entries.firstOrNull { it.label.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * How the tracked table is ordered.
 *
 * A small local enum rather than reusing `WatchSort`, which is coupled to the two watch tables and
 * their paging. This table has four meaningful orderings and no paging, so a shared abstraction
 * would cost more than it saves.
 *
 * Sorting happens in Kotlin, not SQL, deliberately: `isAtLowestEver` and `discountPercent` are
 * computed properties on `Deal`, so ordering by them in the repository would mean a second
 * definition of each in SQL — the drift this project guards against in `DealScore`.
 */
internal enum class TrackedSort(val key: String, val label: String) {
    /** Default. Cheapest first — the question the page exists to answer. */
    PRICE("price", "Now"),

    /** Amazon's advertised discount, deepest first. Rows with no list price sort last. */
    OFF("off", "Off"),

    /** Genuinely at its lowest recorded price first, then by how far it fell. */
    LOWEST("lowest", "Low"),

    /** Most recently checked first. */
    CHECKED("checked", "Checked");

    companion object {
        fun of(value: String?): TrackedSort =
            entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: PRICE
    }
}

/**
 * The comparator for each ordering.
 *
 * Every one puts "no value" LAST rather than treating it as zero. A row with no list price is not a
 * 0%-off row, and a row with one observation is not a row that failed to fall — the same rule the
 * columns follow when they render `—`.
 */
internal fun comparatorFor(sort: TrackedSort): Comparator<Deal> = when (sort) {
    TrackedSort.PRICE -> compareBy { it.price }
    TrackedSort.OFF -> compareByDescending<Deal> { it.discountPercent ?: BigDecimal("-1") }
        .thenBy { it.price }
    // At-lowest first, then deepest fall from the recorded peak. `isAtLowestEver` already requires
    // an observed decline, so this cannot be satisfied by a price that simply never moved.
    TrackedSort.LOWEST -> compareByDescending<Deal> { it.isAtLowestEver }
        .thenByDescending { it.dropFromPeak ?: BigDecimal.ZERO }
        .thenBy { it.price }
    TrackedSort.CHECKED -> compareByDescending { it.lastSeenAt }
}

/**
 * The canonical `/tracked` URL for a given filter and ordering.
 *
 * Built in one place so the type tags and the column headers cannot encode state differently —
 * each control changes its own dimension and carries the other along unchanged.
 */
private fun trackedUrl(
    types: Set<ProductType>,
    sort: TrackedSort,
    brand: String? = null,
    origin: TrackedOrigin? = null,
    showNoShip: Boolean = false,
    freeShip: Boolean = false,
): String {
    val params = buildList {
        if (types.isNotEmpty()) add("type=" + types.joinToString(",") { it.label })
        // The default is omitted, so the plain path stays the canonical URL for it.
        if (sort != TrackedSort.PRICE) add("sort=${sort.key}")
        brand?.takeIf { it.isNotBlank() }?.let { add("brand=" + java.net.URLEncoder.encode(it, "UTF-8")) }
        origin?.let { add("origin=${it.label}") }
        // Hidden is the default, so only the opt-in is written.
        if (showNoShip) add("noship=show")
        if (freeShip) add("freeship=1")
    }
    return if (params.isEmpty()) "/tracked" else "/tracked?" + params.joinToString("&")
}

/**
 * A sortable column header, preserving whatever type tags are selected.
 *
 * Plain links carrying state in the query string, like the type tags — so the whole view stays
 * shareable and needs no JavaScript.
 */
private fun kotlinx.html.TR.sortTh(
    label: String,
    sort: TrackedSort,
    current: TrackedSort,
    selected: Set<ProductType>,
    classes: String,
    brand: String?,
    origin: TrackedOrigin?,
    showNoShip: Boolean,
    freeShip: Boolean = false,
    tooltip: String? = null,
) {
    val href = trackedUrl(selected, sort, brand, origin, showNoShip, freeShip)

    th(classes = classes) {
        tooltip?.let { title = it }
        a(href = href, classes = if (current == sort) "sorted" else null) {
            +label
            // A glyph as well as the class, so the active column does not rely on colour alone.
            if (current == sort) span("dir") { +" ↓" }
        }
    }
}

/**
 * "Add an item by URL" — the manual tracking form.
 *
 * ## Why this is the more trustworthy of the two routes
 *
 * The searches find items by keyword and let Amazon filter by size, which is imprecise by nature:
 * Amazon's size filter returns a REPRESENTATIVE of the product family, not strictly the matching
 * child. A size-8 search returned the 8.5 ASIN at $130.26 while size 8 sold for $107.41.
 *
 * A pasted URL names an exact ASIN, so the price is the price of the thing actually wanted and the
 * size is read from that ASIN's own variation entry. When a specific item matters, paste it.
 *
 * Brand and type are OPTIONAL and normally left blank: the product page publishes a brand
 * ("Visit the New Balance Store") and a breadcrumb category, both of which are read automatically.
 * They exist for the cases where the page says nothing useful, and they win when supplied.
 *
 * A plain POST-and-redirect rather than htmx: the add costs a live Amazon fetch and can take
 * seconds, so a full response with a result message is clearer than a silent swap.
 */
/**
 * The configured searches, grouped by garment, each with a switch. Off searches are skipped by the
 * next fetch. Group totals count the rows currently on the tab, not per search: `deals` does not
 * record which search found a row.
 */
private fun MAIN.searchesPanel(
    toggles: List<Triple<String, String, Boolean>>,
    deals: List<Deal>,
    open: Boolean,
) {
    if (toggles.isEmpty()) return
    val rowsByType = deals.groupingBy { ProductType.of(it.title) }.eachCount()
    details("additem searches") {
        id = "searches"
        if (open) attributes["open"] = ""
        summary {
            +tr("Manage searches", "Gestionar búsquedas")
            +" (${toggles.count { it.third }}/${toggles.size})"
        }
        div("additem-notice") {
            +tr(
                "A search that is off is skipped by the next fetch. Its price history is kept, and switching it back on resumes it.",
                "Una búsqueda apagada se omite en la próxima consulta. Su historial de precios se conserva y al encenderla se reanuda.",
            )
        }
        div("search-cols") {
            toggles.groupBy { ProductType.of(it.second) }.forEach { (type, group) ->
                val allOn = group.all { it.third }
                div("search-grp") {
                    div("search-hd") {
                        span { +type.display }
                        span("search-n") { +tr("${rowsByType[type] ?: 0} item(s)", "${rowsByType[type] ?: 0} producto(s)") }
                        searchForm(group, !allOn) {
                            button(type = kotlinx.html.ButtonType.submit, classes = "search-all") {
                                +if (allOn) tr("turn all off", "apagar todo") else tr("turn all on", "encender todo")
                            }
                        }
                    }
                    group.forEach { g ->
                        val (_, term, enabled) = g
                        div(if (enabled) "search-row" else "search-row off") {
                            searchForm(listOf(g), !enabled) {
                                button(type = kotlinx.html.ButtonType.submit, classes = if (enabled) "sw on" else "sw") {
                                    attributes["role"] = "switch"
                                    attributes["aria-checked"] = enabled.toString()
                                    attributes["aria-label"] = term
                                }
                            }
                            span { +term }
                            if (!enabled) span("search-n") { +tr("paused", "en pausa") }
                        }
                    }
                }
            }
        }
    }
}

private fun kotlinx.html.FlowContent.searchForm(
    group: List<Triple<String, String, Boolean>>,
    enable: Boolean,
    block: kotlinx.html.FORM.() -> Unit,
) {
    form {
        attributes["method"] = "post"
        attributes["action"] = "/tracked/search"
        group.forEach { (provider, term, _) ->
            input(type = InputType.hidden, name = "search") { value = "$provider\t$term" }
        }
        input(type = InputType.hidden, name = "enabled") { value = enable.toString() }
        block()
    }
}

private fun MAIN.addItemForm(notice: String?) {
    details("additem") {
        // Open when there is a result to show. The notice lives inside the details, and a collapsed
        // one after the redirect made a successful add look like the button did nothing.
        if (notice != null) attributes["open"] = ""
        summary { +tr("Track an item by URL", "Seguir un producto por URL") }

        notice?.let { div("additem-notice") { +it } }

        form(classes = "additem-form") {
            attributes["method"] = "post"
            attributes["action"] = "/tracked/add"

            div("f wide") {
                label { +tr("Amazon URL", "URL de Amazon") }
                input(type = InputType.text, name = "url") {
                    required = true
                    placeholder = "https://www.amazon.com/dp/B074JLDHK3"
                    title = tr(
                        "Any amazon.com product link — the /dp/<ASIN> part is what matters, and " +
                            "a ?th=1&psc=1 tail is fine.",
                        "Cualquier enlace de producto de amazon.com — lo que importa es la parte /dp/<ASIN>, " +
                            "y un final ?th=1&psc=1 no molesta.",
                    )
                }
            }
            div("f") {
                label { +tr("Brand (optional)", "Marca (opcional)") }
                input(type = InputType.text, name = "brand") {
                    placeholder = tr("read from the page", "se lee de la página")
                    title = tr(
                        "Only needed if the page's byline does not name a brand.",
                        "Solo hace falta si la página no dice la marca.",
                    )
                }
            }
            div("f") {
                label { +tr("Type (optional)", "Tipo (opcional)") }
                input(type = InputType.text, name = "productType") {
                    placeholder = tr("read from the breadcrumb", "se lee de la ruta de categorías")
                    title = tr(
                        "Only needed if Amazon's breadcrumb categorises it unhelpfully.",
                        "Solo hace falta si la categoría que da Amazon no sirve.",
                    )
                }
            }
            div("f") {
                label { +"" }
                button { +tr("Add & fetch", "Agregar y consultar") }
            }
        }
    }
}

/**
 * Brand filter, as a dropdown rather than tags.
 *
 * A select is right here where tags are right for product type, and the difference is cardinality:
 * there are four garment types and that will not change, but the brands are whatever Amazon returns
 * — 9 distinct ones on a single captured page, including one-off marketplace sellers like `DUORO`
 * and `Pozvnn`. Rendering those as tags would wrap the filter bar and bury the four that matter.
 *
 * Built from the rows actually present, so it never offers a brand that would empty the page, and
 * ordered by count so the brands you track lead. Submits on change via a tiny inline handler — the
 * one place this page uses JavaScript, because a select with no submit button otherwise needs a
 * "Go" button that a dropdown should not need.
 */
private fun MAIN.brandPicker(
    deals: List<Deal>,
    types: Set<ProductType>,
    sort: TrackedSort,
    brand: String?,
    origin: TrackedOrigin?,
    showNoShip: Boolean,
    freeShip: Boolean = false,
) {
    // Case-insensitive grouping: V12's backfill recovered the folded form (`adidas`) from
    // model_key while a fresh fetch stores the source's own casing (`Nike`), and the two must not
    // appear as separate brands.
    val counts = deals.mapNotNull { it.brand?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it.lowercase() }.eachCount()
    val display = deals.mapNotNull { it.brand?.trim()?.takeIf(String::isNotEmpty) }
        .associateBy { it.lowercase() }

    if (counts.isEmpty()) return

    div("brandpick") {
        label {
            +tr("Brand", "Marca")
            select {
                attributes["onchange"] = "location.href=this.value"

                option {
                    value = trackedUrl(types, sort, null, origin, showNoShip, freeShip)
                    if (brand.isNullOrBlank()) this.selected = true
                    +tr("all brands", "todas las marcas")
                }
                counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .forEach { (key, n) ->
                        val name = display[key] ?: key
                        option {
                            value = trackedUrl(types, sort, name, origin, showNoShip, freeShip)
                            if (brand?.lowercase() == key) this.selected = true
                            +"$name ($n)"
                        }
                    }
            }
        }
    }
}

/**
 * "How it got here" tags: found by a search, or added by URL.
 *
 * Tags rather than a dropdown, matching the type row: there are exactly two values and both fit.
 * Counted within the active brand for the same reason the type tags are — a tag that promises rows
 * the page will not show teaches you to distrust the numbers.
 */
private fun MAIN.originTags(
    deals: List<Deal>,
    manualAsins: Set<String>,
    selected: Set<ProductType>,
    sort: TrackedSort,
    brand: String?,
    origin: TrackedOrigin?,
    showNoShip: Boolean,
    freeShip: Boolean = false,
) {
    val inBrand = deals.filter {
        brand.isNullOrBlank() || it.brand?.trim()?.lowercase() == brand.trim().lowercase()
    }
    val manual = inBrand.count { it.asin != null && it.asin in manualAsins }
    val found = inBrand.size - manual

    // Nothing to choose between if every row came the same way.
    if (manual == 0 || found == 0) return

    div("typetags") {
        span("typetags-label") { +tr("Origin", "Origen") }

        listOf(TrackedOrigin.MANUAL to manual, TrackedOrigin.SEARCH to found).forEach { (which, n) ->
            val on = origin == which
            a(
                classes = if (on) "typetag on" else "typetag",
                href = trackedUrl(selected, sort, brand, if (on) null else which, showNoShip, freeShip),
            ) {
                +which.display
                span("typetag-n") { +n.toString() }
            }
        }
    }
}

/**
 * The selectable type tags.
 *
 * Tags rather than a dropdown, as asked: with four options the whole choice is visible at once and
 * costs one click, where a select costs two and hides the alternatives. Multi-select, because
 * "shoes and hoodies" is a reasonable thing to want.
 *
 * Plain links with the state in the query string — no JavaScript. That keeps the page shareable and
 * bookmarkable, and matches how the feed's own filters already work.
 */
private fun MAIN.typeTags(
    deals: List<Deal>,
    selected: Set<ProductType>,
    sort: TrackedSort,
    brand: String?,
    origin: TrackedOrigin?,
    showNoShip: Boolean,
    freeShip: Boolean = false,
) {
    // Counted within the active BRAND, but across all types.
    //
    // Both halves matter. Ignoring the brand promised rows the page would not show -- with
    // "New Balance (30)" selected the tags still read "shoes 75", which is the whole catalogue. And
    // counting within the selected TYPES too would make each tag show only its own rows, so
    // deselecting one could never be predicted from the numbers.
    //
    // So: brand narrows the population, type does not.
    val inBrand = deals.filter {
        brand.isNullOrBlank() || it.brand?.trim()?.lowercase() == brand.trim().lowercase()
    }
    val counts = inBrand.groupingBy { ProductType.of(it.title) }.eachCount()

    div("typetags") {
        span("typetags-label") { +tr("Type", "Tipo") }

        ProductType.entries.forEach { type ->
            val n = counts[type] ?: 0
            // A type nothing matches is not offered. Showing "shirts 0" invites a click that
            // empties the page and teaches you the filter is broken.
            if (n == 0) return@forEach

            val on = type in selected
            val next = if (on) selected - type else selected + type
            val href = trackedUrl(next, sort, brand, origin, showNoShip, freeShip)

            a(classes = if (on) "typetag on" else "typetag", href = href) {
                +type.display
                span("typetag-n") { +n.toString() }
            }
        }

        if (selected.isNotEmpty()) {
            // Clears the TYPE filter only. Resetting the sort too would be a second, unasked-for
            // change hiding behind one control.
            a(classes = "typetag clear", href = trackedUrl(emptySet(), sort, brand, origin, showNoShip, freeShip)) { +tr("clear", "quitar") }
        }
    }
}

/**
 * "Show items that don't ship to Colombia" — hidden by default.
 *
 * A checkbox rather than a tag: it is a single on/off, and it is not a kind of item the way the
 * type and origin tags are. Navigates on change, like the brand select, so the state lives in the
 * query string and the view stays shareable with no form to submit.
 *
 * The count is of rows the CURRENT filters would add back, so ticking it changes the table by
 * exactly the number it promised. Not offered when there are none.
 */
private fun MAIN.noShipToggle(
    deals: List<Deal>,
    selected: Set<ProductType>,
    sort: TrackedSort,
    brand: String?,
    origin: TrackedOrigin?,
    manualAsins: Set<String>,
    showNoShip: Boolean,
    freeShip: Boolean = false,
) {
    val n = trackedShown(deals, selected, brand, origin, manualAsins, sort, showNoShip = true)
        .count { it.coShipping == CoShipping.NO }
    if (freeShip || (n == 0 && !showNoShip)) return

    div("noship-toggle") {
        label {
            input(type = InputType.checkBox) {
                checked = showNoShip
                attributes["onchange"] =
                    "location.href=this.checked?'${trackedUrl(selected, sort, brand, origin, true, freeShip)}'" +
                    ":'${trackedUrl(selected, sort, brand, origin, false, freeShip)}'"
            }
            +tr(" show items that don't ship to Colombia", " mostrar productos que no envían a Colombia")
            span("typetag-n") { +n.toString() }
        }
    }
}

/**
 * "Only free shipping to Colombia". Off by default; the state lives in the query string like the
 * other toggles. The count is of rows that would remain, so ticking it changes the table by
 * exactly the number it promised.
 */
private fun MAIN.freeShipToggle(
    deals: List<Deal>,
    selected: Set<ProductType>,
    sort: TrackedSort,
    brand: String?,
    origin: TrackedOrigin?,
    showNoShip: Boolean,
    freeShip: Boolean,
) {
    val n = deals.count { it.freeShipToColombia }
    div("noship-toggle") {
        label {
            input(type = InputType.checkBox) {
                checked = freeShip
                attributes["onchange"] =
                    "location.href=this.checked?'${trackedUrl(selected, sort, brand, origin, showNoShip, true)}'" +
                    ":'${trackedUrl(selected, sort, brand, origin, showNoShip, false)}'"
            }
            +tr(" only free shipping to Colombia", " solo envío gratis a Colombia")
            span("typetag-n") { +n.toString() }
        }
    }
}

/**
 * The tracked-items view: "what is this costing me over time?"
 *
 * ## Why this is its own page rather than a filter on the feed
 *
 * The feed answers "what is discounted right now?" — it is sorted by composed deal quality, and
 * its job is to rank hundreds of rows you have not chosen. For an item you *have* chosen, ranking
 * is beside the point: you already decided you want the shoe, so a 40%-off badge on a different
 * one is noise.
 *
 * What matters here is movement — what it costs now, the cheapest and dearest we have recorded,
 * and which direction it went. Those come from `price_observations`, which the feed shows only as
 * a small chip.
 *
 * ## Two kinds of number, deliberately side by side
 *
 * `List` and `Off` are **Amazon's claim** about its own former price. `Lowest`, `Highest` and
 * `Change` are **our measurements**. They sit in adjacent columns because comparing them is the
 * point: this project documents at length that Éxito flags ~94% of its catalogue as discounted, so
 * an advertised percentage is evidence of nothing on its own, while an observed decline cannot be
 * manufactured. Keeping both visible lets a real markdown be told from a standing "sale".
 *
 * Same `deals` rows underneath, read through the same `DealRepository.feed` — this is a different
 * presentation, not a second store of truth.
 *
 * ## Honest when the history is thin
 *
 * A tracked item with one observation has no low, no high and no change, and this says so with
 * `—` rather than repeating the current price three times. Showing `US$49.00 / US$49.00 / 0%`
 * would look like a measured flat trend when the truth is that nothing has been measured yet.
 * That is the same trap `Deal.isAtLowestEver` documents: a signal that is trivially true for
 * every row teaches you to ignore it.
 */
internal fun MAIN.trackedPage(
    allDeals: List<Deal>,
    sources: List<String>,
    selected: Set<ProductType> = emptySet(),
    sort: TrackedSort = TrackedSort.PRICE,
    brand: String? = null,
    notice: String? = null,
    manualAsins: Set<String> = emptySet(),
    origin: TrackedOrigin? = null,
    showNoShip: Boolean = false,
    freeShip: Boolean = false,
    searchToggles: List<Triple<String, String, Boolean>> = emptyList(),
    searchesOpen: Boolean = false,
) {
    addItemForm(notice)

    // Before anything counts rows, so the tags, the total and the table all agree on one row per
    // product. See collapseVariants.
    val (deals, variants) = collapseVariants(allDeals, manualAsins)
    searchesPanel(searchToggles, deals, searchesOpen)

    p("tracked-intro") {
        +tr(
            "Items tracked by name and size, with the price movement we have recorded. ",
            "Productos seguidos por nombre y talla, con el movimiento de precio que hemos registrado. ",
        )
        +tr(
            "This is not a deal feed — nothing here is filtered by discount. ",
            "Esto no es un listado de ofertas — aquí nada se filtra por descuento. ",
        )
        a(href = "/") { +tr("The feed", "El listado") }
        +tr(
            " answers what is on offer; this answers what your items cost.",
            " responde qué está en oferta; esto responde cuánto cuestan tus productos.",
        )
    }

    if (deals.isEmpty()) {
        div("empty") {
            if (sources.isEmpty()) {
                +tr("No tracking providers are enabled. Add one with ", "No hay proveedores de seguimiento activos. Agrega uno con ")
                code { +"type: amazon" }
                +tr(" in config/sources.yaml.", " en config/sources.yaml.")
            } else {
                +tr("Nothing tracked yet. Configure ", "Todavía no hay nada en seguimiento. Configura ")
                code { +"trackSearches" }
                +tr(" and your sizes under the ", " y tus tallas en el proveedor ")
                code { +sources.joinToString(", ") }
                +tr(" provider, then run ", " y luego ejecuta ")
                code { +"./dt run --args=\"--fetch-once --source=${sources.first()}\"" }
                +tr(
                    ". Amazon rate-limits hard, so expect a run to bring back a handful at a time.",
                    ". Amazon limita mucho las consultas, así que cada ejecución trae solo unos pocos.",
                )
            }
        }
        return
    }

    // Rows Amazon refuses to ship to Colombia are out of the population entirely unless asked for,
    // tags included: a tag counting rows the table then hides is the mismatch the brand and type
    // counts were built to avoid.
    val pool = (if (showNoShip) deals else deals.filterNot { it.coShipping == CoShipping.NO })
        .filter { !freeShip || it.freeShipToColombia }

    // Tags are built from the UNFILTERED set, so the counts stay stable and a deselected type does
    // not vanish from the row the moment you click it.
    brandPicker(pool, selected, sort, brand, origin, showNoShip, freeShip)
    typeTags(pool, selected, sort, brand, origin, showNoShip, freeShip)
    originTags(pool, manualAsins, selected, sort, brand, origin, showNoShip, freeShip)
    noShipToggle(deals, selected, sort, brand, origin, manualAsins, showNoShip, freeShip)
    freeShipToggle(deals, selected, sort, brand, origin, showNoShip, freeShip)

    val shown = trackedShown(deals, selected, brand, origin, manualAsins, sort, showNoShip, freeShip)

    if (shown.isEmpty()) {
        div("empty") {
            +tr("No tracked items of that type. ", "No hay productos en seguimiento de ese tipo. ")
            a(href = trackedUrl(emptySet(), sort, brand, origin, showNoShip, freeShip)) { +tr("Show all", "Ver todos") }
            +"."
        }
        return
    }

    div("tracked-count") {
        +if (selected.isEmpty()) {
            tr("${shown.size} item(s) tracked", "${shown.size} producto(s) en seguimiento")
        } else {
            tr("${shown.size} of ${pool.size} item(s) tracked", "${shown.size} de ${pool.size} producto(s) en seguimiento")
        }
        val awaiting = shown.count { it.observationCount < 2 }
        if (awaiting > 0) {
            // Stated up front, because on the first day EVERY row is in this state and a page
            // full of dashes otherwise reads as broken rather than as young.
            span("tracked-note") {
                +tr(
                    " · $awaiting awaiting a second price check before movement can be shown",
                    " · $awaiting esperando una segunda revisión de precio para mostrar movimiento",
                )
            }
        }
        // Counted for the same reason: until the check runs, a row may not ship at all.
        val unchecked = shown.count { it.shippingUnchecked }
        if (unchecked > 0) {
            span("tracked-note") {
                +tr(
                    " · $unchecked not yet checked for shipping to Colombia",
                    " · $unchecked sin revisar si envían a Colombia",
                )
            }
        }
    }

    sortTags(
        listOf(
            tr("Now", "Ahora") to TrackedSort.PRICE,
            tr("Off", "Dto.") to TrackedSort.OFF,
            tr("Lowest yet", "Mínimo histórico") to TrackedSort.LOWEST,
            tr("Checked", "Revisado") to TrackedSort.CHECKED,
        )
            .map { (label, s) -> SortOption(label, trackedUrl(selected, s, brand, origin, showNoShip, freeShip), sort == s) },
    )
    table("deals tracked") {
        thead {
            tr {
                th(classes = "col-thumb") { attributes["aria-label"] = tr("image", "imagen") }
                th(classes = "col-deal") { +tr("Item", "Producto") }
                th(classes = "col-size") { +tr("Size", "Talla") }
                sortTh(tr("Now", "Ahora"), TrackedSort.PRICE, sort, selected, "col-price", brand, origin, showNoShip, freeShip)
                th(classes = "col-price") {
                    title = tr(
                        "Amazon's own \"List:\" price, where it publishes one",
                        "El precio \"List:\" de Amazon, cuando lo publica",
                    )
                    +tr("List", "Lista")
                }
                sortTh(
                    tr("Off", "Dto."), TrackedSort.OFF, sort, selected, "col-disc", brand, origin, showNoShip, freeShip,
                    tooltip = tr(
                        "Discount against Amazon's list price — its claim about its own " +
                            "former price, not a decline we observed. Blank where no list price is " +
                            "shown; those rows sort last.",
                        "Descuento sobre el precio de lista de Amazon — lo que Amazon dice de su " +
                            "propio precio anterior, no una baja que hayamos visto. Vacío cuando no hay " +
                            "precio de lista; esas filas quedan de últimas.",
                    ),
                )
                th(classes = "col-extra") {
                    title = tr(
                        "International shipping and import charges Amazon quotes on top of " +
                            "the price, plus what the item therefore actually costs delivered. " +
                            "Blank until the Colombia shipping check has run for that item.",
                        "Envío internacional e impuestos de importación que Amazon cobra además del " +
                            "precio, y lo que el producto cuesta entonces puesto en casa. " +
                            "Vacío hasta que se revise el envío a Colombia de ese producto.",
                    )
                    +"Extra"
                }
                th(classes = "col-price") {
                    title = tr("Cheapest price we have recorded for this item", "El precio más bajo que hemos registrado para este producto")
                    +tr("Lowest", "Mínimo")
                }
                th(classes = "col-price") {
                    title = tr("Dearest price we have recorded for this item", "El precio más alto que hemos registrado para este producto")
                    +tr("Highest", "Máximo")
                }
                sortTh(
                    tr("Low", "Mín."), TrackedSort.LOWEST, sort, selected, "col-low", brand, origin, showNoShip, freeShip,
                    tooltip = tr(
                        "At the lowest price we have ever recorded, AND genuinely down from " +
                            "a higher one we saw. A price that never moved does not qualify.",
                        "En el precio más bajo que hemos registrado, Y de verdad por debajo de " +
                            "uno más alto que vimos. Un precio que nunca se movió no cuenta.",
                    ),
                )
                th(classes = "col-chg") {
                    title = tr(
                        "Change from the dearest price we recorded. Blank until a second " +
                            "observation exists — one price is not a trend.",
                        "Cambio frente al precio más alto que registramos. Vacío hasta que haya una " +
                            "segunda observación — un solo precio no es una tendencia.",
                    )
                    +tr("Change", "Cambio")
                }
                sortTh(tr("Checked", "Revisado"), TrackedSort.CHECKED, sort, selected, "col-seen", brand, origin, showNoShip, freeShip)
                th(classes = "col-act") { +"" }
            }
        }
        tbody {
            shown.forEach { trackedRow(it, manualAsins, variants[it.id].orEmpty()) }
        }
    }
}

/**
 * Tracked rows with the same product listed once, and the rows each one stands in for.
 *
 * [others] is keyed by the id of the row kept in [rows].
 */
internal data class CollapsedVariants(val rows: List<Deal>, val others: Map<Long, List<Deal>>)

/**
 * Folds rows that are the same product under several ASINs into one.
 *
 * A size-filtered search does not return the child ASIN in the wanted size: it returns SOME variant
 * of each product family that offers that size, and not the same one every poll. So one shoe gathers
 * several rows that are really different sizes, all labelled with the filter's size. The New Balance
 * 608 V5 had three rows labelled "8", which were really 8.5, 8.5 Wide and 9.5. On the tab they read
 * as the same item listed three times.
 *
 * This only tidies the DISPLAY. The kept row is still one of those variants, so its link, price and
 * history are that variant's, not the wanted size's. The real fix is resolving the family to the
 * right child ASIN at ingest.
 *
 * Same source, title (which carries the size label) and image. The image is required: two products
 * can share a short title, but not a title and a photo. A row with no image never groups.
 *
 * The row kept is: one added by URL (its ASIN is exact), then one not refused for Colombia, then the
 * most recently checked, since a variant the search stopped returning goes stale first.
 */
internal fun collapseVariants(deals: List<Deal>, manualAsins: Set<String>): CollapsedVariants {
    val preference = compareByDescending<Deal> { it.asin != null && it.asin in manualAsins }
        .thenByDescending { it.coShipping != CoShipping.NO }
        .thenByDescending { it.lastSeenAt }
        .thenByDescending { it.id }

    val others = mutableMapOf<Long, List<Deal>>()
    val keep = mutableSetOf<Long>()
    deals.groupBy { d -> d.imageUrl?.let { Triple(d.source, d.title.trim().lowercase(), it) } ?: d.id }
        .values
        .forEach { group ->
            val sorted = group.sortedWith(preference)
            keep += sorted.first().id
            if (sorted.size > 1) others[sorted.first().id] = sorted.drop(1)
        }
    // Filtered rather than rebuilt from the groups, so the caller's order survives.
    return CollapsedVariants(deals.filter { it.id in keep }, others)
}

/**
 * Drops rows that only a switched-off search would be refreshing.
 *
 * `deals` does not record which search found a row, so a row is matched to a search the way a
 * person would: same brand (the term's words before " men") and same product type. A row is hidden
 * only when a disabled search covers it AND no enabled search does, so switching off "nike men
 * shoes" does not hide shoes that "nike men running shoes" still tracks.
 *
 * When EVERY search for a product type is off, the whole type goes, whatever the brand: a search
 * for "nike men boxer" also returns Reebok and Hanes boxers, and those are as unwanted as Nike's.
 * Items added by URL are never hidden: they are tracked by ASIN, not by search.
 */
internal fun withoutSwitchedOffSearches(
    deals: List<Deal>,
    toggles: List<Triple<String, String, Boolean>>,
    manualAsins: Set<String>,
): List<Deal> {
    if (toggles.all { it.third }) return deals
    fun key(term: String) = term.substringBefore(" men").trim().lowercase() to ProductType.of(term)
    val on = toggles.filter { it.third }.map { key(it.second) }.toSet()
    val off = toggles.filterNot { it.third }.map { key(it.second) }.toSet() - on
    val typesOff = toggles.groupBy { ProductType.of(it.second) }.filterValues { g -> g.none { it.third } }.keys
    return deals.filterNot { d ->
        if (d.asin != null && d.asin in manualAsins) return@filterNot false
        val type = ProductType.of(d.title)
        if (type in typesOff) return@filterNot true
        val brand = d.brand?.trim()?.lowercase() ?: return@filterNot false
        off.any { (b, t) -> brand == b && type == t }
    }
}

/**
 * The rows the tracked tab shows for a given set of controls, in the order it shows them.
 *
 * Extracted so the Ask panel reads exactly what the page renders. A second copy of these filters
 * would drift, and the failure would be an answer about rows the operator cannot see.
 */
internal fun trackedShown(
    deals: List<Deal>,
    selected: Set<ProductType>,
    brand: String?,
    origin: TrackedOrigin?,
    manualAsins: Set<String>,
    sort: TrackedSort,
    showNoShip: Boolean = false,
    freeShip: Boolean = false,
): List<Deal> = deals
    // A verified "does not ship" is not a price worth watching, so it is hidden unless asked for.
    // Only a NO: an unchecked row stays, since it may well ship and hiding it would hide most of
    // the tab while the check catches up.
    .filter { showNoShip || it.coShipping != CoShipping.NO }
    .filter { !freeShip || it.freeShipToColombia }
    .filter { selected.isEmpty() || ProductType.of(it.title) in selected }
    // Case-insensitive, for the same reason the picker groups that way: the stored casing
    // differs between a V12-backfilled row and a freshly fetched one.
    .filter { brand.isNullOrBlank() || it.brand?.trim()?.lowercase() == brand.trim().lowercase() }
    .filter {
        when (origin) {
            null -> true
            TrackedOrigin.MANUAL -> it.asin != null && it.asin in manualAsins
            TrackedOrigin.SEARCH -> it.asin == null || it.asin !in manualAsins
        }
    }
    .sortedWith(comparatorFor(sort))

private fun kotlinx.html.TBODY.trackedRow(deal: Deal, manualAsins: Set<String>, variants: List<Deal>) {
    tr {
        // htmx targets this to remove the row on hide.
        id = "tracked-${deal.id}"
        td(classes = "col-thumb") {
            val img = deal.imageUrl
            if (img != null) {
                a(href = deal.url) {
                    attributes["target"] = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    img(classes = "thumb", src = img, alt = "") {
                        attributes["loading"] = "lazy"
                    }
                }
            }
        }

        td(classes = "col-deal") {
            a(classes = "title", href = deal.url) {
                attributes["target"] = "_blank"
                attributes["rel"] = "noopener noreferrer"
                +itemName(deal.title)
            }
            div("meta") {
                span { +deal.source }
                // Marks the rows whose price and size are exact rather than filter-derived. See
                // TrackedOrigin for why that distinction is worth surfacing.
                if (deal.asin != null && deal.asin in manualAsins) {
                    span("manual-badge") {
                        title = tr(
                            "Added by URL, so its ASIN is exact — the price and size are this " +
                                "item's own, not a size filter's best guess.",
                            "Agregado por URL, así que su ASIN es exacto — el precio y la talla son los " +
                                "de este producto, no la mejor suposición de un filtro de talla.",
                        )
                        +tr("added", "agregado")
                    }
                }
                deal.asin?.let { span { +"ASIN $it" } }
                if (variants.isNotEmpty()) {
                    span("variants-note") {
                        val ids = variants.joinToString(", ") { v -> v.asin ?: v.sourceDealId }
                        title = tr(
                            "Also returned under $ids. Same product, other variants the size filter picked; " +
                                "listed once here. Hiding this row hides them all.",
                            "También aparece como $ids. Mismo producto, otras variantes que eligió el filtro " +
                                "de talla; aquí se muestra una vez. Ocultar esta fila las oculta todas.",
                        )
                        val plural = if (variants.size == 1) "" else "s"
                        +tr("+${variants.size} variant$plural", "+${variants.size} variante$plural")
                    }
                }
                deal.categoryGroup?.let { span("cat") { +categoryLabel(it) } }
            }
        }

        // Pulled out of the title so it can be scanned down the column. The size lives in the
        // title because `deals` has no size column; this is the one place that matters enough
        // to read it back out.
        td(classes = "col-size") {
            cardLabel(tr("Size", "Talla"))
            sizeOf(deal.title)?.let { span("size-chip") { +it } } ?: span("muted") { +"—" }
        }

        td(classes = "col-price") {
            cardLabel(tr("Now", "Ahora"))
            div("price") { +formatMoney(deal.price, deal.currency) }
        }

        // Amazon's own list price and the discount it advertises against it.
        //
        // Deliberately separate from the Lowest/Highest columns beside them, which come from OUR
        // recorded observations. These two are Amazon's claim about its own former price; those are
        // measurements. Conflating them would be the "inflated list price" trap this project
        // documents at length for Éxito, where ~94% of the catalogue is flagged as discounted.
        td(classes = "col-price") {
            cardLabel(tr("List", "Lista"))
            deal.listPrice?.let { div("was") { +formatMoney(it, deal.currency) } }
                ?: span("muted") { +"—" }
        }

        td(classes = "col-disc") {
            cardLabel(tr("Off", "Dto."))
            val pct = deal.discountPercent
            if (pct == null) {
                // Blank, never 0%. Most tiles carry no list price, and a zero would read as
                // "measured, no discount" rather than "Amazon did not say".
                span("muted") { +"—" }
            } else {
                span("disc disc-s${discountTier(pct)}") { +"−${pct.toPlainString()}%" }
            }
        }

        // What the item ACTUALLY costs delivered, which is the number a tracker is asked for.
        //
        // Deliberately its own column rather than a chip beside the price. On the verified rows the
        // freight is ~$6.99 against items of $20-45, so it moves a 51%-off tee to roughly 33% off --
        // enough to change a decision, which is the test for whether a fact earns a column.
        //
        // The project already measured the extreme of this: of 27 verified deals carrying a quoted
        // fee, 23 had freight above half the item's price and 10 above the whole price -- $248.68 to
        // ship a $267.99 television. Every one of those displayed as a plain green "ships".
        //
        // Must stay in the same position as its header, right after Off. It once rendered after
        // Change while the header sat here, which shifted Lowest, Highest, Low and Change one
        // column left and left every value under the wrong heading.
        td(classes = "col-extra") {
            cardLabel("Extra")
            extraCosts(deal)
        }

        // Blank, not the current price, when there is nothing to compare against.
        td(classes = "col-price") {
            cardLabel(tr("Lowest", "Mínimo"))
            priceOrDash(deal.minPriceEver, deal)
        }
        td(classes = "col-price") {
            cardLabel(tr("Highest", "Máximo"))
            priceOrDash(deal.maxPriceEver, deal)
        }

        // The badge you asked for: at its lowest recorded price, with an actual decline behind it.
        //
        // `Deal.isAtLowestEver` is reused rather than reimplemented, and it already demands the
        // second half: `price <= minPriceEver` alone is trivially TRUE for every row whose price
        // never moved, because the minimum includes the current observation. That exact mistake put
        // "lowest yet" on every row of the feed once, which is what made the badge meaningless.
        td(classes = "col-low") {
            cardLabel(tr("Low", "Mín."))
            when {
                deal.isAtLowestEver -> span("lowbadge") {
                    val from = deal.maxPriceEver?.let { formatMoney(it, deal.currency) }
                    title = tr(
                        "Lowest of ${deal.observationCount} recorded price(s), and down from " + (from ?: "a higher price"),
                        "El más bajo de ${deal.observationCount} precio(s) registrado(s), y por debajo de " +
                            (from ?: "un precio más alto"),
                    )
                    +tr("↓ lowest", "↓ mínimo")
                }
                // Distinguishes "no evidence yet" from "seen higher prices, not at the low now".
                deal.observationCount < 2 -> span("muted") { title = tr("Only one price check so far", "Solo una revisión de precio hasta ahora"); +"—" }
                else -> span("muted") { +"" }
            }
        }

        td(classes = "col-chg") {
            cardLabel(tr("Change", "Cambio"))
            changeCell(deal)
        }

        td(classes = "col-seen") {
            cardLabel(tr("Checked", "Revisado"))
            +relativeTime(deal.lastSeenAt)
        }

        // Reuses the feed's own `.actions` styling rather than introducing a `.btn` class that
        // exists nowhere else in the stylesheet.
        td(classes = "col-act") {
            div("actions") {
                a(href = "/deal/${deal.id}") { +tr("history", "historial") }
                // Soft delete via the existing DISMISSED state, not a row delete: a deleted row is
                // re-inserted by the next poll (upsert is keyed on source + source_deal_id) and
                // would take its price history with it. See POST /tracked/{id}/hide.
                button(classes = "hide-btn") {
                    // The whole group, or the next variant would take this one's place on reload.
                    attributes["hx-post"] = "/tracked/${deal.id}/hide" +
                        if (variants.isEmpty()) "" else "?also=" + variants.joinToString(",") { it.id.toString() }
                    attributes["hx-target"] = "#tracked-${deal.id}"
                    attributes["hx-swap"] = "outerHTML"
                    // A confirm, because the row disappears and the undo lives on another page.
                    attributes["hx-confirm"] = tr(
                        "Stop tracking this item? It stays in the database " +
                            "with its price history, and can be brought back from the feed's State filter.",
                        "¿Dejar de seguir este producto? Se queda en la base de datos con su historial " +
                            "de precios, y se puede recuperar desde el filtro Estado del listado.",
                    )
                    title = tr(
                        "Stop tracking this item. The row and its price history are kept, " +
                            "and the next poll will not bring it back.",
                        "Deja de seguir este producto. La fila y su historial de precios se " +
                            "conservan, y la próxima consulta no lo traerá de vuelta.",
                    )
                    +tr("stop tracking", "dejar de seguir")
                }
            }
        }
    }
}

/**
 * Shipping and import charges Amazon adds on top of the price, and the resulting landed cost.
 *
 * Four states, and they are genuinely different things rather than shades of one:
 *
 *  - **not checked yet** — `co_shipping = UNKNOWN`. Most rows. Blank, because "we have not asked"
 *    is not "there are no charges", and showing `$0` would assert the second.
 *  - **does not ship** — the charges question is moot; say so instead of a number.
 *  - **free** — Amazon quoted shipping and it was zero. A real, checked answer worth distinguishing
 *    from silence, which is why `co_shipping_cost` stores ZERO rather than null for it.
 *  - **a fee** — shown with the landed total, because the total is the decision.
 *
 * Reuses `Deal.shippingCostPercent` and `shippingCostDominates` rather than recomputing: the feed
 * already renders these and the two views must not disagree about what a fee means.
 */
private fun kotlinx.html.TD.extraCosts(deal: Deal) {
    // Stated as a chip, not a quiet "n/a": a row that cannot reach Colombia is not a price worth
    // watching, and the tab keeps it listed only so the verdict is visible rather than silent.
    if (deal.coShipping == CoShipping.NO) {
        span("prov kill-prov") {
            title = tr("Amazon will not ship this to Colombia", "Amazon no envía esto a Colombia")
            +tr("no ship to CO", "no envía a CO")
        }
        return
    }

    val cost = deal.coShippingCost
    val importFees = deal.coShippingImportFree == false

    if (cost == null && !importFees) {
        // Never "$0", and never a bare dash either. Unchecked, free-of-charges and "does not
        // ship" are three different facts. A dash read as "nothing to add", which is how a
        // T-shirt Amazon refuses to send to Colombia sat on this tab looking like a clean price.
        span("prov unchecked-prov") {
            title = tr(
                "The Colombia shipping check has not run for this item yet, so whether it " +
                    "ships at all — and what freight and import charges apply — is unknown, not " +
                    "known to be fine.",
                "Todavía no se ha revisado el envío a Colombia de este producto, así que si se " +
                    "envía — y qué flete e impuestos de importación aplican — es desconocido, no " +
                    "algo que sepamos que está bien.",
            )
            +tr("unchecked", "sin revisar")
        }
        return
    }

    div("extra") {
        if (cost != null && cost.signum() == 0) {
            span("prov") { +tr("free ship", "envío gratis") }
        } else if (cost != null) {
            val share = deal.shippingCostPercent
            // A fee worth more than half the item is not a footnote on a good deal, it is the
            // reason it is not one -- so it states the proportion, not just the amount.
            span(if (deal.shippingCostDominates) "prov kill-prov" else "prov warn-prov") {
                val fee = formatMoney(cost, deal.currency)
                title = tr(
                    "Amazon quoted $fee to ship this" + (share?.let { " — $it% of the item's own price" } ?: ""),
                    "Amazon cotizó $fee por enviar esto" + (share?.let { " — el $it% del precio del producto" } ?: ""),
                )
                +tr("+$fee ship", "+$fee envío")
            }
        }

        if (importFees) span("prov warn-prov") { +tr("import fees", "impuestos de importación") }

        // The landed total: price plus freight. Import charges are NOT added, because Amazon
        // quotes their presence without always quoting an amount -- so the chip above says they
        // apply, and this total stays a number that can be stood behind.
        cost?.takeIf { it.signum() > 0 }?.let {
            div("extra-total") {
                title = tr(
                    "Price plus the quoted shipping. Import charges, where they apply, are " +
                        "flagged separately because Amazon does not always quote an amount for them.",
                    "Precio más el envío cotizado. Los impuestos de importación, cuando aplican, se " +
                        "marcan aparte porque Amazon no siempre dice cuánto son.",
                )
                +"= ${formatMoney(deal.price.add(it), deal.currency)}"
            }
        }
    }
}

/**
 * The change from our recorded peak, as a percentage.
 *
 * Measured against the **highest** price recorded rather than the previous observation, because
 * that is the question a tracker is asked: is this cheaper than it has been? A drop from the
 * peak is the same evidence `Deal.dropFromPeak` and the feed's "↓" chip rest on, and it cannot
 * be manufactured by a retailer inflating a list price.
 *
 * Requires two observations. With one, the peak *is* the current price and the answer would
 * always be 0% — a number that looks measured and is not.
 */
private fun kotlinx.html.TD.changeCell(deal: Deal) {
    val peak = deal.maxPriceEver
    if (deal.observationCount < 2 || peak == null || peak <= BigDecimal.ZERO) {
        span("muted") {
            title = tr(
                "Only ${deal.observationCount} price check so far — no movement to report yet",
                "Solo ${deal.observationCount} revisión(es) de precio hasta ahora — todavía no hay movimiento",
            )
            +tr("new", "nuevo")
        }
        return
    }

    val delta = deal.price.subtract(peak)
    if (delta.signum() == 0) {
        span("muted") {
            title = tr(
                "Unchanged across ${deal.observationCount} checks",
                "Sin cambios en ${deal.observationCount} revisiones",
            )
            +"—"
        }
        return
    }

    val percent = delta.divide(peak, 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(0, RoundingMode.HALF_UP)

    // Down is good news for a buyer, so it gets the positive styling. Both carry a glyph as
    // well as a colour, following the rule that colour is never the only cue.
    val down = delta.signum() < 0
    span(if (down) "chg chg-down" else "chg chg-up") {
        val amount = formatMoney(delta.abs(), deal.currency)
        val top = formatMoney(peak, deal.currency)
        title = tr(
            "${if (down) "Down" else "Up"} $amount from the $top peak we recorded, across ${deal.observationCount} checks",
            "${if (down) "Bajó" else "Subió"} $amount frente al máximo de $top que registramos, en ${deal.observationCount} revisiones",
        )
        +"${if (down) "↓" else "↑"} ${percent.abs()}%"
    }
}

private fun kotlinx.html.TD.priceOrDash(amount: BigDecimal?, deal: Deal) {
    if (amount == null || deal.observationCount < 2) {
        span("muted") { +"—" }
    } else {
        +formatMoney(amount, deal.currency)
    }
}

/**
 * The size this row tracks, read back out of the title suffix the adapter appended.
 *
 * Deliberately conservative: it only recognises the exact `" — <size>"` shape `AmazonSource`
 * writes, so a product whose own name happens to contain an em dash is not mistaken for a
 * tracked size. Returns null rather than guessing, and the column then shows a dash.
 */
/**
 * No Colombia verdict and no quoted charge: the check has not run, so the row might not ship at
 * all. The same condition [extraCosts] renders as "unchecked", kept in one place so the chip and
 * the count above the table cannot disagree.
 */
/**
 * Verified free to ship to Colombia: a quoted fee of exactly zero. A row with a fee, or with no
 * quote at all (not yet verified), is not free -- null is "unknown", never zero.
 */
internal val Deal.freeShipToColombia: Boolean
    get() = coShipping != CoShipping.NO && coShippingCost?.signum() == 0

internal val Deal.shippingUnchecked: Boolean
    get() = coShipping != CoShipping.NO && coShippingCost == null && coShippingImportFree != false

internal fun sizeOf(title: String): String? =
    title.substringAfterLast(SIZE_SEPARATOR, "").trim().takeIf {
        it.isNotEmpty() && it.length <= MAX_SIZE_LABEL && title.contains(SIZE_SEPARATOR)
    }

/** The product name without the size suffix, so the size is not stated twice on one row. */
internal fun itemName(title: String): String =
    if (sizeOf(title) != null) title.substringBeforeLast(SIZE_SEPARATOR).trim() else title

/** Matches the separator `AmazonSource.titleWithSize` writes. */
private const val SIZE_SEPARATOR = " — "

/**
 * Longest plausible size label. Real ones run to `3X-Large` and `8.5 Women/7.5 Men`; a
 * 40-character tail is prose, which means the em dash belonged to the product name.
 */
private const val MAX_SIZE_LABEL = 40
