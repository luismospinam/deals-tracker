package com.luis.dealstracker.web

import com.luis.dealstracker.ingest.WatchEvaluator
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.FxRate
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchCandidate
import com.luis.dealstracker.model.WatchMatch
import com.luis.dealstracker.model.formatMoney
import io.ktor.http.Parameters
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.MAIN
import kotlinx.html.TBODY
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.math.BigDecimal

/**
 * Rows per page in the "Matching now" table.
 *
 * This used to be a hard cap of 40, and that was a real trap: with 426 matches, re-sorting
 * swapped which 40 you saw rather than reordering them, so the dearest televisions were simply
 * unreachable. Paging means the sort orders the whole set and you can walk through it.
 *
 * 15 rather than 40 because each row is two lines — title, then the retailer link and the
 * cross-retailer note — so 40 was several screens of scrolling. 15 is about one. The cost is
 * more pages (426 matches becomes 29), which is why the pager carries first/last jumps: with
 * the table sorted, the two ends are the interesting ones.
 */
private const val MATCHES_PER_PAGE = 15

/** Page number for the "Matching now" table. 1-based, because it appears in a URL. */
private const val MATCHING_PAGE_PARAM = "mpage"

/**
 * Which column a watch table is ordered by, and in which direction.
 *
 * Server-side via query parameters rather than client-side JavaScript, for the same reason the
 * feed's `sort` works that way: the ordering survives a reload, is linkable, and needs no
 * script. The two tables on a watch page carry independent parameters so sorting one does not
 * disturb the other.
 */
data class WatchSort(val key: String, val desc: Boolean) {
    fun isOn(column: String) = key == column

    /** Clicking the active column flips it; clicking any other starts at that column's default. */
    fun toggledFor(column: String, defaultDesc: Boolean) =
        if (isOn(column)) WatchSort(column, !desc) else WatchSort(column, defaultDesc)

    companion object {
        /** Query-parameter names, one pair per table so they cannot collide. */
        const val MATCHING_PARAM = "msort"
        const val ALERTS_PARAM = "asort"

        /** Cheapest first for products, newest first for alerts — what each is usually read for. */
        val MATCHING_DEFAULT = WatchSort("price", desc = false)
        val ALERTS_DEFAULT = WatchSort("when", desc = true)

        /**
         * Parses `price` / `price:desc`. An unknown column falls back to the default rather
         * than erroring: a stale bookmark should still render the page.
         */
        fun parse(raw: String?, allowed: Set<String>, default: WatchSort): WatchSort {
            val value = raw?.takeIf { it.isNotBlank() } ?: return default
            val key = value.substringBefore(':')
            if (key !in allowed) return default
            return WatchSort(key, desc = value.substringAfter(':', "") == "desc")
        }
    }

    /** The `key:desc` form used in a URL; direction is omitted when ascending. */
    fun asParam(): String = if (desc) "$key:desc" else key
}

private val MATCHING_COLUMNS = setOf("product", "price", "floor", "source")
private val ALERTS_COLUMNS = setOf("deal", "price", "why", "when")

/**
 * Reads one table's sort from the query string.
 *
 * Lives here rather than in `Routes` so the allowed columns sit next to the tables that render
 * them — a column added to a table and not to its allow-list would silently fall back to the
 * default, which is a confusing way to fail.
 */
fun watchSortFrom(params: Parameters, param: String): WatchSort = when (param) {
    WatchSort.ALERTS_PARAM ->
        WatchSort.parse(params[param], ALERTS_COLUMNS, WatchSort.ALERTS_DEFAULT)
    else ->
        WatchSort.parse(params[param], MATCHING_COLUMNS, WatchSort.MATCHING_DEFAULT)
}

/**
 * Reads the "Matching now" page number.
 *
 * Anything unparseable or below 1 becomes 1; the upper bound is clamped where the page size is
 * known, so a bookmark to a page that has since shrunk still renders.
 */
fun watchPageFrom(params: Parameters): Int =
    params[MATCHING_PAGE_PARAM]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

