package com.luis.dealstracker.web

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.FeedFilter
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.ingest.IngestService
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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.html.p
import kotlinx.html.strong
import io.ktor.server.http.content.staticResources
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun Application.routes(
    repo: DealRepository,
    ingest: IngestService,
    app: AppConfig,
    categorizer: Categorizer,
    scope: CoroutineScope,
) {
    routing {
        // Serves src/main/resources/static/*. Keeping the CSS on disk rather than in a
        // Kotlin constant means style edits need only a browser refresh.
        staticResources("/static", "static")

        get("/") {
            val params = call.request.queryParameters
            val q = FILTER_PARAMS.associateWith { params[it].orEmpty() }
            val filter = feedFilterFrom(params)

            val page = repo.feedPage(filter)
            // Sources come from config plus whatever is already in the DB, so a
            // provider added to sources.yaml appears in the dropdown immediately.
            val sources = (app.providers.map { it.id } + repo.knownSources()).distinct().sorted()

            call.respondHtml {
                page("Deals Tracker") {
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
        ?: listOf(DealState.NEW, DealState.SEEN, DealState.SAVED),
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

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
