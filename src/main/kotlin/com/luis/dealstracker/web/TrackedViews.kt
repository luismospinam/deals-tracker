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
        // says hoodie, pullover, crewneck or sweatshirt as well.
        listOf(
            "hoodie", "pullover", "sweatshirt", "crewneck", "outerwear", "jacket",
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
private fun trackedUrl(types: Set<ProductType>, sort: TrackedSort, brand: String? = null): String {
    val params = buildList {
        if (types.isNotEmpty()) add("type=" + types.joinToString(",") { it.label })
        // The default is omitted, so the plain path stays the canonical URL for it.
        if (sort != TrackedSort.PRICE) add("sort=${sort.key}")
        brand?.takeIf { it.isNotBlank() }?.let { add("brand=" + java.net.URLEncoder.encode(it, "UTF-8")) }
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
    tooltip: String? = null,
) {
    val href = trackedUrl(selected, sort, brand)

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
            +"Brand"
            select {
                attributes["onchange"] = "location.href=this.value"

                option {
                    value = trackedUrl(types, sort, null)
                    if (brand.isNullOrBlank()) this.selected = true
                    +"all brands"
                }
                counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .forEach { (key, n) ->
                        val name = display[key] ?: key
                        option {
                            value = trackedUrl(types, sort, name)
                            if (brand?.lowercase() == key) this.selected = true
                            +"$name ($n)"
                        }
                    }
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
        span("typetags-label") { +"Type" }

        ProductType.entries.forEach { type ->
            val n = counts[type] ?: 0
            // A type nothing matches is not offered. Showing "shirts 0" invites a click that
            // empties the page and teaches you the filter is broken.
            if (n == 0) return@forEach

            val on = type in selected
            val next = if (on) selected - type else selected + type
            val href = trackedUrl(next, sort, brand)

            a(classes = if (on) "typetag on" else "typetag", href = href) {
                +type.label
                span("typetag-n") { +n.toString() }
            }
        }

        if (selected.isNotEmpty()) {
            // Clears the TYPE filter only. Resetting the sort too would be a second, unasked-for
            // change hiding behind one control.
            a(classes = "typetag clear", href = trackedUrl(emptySet(), sort, brand)) { +"clear" }
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
    deals: List<Deal>,
    sources: List<String>,
    selected: Set<ProductType> = emptySet(),
    sort: TrackedSort = TrackedSort.PRICE,
    brand: String? = null,
) {
    p("tracked-intro") {
        +"Items tracked by name and size, with the price movement we have recorded. "
        +"This is not a deal feed — nothing here is filtered by discount. "
        a(href = "/") { +"The feed" }
        +" answers what is on offer; this answers what your items cost."
    }

    if (deals.isEmpty()) {
        div("empty") {
            if (sources.isEmpty()) {
                +"No tracking providers are enabled. Add one with "
                code { +"type: amazon" }
                +" in config/sources.yaml."
            } else {
                +"Nothing tracked yet. Configure "
                code { +"trackSearches" }
                +" and your sizes under the "
                code { +sources.joinToString(", ") }
                +" provider, then run "
                code { +"./dt run --args=\"--fetch-once --source=${sources.first()}\"" }
                +". Amazon rate-limits hard, so expect a run to bring back a handful at a time."
            }
        }
        return
    }

    // Tags are built from the UNFILTERED set, so the counts stay stable and a deselected type does
    // not vanish from the row the moment you click it.
    brandPicker(deals, selected, sort, brand)
    typeTags(deals, selected, sort, brand)

    val shown = deals
        .filter { selected.isEmpty() || ProductType.of(it.title) in selected }
        // Case-insensitive, for the same reason the picker groups that way: the stored casing
        // differs between a V12-backfilled row and a freshly fetched one.
        .filter { brand.isNullOrBlank() || it.brand?.trim()?.lowercase() == brand.trim().lowercase() }
        .sortedWith(comparatorFor(sort))

    if (shown.isEmpty()) {
        div("empty") {
            +"No tracked items of that type. "
            a(href = trackedUrl(emptySet(), sort, brand)) { +"Show all" }
            +"."
        }
        return
    }

    div("tracked-count") {
        +if (selected.isEmpty()) {
            "${shown.size} item(s) tracked"
        } else {
            "${shown.size} of ${deals.size} item(s) tracked"
        }
        val awaiting = shown.count { it.observationCount < 2 }
        if (awaiting > 0) {
            // Stated up front, because on the first day EVERY row is in this state and a page
            // full of dashes otherwise reads as broken rather than as young.
            span("tracked-note") {
                +" · $awaiting awaiting a second price check before movement can be shown"
            }
        }
    }

    table("deals tracked") {
        thead {
            tr {
                th(classes = "col-thumb") { attributes["aria-label"] = "image" }
                th { +"Item" }
                th(classes = "col-size") { +"Size" }
                sortTh("Now", TrackedSort.PRICE, sort, selected, "col-price", brand)
                th(classes = "col-price") {
                    title = "Amazon's own \"List:\" price, where it publishes one"
                    +"List"
                }
                sortTh(
                    "Off", TrackedSort.OFF, sort, selected, "col-disc", brand,
                    tooltip = "Discount against Amazon's list price — its claim about its own " +
                        "former price, not a decline we observed. Blank where no list price is " +
                        "shown; those rows sort last.",
                )
                th(classes = "col-extra") {
                    title = "International shipping and import charges Amazon quotes on top of " +
                        "the price, plus what the item therefore actually costs delivered. " +
                        "Blank until the Colombia shipping check has run for that item."
                    +"Extra"
                }
                th(classes = "col-price") {
                    title = "Cheapest price we have recorded for this item"
                    +"Lowest"
                }
                th(classes = "col-price") {
                    title = "Dearest price we have recorded for this item"
                    +"Highest"
                }
                sortTh(
                    "Low", TrackedSort.LOWEST, sort, selected, "col-low", brand,
                    tooltip = "At the lowest price we have ever recorded, AND genuinely down from " +
                        "a higher one we saw. A price that never moved does not qualify.",
                )
                th(classes = "col-chg") {
                    title = "Change from the dearest price we recorded. Blank until a second " +
                        "observation exists — one price is not a trend."
                    +"Change"
                }
                sortTh("Checked", TrackedSort.CHECKED, sort, selected, "col-seen", brand)
                th(classes = "col-act") { +"" }
            }
        }
        tbody {
            shown.forEach { trackedRow(it) }
        }
    }
}

private fun kotlinx.html.TBODY.trackedRow(deal: Deal) {
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

        td {
            a(classes = "title", href = deal.url) {
                attributes["target"] = "_blank"
                attributes["rel"] = "noopener noreferrer"
                +itemName(deal.title)
            }
            div("meta") {
                span { +deal.source }
                deal.asin?.let { span { +"ASIN $it" } }
                deal.categoryGroup?.let { span("cat") { +it } }
            }
        }

        // Pulled out of the title so it can be scanned down the column. The size lives in the
        // title because `deals` has no size column; this is the one place that matters enough
        // to read it back out.
        td(classes = "col-size") {
            sizeOf(deal.title)?.let { span("size-chip") { +it } } ?: span("muted") { +"—" }
        }

        td(classes = "col-price") { div("price") { +formatMoney(deal.price, deal.currency) } }

        // Amazon's own list price and the discount it advertises against it.
        //
        // Deliberately separate from the Lowest/Highest columns beside them, which come from OUR
        // recorded observations. These two are Amazon's claim about its own former price; those are
        // measurements. Conflating them would be the "inflated list price" trap this project
        // documents at length for Éxito, where ~94% of the catalogue is flagged as discounted.
        td(classes = "col-price") {
            deal.listPrice?.let { div("was") { +formatMoney(it, deal.currency) } }
                ?: span("muted") { +"—" }
        }

        td(classes = "col-disc") {
            val pct = deal.discountPercent
            if (pct == null) {
                // Blank, never 0%. Most tiles carry no list price, and a zero would read as
                // "measured, no discount" rather than "Amazon did not say".
                span("muted") { +"—" }
            } else {
                span("disc disc-s${discountTier(pct)}") { +"−${pct.toPlainString()}%" }
            }
        }

        // Blank, not the current price, when there is nothing to compare against.
        td(classes = "col-price") { priceOrDash(deal.minPriceEver, deal) }
        td(classes = "col-price") { priceOrDash(deal.maxPriceEver, deal) }

        // The badge you asked for: at its lowest recorded price, with an actual decline behind it.
        //
        // `Deal.isAtLowestEver` is reused rather than reimplemented, and it already demands the
        // second half: `price <= minPriceEver` alone is trivially TRUE for every row whose price
        // never moved, because the minimum includes the current observation. That exact mistake put
        // "lowest yet" on every row of the feed once, which is what made the badge meaningless.
        td(classes = "col-low") {
            when {
                deal.isAtLowestEver -> span("lowbadge") {
                    title = "Lowest of ${deal.observationCount} recorded price(s), and down from " +
                        (deal.maxPriceEver?.let { formatMoney(it, deal.currency) } ?: "a higher price")
                    +"↓ lowest"
                }
                // Distinguishes "no evidence yet" from "seen higher prices, not at the low now".
                deal.observationCount < 2 -> span("muted") { title = "Only one price check so far"; +"—" }
                else -> span("muted") { +"" }
            }
        }

        td(classes = "col-chg") { changeCell(deal) }

        // What the item ACTUALLY costs delivered, which is the number a tracker is asked for.
        //
        // Deliberately its own column rather than a chip beside the price. On the verified rows the
        // freight is ~$6.99 against items of $20-45, so it moves a 51%-off tee to roughly 33% off --
        // enough to change a decision, which is the test for whether a fact earns a column.
        //
        // The project already measured the extreme of this: of 27 verified deals carrying a quoted
        // fee, 23 had freight above half the item's price and 10 above the whole price -- $248.68 to
        // ship a $267.99 television. Every one of those displayed as a plain green "ships".
        td(classes = "col-extra") { extraCosts(deal) }

        td(classes = "col-seen") { +relativeTime(deal.lastSeenAt) }

        // Reuses the feed's own `.actions` styling rather than introducing a `.btn` class that
        // exists nowhere else in the stylesheet.
        td(classes = "col-act") {
            div("actions") {
                a(href = "/deal/${deal.id}") { +"history" }
                // Soft delete via the existing DISMISSED state, not a row delete: a deleted row is
                // re-inserted by the next poll (upsert is keyed on source + source_deal_id) and
                // would take its price history with it. See POST /tracked/{id}/hide.
                button(classes = "hide-btn") {
                    attributes["hx-post"] = "/tracked/${deal.id}/hide"
                    attributes["hx-target"] = "#tracked-${deal.id}"
                    attributes["hx-swap"] = "outerHTML"
                    // A confirm, because the row disappears and the undo lives on another page.
                    attributes["hx-confirm"] = "Stop tracking this item? It stays in the database " +
                        "with its price history, and can be brought back from the feed's State filter."
                    title = "Stop showing this item. Soft delete — the row and its price history " +
                        "are kept, and the next poll will not bring it back."
                    +"hide"
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
    if (deal.coShipping == CoShipping.NO) {
        span("muted") { title = "Amazon will not ship this to Colombia"; +"n/a" }
        return
    }

    val cost = deal.coShippingCost
    val importFees = deal.coShippingImportFree == false

    if (cost == null && !importFees) {
        // Never "$0". Unchecked and free-of-charges are different facts.
        span("muted") {
            title = "The Colombia shipping check has not run for this item yet, so Amazon's " +
                "shipping and import charges are unknown — not known to be zero."
            +"—"
        }
        return
    }

    div("extra") {
        if (cost != null && cost.signum() == 0) {
            span("prov") { +"free ship" }
        } else if (cost != null) {
            val share = deal.shippingCostPercent
            // A fee worth more than half the item is not a footnote on a good deal, it is the
            // reason it is not one -- so it states the proportion, not just the amount.
            span(if (deal.shippingCostDominates) "prov kill-prov" else "prov warn-prov") {
                title = "Amazon quoted ${formatMoney(cost, deal.currency)} to ship this" +
                    (share?.let { " — $it% of the item's own price" } ?: "")
                +"+${formatMoney(cost, deal.currency)} ship"
            }
        }

        if (importFees) span("prov warn-prov") { +"import fees" }

        // The landed total: price plus freight. Import charges are NOT added, because Amazon
        // quotes their presence without always quoting an amount -- so the chip above says they
        // apply, and this total stays a number that can be stood behind.
        cost?.takeIf { it.signum() > 0 }?.let {
            div("extra-total") {
                title = "Price plus the quoted shipping. Import charges, where they apply, are " +
                    "flagged separately because Amazon does not always quote an amount for them."
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
            title = "Only ${deal.observationCount} price check so far — no movement to report yet"
            +"new"
        }
        return
    }

    val delta = deal.price.subtract(peak)
    if (delta.signum() == 0) {
        span("muted") { title = "Unchanged across ${deal.observationCount} checks"; +"—" }
        return
    }

    val percent = delta.divide(peak, 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(0, RoundingMode.HALF_UP)

    // Down is good news for a buyer, so it gets the positive styling. Both carry a glyph as
    // well as a colour, following the rule that colour is never the only cue.
    val down = delta.signum() < 0
    span(if (down) "chg chg-down" else "chg chg-up") {
        title = "${if (down) "Down" else "Up"} ${formatMoney(delta.abs(), deal.currency)} " +
            "from the ${formatMoney(peak, deal.currency)} peak we recorded, " +
            "across ${deal.observationCount} checks"
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