/** Human wording for the hint line, so the page states what it is showing. */
private fun sortLabel(sort: WatchSort): String = when (sort.key) {
    "price" -> if (sort.desc) "price, dearest first" else "price, cheapest first"
    "floor" -> if (sort.desc) "window low, highest first" else "window low, lowest first"
    "source" -> "source" + if (sort.desc) ", Z–A" else ", A–Z"
    "product" -> "product name" + if (sort.desc) ", Z–A" else ", A–Z"
    else -> sort.key
}

/**
 * Ordering for the "Matching now" table.
 *
 * Every comparator ends with the deal id, because ties are common — three retailers list the
 * same television at 509,900 — and without a final tiebreak the row order wobbles between
 * renders for no visible reason.
 *
 * `nullsLast` on the window low is deliberate: a listing never seen before has no floor, and
 * "unknown" belongs at the end rather than sorting as zero.
 */
internal fun matchingComparator(sort: WatchSort): Comparator<WatchCandidate> {
    val base: Comparator<WatchCandidate> = when (sort.key) {
        "product" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        "floor" -> compareBy(nullsLast<BigDecimal>()) { it.priorFloor }
        "source" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.source }
        else -> compareBy { it.price }
    }
    return (if (sort.desc) base.reversed() else base).thenBy { it.dealId }
}

/**
 * Ordering for the alerts ledger.
 *
 * "Why" sorts by reason then by how large the gap or drop was, since two alerts of the same
 * kind are most usefully ranked by how much they are worth.
 */
private fun alertsComparator(sort: WatchSort, dealsById: Map<Long, Deal>): Comparator<WatchMatch> {
    val base: Comparator<WatchMatch> = when (sort.key) {
        // Falls back to the id when the deal has since been removed and has no title to sort on.
        "deal" -> compareBy(String.CASE_INSENSITIVE_ORDER) { dealsById[it.dealId]?.title ?: "" }
        "price" -> compareBy { it.priceAtMatch }
        "why" -> compareBy<WatchMatch> { it.reason.name }.thenByDescending { it.priceAtMatch }
        else -> compareBy { it.matchedAt }
    }
    return (if (sort.desc) base.reversed() else base).thenByDescending { it.id }
}

/**
 * Every link on a watch page carries the full state, so following one never silently resets
 * something else — sorting the alerts must not jump the product list back to page 1, and paging
 * must not lose the sort.
 */
private fun watchUrl(basePath: String, matching: WatchSort, alerts: WatchSort, page: Int): String {
    val query = buildList {
        add("${WatchSort.MATCHING_PARAM}=${matching.asParam()}")
        add("${WatchSort.ALERTS_PARAM}=${alerts.asParam()}")
        // Omitted on the first page, so the common URL stays clean and shareable.
        if (page > 1) add("$MATCHING_PAGE_PARAM=$page")
    }
    return "$basePath?${query.joinToString("&")}"
}

/**
 * A clickable column heading.
 *
 * Changing the sort returns to page 1. Staying on page 6 while re-sorting shows a slice of a
 * set whose beginning you have not seen, which reads as the table losing your place.
 */
private fun TR.sortableTh(
    label: String,
    column: String,
    classes: String,
    isAlerts: Boolean,
    matching: WatchSort,
    alerts: WatchSort,
    defaultDesc: Boolean = false,
    basePath: String,
) {
    val current = if (isAlerts) alerts else matching
    val next = current.toggledFor(column, defaultDesc)
    val href = if (isAlerts) {
        watchUrl(basePath, matching, next, page = 1)
    } else {
        watchUrl(basePath, next, alerts, page = 1)
    }
    th(classes = classes) {
        a(href = href, classes = if (current.isOn(column)) "sorted" else null) {
            +label
            if (current.isOn(column)) {
                // A glyph as well as the class, so the active direction does not rely on colour.
                span("dir") { +if (current.desc) " ↓" else " ↑" }
            }
        }
    }
}

/**
 * Previous/next with the current position stated.
 *
 * Rendered above and below the table: with 40 rows you have scrolled past the top control by
 * the time you want it.
 */
