package com.luis.dealstracker.web

import com.luis.dealstracker.ask.AskService
import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.FeedFilter
import com.luis.dealstracker.db.GroceryFilter
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.db.WatchDraft
import com.luis.dealstracker.db.ManualItemRepository
import com.luis.dealstracker.db.WatchRepository
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.ingest.ExchangeRates
import com.luis.dealstracker.ingest.IngestService
import com.luis.dealstracker.ingest.ManualItemService
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.verify.AmazonVerifier
import com.luis.dealstracker.verify.VerifyOutcome
import com.luis.dealstracker.verify.VerifySlot
import com.luis.dealstracker.verifyConfig
import com.luis.dealstracker.i18n.tr
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.strong
import io.ktor.server.http.content.staticResources
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun Application.routes(
    repo: DealRepository,
    watches: WatchRepository,
    ingest: IngestService,
    manual: ManualItemService,
    manualItems: ManualItemRepository,
    app: AppConfig,
    categorizer: Categorizer,
    scope: CoroutineScope,
    ask: AskService,
    rates: ExchangeRates,
    groceries: GroceryRepository,
    disabledSearches: com.luis.dealstracker.db.DisabledSearchRepository,
) {
    routing {
        askRoutes(ask, repo, watches, manualItems, app, rates, groceries)
        languageRoute()

        // Serves src/main/resources/static/*. Keeping the CSS on disk rather than in a
        // Kotlin constant means style edits need only a browser refresh.
        staticResources("/static", "static")

        get("/") {
            val params = call.request.queryParameters
            val q = FILTER_PARAMS.associateWith { params[it].orEmpty() } + ("source" to feedSourceParam(params, app))
            val filter = feedScreenFilter(params, app)

            val page = repo.feedPage(filter)
            val sources = sourceNames(repo, app)
            // For the scope row's badge. Unscoped by the current filters on purpose: it answers "how
            // many favorites do I have", not "how many match what I am currently searching for" -- a
            // count that fell to 0 because of an unrelated search would read as having lost them.
            val favoriteCount = repo.countMatching(favoritesFilter(Parameters.Empty))

            call.respondPage {
                page("Deals Tracker") {
                    feedScopes(q, FeedScreen.FEED, favoriteCount)
                    filterForm(sources, categorizer.categoryNames, q)
                    p("result-count") {
                        // State the total explicitly when the page is capped -- showing
                        // only the page size reads as a total and hides that rows exist.
                        if (page.truncated) {
                            +tr("showing ", "mostrando ")
                            strong { +"${page.deals.size}" }
                            +tr(" of ", " de ")
                            strong { +"${page.totalMatching}" }
                            +tr(" matching deals", " ofertas que coinciden")
                        } else {
                            strong { +"${page.totalMatching}" }
                            +tr(" deals", " ofertas")
                        }
                    }
                    askPanel(
                        "feed",
                        tr(
                            "e.g. which of these are real discounts, not inflated list prices?",
                            "p. ej. ¿cuáles de estos son descuentos reales y no precios de lista inflados?",
                        ),
                    )
                    dealTable(page.deals)
                    recentRuns(repo.recentRuns())
                }
            }
        }

        /**
         * Excel export of the current filter selection.
         *
         * Reached by the Export button, which is a submit button on the same GET filter
         * form with `formaction` pointed here — so it receives byte-identical query
         * parameters to the page and reuses [feedFilterFrom]. The filter shown and the
         * filter exported cannot disagree.
         *
         * The display limit is deliberately raised: see DealsExport.
         */
        get("/export.xlsx") {
            val filter = feedScreenFilter(call.request.queryParameters, app)
            val zone = ZoneId.systemDefault()
            val lang = call.lang

            // Both the queries and the workbook build are blocking, and an export can be
            // far larger than a rendered page, so keep all of it off the event loop.
            val (bytes, meta) = withContext(Dispatchers.IO) {
                val totalMatching = repo.countMatching(filter)
                val deals = repo.feed(filter.copy(limit = DealsExport.MAX_ROWS))
                val meta = DealsExport.Meta(Instant.now(), filter, totalMatching, deals.size)
                DealsExport.build(deals, meta, zone, lang) to meta
            }

            // Counted in the log as well as in the file, matching how ingest reports
            // exclusions -- a short export should never be silent.
            println(
                "[export] ${meta.rowsExported} row(s) of ${meta.totalMatching} matching" +
                    (if (meta.truncated) " -- TRUNCATED at ${DealsExport.MAX_ROWS}" else ""),
            )

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, DealsExport.filename(LocalDate.now(zone)))
                    .toString(),
            )
            call.respondBytes(bytes, ContentType.parse(DealsExport.CONTENT_TYPE))
        }

        /**
         * The favorites screen: the rows Luis marked, and only those.
         *
         * Reuses the feed's own [filterForm] and [dealTable] rather than a bespoke table — favorites
         * IS the feed restricted to chosen rows, so search, sort, the chip presets and the Excel
         * export all work here for free, and a second presentation would be a second thing to keep
         * in step. [FeedScreen] carries the three things that differ.
         *
         * `recentRuns` is deliberately omitted: fetch bookkeeping is about whether the poller is
         * healthy, which belongs on the feed and says nothing about a chosen row.
         */
        get("/favorites") {
            val params = call.request.queryParameters
            val q = FILTER_PARAMS.associateWith { params[it].orEmpty() }
            val filter = favoritesFilter(params)

            val page = repo.feedPage(filter)
            val sources = sourceNames(repo, app)

            call.respondPage {
                page(tr("Favorites — Deals Tracker", "Favoritos — Deals Tracker")) {
                    // `page.totalMatching` is this scope's own unfiltered-by-nothing total only when
                    // no filter is applied, so the badge reuses the same unscoped count the feed
                    // computes rather than the possibly-narrowed number shown below.
                    feedScopes(q, FeedScreen.FAVORITES, repo.countMatching(favoritesFilter(Parameters.Empty)))
                    filterForm(sources, categorizer.categoryNames, q, FeedScreen.FAVORITES)
                    p("result-count") {
                        if (page.truncated) {
                            +tr("showing ", "mostrando ")
                            strong { +"${page.deals.size}" }
                            +tr(" of ", " de ")
                            strong { +"${page.totalMatching}" }
                            +tr(" favorites", " favoritos")
                        } else {
                            strong { +"${page.totalMatching}" }
                            +tr(" favorite(s)", " favorito(s)")
                        }
                        // Stated up front rather than left to be inferred from the chips, because
                        // this page breaks the rule every other view follows: a retired deal is
                        // shown, and its price is the last one recorded rather than a live one.
                        val expired = page.deals.count { !it.isActive }
                        if (expired > 0) {
                            span("tracked-note") { +tr(" · $expired whose offer has since expired", " · $expired cuya oferta ya venció") }
                        }
                    }
                    askPanel(
                        "favorites",
                        tr("e.g. which of my favorites is the best buy right now?", "p. ej. ¿cuál de mis favoritos es la mejor compra ahora mismo?"),
                    )
                    // Whether a control is NARROWING the page, which is what changes the meaning of
                    // an empty one. `sort` only reorders and `state` is pinned by favoritesFilter,
                    // so neither can be why a favorite is missing.
                    dealTable(
                        page.deals,
                        FeedScreen.FAVORITES,
                        filtered = q.any { (k, v) -> k !in NON_NARROWING_PARAMS && v.isNotBlank() },
                    )
                }
            }
        }

        /** As `/export.xlsx`, scoped to favorites. See [favoritesFilter]. */
        get("/favorites/export.xlsx") {
            val filter = favoritesFilter(call.request.queryParameters)
            val zone = ZoneId.systemDefault()
            val lang = call.lang

            val (bytes, meta) = withContext(Dispatchers.IO) {
                val totalMatching = repo.countMatching(filter)
                val deals = repo.feed(filter.copy(limit = DealsExport.MAX_ROWS))
                val meta = DealsExport.Meta(Instant.now(), filter, totalMatching, deals.size)
                DealsExport.build(deals, meta, zone, lang) to meta
            }

            println("[export] ${meta.rowsExported} favorite(s) of ${meta.totalMatching} matching")

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(
                        ContentDisposition.Parameters.FileName,
                        "favorites-${LocalDate.now(zone)}.xlsx",
                    )
                    .toString(),
            )
            call.respondBytes(bytes, ContentType.parse(DealsExport.CONTENT_TYPE))
        }

        /**
         * Un-favorites a deal from the favorites screen itself.
         *
         * Distinct from `POST /deal/{id}/state` — which performs the same update — because that one
         * answers with a rendered row. Swapped into this page it would leave a row that no longer
         * belongs here still sitting among the favorites, which is the "stale row claiming to be
         * tracked" problem `POST /tracked/{id}/hide` exists to avoid. An empty body plus htmx's
         * `outerHTML` swap removes it instead.
         *
         * Writes SEEN rather than NEW: you have plainly seen a row you once favorited. See
         * `favoriteToggle`.
         */
        post("/favorites/{id}/remove") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)

            withContext(Dispatchers.IO) { repo.updateState(id, DealState.SEEN) }
            call.respondText("", ContentType.Text.Html)
        }

        /**
         * The tracked-items tab.
         *
         * Reads the same rows through the same `feed` query as the page at `/`, filtered to the
         * providers whose job is tracking named items rather than surfacing offers, and sorted
         * cheapest-first because that is the question being asked here.
         *
         * `onlyPromoted` is deliberately left off: a tracked item is interesting because you
         * chose it, not because it cleared a discount threshold, and these rows carry no list
         * price for a threshold to be computed from in the first place.
         */
        get("/tracked") {
            val sources = trackingSources(app)
            val deals = withContext(Dispatchers.IO) { trackedDeals(repo, sources) }
            // Unknown labels are ignored rather than rejected, so a hand-edited or stale URL
            // degrades to "no filter" instead of a 400.
            val selected = call.request.queryParameters["type"]
                ?.split(',')
                ?.mapNotNull { label ->
                    ProductType.entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
                }
                ?.toSet()
                .orEmpty()

            // Unknown values fall back to the default rather than erroring, same as `type`.
            val sort = TrackedSort.of(call.request.queryParameters["sort"])
            // A brand that matches nothing simply shows an empty result with a way back, rather
            // than erroring -- same tolerance as `type` and `sort`.
            val brand = call.request.queryParameters["brand"]?.trim()?.takeIf { it.isNotEmpty() }

            val notice = call.request.queryParameters["notice"]?.trim()?.takeIf { it.isNotEmpty() }
            // Unknown values fall back to "no filter", same tolerance as type/sort/brand.
            val origin = TrackedOrigin.of(call.request.queryParameters["origin"])
            val manualAsins = withContext(Dispatchers.IO) { manualItems.trackedAsins() }
            // Items Amazon refuses to ship to Colombia are hidden unless this is set.
            val showNoShip = call.request.queryParameters["noship"] == "show"
            val searchToggles = withContext(Dispatchers.IO) {
                app.enabledProviders.filter { it.id in sources }.flatMap { p ->
                    val off = disabledSearches.disabled(p.id)
                    p.list("trackSearches").map { Triple(p.id, it, it !in off) }
                }
            }
            val visible = withoutSwitchedOffSearches(deals, searchToggles, manualAsins)

            call.respondPage {
                page(tr("Tracked — Deals Tracker", "Seguimiento — Deals Tracker")) {
                    askPanel(
                        "tracked",
                        tr(
                            "e.g. which of these has dropped the most, and is it worth buying now?",
                            "p. ej. ¿cuál de estos ha bajado más, y vale la pena comprarlo ahora?",
                        ),
                    )
                    trackedPage(visible, sources, selected, sort, brand, notice, manualAsins, origin, showNoShip, searchToggles = searchToggles, searchesOpen = call.request.queryParameters["searches"] == "open", freeShip = call.request.queryParameters["freeship"] == "1")
                }
            }
        }

        /**
         * Hides a tracked item: a soft delete via the existing `DISMISSED` state.
         *
         * Deliberately NOT a row DELETE. Three reasons, in order of how much they would hurt:
         *
         *  - **It would come straight back.** The next poll re-fetches the same search, and
         *    `upsertDeal` is keyed on `(source, source_deal_id)` — a deleted row is simply inserted
         *    again, as brand new. Dismissing survives because `upsertDeal` deliberately never
         *    touches `state` on conflict: "those are Luis's decisions".
         *  - It would take the item's `price_observations` with it (ON DELETE CASCADE), discarding
         *    history that is the whole point of the tab and cannot be recovered.
         *  - It is reversible. The feed's State filter can show dismissed rows, so a mis-click is
         *    not destructive.
         *
         * Distinct from `POST /deal/{id}/state` — which exists and does the same update — because
         * that one responds with a *feed*-shaped row fragment. Swapped into the tracked table it
         * would inject the wrong columns. Here the row is simply removed from the DOM, which is
         * also the honest thing to show: the item no longer belongs on this page.
         */
        /**
         * Adds an item by URL and fetches it immediately.
         *
         * Synchronous on purpose. The add costs a live Amazon request that can take seconds, and the
         * operator needs to know whether it worked — a fire-and-forget would report success for a URL
         * that names no product. The result is carried back as a `notice` on the redirect target, so a
         * reload does not re-submit.
         */
        post("/tracked/search") {
            val params = call.receiveParameters()
            val enabled = params["enabled"] == "true"
            // Each value is "provider<TAB>term"; a group switch sends several. Only a term that is
            // actually configured can be toggled, so this cannot grow junk rows.
            val picks = params.getAll("search").orEmpty().mapNotNull { v ->
                val (provider, term) = v.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                (provider to term).takeIf { app.enabledProviders.any { p -> p.id == provider && term in p.list("trackSearches") } }
            }
            if (picks.isEmpty()) return@post call.respondText("unknown search", status = HttpStatusCode.BadRequest)
            withContext(Dispatchers.IO) { picks.forEach { (p, t) -> disabledSearches.setEnabled(p, t, enabled) } }
            call.respondRedirect("/tracked?searches=open#searches")
        }

        post("/tracked/add") {
            val params = call.receiveParameters()
            val url = params["url"]?.trim().orEmpty()
            if (url.isEmpty()) {
                val msg = call.lang.tr("paste a URL first", "primero pega una URL")
                return@post call.respondRedirect("/tracked?notice=" + java.net.URLEncoder.encode(msg, "UTF-8"))
            }

            val outcome = manual.add(
                url = url,
                brand = params["brand"]?.trim()?.takeIf { it.isNotEmpty() },
                productType = params["productType"]?.trim()?.takeIf { it.isNotEmpty() },
                lang = call.lang,
            )

            call.respondRedirect("/tracked?notice=" + java.net.URLEncoder.encode(outcome.message, "UTF-8"))
        }

        /**
         * Exito and Carulla's grocery aisles at Laureles -- see [groceryPage].
         *
         * Every row is loaded and the view filtered in Kotlin, as `/tracked` does: the deal rule is a
         * property of [com.luis.dealstracker.db.GroceryRow], and a second copy of it in SQL would be
         * the drift `DealScore` exists to prevent. The subcategory filter IS pushed to SQL, since it
         * is a plain column and the one that makes a full-catalogue view small.
         */
        get("/groceries") {
            val stores = groceryStores(app)
            val controls = GroceryControls.from(call.request.queryParameters)
            // The store filter narrows the sources read, so the aisle counts, the pinned badge and the
            // rows all describe the chosen stores. The sync footer keeps every store: it is about the
            // crawls, not the page's selection.
            val allSources = grocerySources(app)
            val sources = sourcesForChains(stores, controls.chains)
            val chainCounts = withContext(Dispatchers.IO) {
                val counts = groceries.chainProductCounts(stores.mapValues { it.value.chain })
                chainNames(stores).map { (chain, name) -> Triple(chain, name, counts[chain] ?: 0) }
                    .sortedByDescending { it.third }
            }
            val (rows, groups, pinnedCount) = withContext(Dispatchers.IO) {
                // The Pinned view ignores the aisle filter and search: it is the whole shopping list.
                val filter = if (controls.view == GroceryView.PINNED) {
                    GroceryFilter(sources, pinnedOnly = true)
                } else {
                    GroceryFilter(sources, controls.groups.toList(), controls.search)
                }
                Triple(mergeStores(groceries.rows(filter), stores), groceries.groups(sources), groceries.pinnedCount(sources))
            }
            val (sync, storage) = withContext(Dispatchers.IO) {
                grocerySyncLines(app, groceries.syncStatus(allSources)) to runCatching { groceries.storage() }.getOrNull()
            }
            call.respondPage {
                page(tr("Groceries — Deals Tracker", "Mercado — Deals Tracker")) {
                    askPanel(
                        "groceries",
                        tr(
                            "e.g. what meat is well under its usual price this week?",
                            "p. ej. ¿qué carne está bastante por debajo de su precio habitual esta semana?",
                        ),
                    )
                    groceryPage(rows, groups, controls, allSources, pinnedCount, sync, storage, chainCounts)
                }
            }
        }

        // Same shape as the feed's favorite toggle: on Deals and All items the row is swapped for itself
        // with the new pin state; on Pinned an unpin answers with an empty body, so htmx's outerHTML swap
        // removes the row -- leaving it there would put an unpinned item on the Pinned list.
        post("/groceries/{id}/{action}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val pinned = when (call.parameters["action"]) {
                "pin" -> true
                "unpin" -> false
                else -> return@post call.respondText("unknown action", status = HttpStatusCode.NotFound)
            }
            val view = GroceryView.of(call.request.queryParameters["view"])
            val sources = grocerySources(app)
            val row = withContext(Dispatchers.IO) {
                if (groceries.setPinned(id, pinned, sources).isEmpty()) null
                // Re-merged, so the swapped row still shows the cheaper store and the other one's price.
                else mergeStores(groceries.rows(GroceryFilter(sources, id = id)), groceryStores(app)).firstOrNull()
            } ?: return@post call.respondText("no such item", status = HttpStatusCode.NotFound)
            call.respondFragment { if (view == GroceryView.PINNED && !pinned) "" else groceryRowFragment(row, view) }
        }

        post("/tracked/{id}/hide") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)

            // `also` carries the variants folded into this row (see collapseVariants), which must go
            // with it. Non-numeric entries are dropped rather than failing the whole hide.
            val also = call.request.queryParameters["also"]
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
            withContext(Dispatchers.IO) { (listOf(id) + also).forEach { repo.updateState(it, DealState.DISMISSED) } }

            // Empty body + an outerHTML swap removes the <tr>. No reload, and no stale row left
            // claiming to be tracked.
            call.respondText("", ContentType.Text.Html)
        }

        get("/deal/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val deal = repo.findById(id)
                ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            call.respondPage { page(deal.title) { dealDetail(deal, repo.priceHistory(id)) } }
        }

        post("/deal/{id}/state") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val state = call.request.queryParameters["value"]
                ?.let { runCatching { DealState.valueOf(it) }.getOrNull() }
                ?: return@post call.respondText("bad state", status = HttpStatusCode.BadRequest)

            repo.updateState(id, state)
            // A dismissed row is not on the page after a reload, so it must not be after the click
            // either. Re-rendering it looked like Hide had done nothing: the row has no dismissed
            // styling, so it stayed put until the next reload. Empty body + the button's outerHTML
            // swap removes the <tr>, the same way /tracked/{id}/hide does.
            if (state == DealState.DISMISSED) return@post call.respondText("", ContentType.Text.Html)
            respondRow(id, repo)
        }

        post("/deal/{id}/co-shipping") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val value = call.request.queryParameters["value"]
                ?.let { runCatching { CoShipping.valueOf(it) }.getOrNull() }
                ?: return@post call.respondText("bad value", status = HttpStatusCode.BadRequest)

            repo.updateCoShipping(id, value)
            respondRow(id, repo)
        }

        /**
         * Runs the real browser check for one deal.
         *
         * Synchronous on purpose, unlike `/refresh`: the click is a question and the answer
         * belongs in the row that asked it. That costs a request held open for tens of
         * seconds — a browser cold start plus Amazon's AJAX-filled delivery block — which is
         * why the button disables itself and shows an indicator while it waits.
         */
        post("/deal/{id}/verify") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)

            // Checked before launching anything: a deal that is already decided, MANUAL, or
            // has no ASIN must not cost a browser launch to find that out.
            if (repo.coShippingCandidate(id) == null) {
                return@post respondRow(
                    id, repo,
                    call.lang.tr(
                        "nothing to check — already decided, your own verdict, or no ASIN",
                        "nada que revisar: ya está decidido, lo marcaste tú, o no tiene ASIN",
                    ),
                )
            }

            // Bounded by the shorter poll wall-wait rather than the CLI's 180s. Someone is
            // watching a spinner, and an HTTP request should not hang for three minutes.
            val cfg = verifyConfig(
                app,
                batchSize = 1,
                wallWaitMillis = app.defaults.coShippingPollWallWaitMillis,
            )

            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    VerifySlot.tryWith(
                        VerifySlot.lockFileFor(cfg.profileDir),
                        onBusy = { BUSY },
                    ) { AmazonVerifier(repo, cfg).run(only = id) }
                }.getOrElse { e ->
                    println("[verify] click failed for deal $id: ${e.message}")
                    null
                }
            }

            // Every branch that leaves the verdict UNKNOWN needs words, or the row swaps
            // back looking untouched and the button appears broken.
            val l = call.lang
            val wallSeconds = app.defaults.coShippingPollWallWaitMillis / 1000
            val note = when {
                outcome == null -> l.tr("the check failed to run — see the app log", "la revisión no pudo ejecutarse; mira el log de la app")
                outcome === BUSY -> l.tr(
                    "a verification is already running — try again in a minute",
                    "ya hay una verificación en curso; intenta de nuevo en un minuto",
                )
                // Actionable rather than merely accurate. The wall is a button in a Chrome
                // window that is open right now, but you are looking at this page instead —
                // so "a human check was shown" alone leaves you with nowhere to go.
                outcome.blocked -> l.tr(
                    "Amazon asked for a human check. Clear it in the Chrome window that opened, " +
                        "then click verify again — the window closes after ${wallSeconds}s.",
                    "Amazon pidió una verificación humana. Resuélvela en la ventana de Chrome que se abrió " +
                        "y vuelve a pulsar verificar; la ventana se cierra después de ${wallSeconds}s.",
                )
                outcome.unclear > 0 -> l.tr("the page did not state it either way", "la página no lo indicaba en ningún sentido")
                outcome.checked == 0 -> l.tr("nothing to check", "nada que revisar")
                else -> null
            }
            respondRow(id, repo, note)
        }

        // --- watches ---

        get("/watches") {
            val all = watches.all()
            val counts = watches.matchCounts()
            call.respondPage {
                page(tr("Watches", "Búsquedas")) {
                    watchesPage(all, counts, sourceNames(repo, app), categorizer.categoryNames)
                }
            }
        }

        get("/watches/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val watch = watches.findById(id)
                ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)

            val fx = rates.current()
            val (candidates, matches, dealsById) = withContext(Dispatchers.IO) {
                val candidates = watches.candidates(watch, fx)
                val matches = watches.matches(id)
                // Titles for the alert list. Looked up in one pass rather than per row.
                val deals = matches.mapNotNull { repo.findById(it.dealId) }.associateBy { it.id }
                Triple(candidates, matches, deals)
            }

            // Both tables sort independently, so each has its own parameter. An unknown column
            // falls back to the default rather than erroring, so a stale link still renders.
            val params = call.request.queryParameters
            val matchingSort = watchSortFrom(params, WatchSort.MATCHING_PARAM)
            val alertsSort = watchSortFrom(params, WatchSort.ALERTS_PARAM)
            val matchingPage = watchPageFrom(params)
            val matchingSource = watchSourceFrom(params)

            call.respondPage {
                page(watch.name) {
                    askPanel(
                        "watch:${watch.id}",
                        tr(
                            "e.g. which is the best option for what I'm looking for, and why?",
                            "p. ej. ¿cuál es la mejor opción para lo que busco, y por qué?",
                        ),
                    )
                    watchDetail(
                        watch, candidates, matches, dealsById,
                        sourceNames(repo, app), categorizer.categoryNames,
                        matchingSort, alertsSort, matchingPage, fx, matchingSource,
                    )
                }
            }
        }

        post("/watches") {
            val draft = watchDraftFrom(call.receiveParameters())
                ?: return@post call.respondText("a watch needs a name and terms", status = HttpStatusCode.BadRequest)
            watches.create(draft)
            call.respondRedirect("/watches")
        }

        post("/watches/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val draft = watchDraftFrom(call.receiveParameters())
                ?: return@post call.respondText("a watch needs a name and terms", status = HttpStatusCode.BadRequest)
            if (!watches.update(id, draft)) {
                return@post call.respondText("not found", status = HttpStatusCode.NotFound)
            }
            call.respondRedirect("/watches/$id")
        }

        post("/watches/{id}/enabled") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val value = call.request.queryParameters["value"]?.toBooleanStrictOrNull()
                ?: return@post call.respondText("bad value", status = HttpStatusCode.BadRequest)

            watches.setEnabled(id, value)
            val updated = watches.findById(id)
                ?: return@post call.respondText("", status = HttpStatusCode.OK)
            call.respondFragment { watchRowFragment(updated, watches.matchCounts()[id]) }
        }

        post("/watches/{id}/delete") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            watches.delete(id)
            // Empty body with an outerHTML swap removes the row, the same convention
            // respondRow uses when a deal vanishes mid-request.
            call.respondText("", ContentType.Text.Html)
        }

        post("/refresh") {
            // Fire and forget: a full poll takes far longer than a request should.
            scope.launch(Dispatchers.IO) {
                runCatching { ingest.runAll() }
                    .onFailure { println("[refresh] failed: ${it.message}") }
            }
            call.respondText("started")
        }

        get("/health") {
            call.respondText("ok")
        }
    }
}

