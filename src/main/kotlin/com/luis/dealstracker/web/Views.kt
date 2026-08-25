package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.model.PriceObservation
import kotlinx.html.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Bumped whenever app.css changes, to defeat browser caching of the stylesheet.
 * The CSS lives in src/main/resources/static/app.css so it can be edited and reloaded
 * without a Gradle rebuild.
 */
private const val CSS_VERSION = "7"

/** Slickdeals forum names that masquerade as product categories and carry no signal. */
private val GENERIC_CATEGORIES = setOf("Hot Deals", "Marketplace Deals", "Deal Talk", "Freebies")

fun HTML.page(title: String, block: MAIN.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(title)
        link(rel = "stylesheet", href = "/static/app.css?v=$CSS_VERSION")
        script(src = "https://unpkg.com/htmx.org@1.9.12") {}
    }
    body {
        header {
            h1 { +"Deals Tracker" }
            span("sub") { a(href = "/") { +"feed" } }
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

fun relativeTime(instant: Instant): String {
    val d = Duration.between(instant, Instant.now())
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
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
        button(classes = "primary") { type = ButtonType.submit; +"Apply" }
        button {
            attributes["hx-post"] = "/refresh"
            attributes["hx-swap"] = "none"
            type = ButtonType.button
            +"Refresh now"
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
    tr {
        id = "deal-${deal.id}"

        td(classes = "col-thumb") {
            val img = deal.imageUrl
            if (img != null) img(classes = "thumb", src = img, alt = "") else div("thumb-blank") {}
        }

        td {
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
                    title = "Posted by the source ${relativeTime(posted)} ago"
                    +"${relativeTime(posted)} old"
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
        when (deal.coShipping) {
            CoShipping.YES -> span("co-yes") { +"✓ ships" }
            CoShipping.NO -> span("co-no") { +"✗ no" }
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
        deal.merchant?.let { +it }
        deal.asin?.let { +"ASIN $it" }
        +"first seen ${relativeTime(deal.firstSeenAt)} ago"
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
                        td { +"${relativeTime(obs.observedAt)} ago" }
                        td { +formatMoney(obs.price, obs.currency) }
                        td { +(obs.listPrice?.let { formatMoney(it, obs.currency) } ?: "—") }
                    }
                }
            }
        }
    }
}

/** Inline SVG sparkline — no chart library needed for a single series. */
private fun MAIN.sparkline(history: List<PriceObservation>) {
    val prices = history.map { it.price }
    val min = prices.min()
    val max = prices.max()
    val range = (max - min).takeIf { it.signum() > 0 }
    val w = 640.0
    val h = 120.0
    val stepX = if (history.size > 1) w / (history.size - 1) else w

    val points = history.mapIndexed { i, obs ->
        val x = i * stepX
        val y = if (range == null) h / 2 else {
            h - ((obs.price - min).toDouble() / range.toDouble()) * (h - 12) - 6
        }
        "${"%.1f".format(x)},${"%.1f".format(y)}"
    }.joinToString(" ")

    div {
        unsafe {
            +"""<svg class="spark" viewBox="0 0 $w $h" width="100%" height="$h"
                 preserveAspectRatio="none" role="img" aria-label="price history">
                 <polyline fill="none" stroke="#5b9dff" stroke-width="2" points="$points"/>
               </svg>"""
        }
    }
    p("sub") {
        +"range ${formatMoney(min, history.first().currency)} – ${formatMoney(max, history.first().currency)}"
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
                        td { +((run["startedAt"] as? Instant)?.let { "${relativeTime(it)} ago" } ?: "—") }
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