private fun MAIN.pager(
    basePath: String,
    matching: WatchSort,
    alerts: WatchSort,
    page: Int,
    pages: Int,
) {
    if (pages <= 1) return
    div("pager") {
        // First and last matter more than they would with a bigger page size: sorted by price,
        // the cheapest and dearest ends are exactly what you want, and walking 29 pages to
        // reach one of them is not navigation.
        if (page > 1) {
            a(href = watchUrl(basePath, matching, alerts, 1)) { +"« first" }
            a(href = watchUrl(basePath, matching, alerts, page - 1)) { +"‹ prev" }
        } else {
            // Inert text rather than nothing, so the controls keep their position between pages.
            span("off") { +"« first" }
            span("off") { +"‹ prev" }
        }
        span("at") { +"page $page of $pages" }
        if (page < pages) {
            a(href = watchUrl(basePath, matching, alerts, page + 1)) { +"next ›" }
            a(href = watchUrl(basePath, matching, alerts, pages)) { +"last »" }
        } else {
            span("off") { +"next ›" }
            span("off") { +"last »" }
        }
    }
}

/**
 * The watchlist pages, kept out of [Views] which is already 800-odd lines.
 *
 * Reuses that file's vocabulary throughout rather than inventing a second one: `.chip` for
 * badges, `.actions` for row buttons, `.empty` for the nothing-yet state, and the same HTMX
 * row-swap idiom the feed uses for per-deal actions.
 */
fun MAIN.watchesPage(
    watches: List<Watch>,
    counts: Map<Long, Pair<Int, Int>>,
    sources: List<String>,
    categories: List<String>,
) {
    h2 { +"Watches" }
    p("hint") {
        +"Things you are trying to buy. Each one hunts on every source that can search, and "
        +"filters everything else that arrives. Alerts land as a desktop notification."
    }

    if (watches.isEmpty()) {
        div("empty") {
            p { +"No watches yet. Add one below — for a fridge, try:" }
            p { code { +"nevera | refrigerador" } }
            p("hint") {
                +"Set a minimum price too. Searching \"nevera\" also returns fridge "
                +"organisers at 20.000, and without a floor those would win every time."
            }
        }
    } else {
        table("deals watches") {
            thead {
                tr {
                    th(classes = "col-deal") { +"Watch" }
                    th(classes = "col-price") { +"Price band" }
                    th(classes = "col-disc") { +"Window" }
                    th(classes = "col-sig") { +"Matches" }
                    th(classes = "col-act") { +"" }
                }
            }
            tbody { watches.forEach { watchRow(it, counts[it.id]) } }
        }
    }

    watchForm(sources, categories)
}

/** Split from [watchRow] so the HTMX fragment and the table body cannot drift apart. */
private fun TBODY.watchRow(watch: Watch, counts: Pair<Int, Int>?) {
    tr(classes = watchRowClass(watch)) {
        attributes["id"] = "watch-${watch.id}"
        watchRowBody(watch, counts)
    }
}

/**
 * `paused` dims a disabled watch's whole row, so the ones still hunting stand out at a glance.
 *
 * On the `<tr>` rather than inside a cell, so the pause/resume swap — which replaces the row —
 * changes the look with it. The "paused" chip stays too: colour is never the only cue.
 */
private fun watchRowClass(watch: Watch): String? = if (watch.enabled) null else "paused"

