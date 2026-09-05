package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.model.PriceObservation
import kotlinx.html.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Bumped whenever app.css changes, to defeat browser caching of the stylesheet.
 * The CSS lives in src/main/resources/static/app.css so it can be edited and reloaded
 * without a Gradle rebuild.
 */
private const val CSS_VERSION = "12"

/**
 * Typefaces for the "Terminal Ledger" look: an editorial serif nameplate over an
 * industrial sans for prose and a true monospace for every number and column label.
 *
 * Fetched from a CDN, consistent with how htmx is already loaded below. `display=swap`
 * means a slow font never blocks the feed from rendering, and app.css declares a local
 * fallback for each family, so the page is fully usable offline — only the character of
 * the type is lost, not the layout.
 */
private const val FONT_HREF = "https://fonts.googleapis.com/css2" +
    "?family=Archivo:wght@400..700" +
    "&family=IBM+Plex+Mono:wght@400;500;600;700" +
    "&family=Instrument+Serif:ital@0;1" +
    "&display=swap"

/** Slickdeals forum names that masquerade as product categories and carry no signal. */
private val GENERIC_CATEGORIES = setOf("Hot Deals", "Marketplace Deals", "Deal Talk", "Freebies")

fun HTML.page(title: String, block: MAIN.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(title)
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
        link(rel = "stylesheet", href = FONT_HREF)
        link(rel = "stylesheet", href = "/static/app.css?v=$CSS_VERSION")
        script(src = "https://unpkg.com/htmx.org@1.9.12") {}
    }
    body {
        header {
            h1 { +"Deals Tracker" }
            span("sub") { a(href = "/") { +"feed" } }
            // Holds the right rail apart from the nameplate, so the serif sits alone
            // instead of being crowded by the mono labels.
            div("masthead-gap") {}
            span("sub") { +"local price monitor" }
        }
        main { block() }
    }
}

/** COP has no cents in practice; USD does. Formatting reflects that. */
fun formatMoney(amount: BigDecimal, currency: String): String = when (currency) {
    "COP" -> "$" + String.format("%,d", amount.toBigInteger())
    "USD" -> "US$" + String.format("%,.2f", amount)
    else -> "$currency " + String.format("%,.2f", amount)
}

/** The under-a-minute case, which is a complete phrase and so cannot take a suffix. */
private const val JUST_NOW = "just now"

