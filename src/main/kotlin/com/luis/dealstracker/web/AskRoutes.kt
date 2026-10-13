package com.luis.dealstracker.web

import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.ingest.ExchangeRates
import com.luis.dealstracker.ingest.WatchEvaluator
import com.luis.dealstracker.model.FxRate
import com.luis.dealstracker.ask.AskContext
import com.luis.dealstracker.ask.HISTORY_DAYS
import com.luis.dealstracker.ask.HISTORY_POINTS
import com.luis.dealstracker.ask.AskService
import com.luis.dealstracker.ask.SPEC_REQUEST
import com.luis.dealstracker.ask.firstPrompt
import com.luis.dealstracker.ask.parseSpecRequest
import com.luis.dealstracker.ask.specsPrompt
import com.luis.dealstracker.db.Db
import com.luis.dealstracker.db.DealSpecStore
import com.luis.dealstracker.ask.followUpPrompt
import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.FeedFilter
import com.luis.dealstracker.db.ManualItemRepository
import com.luis.dealstracker.db.WatchRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.luis.dealstracker.ask.mentionedDeals
import com.luis.dealstracker.model.formatMoney
import com.luis.dealstracker.db.GroceryFilter
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.db.GroceryRow
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /ask` — one question about the screen the panel sits on.
 *
 * The form carries `screen` (feed, favorites, tracked or watch), `qs` (that page's own query
 * string, verbatim), an optional `conversation` id, and the `question`. The rows are rebuilt from
 * `screen` + `qs` with the very functions the page used, so the answer is about what is on screen.
 *
 * The answer streams back as newline-delimited JSON — `{"t": text}` fragments, then one
 * `{"done": …}` line with the conversation id and cost, or `{"error": …}`. NDJSON rather than
 * server-sent events because this is a POST (EventSource can only GET) and a plain `fetch` reader
 * parses it in a dozen lines, with no dependency added.
 */
fun Routing.askRoutes(
    ask: AskService,
    repo: DealRepository,
    watches: WatchRepository,
    manualItems: ManualItemRepository,
    app: AppConfig,
    rates: ExchangeRates,
    groceries: GroceryRepository,
) {
    post("/ask") {
        val form = call.receiveParameters()
        val question = form["question"]?.trim().orEmpty()
        val l = call.lang
        if (question.isEmpty()) {
            return@post call.respondText(l.tr("ask a question first", "primero escribe una pregunta"), status = HttpStatusCode.BadRequest)
        }
        // Long enough for a real question, short enough that a pasted page cannot become the prompt.
        if (question.length > MAX_QUESTION) {
            return@post call.respondText(
                l.tr("keep the question under $MAX_QUESTION characters", "la pregunta debe tener menos de $MAX_QUESTION caracteres"),
                status = HttpStatusCode.BadRequest,
            )
        }

        val screen = form["screen"].orEmpty()
        // The operator's tick box. Without it no spec is offered, so the model cannot ask for any.
        val withSpecs = form["specs"] == "1"
        val qs = form["qs"].orEmpty().removePrefix("?")
        val context = withContext(Dispatchers.IO) {
            askContextFor(screen, parseQueryString(qs), ask.maxRows, repo, watches, manualItems, app, rates.current(), groceries)
        }?.let { ctx ->
            // Only a first prompt carries rows, so a follow-up skips the query. Failure degrades to
            // the plain table: history is an enrichment, never a reason to refuse a question.
            if (ask.historyRows <= 0) ctx else withContext(Dispatchers.IO) {
                val ids = ctx.deals.take(ask.historyRows).map { it.id }
                runCatching { ctx.withHistory(repo.priceChanges(ids, HISTORY_DAYS, HISTORY_POINTS), ids.size) }.getOrDefault(ctx)
            }
        }?.let { ctx ->
            // Watch screens only, and only which rows HAVE specs -- the specs themselves are sent when
            // the model asks for them. Failure degrades to the plain table, like history above.
            if (!screen.startsWith("watch:") || !withSpecs) ctx else withContext(Dispatchers.IO) {
                runCatching { ctx.withSpecIds(DealSpecStore.idsWithSpecs(Db.dataSource, ctx.deals.map { it.id })) }.getOrDefault(ctx)
            }
        } ?: return@post call.respondText("unknown screen", status = HttpStatusCode.BadRequest)

        // The screen's identity for follow-ups: same screen, same filters. See AskService.
        // Includes the tick: a session started without the list of rows that have specs cannot ask for
        // them, so switching it on mid-conversation has to start a fresh one.
        val screenKey = "$screen?$qs" + if (withSpecs) "|specs" else ""
        val (sessionId, resume) = ask.sessionFor(form["conversation"], screenKey)
        val prompt = if (resume) followUpPrompt(question) else context.firstPrompt(question)

        call.respondTextWriter(ContentType.parse("application/x-ndjson; charset=utf-8")) {
            fun emit(line: kotlinx.serialization.json.JsonObject) {
                write(line.toString())
                write("\n")
                flush()
            }

            // One at a time per slot; a second tab asking at once waits rather than spawning a
            // third Node process against the same plan limit.
            if (!ask.gate.tryAcquire()) {
                emit(buildJsonObject { put("status", l.tr("waiting for another question to finish…", "esperando a que termine otra pregunta…")) })
                ask.gate.acquire()
            }
            try {
                emit(
                    buildJsonObject {
                        val n = context.deals.size
                        put("status", if (resume) l.tr("follow-up", "seguimiento") else l.tr("asking about $n row(s)", "preguntando sobre $n fila(s)"))
                    },
                )
                val answer = StringBuilder()

                // On a screen with stored specs the model may answer with `NEED_SPECS: ids` instead of an
                // answer. That line is held back from the panel, and the second turn streams the real
                // answer. Anything else is passed straight through, as before.
                val canAskSpecs = context.specIds.isNotEmpty()
                val held = StringBuilder()
                var decided = !canAskSpecs
                var requested = false
                val firstTurn: (String) -> Unit = { text ->
                    if (decided) {
                        if (requested) held.append(text) else { answer.append(text); emit(buildJsonObject { put("t", text) }) }
                    } else {
                        held.append(text)
                        val head = held.toString().trimStart()
                        if (head.length >= SPEC_REQUEST.length || !SPEC_REQUEST.startsWith(head, ignoreCase = true)) {
                            decided = true
                            requested = head.startsWith(SPEC_REQUEST, ignoreCase = true)
                            if (!requested) {
                                answer.append(held); emit(buildJsonObject { put("t", held.toString()) }); held.clear()
                            }
                        }
                    }
                }
                var outcome = withContext(Dispatchers.IO) { ask.run(prompt, sessionId, resume, firstTurn) }
                // A reply shorter than the marker never reached a decision; it is an answer.
                if (!decided && held.isNotEmpty()) {
                    answer.append(held); emit(buildJsonObject { put("t", held.toString()) }); held.clear()
                }
                if (requested && outcome.error == null) {
                    val ids = context.parseSpecRequest(held.toString()).orEmpty()
                    emit(buildJsonObject { put("status", l.tr("reading specifications of ${ids.size} item(s)…", "leyendo las especificaciones de ${ids.size} artículo(s)…")) })
                    val specs = withContext(Dispatchers.IO) { DealSpecStore.load(Db.dataSource, ids) }
                    val second = withContext(Dispatchers.IO) {
                        ask.run(context.specsPrompt(specs, ids), sessionId, true) { text ->
                            answer.append(text)
                            emit(buildJsonObject { put("t", text) })
                        }
                    }
                    println("[ask] specs turn: asked for ${ids.size}, ${specs.size} stored")
                    // One answer to the operator, so one cost and one duration across both turns.
                    outcome = second.copy(
                        costUsd = listOfNotNull(outcome.costUsd, second.costUsd).sum().takeIf { outcome.costUsd != null || second.costUsd != null },
                        durationMs = outcome.durationMs + second.durationMs,
                    )
                }
                println(
                    "[ask] ${context.label}: ${context.deals.size} rows, resume=$resume, " +
                        "${outcome.durationMs}ms, cost=${outcome.costUsd?.let { "$%.4f".format(it) } ?: "?"}" +
                        (outcome.error?.let { " -- ERROR $it" } ?: ""),
                )
                if (outcome.error != null) {
                    ask.forget(sessionId)
                    emit(buildJsonObject { put("error", outcome.error) })
                } else {
                    ask.record(sessionId, screenKey)
                    emit(
                        buildJsonObject {
                            put("done", true)
                            put("conversation", sessionId)
                            outcome.costUsd?.let { put("cost", it) }
                            put("ms", outcome.durationMs)
                            put("rows", context.deals.size)
                            put("omitted", context.omitted)
                            // Resolved here rather than asking the model for URLs: the URLs are not
                            // in the prompt (they would cost tokens on every row), and a model
                            // writing one from memory could get it wrong. The link comes from the
                            // database, so it is always the retailer's real page.
                            put(
                                "links",
                                buildJsonArray {
                                    context.mentionedDeals(answer.toString()).forEach { d ->
                                        add(
                                            buildJsonObject {
                                                put("id", d.id)
                                                put("title", d.title)
                                                put("url", d.url)
                                                put("source", d.source)
                                                put("price", formatMoney(d.price, d.currency))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            } finally {
                ask.gate.release()
            }
        }
    }
}

private const val MAX_QUESTION = 2_000

/**
 * The rows a screen shows, rebuilt from its own query string.
 *
 * Every branch calls the same function its page calls — [feedFilterFrom], [favoritesFilter],
 * [trackedShown], `watches.candidates` + [matchingComparator] — which is what makes "the rows on
 * screen" a fact rather than an approximation. Null for a screen this does not know.
 */
internal fun askContextFor(
    screen: String,
    params: Parameters,
    maxRows: Int,
    repo: DealRepository,
    watches: WatchRepository,
    manualItems: ManualItemRepository,
    app: AppConfig,
    fx: FxRate,
    groceries: GroceryRepository? = null,
): AskContext? = when {
    screen == "feed" -> feedContext("deal feed", feedScreenFilter(params, app), maxRows, repo)
    screen == "favorites" -> feedContext("favorites", favoritesFilter(params), maxRows, repo)
    screen == "tracked" -> {
        val sources = trackingSources(app)
        val manualAsins = manualItems.trackedAsins()
        // Collapsed as the page does, so the answer is about the rows the operator sees.
        val all = collapseVariants(trackedDeals(repo, sources), manualAsins).rows
        val selected = params["type"]?.split(',')
            ?.mapNotNull { l -> ProductType.entries.firstOrNull { it.label.equals(l.trim(), ignoreCase = true) } }
            ?.toSet().orEmpty()
        val shown = trackedShown(
            all,
            selected,
            params["brand"]?.trim()?.takeIf { it.isNotEmpty() },
            TrackedOrigin.of(params["origin"]),
            manualAsins,
            TrackedSort.of(params["sort"]),
            showNoShip = params["noship"] == "show",
            freeShip = params["freeship"] == "1",
        )
        AskContext(
            label = "tracked Amazon items (one size each)",
            preamble = listOf(
                "These are specific items the operator tracks in their own size; " +
                    "the question is usually about price movement, not discount.",
            ),
            deals = shown.take(maxRows),
            omitted = (shown.size - maxRows).coerceAtLeast(0),
        )
    }
    screen == "groceries" && groceries != null -> {
        val controls = GroceryControls.from(params)
        // The same store selection the page applied, so the answer is about the rows on screen.
        val stores = groceryStores(app)
        val sources = sourcesForChains(stores, controls.chains)
        val rows = groceries.rows(
            if (controls.view == GroceryView.PINNED) GroceryFilter(sources, pinnedOnly = true)
            else GroceryFilter(sources, controls.groups.toList(), controls.search),
        )
        val shown = groceryShown(mergeStores(rows, stores), controls)
        AskContext(
            label = "groceries (${controls.view.label.lowercase()}" +
                (if (controls.chains.isEmpty()) "" else ", " + controls.chains.sorted().joinToString("/")) + ")",
            preamble = buildList {
                add(
                    "Grocery items at Medellín stores: Exito Laureles and Colombia, Carulla Laureles, and PriceSmart " +
                        "Las Américas and El Poblado (member prices). A product sold at two stores of one chain is " +
                        "shown once, at the cheaper one, and 'source' names that store. 'low' and 'high' are over all " +
                        "recorded days; a deal on this screen means at least ${GroceryRow.DEAL_PERCENT}% under the " +
                        "item's own 30-day median daily price. Compare pack sizes from the title before " +
                        "calling one item cheaper than another.",
                )
                // The per-row usual price is not a table column, so the rows that have one say it here.
                shown.take(maxRows).filter { it.belowUsual != null }.take(60).forEach {
                    add("#${it.deal.id} at ${it.store}: usual ${it.usual?.toPlainString()} (${it.belowUsual}% below, ${it.days} days)")
                }
            },
            deals = shown.take(maxRows).map { it.deal },
            omitted = (shown.size - maxRows).coerceAtLeast(0),
        )
    }
    screen.startsWith("watch:") -> {
        val watch = screen.removePrefix("watch:").toLongOrNull()?.let { watches.findById(it) }
        watch?.let {
            // Same order the "Matching now" table uses, so a cut at maxRows drops the same tail.
            // Narrowed to the page's source filter too, so the answer is about the rows on screen.
            val ordered = filterBySource(WatchEvaluator.comparable(it, watches.candidates(it, fx), fx), watchSourceFrom(params))
                .sortedWith(matchingComparator(watchSortFrom(params, WatchSort.MATCHING_PARAM)))
                .map { c -> c.dealId }
            AskContext.forWatch(
                it,
                repo.findByIds(ordered.take(maxRows)),
                omitted = (ordered.size - maxRows).coerceAtLeast(0),
                fx = fx,
            )
        }
    }
    else -> null
}

/**
 * The feed and favorites share one query, in the page's own order, so the first [maxRows] sent are
 * the first [maxRows] shown. The omitted count is the real total, not "at least one more".
 */
private fun feedContext(name: String, filter: FeedFilter, maxRows: Int, repo: DealRepository): AskContext {
    val deals = repo.feed(filter.copy(limit = maxRows))
    val total = repo.countMatching(filter)
    val search = filter.search?.takeIf { it.isNotBlank() }?.let { " searching \"$it\"" }.orEmpty()
    return AskContext("$name$search", emptyList(), deals, (total - deals.size).coerceAtLeast(0))
}