private fun TR.watchRowBody(watch: Watch, counts: Pair<Int, Int>?) {
    td(classes = "col-deal") {
        div("title") { a(href = "/watches/${watch.id}") { +watch.name } }
        div("meta") {
            span { code { +watch.terms } }
            watch.excludeTerms?.takeIf { it.isNotBlank() }?.let {
                span("dim") { +"not: $it" }
            }
            watch.categoryGroup?.let { span { +it } }
            span { +if (watch.sources.isEmpty()) "all sources" else watch.sources.joinToString(", ") }
            watch.amazonTerms?.takeIf { it.isNotBlank() }?.let {
                span { +"amazon: "; code { +it } }
            }
            if (!watch.enabled) span("chip") { +"paused" }
        }
    }
    td(classes = "col-price") {
        val min = watch.minPrice?.let { formatMoney(it, watch.currency) }
        val max = watch.maxPrice?.let { formatMoney(it, watch.currency) }
        when {
            min != null && max != null -> +"$min – $max"
            max != null -> +"up to $max"
            min != null -> +"from $min"
            // A watch with no floor is the noisy case, so it is called out rather than left blank.
            else -> span("dim") { +"any (no floor)" }
        }
    }
    td(classes = "col-disc") {
        +"${watch.lookbackDays}d"
        br()
        span("dim") { +"−${watch.crossSiteMinSpread.stripTrailingZeros().toPlainString()}% x-site" }
    }
    td(classes = "col-sig") {
        val (total, unseen) = counts ?: (0 to 0)
        if (total == 0) {
            span("dim") { +"—" }
        } else {
            +"$total"
            if (unseen > 0) span("act-count") { +"$unseen new" }
        }
    }
    td(classes = "col-act") {
        div("actions") {
            val next = if (watch.enabled) "false" else "true"
            button {
                attributes["hx-post"] = "/watches/${watch.id}/enabled?value=$next"
                attributes["hx-target"] = "#watch-${watch.id}"
                attributes["hx-swap"] = "outerHTML"
                +if (watch.enabled) "pause" else "resume"
            }
            button {
                attributes["hx-post"] = "/watches/${watch.id}/delete"
                attributes["hx-target"] = "#watch-${watch.id}"
                attributes["hx-swap"] = "outerHTML"
                // Deleting takes the match history with it (FK cascade), so it asks first.
                attributes["hx-confirm"] = "Delete \"${watch.name}\" and its match history?"
                +"delete"
            }
        }
    }
}

/** Bare `<tr>` for an HTMX swap — never a full document, which would nest a table. */
fun watchRowFragment(watch: Watch, counts: Pair<Int, Int>?): String =
    createHTML(prettyPrint = false).tr(classes = watchRowClass(watch)) {
        attributes["id"] = "watch-${watch.id}"
        watchRowBody(watch, counts)
    }

/**
 * Create/edit form.
 *
 * A plain POST rather than HTMX: adding a watch changes the whole list and the redirect
 * gives a clean URL to reload, whereas the row-level pause/delete genuinely only touch one
 * row and are worth swapping in place.
 */
private fun MAIN.watchForm(
    sources: List<String>,
    categories: List<String>,
    watch: Watch? = null,
) {
    val editing = watch != null
    h3 { +if (editing) "Edit watch" else "New watch" }

    form(
        action = if (editing) "/watches/${watch!!.id}" else "/watches",
        method = FormMethod.post,
        classes = "filters watchform",
    ) {
        div("f") {
            label { +"Name" }
            input(type = InputType.text, name = "name") {
                required = true
                placeholder = "Nevera"
                value = watch?.name ?: ""
            }
        }
        div("f wide") {
            label { +"Match terms" }
            input(type = InputType.text, name = "terms") {
                required = true
                placeholder = "nevera | refrigerador"
                value = watch?.terms ?: ""
            }
        }
        div("f wide") {
            label { +"Amazon terms" }
            input(type = InputType.text, name = "amazonTerms") {
                placeholder = "55 inch 4k tv | smart tv 65"
                attributes["title"] = "What to search amazon.com for, in English. Blank = this watch does not " +
                    "search Amazon. Each term costs up to two Amazon page loads an hour."
                value = watch?.amazonTerms ?: ""
            }
        }
        div("f wide") {
            label { +"Exclude" }
            input(type = InputType.text, name = "excludeTerms") {
                placeholder = "organizador | repuesto | filtro"
                value = watch?.excludeTerms ?: ""
            }
        }
        div("f") {
            label { +"Min price" }
            input(type = InputType.number, name = "minPrice") {
                placeholder = "800000"
                value = watch?.minPrice?.toBigInteger()?.toString() ?: ""
            }
        }
        div("f") {
            label { +"Max price" }
            input(type = InputType.number, name = "maxPrice") {
                placeholder = "3500000"
                value = watch?.maxPrice?.toBigInteger()?.toString() ?: ""
            }
        }

        details("more") {
            summary { +"more" }
            div("morepanel") {
                div("f") {
                    label { +"Category" }
                    select { name = "categoryGroup"
                        option { value = ""; +"any" }
                        categories.forEach {
                            option {
                                value = it
                                if (watch?.categoryGroup == it) selected = true
                                +it
                            }
                        }
                    }
                }
                div("f") {
                    label { +"Source" }
                    select { name = "source"
                        option { value = ""; +"all" }
                        sources.forEach {
                            option {
                                value = it
                                if (watch?.sources?.contains(it) == true) selected = true
                                +it
                            }
                        }
                    }
                }
                div("f") {
                    label { +"Currency" }
                    select { name = "currency"
                        listOf("COP", "USD").forEach {
                            option {
                                value = it
                                if ((watch?.currency ?: "COP") == it) selected = true
                                +it
                            }
                        }
                    }
                }
                div("f") {
                    label { +"Window (days)" }
                    input(type = InputType.number, name = "lookbackDays") {
                        value = (watch?.lookbackDays ?: 30).toString()
                    }
                }
                div("f") {
                    label { +"Cross-site %" }
                    input(type = InputType.number, name = "crossSiteMinSpread") {
                        value = (watch?.crossSiteMinSpread ?: BigDecimal("15")).stripTrailingZeros().toPlainString()
                    }
                }
            }
        }

        div("util") {
            button(type = ButtonType.submit, classes = "primary") { +if (editing) "Save" else "Add watch" }
            if (editing) a(href = "/watches", classes = "btnlike") { +"cancel" }
        }
    }
}

