package com.luis.dealstracker.web

import com.luis.dealstracker.model.CardOffer
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.model.PriceObservation
import com.luis.dealstracker.model.formatMoney
import io.ktor.http.encodeURLParameter
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Locale
import com.luis.dealstracker.i18n.categoryLabel
import com.luis.dealstracker.i18n.tr

/**
 * Bumped whenever app.css changes, to defeat browser caching of the stylesheet.
 * The CSS lives in src/main/resources/static/app.css so it can be edited and reloaded
 * without a Gradle rebuild.
 */
// 20: both sides of the develop merge edited app.css (relevance-score styling there, watch
// tables and pager here), so this has to clear the highest either had reached.
private const val CSS_VERSION = "51"

/**
 * Which feed-shaped screen is being rendered.
 *
 * `/` and `/favorites` share every column, every filter and the whole row renderer — favorites is
 * the feed restricted to rows Luis chose, not a second presentation of them. What differs is small
 * and varies *together*, which is why it travels as one value rather than three parameters: a form
 * posting to `/favorites` while its export button points at `/export.xlsx` would hand over a file
 * that silently disagreed with the page it was taken from.
 */
// Public rather than internal only because `filterForm`, `dealTable` and `rowFragment` are, and an
// internal type cannot appear in a public signature.
enum class FeedScreen(
    /** Where the filter form submits. */
    val action: String,
    /** The Export button's `formaction`, so the download matches the controls as they stand. */
    val exportAction: String,
    /**
     * Whether to render the State control.
     *
     * False on favorites, which pins `states` server-side: a dropdown offering "dismissed" on a
     * page that can only ever show favorites would be a control that does nothing.
     */
    val showState: Boolean,
) {
    FEED("/", "/export.xlsx", showState = true),
    FAVORITES("/favorites", "/favorites/export.xlsx", showState = false),
}

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

/**
 * Names a cell's column for the phone layout, where app.css turns each row into a card and hides
 * the header row -- so a bare "$7,540" under "Unit price" would otherwise lose its heading.
 */
internal fun TD.cardLabel(label: String) {
    attributes["data-label"] = label
}

/** One ordering offered by [sortTags]: its label, the URL that applies it, and whether it is current. */
internal data class SortOption(val label: String, val href: String, val on: Boolean)

/**
 * The column-header sorts, as a tag row for phones.
 *
 * The card layout hides the header row, and with it every sortable `th` link, so without this a
 * phone could not reorder the list at all. Same links as the headers -- plain anchors carrying
 * state in the query string, no JavaScript -- styled as the Store / Aisle tags beside it.
 * Hidden on desktop, where the headers already do this and a second control would be clutter.
 */
internal fun FlowContent.sortTags(options: List<SortOption>) {
    div("typetags sorttags") {
        span("typetags-label") { +tr("Sort", "Ordenar") }
        options.forEach { o ->
            a(classes = if (o.on) "typetag on" else "typetag", href = o.href) {
                if (o.on) attributes["aria-current"] = "true"
                +o.label
            }
        }
    }
}

/**
 * "Mastercard −20% → $3,280", "Store card only −30% → $7,000", or "CMR −26% → $589,900".
 *
 * Shared by the Groceries tab and each watch's "Matching now" table, rather than copied into both:
 * the chip makes a conditional claim about who can pay a price, and two copies would let that
 * wording drift.
 *
 * Offers restricted to one retailer's own card are marked in words and in colour, since most
 * Mastercards will not get them: the store's own card is a Mastercard, but not every Mastercard is
 * the store's card.
 */