fun relativeTime(instant: Instant): String {
    val d = Duration.between(instant, Instant.now())
    return when {
        d.toMinutes() < 1 -> JUST_NOW
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

/**
 * "3h ago", "2d ago" — but "just now", not "just now ago".
 *
 * Every call site was gluing " ago" onto [relativeTime], which reads correctly for the
 * duration cases and wrongly for the newest rows. Newly ingested deals are exactly the
 * ones most looked at, so the broken phrasing showed up on the most-read rows.
 */
fun relativeAgo(instant: Instant): String =
    relativeTime(instant).let { if (it == JUST_NOW) it else "$it ago" }

/** As [relativeAgo], for the age chip: "3h old", but "brand new" rather than "just now old". */
private fun relativeOld(instant: Instant): String =
    relativeTime(instant).let { if (it == JUST_NOW) "brand new" else "$it old" }

/**
 * A one-click preset over an existing filter parameter.
 *
 * Deliberately *not* a form input of its own. A chip writes into the single canonical
 * control that owns its parameter — the select or number field in the More-filters panel —
 * so no parameter ever has two inputs that could disagree about what is being filtered,
 * and no filtering capability is lost to the shorthand. The arbitrary-value controls all
 * survive; the chips just cover the values actually reached for.
 */
private data class ChipPreset(val param: String, val value: String, val label: String)

/**
 * Note that "Lowest yet" and "Promoted" both target `only`, which is single-valued. Because
 * they write to the same select, turning one on turns the other off for free — the conflict
 * is impossible to express rather than merely discouraged.
 */
private val FILTER_CHIPS = listOf(
    ChipPreset("minDiscount", "50", "50%+ off"),
    ChipPreset("only", "lowest", "Lowest yet"),
    ChipPreset("co", "YES", "Ships to CO"),
    ChipPreset("only", "promoted", "Promoted"),
    ChipPreset("minScore", "20", "Score 20+"),
)

/** Parameters that live in the collapsed panel rather than on the always-visible row. */
private val ADVANCED_PARAMS = listOf("minDiscount", "maxPrice", "minScore", "co", "state", "only")

/**
 * How many filters are engaged but not visible — the number on the More-filters badge.
 *
 * Values a chip is already showing are excluded, because the chip is visible: the badge
 * answers "what is narrowing my results that I cannot see?", which is the only reason to
 * surface a count at all. A collapsed panel that silently filtered the feed would be worse
 * than the cluttered bar it replaces.
 */
internal fun hiddenFilterCount(q: Map<String, String>): Int = ADVANCED_PARAMS.count { p ->
    val v = q[p].orEmpty()
    v.isNotBlank() && FILTER_CHIPS.none { it.param == p && it.value == v }
}

fun MAIN.filterForm(sources: List<String>, categories: List<String>, q: Map<String, String>) {
    form(classes = "filters") {
        attributes["method"] = "get"
        attributes["action"] = "/"

        label {
            +"Search"
            input(type = InputType.text, name = "q") {
                value = q["q"].orEmpty()
                // Discoverability: the pipe syntax is invisible otherwise.
                placeholder = "airpods | airtag | ssd"
                title = "Separate multiple terms with | to match any of them"
                attributes["size"] = "24"
            }
        }
        label {
            +"Source"
            select {
                name = "source"
                option {
                    value = ""
                    if (q["source"].isNullOrBlank()) selected = true
                    +"all"
                }
                sources.forEach { s ->
                    option {
                        value = s
                        if (q["source"] == s) selected = true
                        +s
                    }
                }
            }
        }
        label {
            +"Category"
            select {
                name = "category"
                option {
                    value = ""
                    if (q["category"].isNullOrBlank()) selected = true
                    +"all"
                }
                categories.forEach { c ->
                    option {
                        value = c
                        if (q["category"] == c) selected = true
                        +c
                    }
                }
            }
        }
        // Sort stays on the visible row: it reorders the feed rather than narrowing it, so
        // it does not belong behind a disclosure labelled "More filters".
        label {
            +"Sort"
            select {
                name = "sort"
                listOf(
                    "relevance" to "relevance",
                    "discount" to "discount",
                    "drop" to "biggest drop",
                    "score" to "community score",
                    "newest" to "newest",
                    "price" to "price",
                ).forEach { (v, t) ->
                    option {
                        value = v
                        if (q["sort"] == v) selected = true
                        +t
                    }
                }
            }
        }

        button(classes = "primary") { type = ButtonType.submit; +"Apply" }

        // Export and Refresh act on the feed rather than describing it, so they sit in a
        // right-hand utility group instead of competing with Apply at the end of the row.
        div("util") {
            // A submit button with `formaction`, not a link: it posts this form's live values
            // to the export route, so the download reflects the controls as they stand --
            // including edits not yet applied. A static href would export the previous filter.
            // Needs no JavaScript, and the form is already method=get.
            button {
                type = ButtonType.submit
                attributes["formaction"] = "/export.xlsx"
                title = "Download the matching deals as an Excel file. " +
                    "With nothing filtered this is every active deal except dismissed ones."
                +"Export to Excel"
            }
            button {
                attributes["hx-post"] = "/refresh"
                attributes["hx-swap"] = "none"
                type = ButtonType.button
                +"Refresh now"
            }
        }

        div("fbreak") {}

        // The presets. `aria-pressed` rather than a checkbox because these are buttons that
        // act immediately -- a screen reader should hear a toggle, not an unsubmitted field.
        div("chiprow") {
            FILTER_CHIPS.forEach { chip ->
                val on = q[chip.param] == chip.value
                button(classes = if (on) "chip-tog on" else "chip-tog") {
                    type = ButtonType.button
                    attributes["data-param"] = chip.param
                    attributes["data-value"] = chip.value
                    attributes["aria-pressed"] = on.toString()
                    +chip.label
                }
            }
        }

        div("fbreak") {}

        // Collapsed, but still inside the form: a closed <details> keeps its fields in the
        // DOM, so their values continue to submit and the Excel export continues to match
        // the page. Hiding a control must not quietly drop the filter it holds.
        details("more") {
            summary {
                +"More filters"
                val hidden = hiddenFilterCount(q)
                if (hidden > 0) span("act-count") { +"$hidden" }
            }
            div("more-grid") {
                label {
                    +"Min discount %"
                    input(type = InputType.number, name = "minDiscount") {
                        value = q["minDiscount"].orEmpty(); min = "0"; max = "99"; placeholder = "0"
                        attributes["size"] = "4"
                    }
                }
                label {
                    +"Max price"
                    input(type = InputType.number, name = "maxPrice") {
                        value = q["maxPrice"].orEmpty()
                        attributes["size"] = "8"
                    }
                }
                label {
                    +"Min score"
                    input(type = InputType.number, name = "minScore") {
                        value = q["minScore"].orEmpty()
                        placeholder = "any"
                        title = "Slickdeals community vote score. 0 or below means downvoted."
                        attributes["size"] = "4"
                    }
                }
                label {
                    +"Colombia"
                    select {
                        name = "co"
                        listOf("" to "any", "YES" to "ships", "UNKNOWN" to "unverified", "NO" to "no").forEach { (v, t) ->
                            option {
                                value = v
                                if (q["co"] == v) selected = true
                                +t
                            }
                        }
                    }
                }
                label {
                    +"State"
                    select {
                        name = "state"
                        listOf("" to "active", "NEW" to "new", "SAVED" to "saved", "DISMISSED" to "dismissed").forEach { (v, t) ->
                            option {
                                value = v
                                if (q["state"] == v) selected = true
                                +t
                            }
                        }
                    }
                }
                label {
                    +"Only"
                    select {
                        name = "only"
                        listOf("" to "everything", "promoted" to "promoted", "lowest" to "at lowest ever").forEach { (v, t) ->
                            option {
                                value = v
                                if (q["only"] == v) selected = true
                                +t
                            }
                        }
                    }
                }
            }
        }

        // A chip sets its owning control and submits in the same click. Requiring a second
        // press on Apply would make the shortcut no faster than the dropdown it replaces.
        script {
            unsafe {
                +"""
                document.querySelectorAll('.filters .chip-tog').forEach(function (b) {
                  b.addEventListener('click', function () {
                    var f = b.form, c = f.elements[b.dataset.param];
                    if (!c) return;
                    c.value = b.classList.contains('on') ? '' : b.dataset.value;
                    if (f.requestSubmit) { f.requestSubmit(); } else { f.submit(); }
                  });
                });
                """.trimIndent()
            }
        }
    }
}

fun MAIN.dealTable(deals: List<Deal>) {
    if (deals.isEmpty()) {
        div("empty") {
            +"No deals match those filters. If the database is empty, run "
            code { +"./dt run --args=\"--fetch-once\"" }
            +" or press Refresh now."
        }
        return
    }
    table("deals") {
        thead {
            tr {
                th(classes = "col-thumb") { attributes["aria-label"] = "image" }
                th { +"Deal" }
                th(classes = "col-price") { +"Price" }
                th(classes = "col-disc") { +"Off" }
                th(classes = "col-score") {
                    title = "Community vote score, where the source publishes one"
                    +"Score"
                }
                th(classes = "col-sig") { +"Signals" }
                th(classes = "col-co") { +"Colombia" }
                th(classes = "col-seen") { +"Seen" }
                th(classes = "col-act") { +"Actions" }
            }
        }
        tbody {
            deals.forEach { dealRow(it) }
        }
    }
}

/**
 * Buckets the discount for the colour ramp.
 *
 * Given Exito flags ~94% of its catalog as discounted, the ramp exists so the eye can
 * sort rows by magnitude without reading every number. The number is always rendered
 * alongside, so colour is an accelerator and never the only cue.
 */
internal fun discountTier(percent: BigDecimal): Int = when {
    percent >= BigDecimal(60) -> 4
    percent >= BigDecimal(40) -> 3
    percent >= BigDecimal(25) -> 2
    else -> 1
}

/**
 * Buckets the community score. Thresholds come from the observed distribution across
 * three Slickdeals feeds (149 items, range −14..+384, median ≈20), not from guesswork:
 * tier 0 is downvoted, tier 1 is below median, tier 3 is the top ~25%.
 */
internal fun scoreTier(score: Int): Int = when {
    score <= 0 -> 0
    score < 20 -> 1
    score < 50 -> 2
    else -> 3
}

private fun sourceDotClass(source: String): String = when (source) {
    "slickdeals", "exito", "carulla", "mercadolibre-co" -> "dot-$source"
    else -> "dot-other"
}

private fun TBODY.dealRow(deal: Deal) {
    // Drives the margin heat gutter (see app.css). Deliberately the *same* tier function
    // the discount badge uses, so the bar and the number cannot disagree. A deal with no
    // computable discount gets no class and therefore no bar — a zero-height bar would
    // read as "0% off" rather than "not known".
    // null rather than "" so kotlinx.html omits the attribute entirely — an empty
    // class="" on every discount-less row is 202 wasted attributes on a typical feed.
    val heat = deal.discountPercent?.let { "heat heat-s${discountTier(it)}" }
    tr(classes = heat) {
        id = "deal-${deal.id}"

        td(classes = "col-thumb") {
            val img = deal.imageUrl
            if (img != null) img(classes = "thumb", src = img, alt = "") else div("thumb-blank") {}
        }

        // Classed so it can host the heat gutter at narrow widths, where .col-thumb is
        // display:none and takes its pseudo-element with it.
        td(classes = "col-deal") {
            a(href = deal.url, target = "_blank", classes = "title") { +deal.title }
            div("meta") {
                span("src") {
                    span("dot ${sourceDotClass(deal.source)}") {}
                    +deal.source
                }
                // Each item must be its own element: bare text nodes are not flex
                // items, so the container's `gap` does not separate them and the
                // values ran together as "exito exito.comAccesorios de computador".
                deal.merchant?.takeIf { !it.startsWith(deal.source) }?.let { span { +it } }
                // The normalized bucket is what the filter uses, so show that. The
                // source's own path (where it has one) becomes the tooltip -- Slickdeals'
                // is just its forum name, so generic values are suppressed entirely.
                deal.categoryGroup?.let { group ->
                    span("cat") {
                        deal.category
                            ?.takeIf { it.isNotBlank() && it.substringAfterLast(" > ") !in GENERIC_CATEGORIES }
                            ?.let { title = it }
                        +group
                    }
                }
                deal.asin?.let { span { +"ASIN $it" } }
            }
        }

        td(classes = "col-price") {
            div("price") { +formatMoney(deal.price, deal.currency) }
            deal.listPrice?.let { div("was") { +formatMoney(it, deal.currency) } }
        }

        td(classes = "col-disc") {
            val pct = deal.discountPercent
            if (pct != null) {
                span("disc disc-s${discountTier(pct)}") { +"−${pct.toPlainString()}%" }
            } else {
                // Null, not 0%: Slickdeals publishes no list price at all, so no
                // discount can be computed. The Score column carries the signal instead.
                span("no-disc") {
                    title = "This source publishes no list price, so no discount can be computed — see Score"
                    +"—"
                }
            }
        }

        // Deal-quality signal for sources that have no list price. Slickdeals' community
        // vote is the only evidence available there; a score of 0 or below means the
        // community actively voted it down.
        td(classes = "col-score") {
            val score = deal.communityScore
            if (score == null) {
                span("no-disc") { title = "This source publishes no community score"; +"—" }
            } else {
                span("score score-${scoreTier(score)}") {
                    title = "Slickdeals community vote score"
                    +(if (score > 0) "+$score" else "$score")
                }
            }
        }

        // Signals are deliberately sparse. A badge that appears on every row conveys
        // nothing, so each condition here requires evidence: an actual observed decline,
        // or a genuinely recent first sighting.
        td(classes = "col-sig") {
            div("chips") {
                val drop = deal.dropFromPeak
                if (deal.isAtLowestEver) {
                    span("chip chip-low") {
                        title = "Cheapest we have recorded, down from ${deal.maxPriceEver?.let { formatMoney(it, deal.currency) }}"
                        +"lowest yet"
                    }
                } else if (drop != null) {
                    span("chip chip-drop") { +"↓ ${formatMoney(drop, deal.currency)}" }
                }
                // Time-bounded rather than state-based: `state` stays NEW until Luis acts
                // on a row, so a NEW-based badge ended up on every deal forever.
                if (Duration.between(deal.firstSeenAt, Instant.now()).toHours() < 24) {
                    span("chip chip-new") { +"new" }
                }
                if (deal.state == DealState.SAVED) span("chip") { +"saved" }
            }
        }

        td(classes = "col-co") { coShippingCell(deal) }

        td(classes = "col-seen") {
            // The source's own posting date when known, since that is what tells you the
            // deal is current -- lastSeenAt only records when *we* last polled.
            val posted = deal.postedAt
            if (posted != null) {
                div("seen") {
                    title = "Posted by the source ${relativeAgo(posted)}"
                    +relativeOld(posted)
                }
            } else {
                div("seen") {
                    title = "This source publishes no posting date; showing when we last saw it"
                    +"seen ${relativeTime(deal.lastSeenAt)}"
                }
            }
            div("seen") { +"${deal.observationCount} obs" }
        }

        td(classes = "col-act") {
            div("actions") {
                stateButton(deal.id, DealState.SAVED, "Save")
                stateButton(deal.id, DealState.DISMISSED, "Hide")
                a(href = "/deal/${deal.id}") { +"history" }
            }
        }
    }
}

/**
 * Amazon items are why this column exists: they may or may not reach Colombia, and v1
 * does not determine that automatically. Unverified is the only actionable state, so it
 * carries the warm colour and the inline buttons; resolved states stay quiet.
 */
private fun TD.coShippingCell(deal: Deal) {
    div("co") {
        // Evidence as a tooltip, and an "auto" marker, so an automated verdict can be told
        // apart from one Luis made by hand -- and audited without re-fetching the page.
        val provenance = deal.coShippingEvidence?.takeIf { it.isNotBlank() }
            ?.let { "${deal.coShippingSource ?: "?"}: $it" }
        when (deal.coShipping) {
            CoShipping.YES -> span("co-yes") {
                provenance?.let { title = it }
                +"✓ ships"
                if (deal.coShippingSource == "AUTO") span("prov") { +"auto" }
                // The shipping fee often decides whether a "deal" is still a deal: the same
                // YES verdict covers "FREE Shipping" and "$60.66 Shipping".
                deal.coShippingCost?.let { cost ->
                    if (cost.signum() == 0) {
                        span("prov") { +"free ship" }
                    } else {
                        span("prov warn-prov") {
                            title = "Amazon quoted this international shipping fee"
                            +"+${formatMoney(cost, "USD")} ship"
                        }
                    }
                }
                // Import charges were previously only shown when they did NOT apply, which is
                // backwards -- charges make a deal worse, so that is the case to flag.
                when (deal.coShippingImportFree) {
                    true -> span("prov") { +"no import fees" }
                    false -> span("prov warn-prov") { +"import fees" }
                    null -> Unit
                }
            }
            CoShipping.NO -> span("co-no") {
                provenance?.let { title = it }
                +"✗ no"
                if (deal.coShippingSource == "AUTO") span("prov") { +"auto" }
            }
            CoShipping.UNKNOWN -> {
                span("co-unk") { +"? unverified" }
                div("actions") {
                    button {
                        attributes["hx-post"] = "/deal/${deal.id}/co-shipping?value=YES"
                        attributes["hx-target"] = "#deal-${deal.id}"
                        attributes["hx-swap"] = "outerHTML"
                        title = "Mark as shippable to Colombia"
                        +"ships"
                    }
                    button {
                        attributes["hx-post"] = "/deal/${deal.id}/co-shipping?value=NO"
                        attributes["hx-target"] = "#deal-${deal.id}"
                        attributes["hx-swap"] = "outerHTML"
                        title = "Mark as not shippable"
                        +"no"
                    }
                }
            }
        }
    }
}

private fun DIV.stateButton(dealId: Long, state: DealState, label: String) {
    button {
        attributes["hx-post"] = "/deal/$dealId/state?value=${state.name}"
        attributes["hx-target"] = "#deal-$dealId"
        attributes["hx-swap"] = "outerHTML"
        +label
    }
}

/** Standalone row renderer so HTMX actions can swap a single row in place. */
fun HTML.singleRow(deal: Deal) {
    body {
        table { tbody { dealRow(deal) } }
    }
}

fun MAIN.dealDetail(deal: Deal, history: List<PriceObservation>) {
    h1("detail-head") { a(href = deal.url, target = "_blank") { +deal.title } }
    div("meta") {
        span("src") {
            span("dot ${sourceDotClass(deal.source)}") {}
            +deal.source
        }
        // Each wrapped in its own span. Bare text nodes inside a flex container merge into
        // one anonymous flex item, so `gap` never applied between them and the line
        // rendered as "amazonASIN B0CV8VQJV1first seen 2h ago".
        deal.merchant?.let { span { +it } }
        deal.asin?.let { span { +"ASIN $it" } }
        span { +"first seen ${relativeAgo(deal.firstSeenAt)}" }
    }
    p {
        span("price") { +formatMoney(deal.price, deal.currency) }
        deal.listPrice?.let {
            +" "
            span("was") { +formatMoney(it, deal.currency) }
        }
        deal.minPriceEver?.let {
            +" "
            span("sub") { +"lowest recorded ${formatMoney(it, deal.currency)}" }
        }
    }

    if (history.size < 2) {
        p("sub") {
            val note = "Only ${history.size} observation so far — price history builds up as " +
                "the hourly poll runs, and is what lets you tell a real discount from an " +
                "inflated list price."
            +note
        }
    } else {
        sparkline(history)
        table("history") {
            thead { tr { th { +"When" }; th { +"Price" }; th { +"List price" } } }
            tbody {
                history.reversed().take(50).forEach { obs ->
                    tr {
                        td { +relativeAgo(obs.observedAt) }
                        td { +formatMoney(obs.price, obs.currency) }
                        td { +(obs.listPrice?.let { formatMoney(it, obs.currency) } ?: "—") }
                    }
                }
            }
        }
    }
}

/**
 * Inline SVG sparkline — no chart library needed for a single series.
 *
 * Three things beyond the bare line, each earning its ink: an area fill, because a 3%
 * decline over 120px is close to invisible as a stroke alone; a jade marker on the
 * cheapest observation, since "was it ever cheaper than this?" is the question the detail
 * page exists to answer; and inset padding so a flat series does not graze the frame.
 */
private fun MAIN.sparkline(history: List<PriceObservation>) {
    val prices = history.map { it.price }
    val min = prices.min()
    val max = prices.max()
    val range = (max - min).takeIf { it.signum() > 0 }
    val w = 640.0
    val h = 120.0
    val pad = 10.0
    val stepX = if (history.size > 1) w / (history.size - 1) else w

    // Locale.ROOT, not the default locale: "%.1f" under a comma-decimal locale such as
    // es-CO emits "12,5", which silently corrupts an SVG points list into extra
    // coordinates. The chart would render as garbage only on some machines.
    fun n(v: Double) = "%.1f".format(Locale.ROOT, v)

    val coords = history.mapIndexed { i, obs ->
        val y = if (range == null) h / 2 else {
            h - pad - ((obs.price - min).toDouble() / range.toDouble()) * (h - 2 * pad)
        }
        (i * stepX) to y
    }
    val points = coords.joinToString(" ") { (x, y) -> "${n(x)},${n(y)}" }
    // Closed back along the baseline so the region under the line can be filled.
    val area = "${n(0.0)},${n(h)} $points ${n(w)},${n(h)}"

    val lowIdx = coords.indices.minBy { history[it].price }
    val (lowX, lowY) = coords[lowIdx]

    val currency = history.first().currency
    val label = "price history, ${history.size} observations, " +
        "low ${formatMoney(min, currency)}, high ${formatMoney(max, currency)}"

    div {
        unsafe {
            // No preserveAspectRatio="none" and no height attribute: the previous version
            // stretched the viewBox to a fixed height, which would squash the low marker
            // into an ellipse and thin the stroke unevenly. Letting the aspect ratio hold
            // and capping width in CSS scales the whole chart uniformly instead.
            +"""<svg class="spark" viewBox="0 0 ${n(w)} ${n(h)}" width="100%" role="img" aria-label="$label">
                 <defs>
                   <linearGradient id="sparkFill" x1="0" y1="0" x2="0" y2="1">
                     <stop offset="0%" stop-color="#f2761b" stop-opacity="0.30"/>
                     <stop offset="100%" stop-color="#f2761b" stop-opacity="0"/>
                   </linearGradient>
                 </defs>
                 <polygon fill="url(#sparkFill)" points="$area"/>
                 <polyline fill="none" stroke="#f2761b" stroke-width="2"
                           stroke-linecap="round" stroke-linejoin="round" points="$points"/>
                 <circle cx="${n(lowX)}" cy="${n(lowY)}" r="3.5" fill="#4fbf8b"
                         stroke="#0d0c0a" stroke-width="1.5"/>
               </svg>"""
        }
    }
    p("sub") {
        +"range ${formatMoney(min, currency)} – ${formatMoney(max, currency)}"
    }
}

fun MAIN.recentRuns(runs: List<Map<String, Any?>>) {
    div("runs") {
        h2 { +"Recent fetch runs" }
        table {
            thead { tr { th { +"Source" }; th { +"Started" }; th { +"Items" }; th { +"New" }; th { +"Status" } } }
            tbody {
                if (runs.isEmpty()) tr { td { colSpan = "5"; +"no runs yet" } }
                runs.forEach { run ->
                    tr {
                        td { +(run["source"] as? String ?: "?") }
                        td { +((run["startedAt"] as? Instant)?.let { relativeAgo(it) } ?: "—") }
                        td { +"${run["itemCount"]}" }
                        td { +"${run["newCount"]}" }
                        val error = run["error"] as? String
                        td {
                            when {
                                error != null -> span("err") { +error.take(110) }
                                run["finishedAt"] == null -> +"running…"
                                else -> +"ok"
                            }
                        }
                    }
                }
            }
        }
    }
}