/**
 * One watch in detail: what it is matching now, what it has alerted on, and the
 * cross-site comparison that says whether this retailer is actually the cheap one.
 */
fun MAIN.watchDetail(
    watch: Watch,
    candidates: List<WatchCandidate>,
    matches: List<WatchMatch>,
    dealsById: Map<Long, Deal>,
    sources: List<String>,
    categories: List<String>,
    matchingSort: WatchSort = WatchSort.MATCHING_DEFAULT,
    alertsSort: WatchSort = WatchSort.ALERTS_DEFAULT,
    matchingPage: Int = 1,
    fx: FxRate? = null,
) {
    val basePath = "/watches/${watch.id}"
    h2 { +watch.name }
    p("meta") {
        span { code { +watch.terms } }
        watch.amazonTerms?.takeIf { it.isNotBlank() }?.let { span { +"amazon: "; code { +it } } }
        span { +"${watch.lookbackDays}-day window" }
        span { +"${candidates.size} matching now" }
        if (!watch.enabled) span("chip") { +"paused" }
    }

    // Compared, sorted and highlighted in the watch's currency, the same conversion the rules use.
    val comparable = fx?.let { WatchEvaluator.comparable(watch, candidates, it) } ?: candidates
    matchingTable(watch, comparable, matchingSort, alertsSort, matchingPage, basePath, fx)

    h3 { +"Alerts" }
    if (matches.isEmpty()) {
        div("empty") {
            p { +"Nothing has fired yet." }
            p("hint") {
                +"A watch stays quiet on its first run by design — with one observation per "
                +"listing there is no floor to beat, and \"the cheapest of forty\" is not news. "
                +"Preview what would fire with:"
            }
            p { code { +"./dt run --args=\"--check-watches --dry-run\"" } }
        }
    } else {
        table("deals watchalerts") {
            thead {
                tr {
                    val th = { label: String, col: String, cls: String, desc: Boolean ->
                        sortableTh(label, col, cls, true, matchingSort, alertsSort, desc, basePath)
                    }
                    th("Deal", "deal", "col-deal", false)
                    th("Price", "price", "col-price", false)
                    th("Why", "why", "col-sig", false)
                    // Newest first is what you want on a first click, not oldest.
                    th("When", "when", "col-seen", true)
                }
            }
            tbody {
                matches.sortedWith(alertsComparator(alertsSort, dealsById)).forEach { match ->
                    tr {
                        val deal = dealsById[match.dealId]
                        td(classes = "col-deal") {
                            div("title") {
                                if (deal != null) {
                                    a(href = "/deal/${deal.id}") { +deal.title }
                                } else {
                                    span("dim") { +"deal ${match.dealId} (removed)" }
                                }
                            }
                            deal?.let { div("meta") { span { +it.source } } }
                        }
                        td(classes = "col-price") {
                            // The deal's own currency: the ledger stores the price as quoted, so a
                            // USD alert reads US$399.99 rather than as 400 pesos.
                            +formatMoney(match.priceAtMatch, deal?.currency ?: watch.currency)
                        }
                        td(classes = "col-sig") {
                            span(if (match.reason == MatchReason.CROSS_SITE_SPREAD) "chip chip-drop" else "chip chip-low") {
                                +if (match.reason == MatchReason.CROSS_SITE_SPREAD) "undercuts a rival" else "new low"
                            }
                            match.evidence?.let { div("dim") { +it } }
                        }
                        td(classes = "col-seen") {
                            +relativeAgo(match.matchedAt)
                            // An unnotified row means the banner failed or was suppressed;
                            // saying so is the difference between "quiet" and "broken".
                            if (match.notifiedAt == null) {
                                br()
                                span("dim") { +"not announced" }
                            }
                        }
                    }
                }
            }
        }
    }

    watchForm(sources, categories, watch)
}