internal fun FlowContent.cardChip(card: CardOffer, currency: String) {
    // Every scope but MASTERCARD names one issuer's card, for the same reason STORE_CARD does:
    // holding some other card gets you the cash price.
    val storeOnly = card.scope != CardScope.MASTERCARD && card.scope != CardScope.OTHER
    val pct = card.percent.stripTrailingZeros().toPlainString()
    div(if (storeOnly) "card-price store-only" else "card-price") {
        title = when (card.scope) {
            CardScope.MASTERCARD -> tr(
                "With any Mastercard, including Tarjeta Éxito y Carulla / Carulla Mastercard. " +
                    "$pct% off the price before any shelf promotion — card promotions change daily.",
                "Con cualquier Mastercard, incluida la Tarjeta Éxito y Carulla / Carulla Mastercard. " +
                    "$pct% de descuento sobre el precio antes de cualquier promoción en góndola — las promociones con tarjeta cambian a diario.",
            )
            CardScope.STORE_CARD -> tr(
                "Only with the store's own card (Tarjeta Éxito y Carulla / Carulla Mastercard), " +
                    "not other Mastercards. Card promotions change daily.",
                "Solo con la tarjeta propia de la tienda (Tarjeta Éxito y Carulla / Carulla Mastercard), " +
                    "no con otras Mastercard. Las promociones con tarjeta cambian a diario.",
            )
            // Stated as a saving off the cash price, because that is how it was computed -- the
            // store publishes the CMR price and not a percentage. See `FalabellaNextSource`.
            CardScope.CMR -> tr(
                "Only with the CMR card (Falabella and Homecenter), not other cards. " +
                    "$pct% below the price anyone pays today.",
                "Solo con la tarjeta CMR (Falabella y Homecenter), no con otras tarjetas. " +
                    "$pct% por debajo del precio que paga cualquiera hoy.",
            )
            // Easy publishes the price to pay rather than a percentage, so the saving is quoted
            // against the cash price. See `VtexSource.byCardBin`.
            CardScope.TMC -> tr(
                "Only with Tarjeta Más Cencosud (Easy), and some of these also take Codensa. " +
                    "$pct% below the price anyone pays today.",
                "Solo con la Tarjeta Más Cencosud (Easy); algunas también aceptan Codensa. " +
                    "$pct% por debajo del precio que paga cualquiera hoy.",
            )
            CardScope.CODENSA -> tr(
                "Only with a Codensa card (Easy), not Tarjeta Más Cencosud. " +
                    "$pct% below the price anyone pays today.",
                "Solo con una tarjeta Codensa (Easy), no con la Tarjeta Más Cencosud. " +
                    "$pct% por debajo del precio que paga cualquiera hoy.",
            )
            CardScope.OTHER -> tr(
                "With a specific payment method this app does not recognise yet. Card promotions change daily.",
                "Con un medio de pago específico que esta app aún no reconoce. Las promociones con tarjeta cambian a diario.",
            )
        }
        // The scope's own label is English in the model ("Card" for OTHER); translated here, at render.
        val scopeLabel = when (card.scope) {
            CardScope.MASTERCARD -> card.scope.label
            CardScope.STORE_CARD -> tr("Store card only", "Solo tarjeta de la tienda")
            // Named, not "store card only": the point of the chip is which card pays this price.
            CardScope.CMR -> tr("CMR only", "Solo CMR")
            CardScope.TMC -> tr("TMC only", "Solo TMC")
            CardScope.CODENSA -> tr("Codensa only", "Solo Codensa")
            CardScope.OTHER -> tr(card.scope.label, "Tarjeta")
        }
        +"$scopeLabel −$pct% → ${formatMoney(card.price, currency)}"
    }
}

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
            span("sub") { a(href = "/") { +tr("feed", "ofertas") } }
            // Favorites is deliberately NOT here. It was a fourth top-level tab, which put it beside
            // `tracked` and `watches` and so implied it was a fourth kind of thing — but it is the
            // feed with `state = FAVORITE` pinned: same query, same columns, same row renderer, same
            // filters (see FeedScreen). `tracked` and `watches` genuinely are different questions with
            // different columns and different tables behind them. So favorites moved one level down,
            // to the scope row `feedScopes` renders inside the feed screen itself.
            // A separate tab rather than a filter on the feed, because it answers a different
            // question. The feed asks "what is discounted right now?" and is sorted by deal
            // quality; tracked items ask "what is this costing me over time?", where a discount
            // percentage and a community vote are noise and the movement of the price is the
            // whole point. Same rows underneath, different columns and a different default sort.
            span("sub") { a(href = "/tracked") { +tr("tracked", "seguimiento") } }
            // Its own tab for the same reason as tracked: a different question ("cheaper than this
            // item usually is at my store?") with different columns -- unit price, usual price.
            span("sub") { a(href = "/groceries") { +tr("groceries", "mercado") } }
            span("sub") { a(href = "/watches") { +tr("watches", "búsquedas") } }
            // Holds the right rail apart from the nameplate, so the serif sits alone
            // instead of being crowded by the mono labels.
            div("masthead-gap") {}
            span("sub tagline") { +tr("local price monitor", "monitor local de precios") }
            languageSwitch()
        }
        main { block() }
    }
}

/**
 * EN / ES in the masthead. Links, not a form: `/lang/es?back=<this page>` sets the cookie and comes
 * straight back, so the choice works without JavaScript and keeps the filters in the URL.
 */
private fun HEADER.languageSwitch() {
    val back = CurrentPath.value.encodeURLParameter()
    span("sub langswitch") {
        attributes["aria-label"] = tr("Language", "Idioma")
        com.luis.dealstracker.i18n.Lang.entries.forEachIndexed { i, l ->
            if (i > 0) span("langsep") { +"/" }
            val on = l == com.luis.dealstracker.i18n.I18n.lang
            a(href = "/lang/${l.code}?back=$back", classes = if (on) "on" else null) {
                if (on) attributes["aria-current"] = "true"
                attributes["hreflang"] = l.code
                title = if (l == com.luis.dealstracker.i18n.Lang.ES) "Ver en español" else "View in English"
                +l.code.uppercase()
            }
        }
    }
}

/** The under-a-minute case, which is a complete phrase and so cannot take a suffix. */
private val JUST_NOW: String get() = tr("just now", "ahora mismo")