private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.respondRow(
    id: Long,
    repo: DealRepository,
    note: String? = null,
) {
    val updated = repo.findById(id)
    if (updated == null) {
        // Row vanished (e.g. deactivated mid-request); tell HTMX to remove it.
        call.respondText("", status = HttpStatusCode.OK)
    } else {
        call.respondFragment { rowFragment(updated, note) }
    }
}

/**
 * Sources for a dropdown: config plus whatever is already stored, so a provider added to
 * sources.yaml appears immediately, and one removed from it still lists its stored deals.
 */
private fun sourceNames(repo: DealRepository, app: AppConfig): List<String> =
    (app.providers.map { it.id } + repo.knownSources()).distinct().sorted()

/**
 * Providers whose rows belong on the tracked tab.
 *
 * Derived from the provider's declared intent rather than hardcoding `"amazon"`: a tracking
 * provider is one configured with `trackSearches`, the key that means "watch these specific
 * items" as opposed to `searches`, which means "sweep this retailer for offers". A second
 * tracking provider therefore appears on this tab by adding config, which is the same promise
 * the adapter layer already makes about adding a retailer.
 */
internal fun trackingSources(app: AppConfig): List<String> =
    app.enabledProviders.filter { it.list("trackSearches").isNotEmpty() }.map { it.id }.sorted()