/**
 * What the watch matches right now, cheapest first.
 *
 * The page previously showed only cross-site clusters and fired alerts, which meant a watch
 * could report "135 matching now" and then display nothing at all — every toilet watch looks
 * like that, because bathroom fixtures neither cluster (named models, no barcodes) nor fire on
 * a first run. Whatever else is true, the list of things being watched should be visible.
 */
private fun MAIN.matchingTable(
    watch: Watch,
    candidates: List<WatchCandidate>,
    sort: WatchSort,
    alertsSort: WatchSort,
    page: Int,
    basePath: String,
    fx: FxRate? = null,
) {
    h3 { +"Matching now" }
    if (candidates.isEmpty()) {
        div("empty") {
            p { +"Nothing matches yet." }
            p("hint") {
                +"Either no source has been polled for these terms, or the filters exclude "
                +"everything. Check the terms against what the retailers actually call the thing "
                +"— \"nevecón\" is not \"nevera\" — and check the minimum price is not above the "
                +"whole market. Preview with:"
            }
            p { code { +"./dt run --args=\"--check-watches --dry-run\"" } }
        }
        return
    }

    val ordered = candidates.sortedWith(matchingComparator(sort))
    val pages = (ordered.size + MATCHES_PER_PAGE - 1) / MATCHES_PER_PAGE
    // Clamped rather than rejected: ?mpage=99 on a 3-page watch should show the last page, and
    // a page that shrinks between visits should not 404 a bookmark.
    val current = page.coerceIn(1, pages.coerceAtLeast(1))
    val from = (current - 1) * MATCHES_PER_PAGE
    val shown = ordered.drop(from).take(MATCHES_PER_PAGE)

    p("hint") {
        if (pages > 1) {
            // The exact range, not just a count: "40 of 426" left it ambiguous which 40, which
            // is what made re-sorting look like the table was swapping rows at random.
            +"Rows ${from + 1}–${from + shown.size} of ${ordered.size}. "
        } else {
            +"${ordered.size} match${if (ordered.size == 1) "" else "es"}. "
        }
        +"Sorted by ${sortLabel(sort)}. A highlighted price matches the cheapest this watch has "
        +"on record in its window; \"new\" under Window low means the listing has not been seen "
        +"before."
        if (fx != null && candidates.any { it.quotedPrice != null }) {
            +" Prices marked ≈ are converted at ${fx.describe()}."
        }
    }

    pager(basePath, sort, alertsSort, current, pages)

    // The floor the LOWEST_IN_WINDOW rule is measured against, recomputed here so the page and
    // the rule cannot disagree about what "the floor" is.
    val floor = candidates.mapNotNull { it.priorFloor }.minOrNull()

    // Built once for the whole candidate set rather than per row, and from the same "share any
    // key" rule the alerting uses, so the page cannot name a rival the rule would not.
    val rivals = rivalIndex(candidates)

    table("deals watchmatches") {
        thead {
            tr {
                val th = { label: String, col: String, cls: String, desc: Boolean ->
                    sortableTh(label, col, cls, false, sort, alertsSort, desc, basePath)
                }
                th("Product", "product", "col-deal", false)
                th("Price", "price", "col-price", false)
                th("Window low", "floor", "col-price", false)
                th("Source", "source", "col-sig", false)
            }
        }
        tbody {
            shown.forEach { candidate ->
                tr {
                    td(classes = "col-deal") {
                        div("title") {
                            a(href = "/deal/${candidate.dealId}") { +candidate.title }
                        }
                        // The retailer's own page, since buying happens there rather than here.
                        div("meta") {
                            span { a(href = candidate.url) { +"open at retailer ↗" } }
                            rivalNote(watch, candidate, rivals[candidate.dealId])
                        }
                    }
                    td(classes = "col-price") {
                        val isFloor = floor != null && candidate.price <= floor
                        // The quoted price first for a converted row, then what it is worth here.
                        if (isFloor) {
                            strong("chip chip-low") { +candidate.priceLabel() }
                        } else {
                            +candidate.priceLabel()
                        }
                    }
                    td(classes = "col-price") {
                        // Blank, not zero: no prior observation means unknown, and this listing
                        // has simply not been seen before.
                        candidate.priorFloor
                            ?.let { +formatMoney(it, candidate.currency) }
                            ?: span("dim") { +"new" }
                    }
                    td(classes = "col-sig") { +candidate.source }
                }
            }
        }
    }

    pager(basePath, sort, alertsSort, current, pages)
}

