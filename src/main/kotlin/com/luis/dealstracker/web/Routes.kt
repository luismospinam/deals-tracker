package com.luis.dealstracker.web

import com.luis.dealstracker.ask.AskService
import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.FeedFilter
import com.luis.dealstracker.db.WatchDraft
import com.luis.dealstracker.db.ManualItemRepository
import com.luis.dealstracker.db.WatchRepository
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.ingest.IngestService
import com.luis.dealstracker.ingest.ManualItemService
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.DealState
import com.luis.dealstracker.verify.AmazonVerifier
import com.luis.dealstracker.verify.VerifyOutcome
import com.luis.dealstracker.verify.VerifySlot
import com.luis.dealstracker.verifyConfig
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
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
) {
    routing {
        askRoutes(ask, repo, watches, manualItems, app)

        // Serves src/main/resources/static/*. Keeping the CSS on disk rather than in a
        // Kotlin constant means style edits need only a browser refresh.
        staticResources("/static", "static")

        get("/") {
            val params = call.request.queryParameters
            val q = FILTER_PARAMS.associateWith { params[it].orEmpty() }
            val filter = feedFilterFrom(params)

            val page = repo.feedPage(filter)
            val sources = sourceNames(repo, app)
            // For the scope row's badge. Unscoped by the current filters on purpose: it answers "how
            // many favorites do I have", not "how many match what I am currently searching for" -- a
            // count that fell to 0 because of an unrelated search would read as having lost them.
            val favoriteCount = repo.countMatching(favoritesFilter(Parameters.Empty))

            call.respondHtml {
                page("Deals Tracker") {
                    feedScopes(q, FeedScreen.FEED, favoriteCount)
                    filterForm(sources, categorizer.categoryNames, q)
                    p("result-count") {
                        // State the total explicitly when the page is capped -- showing
                        // only the page size reads as a total and hides that rows exist.
                        if (page.truncated) {
                            +"showing "
                            strong { +"${page.deals.size}" }
                            +" of "
                            strong { +"${page.totalMatching}" }
                            +" matching deals"
                        } else {
                            strong { +"${page.totalMatching}" }
                            +" deals"
                        }
                    }
                    askPanel("feed", "e.g. which of these are real discounts, not inflated list prices?")
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
            val filter = feedFilterFrom(call.request.queryParameters)
            val zone = ZoneId.systemDefault()

            // Both the queries and the workbook build are blocking, and an export can be
            // far larger than a rendered page, so keep all of it off the event loop.
            val (bytes, meta) = withContext(Dispatchers.IO) {
                val totalMatching = repo.countMatching(filter)
                val deals = repo.feed(filter.copy(limit = DealsExport.MAX_ROWS))
                val meta = DealsExport.Meta(Instant.now(), filter, totalMatching, deals.size)
                DealsExport.build(deals, meta, zone) to meta
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

            call.respondHtml {
                page("Favorites — Deals Tracker") {
                    // `page.totalMatching` is this scope's own unfiltered-by-nothing total only when
                    // no filter is applied, so the badge reuses the same unscoped count the feed
                    // computes rather than the possibly-narrowed number shown below.
                    feedScopes(q, FeedScreen.FAVORITES, repo.countMatching(favoritesFilter(Parameters.Empty)))
                    filterForm(sources, categorizer.categoryNames, q, FeedScreen.FAVORITES)
                    p("result-count") {
                        if (page.truncated) {
                            +"showing "
                            strong { +"${page.deals.size}" }
                            +" of "
                            strong { +"${page.totalMatching}" }
                            +" favorites"
                        } else {
                            strong { +"${page.totalMatching}" }
                            +" favorite(s)"
                        }
                        // Stated up front rather than left to be inferred from the chips, because
                        // this page breaks the rule every other view follows: a retired deal is
                        // shown, and its price is the last one recorded rather than a live one.
                        val expired = page.deals.count { !it.isActive }
                        if (expired > 0) {
                            span("tracked-note") { +" · $expired whose offer has since expired" }
                        }
                    }
                    askPanel("favorites", "e.g. which of my favorites is the best buy right now?")
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

            val (bytes, meta) = withContext(Dispatchers.IO) {
                val totalMatching = repo.countMatching(filter)
                val deals = repo.feed(filter.copy(limit = DealsExport.MAX_ROWS))
                val meta = DealsExport.Meta(Instant.now(), filter, totalMatching, deals.size)
                DealsExport.build(deals, meta, zone) to meta
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
            val deals = if (sources.isEmpty()) {
                emptyList()
            } else {
                repo.feed(FeedFilter(sources = sources, sort = "price"))
            }
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

            call.respondHtml {
                page("Tracked — Deals Tracker") {
                    askPanel("tracked", "e.g. which of these has dropped the most, and is it worth buying now?")
                    trackedPage(deals, sources, selected, sort, brand, notice, manualAsins, origin)
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
        post("/tracked/add") {
            val params = call.receiveParameters()
            val url = params["url"]?.trim().orEmpty()
            if (url.isEmpty()) {
                return@post call.respondRedirect("/tracked?notice=" + java.net.URLEncoder.encode("paste a URL first", "UTF-8"))
            }

            val outcome = manual.add(
                url = url,
                brand = params["brand"]?.trim()?.takeIf { it.isNotEmpty() },
                productType = params["productType"]?.trim()?.takeIf { it.isNotEmpty() },
            )

            call.respondRedirect("/tracked?notice=" + java.net.URLEncoder.encode(outcome.message, "UTF-8"))
        }

        post("/tracked/{id}/hide") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)

            withContext(Dispatchers.IO) { repo.updateState(id, DealState.DISMISSED) }

            // Empty body + an outerHTML swap removes the <tr>. No reload, and no stale row left
            // claiming to be tracked.
            call.respondText("", ContentType.Text.Html)
        }

        get("/deal/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val deal = repo.findById(id)
                ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            call.respondHtml { page(deal.title) { dealDetail(deal, repo.priceHistory(id)) } }
        }

        post("/deal/{id}/state") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val state = call.request.queryParameters["value"]
                ?.let { runCatching { DealState.valueOf(it) }.getOrNull() }
                ?: return@post call.respondText("bad state", status = HttpStatusCode.BadRequest)

            repo.updateState(id, state)
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
                return@post respondRow(id, repo, "nothing to check — already decided, your own verdict, or no ASIN")
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
            val note = when {
                outcome == null -> "the check failed to run — see the app log"
                outcome === BUSY -> "a verification is already running — try again in a minute"
                // Actionable rather than merely accurate. The wall is a button in a Chrome
                // window that is open right now, but you are looking at this page instead —
                // so "a human check was shown" alone leaves you with nowhere to go.
                outcome.blocked ->
                    "Amazon asked for a human check. Clear it in the Chrome window that opened, " +
                        "then click verify again — the window closes after ${app.defaults.coShippingPollWallWaitMillis / 1000}s."
                outcome.unclear > 0 -> "the page did not state it either way"
                outcome.checked == 0 -> "nothing to check"
                else -> null
            }
            respondRow(id, repo, note)
        }

        // --- watches ---

        get("/watches") {
            val all = watches.all()
            val counts = watches.matchCounts()
            call.respondHtml {
                page("Watches") {
                    watchesPage(all, counts, sourceNames(repo, app), categorizer.categoryNames)
                }
            }
        }

        get("/watches/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
            val watch = watches.findById(id)
                ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)

            val (candidates, matches, dealsById) = withContext(Dispatchers.IO) {
                val candidates = watches.candidates(watch)
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

            call.respondHtml {
                page(watch.name) {
                    askPanel(
                        "watch:${watch.id}",
                        "e.g. which is the best option for what I'm looking for, and why?",
                    )
                    watchDetail(
                        watch, candidates, matches, dealsById,
                        sourceNames(repo, app), categorizer.categoryNames,
                        matchingSort, alertsSort, matchingPage,
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
            call.respondText(
                watchRowFragment(updated, watches.matchCounts()[id]),
                ContentType.Text.Html,
            )
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
        call.respondText(rowFragment(updated, note), ContentType.Text.Html)
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
    "q", "source", "category", "minDiscount", "maxPrice", "minScore", "co", "state", "sort", "only",
)

/**
 * Filter parameters that cannot remove a row from the page.
 *
 * `sort` only reorders, and `state` is pinned server-side by [favoritesFilter] and has no control
 * on that screen. So neither can be the reason a favorite is missing, and treating either as a
 * narrowing filter would offer to clear something that was never excluding anything.
 */
private val NON_NARROWING_PARAMS = setOf("sort", "state")

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
    // "relevance" (promoted first, then recency, then discount) rather than "discount",
    // which buries every source that publishes no list price.
    sort = params["sort"]?.takeIf { it.isNotBlank() } ?: "relevance",
)

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
)

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