/** "3m", "3h", "3d", or null under a minute -- the duration alone, before any wording. */
private fun shortAge(instant: Instant): String? {
    val d = Duration.between(instant, Instant.now())
    return when {
        d.toMinutes() < 1 -> null
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

fun relativeTime(instant: Instant): String = shortAge(instant) ?: JUST_NOW

/**
 * "3h ago", "2d ago" — but "just now", not "just now ago".
 *
 * Every call site was gluing " ago" onto [relativeTime], which reads correctly for the
 * duration cases and wrongly for the newest rows. Newly ingested deals are exactly the
 * ones most looked at, so the broken phrasing showed up on the most-read rows.
 */
fun relativeAgo(instant: Instant): String =
    shortAge(instant)?.let { tr("$it ago", "hace $it") } ?: JUST_NOW

/** As [relativeAgo], for the age chip: "3h old", but "brand new" rather than "just now old". */
private fun relativeOld(instant: Instant): String =
    shortAge(instant)?.let { tr("$it old", "hace $it") } ?: tr("brand new", "recién publicado")

/**
 * A one-click preset over an existing filter parameter.
 *
 * Deliberately *not* a form input of its own. A chip writes into the single canonical
 * control that owns its parameter — the select or number field in the More-filters panel —
 * so no parameter ever has two inputs that could disagree about what is being filtered,
 * and no filtering capability is lost to the shorthand. The arbitrary-value controls all
 * survive; the chips just cover the values actually reached for.
 */
private data class ChipPreset(val param: String, val value: String, val en: String, val es: String) {
    val label: String get() = tr(en, es)
}

/**
 * Note that "Lowest yet" and "Promoted" both target `only`, which is single-valued. Because
 * they write to the same select, turning one on turns the other off for free — the conflict
 * is impossible to express rather than merely discouraged.
 */
private val FILTER_CHIPS = listOf(
    ChipPreset("minDiscount", "50", "50%+ off", "50%+ dto."),
    ChipPreset("only", "lowest", "Lowest yet", "Mínimo histórico"),
    ChipPreset("co", "YES", "Ships to CO", "Envía a CO"),
    ChipPreset("only", "promoted", "Promoted", "Destacadas"),
    ChipPreset("minScore", "20", "Score 20+", "Puntaje 20+"),
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
internal fun hiddenFilterCount(q: Map<String, String>, screen: FeedScreen = FeedScreen.FEED): Int =
    ADVANCED_PARAMS.count { p ->
        // A parameter whose control this screen does not render cannot be counted: the badge means
        // "something you cannot see is narrowing this", and offering to reveal a control that is
        // not there would be a dead end. Favorites pins `state` server-side.
        if (p == "state" && !screen.showState) return@count false
        val v = q[p].orEmpty()
        v.isNotBlank() && FILTER_CHIPS.none { it.param == p && it.value == v }
    }

/**
 * The scope row: which slice of the feed is being looked at.
 *
 * A scope is not a filter, and the distinction is why this is its own control above the filter bar
 * rather than a fourth chip in [FILTER_CHIPS]. A chip narrows *within* what you are looking at and
 * composes with the others; scope decides what the set is in the first place, and the two scopes are
 * mutually exclusive. Putting "Favorites" among the chips would also have implied it could be
 * combined with `state=DISMISSED`, which [favoritesFilter] pins server-side precisely to prevent.
 *
 * The current filters ride along in the links, so switching scope keeps your search and sort. Only
 * params this screen actually renders are carried — `state` is dropped, since favorites pins it and
 * a carried value would be silently ignored.
 *
 * Deliberately anchors rather than form buttons: these are two addressable pages (`/` and
 * `/favorites`), both bookmarkable, and an anchor is what the browser's back button expects.
 */
fun MAIN.feedScopes(q: Map<String, String>, screen: FeedScreen, favoriteCount: Int) {
    // Rebuilt rather than passed through wholesale: `state` is meaningless on favorites, and an
    // empty value would add `?state=` noise to every link.
    val carried = q.filterKeys { it != "state" }
        .filterValues { it.isNotBlank() }
        .map { (k, v) -> "$k=${v.encodeURLParameter()}" }
        .joinToString("&")
    val suffix = if (carried.isEmpty()) "" else "?$carried"

    div("scopes") {
        // aria-current is what tells a screen reader which of the two is active; the visual
        // treatment alone conveys nothing.
        a(href = "/$suffix", classes = if (screen == FeedScreen.FEED) "scope on" else "scope") {
            if (screen == FeedScreen.FEED) attributes["aria-current"] = "page"
            +tr("All deals", "Todas")
        }
        a(
            href = "/favorites$suffix",
            classes = if (screen == FeedScreen.FAVORITES) "scope on" else "scope",
        ) {
            if (screen == FeedScreen.FAVORITES) attributes["aria-current"] = "page"
            +tr("Favorites", "Favoritos")
            // Shown only when there are some. A "Favorites 0" badge reads as a broken counter,
            // while a bare label reads as an invitation -- and zero is the starting state.
            if (favoriteCount > 0) span("scope-count") { +"$favoriteCount" }
        }
    }
}

/** One on-by-default checkbox of the filter form's toggle row; see the comment where it is used. */
private fun FlowContent.checkToggle(name: String, on: Boolean, text: String, hint: String) {
    label("check-toggle") {
        title = hint
        input(type = InputType.checkBox, name = name) {
            value = "1"
            checked = on
            attributes["onchange"] = "this.form.requestSubmit ? this.form.requestSubmit() : this.form.submit()"
        }
        input(type = InputType.hidden, name = name) { value = "0" }
        +text
    }
}

fun MAIN.filterForm(
    sources: List<String>,
    categories: List<String>,
    q: Map<String, String>,
    screen: FeedScreen = FeedScreen.FEED,
) {
    form(classes = "filters") {
        attributes["method"] = "get"
        attributes["action"] = screen.action

        label {
            +tr("Search", "Buscar")
            input(type = InputType.text, name = "q") {
                value = q["q"].orEmpty()
                // Discoverability: the pipe syntax is invisible otherwise.
                placeholder = "airpods | airtag | !refurbished"
                title = tr(
                    "Separate terms with | to match any of them; start a term with ! to exclude it (!refurbished)",
                    "Separa términos con | para buscar cualquiera; empieza un término con ! para excluirlo (!refurbished)",
                )
                attributes["size"] = "24"
            }
        }
        label {
            +tr("Source", "Fuente")
            select {
                name = "source"
                option {
                    value = ""
                    if (q["source"].isNullOrBlank()) selected = true
                    +tr("all", "todas")
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
            +tr("Category", "Categoría")
            select {
                name = "category"
                option {
                    value = ""
                    if (q["category"].isNullOrBlank()) selected = true
                    +tr("all", "todas")
                }
                categories.forEach { c ->
                    option {
                        value = c
                        if (q["category"] == c) selected = true
                        +categoryLabel(c)
                    }
                }
            }
        }
        // Sort stays on the visible row: it reorders the feed rather than narrowing it, so
        // it does not belong behind a disclosure labelled "More filters".
        label {
            +tr("Sort", "Ordenar")
            select {
                name = "sort"
                listOf(
                    "relevance" to tr("relevance", "relevancia"),
                    "discount" to tr("discount", "descuento"),
                    "drop" to tr("biggest drop", "mayor caída"),
                    "score" to tr("community score", "puntaje de la comunidad"),
                    "newest" to tr("newest", "más recientes"),
                    "price" to tr("price", "precio"),
                ).forEach { (v, t) ->
                    option {
                        value = v
                        if (q["sort"] == v) selected = true
                        +t
                    }
                }
            }
        }

        button(classes = "primary") { type = ButtonType.submit; +tr("Apply", "Aplicar") }

        // Export and Refresh act on the feed rather than describing it, so they sit in a
        // right-hand utility group instead of competing with Apply at the end of the row.
        div("util") {
            // A submit button with `formaction`, not a link: it posts this form's live values
            // to the export route, so the download reflects the controls as they stand --
            // including edits not yet applied. A static href would export the previous filter.
            // Needs no JavaScript, and the form is already method=get.
            button {
                type = ButtonType.submit
                attributes["formaction"] = screen.exportAction
                title = when (screen) {
                    FeedScreen.FEED -> tr(
                        "Download the matching deals as an Excel file. " +
                            "With nothing filtered this is every active deal except dismissed ones.",
                        "Descarga las ofertas que coinciden como archivo de Excel. " +
                            "Sin filtros, son todas las ofertas activas excepto las ocultas.",
                    )
                    // Says so explicitly, because this export breaks the guarantee the feed's
                    // makes -- retired offers are included here, and the file's Export info
                    // sheet records that too.
                    FeedScreen.FAVORITES -> tr(
                        "Download your favorites as an Excel file, " +
                            "including any whose offer has since expired.",
                        "Descarga tus favoritos como archivo de Excel, " +
                            "incluidos los que ya vencieron.",
                    )
                }
                +tr("Export to Excel", "Exportar a Excel")
            }
            button {
                attributes["hx-post"] = "/refresh"
                attributes["hx-swap"] = "none"
                type = ButtonType.button
                +tr("Refresh now", "Actualizar ahora")
            }
        }

        div("fbreak") {}
        // Checkboxes rather than chips: plain on/off switches with nothing to clear. Both are ON by
        // default, so "off" has to be sent explicitly: the hidden `0` after each box is what an
        // unticked form submits (see `feedFilterFrom`, which reads the FIRST value, so the hidden
        // input must come after its box). Feed only -- see `favoritesFilter`. They submit on
        // change, like the chips do.
        if (screen == FeedScreen.FEED) {
            div("toggles") {
                checkToggle(
                    "hideWatched", q["hideWatched"] != "0",
                    tr("Hide watched items", "Ocultar items vigilados"),
                    tr(
                        "Hide deals that one of your watches already covers, paused ones included",
                        "Oculta las ofertas que ya cubre alguna de tus vigilancias, incluidas las pausadas",
                    ),
                )
                checkToggle(
                    "hideNoShip", q["hideNoShip"] != "0",
                    tr("Hide items that don't ship to Colombia", "Ocultar items que no envían a Colombia"),
                    tr(
                        "Hide deals verified as NOT shipping to Colombia. Unverified ones stay: " +
                            "unknown is not a no.",
                        "Oculta las ofertas verificadas como sin envío a Colombia. Las no verificadas " +
                            "se quedan: desconocido no es un no.",
                    ),
                )
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
                +tr("More filters", "Más filtros")
                val hidden = hiddenFilterCount(q, screen)
                if (hidden > 0) span("act-count") { +"$hidden" }
            }
            div("more-grid") {
                label {
                    +tr("Min discount %", "Descuento mín. %")
                    input(type = InputType.number, name = "minDiscount") {
                        value = q["minDiscount"].orEmpty(); min = "0"; max = "99"; placeholder = "0"
                        attributes["size"] = "4"
                    }
                }
                label {
                    +tr("Max price", "Precio máx.")
                    input(type = InputType.number, name = "maxPrice") {
                        value = q["maxPrice"].orEmpty()
                        attributes["size"] = "8"
                    }
                }
                label {
                    +tr("Min score", "Puntaje mín.")
                    input(type = InputType.number, name = "minScore") {
                        value = q["minScore"].orEmpty()
                        placeholder = tr("any", "cualquiera")
                        title = tr(
                            "Slickdeals community vote score. 0 or below means downvoted.",
                            "Puntaje de votos de la comunidad de Slickdeals. 0 o menos significa votos negativos.",
                        )
                        attributes["size"] = "4"
                    }
                }
                label {
                    +"Colombia"
                    select {
                        name = "co"
                        listOf(
                            "" to tr("any", "cualquiera"),
                            "YES" to tr("ships", "envía"),
                            "UNKNOWN" to tr("unverified", "sin verificar"),
                            "NO" to tr("no", "no"),
                        ).forEach { (v, t) ->
                            option {
                                value = v
                                if (q["co"] == v) selected = true
                                +t
                            }
                        }
                    }
                }
                // Omitted, not disabled, on a screen that pins the state: a disabled select submits
                // nothing, so it looks broken rather than inapplicable.
                if (screen.showState) {
                    label {
                        +tr("State", "Estado")
                        select {
                            name = "state"
                            listOf(
                                "" to tr("active", "activas"),
                                "NEW" to tr("new", "nuevas"),
                                "FAVORITE" to tr("favorite", "favoritas"),
                                "DISMISSED" to tr("dismissed", "ocultas"),
                            ).forEach { (v, t) ->
                                option {
                                    value = v
                                    if (q["state"] == v) selected = true
                                    +t
                                }
                            }
                        }
                    }
                }
                label {
                    +tr("Only", "Solo")
                    select {
                        name = "only"
                        listOf(
                            "" to tr("everything", "todo"),
                            "promoted" to tr("promoted", "destacadas"),
                            "lowest" to tr("at lowest ever", "en mínimo histórico"),
                        ).forEach { (v, t) ->
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

/**
 * [filtered] distinguishes "you have none of these" from "your filters exclude them all", which
 * matter differently on the favorites screen: the first needs telling where a favorite comes from,
 * the second needs telling that clearing the filters brings yours back. Conflating them told a
 * page with two favorites on it that it had none.
 */
fun MAIN.dealTable(
    deals: List<Deal>,
    screen: FeedScreen = FeedScreen.FEED,
    filtered: Boolean = false,
) {
    if (deals.isEmpty()) {
        div("empty") {
            when {
                screen == FeedScreen.FEED -> {
                    +tr("No deals match those filters. If the database is empty, run ", "Ninguna oferta coincide con esos filtros. Si la base de datos está vacía, ejecuta ")
                    code { +"./dt run --args=\"--fetch-once\"" }
                    +tr(" or press Refresh now.", " o pulsa Actualizar ahora.")
                }
                filtered -> {
                    +tr("None of your favorites match those filters. ", "Ninguno de tus favoritos coincide con esos filtros. ")
                    a(href = "/favorites") { +tr("Show all favorites", "Ver todos los favoritos") }
                    +"."
                }
                // Says where a favorite comes from, since this page has no control that creates one.
                // Names the scope control by its own visible label ("All deals") rather than "the
                // feed": both scopes are the feed now, so that phrasing no longer points anywhere.
                else -> {
                    +tr("No favorites yet. Press ", "Aún no tienes favoritos. Pulsa ")
                    code { +tr("☆ favorite", "☆ favorita") }
                    +tr(" on any row under ", " en cualquier fila de ")
                    a(href = "/") { +tr("All deals", "Todas") }
                    +tr(
                        " and it will appear here — and stay, even after the offer ends.",
                        " y aparecerá aquí, y se quedará incluso después de que la oferta termine.",
                    )
                }
            }
        }
        return
    }
    table("deals") {
        thead {
            tr {
                th(classes = "col-thumb") { attributes["aria-label"] = tr("image", "imagen") }
                th { +tr("Deal", "Oferta") }
                th(classes = "col-price") { +tr("Price", "Precio") }
                th(classes = "col-disc") { +tr("Off", "Dto.") }
                th(classes = "col-score") {
                    title = tr("Community vote score, where the source publishes one", "Puntaje de votos de la comunidad, cuando la fuente lo publica")
                    +tr("Score", "Puntaje")
                }
                th(classes = "col-rank") {
                    // Says plainly that the column is not strictly descending. The default sort
                    // adds a rotation offset while this column shows the true score, so without
                    // this note a row scoring 52.8 sitting below one scoring 50.9 reads as a bug.
                    title = tr(
                        "Composed relevance score — the ranking behind the default sort. " +
                            "Ordering adds a small rotation offset so comparable deals take turns near " +
                            "the top, which is why this column is not strictly descending. " +
                            "Hover a value for its breakdown.",
                        "Puntaje de relevancia compuesto: el ranking detrás del orden predeterminado. " +
                            "El orden suma una pequeña rotación para que ofertas comparables se turnen " +
                            "arriba, por eso esta columna no es estrictamente descendente. " +
                            "Pasa el cursor sobre un valor para ver su desglose.",
                    )
                    +tr("Rank", "Ranking")
                }
                th(classes = "col-sig") { +tr("Signals", "Señales") }
                th(classes = "col-co") { +"Colombia" }
                th(classes = "col-seen") { +tr("Seen", "Visto") }
                th(classes = "col-act") { +tr("Actions", "Acciones") }
            }
        }
        tbody {
            deals.forEach { dealRow(it, screen = screen) }
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

/** The score to one decimal — enough to distinguish adjacent rows without implying precision. */
internal fun formatRank(relevance: Double): String =
    BigDecimal(relevance).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

/**
 * Names the signals that fed a row's score, for the Rank column's tooltip.
 *
 * Deliberately **descriptive, not derivational**: it lists which inputs are present and what
 * they say, and never recomputes a weight. `DealScore` is the only place the arithmetic
 * lives, so a breakdown that re-derived the total could disagree with the number that
 * actually did the sorting — and a ranking explanation that lies is worse than none.
 *
 * This exists because the score is the one part of this feed that is a judgement rather than
 * a fact. Every other column reports something a source said; this one reports an opinion, so
 * it has to be possible to see what the opinion was based on.
 */
internal fun rankBreakdown(deal: Deal): String {
    val parts = buildList {
        deal.discountPercent?.let { add(tr("$it% off list", "$it% bajo el precio de lista")) }
        deal.dropFromPeak?.let {
            val m = formatMoney(it, deal.currency)
            add(tr("down $m from our recorded peak", "bajó $m desde el máximo registrado"))
        }
        deal.communityScore?.let { add(tr("community $it", "comunidad $it")) }
        deal.coShippingCost?.let { cost ->
            if (cost.signum() == 0) add(tr("free shipping", "envío gratis"))
            else {
                val m = formatMoney(cost, "USD")
                val share = deal.shippingCostPercent
                add(
                    tr(
                        "freight $m${share?.let { " ($it% of price)" } ?: ""}",
                        "flete $m${share?.let { " ($it% del precio)" } ?: ""}",
                    ),
                )
            }
        }
        if (deal.coShippingImportFree == false) add(tr("import charges apply", "aplican impuestos de importación"))
        if (deal.coShipping == CoShipping.NO) add(tr("does not ship to Colombia", "no envía a Colombia"))
    }
    val age = relativeAgo(deal.postedAt ?: deal.firstSeenAt)
    val rank = formatRank(deal.relevance)
    return if (parts.isEmpty()) {
        tr("Rank $rank — nothing but age to go on yet ($age)", "Ranking $rank — por ahora solo cuenta la antigüedad ($age)")
    } else {
        tr("Rank $rank from: ${parts.joinToString("; ")}; $age", "Ranking $rank por: ${parts.joinToString("; ")}; $age")
    }
}

private fun sourceDotClass(source: String): String = when (source) {
    "slickdeals", "exito", "carulla", "mercadolibre-co" -> "dot-$source"
    else -> "dot-other"
}

/**
 * Drives the margin heat gutter (see app.css). Deliberately the *same* tier function the
 * discount badge uses, so the bar and the number cannot disagree. A deal with no computable
 * discount gets no class and therefore no bar — a zero-height bar would read as "0% off"
 * rather than "not known".
 *
 * null rather than "" so kotlinx.html omits the attribute entirely — an empty class="" on
 * every discount-less row is 202 wasted attributes on a typical feed.
 */
private fun heatClass(deal: Deal): String? =
    deal.discountPercent?.let { "heat heat-s${discountTier(it)}" }

private fun TBODY.dealRow(deal: Deal, note: String? = null, screen: FeedScreen = FeedScreen.FEED) {
    tr(classes = heatClass(deal)) { dealRowBody(deal, note, screen) }
}

/**
 * The row's attributes and cells, separate from the `tr` that holds them.
 *
 * Split out so an HTMX swap can emit a bare `<tr>` — see [rowFragment]. The feed renders
 * this inside a `tbody`; the swap renders the identical markup with no wrapper at all, so
 * the two can never drift apart.
 */
private fun TR.dealRowBody(deal: Deal, note: String? = null, screen: FeedScreen = FeedScreen.FEED) {
    run {
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
                        +categoryLabel(group)
                    }
                }
                deal.asin?.let { span { +"ASIN $it" } }
            }
        }

        td(classes = "col-price") {
            cardLabel(tr("Price", "Precio"))
            div("price") { +formatMoney(deal.price, deal.currency) }
            deal.listPrice?.let { div("was") { +formatMoney(it, deal.currency) } }
        }

        td(classes = "col-disc") {
            cardLabel(tr("Off", "Dto."))
            val pct = deal.discountPercent
            if (pct != null) {
                span("disc disc-s${discountTier(pct)}") { +"−${pct.toPlainString()}%" }
            } else {
                // Null, not 0%: Slickdeals publishes no list price at all, so no
                // discount can be computed. The Score column carries the signal instead.
                span("no-disc") {
                    title = tr(
                        "This source publishes no list price, so no discount can be computed — see Score",
                        "Esta fuente no publica precio de lista, así que no se puede calcular un descuento; mira el Puntaje",
                    )
                    +"—"
                }
            }
        }

        // Deal-quality signal for sources that have no list price. Slickdeals' community
        // vote is the only evidence available there; a score of 0 or below means the
        // community actively voted it down.
        td(classes = "col-score") {
            cardLabel(tr("Score", "Puntaje"))
            val score = deal.communityScore
            if (score == null) {
                span("no-disc") { title = tr("This source publishes no community score", "Esta fuente no publica puntaje de la comunidad"); +"—" }
            } else {
                span("score score-${scoreTier(score)}") {
                    title = tr("Slickdeals community vote score", "Puntaje de votos de la comunidad de Slickdeals")
                    +(if (score > 0) "+$score" else "$score")
                }
            }
        }

        // The composed score that produced this row's position. Shown as a column rather
        // than a chip precisely because it IS on every row -- the Signals column next to it
        // stays sparse because a badge everywhere means nothing, whereas a ranking everywhere
        // is the point of a ranking. Without it the default order is unexplainable.
        td(classes = "col-rank") {
            cardLabel(tr("Rank", "Ranking"))
            span(if (deal.relevance < 0) "rank rank-neg" else "rank") {
                title = rankBreakdown(deal)
                +formatRank(deal.relevance)
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
                        val peak = deal.maxPriceEver?.let { formatMoney(it, deal.currency) }
                        title = tr("Cheapest we have recorded, down from $peak", "El más barato que hemos registrado, bajó desde $peak")
                        +tr("lowest yet", "mínimo histórico")
                    }
                } else if (drop != null) {
                    span("chip chip-drop") { +"↓ ${formatMoney(drop, deal.currency)}" }
                }
                // Time-bounded rather than state-based: `state` stays NEW until Luis acts
                // on a row, so a NEW-based badge ended up on every deal forever.
                if (Duration.between(deal.firstSeenAt, Instant.now()).toHours() < 24) {
                    span("chip chip-new") { +tr("new", "nueva") }
                }
                if (deal.state == DealState.FAVORITE) {
                    span("chip chip-fav") {
                        title = tr(
                            "One of your favorites. It stays on /favorites even after the " +
                                "offer expires.",
                            "Una de tus favoritas. Se queda en /favorites incluso después de que la oferta vence.",
                        )
                        +tr("★ favorite", "★ favorita")
                    }
                }
                // Only reachable on /favorites, which is the one screen that does not filter on
                // is_active. Says WHEN it was last seen rather than merely that it is gone: the
                // price history is intact, and how stale the last observation is decides whether
                // the number beside it still means anything.
                if (!deal.isActive) {
                    span("chip chip-expired") {
                        val ago = relativeAgo(deal.lastSeenAt)
                        title = tr(
                            "The source stopped returning this deal, so it was retired " +
                                "$ago. The price shown is the last one " +
                                "we recorded, not a current one.",
                            "La fuente dejó de mostrar esta oferta, así que se retiró $ago. " +
                                "El precio mostrado es el último que registramos, no uno actual.",
                        )
                        +tr("expired", "vencida")
                    }
                }
            }
        }

        td(classes = "col-co") {
            cardLabel("Colombia")
            coShippingCell(deal, note)
        }

        td(classes = "col-seen") {
            cardLabel(tr("Seen", "Visto"))
            // The source's own posting date when known, since that is what tells you the
            // deal is current -- lastSeenAt only records when *we* last polled.
            val posted = deal.postedAt
            if (posted != null) {
                div("seen") {
                    val ago = relativeAgo(posted)
                    title = tr("Posted by the source $ago", "Publicada por la fuente $ago")
                    +relativeOld(posted)
                }
            } else {
                div("seen") {
                    title = tr(
                        "This source publishes no posting date; showing when we last saw it",
                        "Esta fuente no publica fecha; se muestra cuándo la vimos por última vez",
                    )
                    val t = relativeTime(deal.lastSeenAt)
                    +tr("seen $t", "vista $t")
                }
            }
            div("seen") { +tr("${deal.observationCount} obs", "${deal.observationCount} obs.") }
        }

        td(classes = "col-act") {
            div("actions") {
                favoriteToggle(deal, screen)
                // Deliberately absent on a favorited row. `state` holds ONE value, so DISMISSED
                // would overwrite FAVORITE -- hiding would silently un-favorite, and the undo
                // lives behind a dropdown on another screen. Un-favorite first, and Hide returns.
                if (deal.state != DealState.FAVORITE) {
                    stateButton(deal.id, DealState.DISMISSED, tr("Hide", "Ocultar"))
                }
                a(href = "/deal/${deal.id}") { +tr("history", "historial") }
            }
        }
    }
}

/**
 * Amazon items are why this column exists: they may or may not reach Colombia, and v1
 * does not determine that automatically. Unverified is the only actionable state, so it
 * carries the warm colour and the inline buttons; resolved states stay quiet.
 */
private fun TD.coShippingCell(deal: Deal, note: String? = null) {
    div("co") {
        // Evidence as a tooltip, and an "auto" marker, so an automated verdict can be told
        // apart from one Luis made by hand -- and audited without re-fetching the page.
        val provenance = deal.coShippingEvidence?.takeIf { it.isNotBlank() }
            ?.let { "${deal.coShippingSource ?: "?"}: $it" }
        when (deal.coShipping) {
            CoShipping.YES -> span("co-yes") {
                provenance?.let { title = it }
                +tr("✓ ships", "✓ envía")
                if (deal.coShippingSource == "AUTO") span("prov") { +"auto" }
                // The shipping fee often decides whether a "deal" is still a deal: the same
                // YES verdict covers "FREE Shipping" and "$60.66 Shipping".
                deal.coShippingCost?.let { cost ->
                    val share = deal.shippingCostPercent
                    when {
                        cost.signum() == 0 -> span("prov") { +tr("free ship", "envío gratis") }
                        // A fee that is a large share of the item is not a footnote on a good
                        // deal, it is the reason it is not one -- so it states the proportion,
                        // not just the amount. $248.68 reads as merely expensive; "93% of
                        // price" reads as the answer.
                        deal.shippingCostDominates -> span("prov kill-prov") {
                            val m = formatMoney(cost, "USD")
                            title = tr(
                                "Amazon quoted $m to ship this — " +
                                    "$share% of the item's own price. It ships, but almost certainly " +
                                    "is not worth it.",
                                "Amazon cotizó $m por enviarlo, el $share% del precio del producto. " +
                                    "Sí envía, pero casi seguro no vale la pena.",
                            )
                            +tr("+$m ship · $share% of price", "+$m envío · $share% del precio")
                        }
                        else -> span("prov warn-prov") {
                            title = tr(
                                "Amazon quoted this international shipping fee" +
                                    (share?.let { " — $it% of the item's price" } ?: ""),
                                "Amazon cotizó este costo de envío internacional" +
                                    (share?.let { ", el $it% del precio del producto" } ?: ""),
                            )
                            +tr("+${formatMoney(cost, "USD")} ship", "+${formatMoney(cost, "USD")} envío")
                        }
                    }
                }
                // Import charges were previously only shown when they did NOT apply, which is
                // backwards -- charges make a deal worse, so that is the case to flag.
                when (deal.coShippingImportFree) {
                    true -> span("prov") { +tr("no import fees", "sin impuestos de importación") }
                    false -> span("prov warn-prov") { +tr("import fees", "impuestos de importación") }
                    null -> Unit
                }
            }
            CoShipping.NO -> span("co-no") {
                provenance?.let { title = it }
                +"✗ no"
                if (deal.coShippingSource == "AUTO") span("prov") { +"auto" }
            }
            CoShipping.UNKNOWN -> {
                span("co-unk") { +tr("? unverified", "? sin verificar") }
                div("actions") {
                    button {
                        attributes["hx-post"] = "/deal/${deal.id}/co-shipping?value=YES"
                        attributes["hx-target"] = "#deal-${deal.id}"
                        attributes["hx-swap"] = "outerHTML"
                        title = tr("Mark as shippable to Colombia", "Marcar como enviable a Colombia")
                        +tr("ships", "envía")
                    }
                    button {
                        attributes["hx-post"] = "/deal/${deal.id}/co-shipping?value=NO"
                        attributes["hx-target"] = "#deal-${deal.id}"
                        attributes["hx-swap"] = "outerHTML"
                        title = tr("Mark as not shippable", "Marcar como no enviable")
                        +"no"
                    }
                    // Only offered when there is an ASIN to look up. Amazon is reachable only
                    // by /dp/<ASIN> -- the stored URL is the Slickdeals thread, and the feed's
                    // outbound links are click? redirects with expiring tokens. Rendering the
                    // button without one would promise a check that cannot be performed.
                    if (!deal.asin.isNullOrBlank()) {
                        button(classes = "verify-btn") {
                            attributes["hx-post"] = "/deal/${deal.id}/verify"
                            attributes["hx-target"] = "#deal-${deal.id}"
                            attributes["hx-swap"] = "outerHTML"
                            // A real browser has to cold-start and Amazon's widgets are
                            // AJAX-filled, so this is tens of seconds, not milliseconds.
                            // Disable the whole row's buttons while it runs rather than
                            // letting a second click start a competing browser.
                            attributes["hx-disabled-elt"] = "closest .actions button"
                            attributes["hx-indicator"] = "#spin-${deal.id}"
                            title = tr(
                                "Open Amazon in a browser and read the delivery block for this ASIN",
                                "Abrir Amazon en un navegador y leer el bloque de entrega de este ASIN",
                            )
                            +tr("verify", "verificar")
                        }
                        span("htmx-indicator spinner") {
                            id = "spin-${deal.id}"
                            title = tr("Checking Amazon — this takes up to a minute", "Revisando Amazon, esto tarda hasta un minuto")
                            +tr("checking…", "revisando…")
                        }
                    }
                }
                // Outcome of a check that left the verdict UNKNOWN. Without this the row
                // swaps back looking untouched and the click appears to have done nothing.
                note?.let { div("co-note") { +it } }
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

/**
 * The favorite toggle: marks a deal, and un-marks it.
 *
 * ## Why un-favoriting writes SEEN rather than NEW
 *
 * `state` is single-valued, so coming off FAVORITE has to land on something, and NEW would be a
 * lie — you have plainly seen a row you once favorited. This is the first thing in the app to
 * write SEEN, which `DealScore` notes was in the enum but never set; the consequence is that a
 * previously-favorited row loses the feed's "new" chip, which is correct.
 *
 * ## Why un-favoriting posts somewhere different on each screen
 *
 * On the feed the row stays and is swapped in place, so `/deal/{id}/state` — which answers with a
 * feed-shaped row — is exactly right. On `/favorites` the row no longer belongs on the page at
 * all, so it posts to `/favorites/{id}/remove`, which answers empty and lets the `outerHTML` swap
 * delete it. Swapping in a still-rendered row there would leave it claiming to be a favorite on a
 * page that lists favorites — the same trap `POST /tracked/{id}/hide` exists to avoid.
 */
private fun DIV.favoriteToggle(deal: Deal, screen: FeedScreen) {
    val on = deal.state == DealState.FAVORITE
    val post = when {
        !on -> "/deal/${deal.id}/state?value=${DealState.FAVORITE.name}"
        screen == FeedScreen.FAVORITES -> "/favorites/${deal.id}/remove"
        else -> "/deal/${deal.id}/state?value=${DealState.SEEN.name}"
    }

    button(classes = if (on) "fav-btn on" else "fav-btn") {
        attributes["hx-post"] = post
        attributes["hx-target"] = "#deal-${deal.id}"
        attributes["hx-swap"] = "outerHTML"
        // So a screen reader hears a toggle rather than two unrelated buttons, matching the
        // filter chips' convention.
        attributes["aria-pressed"] = on.toString()
        title = if (on) {
            tr("Remove from favorites", "Quitar de favoritos") +
                if (screen == FeedScreen.FAVORITES) tr(" — the row leaves this page", ": la fila sale de esta página") else ""
        } else {
            tr(
                "Keep this deal on the favorites page, even after the offer ends",
                "Guardar esta oferta en favoritos, incluso después de que termine",
            )
        }
        // The glyph is filled or hollow AND the word changes, so the state never rests on colour.
        +if (on) tr("★ favorited", "★ en favoritos") else tr("☆ favorite", "☆ favorita")
    }
}

/**
 * One row as a bare `<tr>` fragment, for an HTMX `outerHTML` swap.
 *
 * This must NOT be a whole document. It used to respond via `respondHtml`, which emits
 * `<html><body><table><tbody><tr>` — and htmx, swapping that into a `<tr>` slot, takes the
 * body's children and drops an entire nested `<table>` into the row position. The nested
 * table brought its own column widths, so every action button silently wrecked the
 * alignment of the feed it was acting on. A fragment has no wrapper to leak.
 *
 * [note] carries the outcome of an action that did not change the verdict — an unclear
 * check, a block, a busy session. Without it those cases swap in a row that looks identical
 * to the one clicked, which reads as the button doing nothing.
 *
 * [screen] has to travel with the swap, not just with the initial render: a row swapped into
 * `/favorites` by a shipping check needs its favorite toggle still pointed at that page's own
 * remove route, or the next click would swap a row back in instead of removing it.
 */
fun rowFragment(deal: Deal, note: String? = null, screen: FeedScreen = FeedScreen.FEED): String {
    val consumer = createHTML(prettyPrint = false)
    TR(mapOf("class" to heatClass(deal)).filterValues { it != null }.mapValues { it.value!! }, consumer)
        .visit { dealRowBody(deal, note, screen) }
    return consumer.finalize()
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
        val firstSeen = relativeAgo(deal.firstSeenAt)
        span { +tr("first seen $firstSeen", "vista por primera vez $firstSeen") }
    }

    // The feed's thumbnail is sized for recognition while scanning; this page is where the
    // decision happens, so it shows the image at something like the size the sources
    // actually serve (Slickdeals 300px square, VTEX 1000px). Previously the detail page
    // carried no image at all -- you clicked through to judge a product and got no picture.
    deal.imageUrl?.let { src ->
        a(href = deal.url, target = "_blank", classes = "detail-img-link") {
            img(alt = "", src = src, classes = "detail-img")
        }
    }

    p {
        span("price") { +formatMoney(deal.price, deal.currency) }
        deal.listPrice?.let {
            +" "
            span("was") { +formatMoney(it, deal.currency) }
        }
        // The retailer's own advertised discount, written the way its page writes it, so the two can
        // be checked against each other. Still the retailer's claim -- see the Off column's notes.
        deal.storeDiscountPercent?.let {
            +" "
            span("disc disc-s${discountTier(BigDecimal(it))}") {
                title = tr(
                    "The store's advertised discount against its own list price, rounded down as its page shows it",
                    "El descuento que anuncia la tienda sobre su propio precio de lista, redondeado hacia abajo como lo muestra su página",
                )
                +"−$it%"
            }
        }
        deal.minPriceEver?.let {
            +" "
            val m = formatMoney(it, deal.currency)
            span("sub") { +tr("lowest recorded $m", "mínimo registrado $m") }
        }
    }

    if (history.size < 2) {
        p("sub") {
            val note = tr(
                "Only ${history.size} observation so far — price history builds up as " +
                    "the hourly poll runs, and is what lets you tell a real discount from an " +
                    "inflated list price.",
                "Solo ${history.size} observación hasta ahora. El historial de precios se construye " +
                    "con la consulta de cada hora, y es lo que permite distinguir un descuento real de " +
                    "un precio de lista inflado.",
            )
            +note
        }
    } else {
        sparkline(history)
        table("history") {
            thead { tr { th { +tr("When", "Cuándo") }; th { +tr("Price", "Precio") }; th { +tr("List price", "Precio de lista") } } }
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
    val lo = formatMoney(min, currency)
    val hi = formatMoney(max, currency)
    val label = tr(
        "price history, ${history.size} observations, low $lo, high $hi",
        "historial de precios, ${history.size} observaciones, mínimo $lo, máximo $hi",
    )

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
        +tr("range $lo – $hi", "rango $lo – $hi")
    }
}

fun MAIN.recentRuns(runs: List<Map<String, Any?>>) {
    div("runs") {
        h2 { +tr("Recent fetch runs", "Consultas recientes") }
        table {
            thead {
                tr {
                    th { +tr("Source", "Fuente") }; th { +tr("Started", "Inicio") }; th { +tr("Items", "Productos") }
                    th { +tr("New", "Nuevos") }; th { +tr("Status", "Estado") }
                }
            }
            tbody {
                if (runs.isEmpty()) tr { td { colSpan = "5"; +tr("no runs yet", "aún no hay consultas") } }
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
                                run["finishedAt"] == null -> +tr("running…", "en curso…")
                                else -> +"ok"
                            }
                        }
                    }
                }
            }
        }
    }
}