/**
 * The cheapest listing of the same product at a *different* retailer, per candidate.
 *
 * Replaces the standalone comparison table that used to sit on this page. That table showed
 * every cluster whether or not the gap was worth anything, went permanently empty for whole
 * categories — toilets are named rather than coded, so they never cluster — and duplicated what
 * a fired alert already says in full.
 *
 * What it did carry that nothing else does is a gap *below* the alert threshold. The clearest
 * case: a MABE side-by-side at 2,999,000 from Grupo Mansión against 3,200,900 at Carulla is
 * 6.3%, under a 15% threshold, so it never alerts — yet it is exactly the retailer worth
 * knowing about. So the comparison survives, inline on the row, instead of as its own table.
 *
 * Uses the same "share any key" rule as [WatchEvaluator.crossSiteSpread] rather than a second
 * definition, so the page cannot claim a rival the alerting rule would not recognise.
 */
private fun rivalIndex(candidates: List<WatchCandidate>): Map<Long, WatchCandidate> {
    val byKey = mutableMapOf<String, MutableList<WatchCandidate>>()
    candidates.forEach { c -> c.keys.forEach { byKey.getOrPut(it) { mutableListOf() } += c } }

    return candidates.mapNotNull { candidate ->
        val cheapestElsewhere = candidate.keys
            .flatMap { byKey[it].orEmpty() }
            .distinctBy { it.dealId }
            .filter { it.source != candidate.source }
            // As in the rule: whatever the rate could not convert is never a rival.
            .filter { it.currency == candidate.currency }
            .minByOrNull { it.price }
            ?: return@mapNotNull null
        candidate.dealId to cheapestElsewhere
    }.toMap()
}

/**
 * "cheapest — 6.3% under carulla", or "carulla has it for $3,200,900".
 *
 * Renders nothing when the product is not identifiable at another retailer, which is the normal
 * case for most of the catalogue and must not read as an absence of competition.
 */
private fun DIV.rivalNote(watch: Watch, candidate: WatchCandidate, rival: WatchCandidate?) {
    if (rival == null) return
    val cheaperHere = candidate.price < rival.price
    if (cheaperHere) {
        val gap = rival.price.subtract(candidate.price)
            .divide(rival.price, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(1, java.math.RoundingMode.HALF_UP)
        // Green, matching the feed's convention that a verified good thing is --good and a
        // magnitude ramp is never reused for identity.
        span("chip chip-low") { +"$gap% under ${rival.source}" }
    } else {
        // Dearer here. Said plainly rather than hidden: the point of the comparison is to send
        // you to the cheaper shop, not to flatter the row you happen to be looking at.
        span("dim") { +"${rival.source} has it for ${rival.priceLabel()}" }
    }
}