/**
 * Every row the tracked tab can show, for the page and the Ask panel alike.
 *
 * Uncapped, unlike the feed. The tab filters and sorts in Kotlin, so the feed's default
 * `limit = 1000` truncated BEFORE any of that ran — and with the cheapest-first order the cut fell
 * on the dearest items. At 1,850 active amazon rows, an item added by URL at US$125 ranked #1,798
 * and was silently missing from the tab, as were two earlier additions: the add had worked, the
 * page just never loaded it.
 */
internal fun trackedDeals(repo: DealRepository, sources: List<String>): List<Deal> =
    if (sources.isEmpty()) emptyList()
    else repo.feed(FeedFilter(sources = sources, sort = "price", limit = Int.MAX_VALUE))

/**
 * Builds a [WatchDraft] from the form, or null when the two required fields are missing.
 *
 * Blank optional fields become null rather than empty strings or zeros, following the rule
 * the rest of the app holds to: blank means unknown, never zero. A `maxPrice` of 0 would
 * silently match nothing, and an empty `excludeTerms` string would be parsed as a term.
 */
internal fun watchDraftFrom(params: Parameters): WatchDraft? {
    val name = params["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val terms = params["terms"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return WatchDraft(
        name = name,
        terms = terms,
        excludeTerms = params["excludeTerms"]?.trim()?.takeIf { it.isNotEmpty() },
        categoryGroup = params["categoryGroup"]?.trim()?.takeIf { it.isNotEmpty() },
        sources = listOfNotNull(params["source"]?.trim()?.takeIf { it.isNotEmpty() }),
        minPrice = params["minPrice"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
        maxPrice = params["maxPrice"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
        currency = params["currency"]?.trim()?.takeIf { it.isNotEmpty() } ?: "COP",
        // Coerced rather than rejected: a 0-day window would make every rule dead, and a
        // silently useless watch is worse than a clamped one.
        lookbackDays = params["lookbackDays"]?.toIntOrNull()?.coerceIn(1, 365) ?: 30,
        crossSiteMinSpread = params["crossSiteMinSpread"]?.takeIf { it.isNotBlank() }
            ?.toBigDecimalOrNull()?.coerceAtLeast(BigDecimal.ZERO) ?: BigDecimal("15.00"),
        amazonTerms = params["amazonTerms"]?.trim()?.takeIf { it.isNotEmpty() },
        // A checkbox sends nothing when unticked, so absence is "off". Safe because the form
        // always renders it; the default-on lives in the form's checked state, not here.
        requireCoShipping = params["requireCoShipping"] != null,
        maxShippingCost = params["maxShippingCost"]?.takeIf { it.isNotBlank() }
            ?.toBigDecimalOrNull()?.coerceAtLeast(BigDecimal.ZERO),
    )
}

/**
 * Sentinel for "the slot was taken", distinguished from a real result by identity.
 *
 * A zero-valued [VerifyOutcome] is indistinguishable from a run that legitimately checked
 * nothing, and the two need different words on the row.
 */
private val BUSY = VerifyOutcome()

/** Query parameters the filter form round-trips, so a submitted value survives re-render. */
internal val FILTER_PARAMS = listOf(
    "q", "source", "category", "minDiscount", "maxPrice", "minScore", "co", "state", "sort", "only", "hideWatched", "hideNoShip",
)

/**
 * Filter parameters that cannot remove a row from the page.
 *
 * `sort` only reorders, and `state` is pinned server-side by [favoritesFilter] and has no control
 * on that screen. So neither can be the reason a favorite is missing, and treating either as a
 * narrowing filter would offer to clear something that was never excluding anything.
 */
private val NON_NARROWING_PARAMS = setOf("sort", "state", "hideWatched", "hideNoShip")

/**
 * Builds a [FeedFilter] from the filter form's query parameters.
 *
 * Shared by the feed page and the Excel export rather than duplicated, for the same reason
 * `IngestService.excludePatterns` is shared between ingest and `--purge-excluded`: two
 * copies of a filter definition drift, and the failure is silent — an export that quietly
 * disagrees with the page it was taken from.
 *
 * Note what the defaults do when the form is untouched, since it is not "no filter":
 * dismissed deals are excluded, and `DealRepository.criteria` additionally restricts every
 * query to active deals. [DealsExport] writes both facts into the exported file.
 */
internal fun feedFilterFrom(params: Parameters): FeedFilter = FeedFilter(
    sources = listOfNotNull(params["source"]?.takeIf { it.isNotBlank() }),
    search = params["q"]?.takeIf { it.isNotBlank() },
    minDiscount = params["minDiscount"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
    maxPrice = params["maxPrice"]?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull(),
    states = params["state"]?.takeIf { it.isNotBlank() }
        ?.let { runCatching { listOf(DealState.valueOf(it)) }.getOrNull() }
        // Dismissed deals are hidden by default -- that is the point of dismissing
        // them -- but remain reachable via the State filter.
        ?: listOf(DealState.NEW, DealState.SEEN, DealState.FAVORITE),
    coShipping = params["co"]?.takeIf { it.isNotBlank() }
        ?.let { runCatching { listOf(CoShipping.valueOf(it)) }.getOrNull() }.orEmpty(),
    minScore = params["minScore"]?.takeIf { it.isNotBlank() }?.toIntOrNull(),
    categoryGroups = listOfNotNull(params["category"]?.takeIf { it.isNotBlank() }),
    onlyPromoted = params["only"] == "promoted",
    onlyLowestEver = params["only"] == "lowest",
    // On unless the form says otherwise. An unchecked checkbox submits nothing, so the form
    // sends a trailing hidden `0`; `params[]` returns the FIRST value, which is the box's `1`
    // when it is ticked and that `0` when it is not.
    hideWatched = params["hideWatched"] != "0",
    hideNoShip = params["hideNoShip"] != "0",
    // "relevance" (promoted first, then recency, then discount) rather than "discount",
    // which buries every source that publishes no list price.
    sort = params["sort"]?.takeIf { it.isNotBlank() } ?: "relevance",
)

/**
 * Provider types whose rows stay off the feed: the ones that fetch items whether or not they are
 * on offer.
 *
 * The feed ranks offers. `amazon` stores the operator's clothing in their size and
 * `amazon-watch` stores whatever a watch searched Amazon for — both at full price as often as not,
 * and both already shown where they belong (Tracked, and each watch's page). `vtex-grocery` is the
 * whole food catalogue of two stores, ~15,000 rows that would bury the feed's cap, judged on its own
 * tab against its own history. By type rather than by id, so a second provider of either kind stays off the feed without a code change.
 */
private val FEED_HIDDEN_TYPES = setOf("amazon", "amazon-watch") + GROCERY_TYPES

/** The provider ids [FEED_HIDDEN_TYPES] resolves to, enabled or not — disabling one keeps its rows. */
internal fun feedHiddenSources(app: AppConfig): List<String> =
    app.providers.filter { it.type in FEED_HIDDEN_TYPES }.map { it.id }.sorted()

/**
 * The feed's own filter: [feedFilterFrom] with the fetch-everything providers left out.
 *
 * Shared by the feed, its export and its Ask panel, for the reason [feedFilterFrom] is shared.
 * Favorites deliberately does NOT use it: a row the operator chose stays theirs wherever it came
 * from.
 */
internal fun feedScreenFilter(params: Parameters, app: AppConfig): FeedFilter {
    val source = feedSourceParam(params, app)
    return feedFilterFrom(params).copy(
        sources = listOfNotNull(source.takeIf { it.isNotBlank() }),
        excludeSources = feedHiddenSources(app),
    )
}

/** The provider the feed opens on, because it is the one whose deals are community-vetted. */
internal const val FEED_DEFAULT_SOURCE = "slickdeals"

/**
 * The feed's effective `source`: what the form sent, or [FEED_DEFAULT_SOURCE] when the URL has no
 * `source` at all. A blank value is a choice -- the "all" option submits `source=` -- so only an
 * absent parameter falls back, and "all" stays one click away. Blank means all. The default is
 * skipped when that provider is not enabled, so a config without it still shows something.
 */
internal fun feedSourceParam(params: Parameters, app: AppConfig): String {
    if (params.contains("source")) return params["source"].orEmpty()
    return FEED_DEFAULT_SOURCE.takeIf { id -> app.providers.any { it.id == id && it.enabled } }.orEmpty()
}

/**
 * The favorites scope, as a [FeedFilter].
 *
 * Shared by `/favorites` and `/favorites/export.xlsx` for the same reason [feedFilterFrom] is shared
 * by the feed and its export: two copies of a filter drift, and the failure is silent.
 *
 * Both overrides are deliberate:
 *
 *  - **`states` is PINNED, not read from the query string.** The screen renders no State control
 *    (see [FeedScreen]), so honouring `?state=DISMISSED` here would let a hand-edited URL turn the
 *    favorites page into a general feed while its heading still said "favorites".
 *  - **`includeInactive`** is what keeps a favorite visible after its offer ends. See the field's
 *    own comment: retirement is keyed on `last_seen_at` alone and never consults `state`.
 *
 * Everything else — search, source, category, sort, the chip presets — comes straight from
 * [feedFilterFrom], so the feed's whole filter bar works here unchanged.
 */
internal fun favoritesFilter(params: Parameters): FeedFilter = feedFilterFrom(params).copy(
    states = listOf(DealState.FAVORITE),
    includeInactive = true,
    // The checkbox is not rendered here, and a favorite is yours whether or not a watch covers it.
    hideWatched = false,
    hideNoShip = false,
)

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
