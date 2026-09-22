package com.luis.dealstracker

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.Db
import com.luis.dealstracker.db.ManualItemRepository
import com.luis.dealstracker.db.WatchRepository
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.ingest.WatchService
import com.luis.dealstracker.verify.AmazonVerifier
import com.luis.dealstracker.verify.CoShippingConfig
import com.luis.dealstracker.ingest.IngestService
import com.luis.dealstracker.ingest.ManualItemService
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.SourceRegistry
import com.luis.dealstracker.sources.algolia.AlgoliaSource
import com.luis.dealstracker.sources.amazon.AmazonSource
import com.luis.dealstracker.sources.falabella.FalabellaNextSource
import com.luis.dealstracker.sources.mercadolibre.MercadoLibreSource
import com.luis.dealstracker.sources.prestashop.PrestashopSource
import com.luis.dealstracker.sources.shopify.ShopifySource
import com.luis.dealstracker.sources.slickdeals.SlickdealsSource
import com.luis.dealstracker.sources.vtex.VtexSource
import com.luis.dealstracker.web.routes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import com.luis.dealstracker.verify.VerifyOutcome
import com.luis.dealstracker.verify.VerifySlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How often the poller checks whether its next cycle is due.
 *
 * Short on purpose. This is not the poll interval -- it is the granularity with which an
 * overdue poll is noticed, which is what makes the loop survive the machine sleeping.
 */
private const val POLL_TICK_MILLIS = 60_000L

fun main(args: Array<String>) {
    val projectDir = File(System.getProperty("user.dir"))
    val app = AppConfig.load(projectDir)

    val registry = SourceRegistry(
        listOf(
            SlickdealsSource(),
            VtexSource(),
            // Serves homecenter.com.co and falabella.com.co: same Next.js build, same product
            // record shape, so the two stores differ only in config.
            FalabellaNextSource(),
            // Both are platform adapters, so the next retailer on either is config only.
            ShopifySource(),
            PrestashopSource(),
            AlgoliaSource(),
            MercadoLibreSource(),
            // Queries amazon.com directly for specific items in a specific size, which the
            // Slickdeals feed cannot do -- it carries whatever the community posted.
            AmazonSource(),
            // Rappi has no adapter: services.rappi.com.co does not resolve and its
            // robots.txt disallows /api, /api-services and /base-api. The provider is
            // present in sources.yaml with enabled:false so this is a config change,
            // not a code change, once an approach exists.
        ),
    )

    // Fail at boot on a typo'd provider type rather than producing an empty feed.
    registry.validate(app.providers).forEach { println("[config] $it") }

    Db.connect(app.db)
    try {
        Db.migrate()
    } catch (e: Exception) {
        System.err.println(
            "[db] migration failed: ${e.message}\n" +
                "Is Postgres running? Start it with:  docker compose up -d",
        )
        throw e
    }

    val repo = DealRepository(Db.dataSource, app.defaults.feedRotationHours)
    val watches = WatchRepository(Db.dataSource)
    val http = PoliteHttp(app.defaults.userAgent, app.defaults.perHostDelayMillis)
    val categorizer = Categorizer.load(projectDir)
    // Watch terms are injected rather than fetched by ingest, so a watch can steer what the
    // adapters go looking for without ingest knowing where watches are stored.
    val ingest = IngestService(repo, registry, app, http, categorizer) { watches.activeSearchTerms() }
    // Env override on top of defaults.httpPort, so a second copy can run alongside the usual
    // one -- a worktree or a branch under test -- without editing committed config and having
    // to remember to put it back. Resolved here rather than at the server, because a
    // notification needs the same value to link back to a watch.
    val port = System.getenv("DEALS_HTTP_PORT")?.toIntOrNull() ?: app.defaults.httpPort
    val watchService = WatchService(watches, baseUrl = "http://localhost:$port")
    // Items tracked by pasted URL. Refreshed every poll alongside the searches: an un-refreshed row
    // is retired by deactivateStale after 48h and silently vanishes from the tab, which reads as
    // "the item stopped existing" rather than "the app stopped looking".
    val manualItems = ManualItemRepository(Db.dataSource)
    val manualService = ManualItemService(repo, manualItems, app, http, categorizer)

    val fetchOnce = args.contains("--fetch-once")
    val onlySource = args.firstOrNull { it.startsWith("--source=") }?.substringAfter("=")

    if (args.contains("--verify-co-shipping")) {
        val limit = args.firstOrNull { it.startsWith("--limit=") }?.substringAfter("=")?.toIntOrNull()
        val cfg = verifyConfig(app, headless = args.contains("--headless"))
        // The running app verifies on a schedule too, and both share one Chrome profile.
        // Without this the loser of that race dies on an opaque Playwright error.
        val outcome = VerifySlot.tryWith(
            VerifySlot.lockFileFor(cfg.profileDir),
            onBusy = {
                println(
                    "[verify] another verification is already running -- most likely the app's " +
                        "own scheduled run. Both share ${cfg.profileDir}, which Chrome locks, " +
                        "so only one can hold it. Retry in a minute.",
                )
                null
            },
        ) { AmazonVerifier(repo, cfg).run(limit) }

        if (outcome != null) {
            println("\n${summarize(outcome)}")
            repo.coShippingStats().forEach { (k, v) -> println("  ${k.padEnd(18)} $v") }
        }
        http.close()
        return
    }

    // Evaluates every watch now rather than waiting for the next hourly poll. `--dry-run`
    // prints what would be announced without writing the ledger or showing a banner, which
    // is how you tune a watch's floor and exclusions without being notified twenty times.
    if (args.contains("--check-watches")) {
        val reports = watchService.runAll(dryRun = args.contains("--dry-run"))
        if (reports.isEmpty()) println("no enabled watches. Add one at http://localhost:$port/watches")
        reports.forEach { println(it.summary()) }
        http.close()
        return
    }

    if (args.contains("--recategorize")) {
        recategorize(repo, categorizer, showOther = args.contains("--show-other"))
        http.close()
        return
    }

    if (args.contains("--purge-old")) {
        purgeOld(
            repo, app,
            dryRun = args.contains("--dry-run"),
            includeUndated = args.contains("--include-undated"),
            onlySource = onlySource,
        )
        http.close()
        return
    }

    if (args.contains("--purge-excluded")) {
        purgeExcluded(repo, ingest, app, dryRun = args.contains("--dry-run"), onlySource = onlySource)
        http.close()
        return
    }

    if (fetchOnce) {
        runBlocking {
            val providers = app.enabledProviders.filter { onlySource == null || it.id == onlySource }
            if (providers.isEmpty()) {
                println(
                    "no enabled provider matches ${onlySource ?: "(any)"}. " +
                        "Enabled: ${app.enabledProviders.map { it.id }}",
                )
            }
            providers.forEach { provider ->
                runCatching { ingest.run(provider) }
                    .onFailure { println("[${provider.id}] FAILED: ${it.message}") }
            }
            // `runAll` does this itself; this path drives providers one at a time, so the
            // drift check would otherwise never fire for a --fetch-once run.
            ingest.reportCategoryDrift()
        }
        http.close()
        return
    }

    val scope = CoroutineScope(SupervisorJob())

    // Hourly poll lives inside the app process rather than a separate cron entry
    // point, so the UI can also trigger a refresh on demand.
    scope.launch {
        val intervalMillis = app.defaults.pollIntervalMinutes * 60_000
        val everyN = app.defaults.coShippingEveryNPolls
        var cycle = 0
        // First cycle is due immediately; every later one is scheduled off wall-clock time.
        var dueAt = System.currentTimeMillis()

        while (true) {
            // A wall-clock deadline checked on a short tick, NOT one delay() spanning the
            // whole interval. A single long delay does not survive the laptop sleeping: six
            // hours of wall clock produced exactly one poll cycle, while Hikari logged the
            // missing time as "clock leap detected" with deltas up to 1h27m. Verification
            // was never broken -- the loop meant to drive it simply was not ticking.
            //
            // Sleeping in short hops instead means a machine that wakes up finds the poll
            // already overdue and runs it at once, rather than waiting out the remainder of
            // a timer that stopped advancing.
            while (System.currentTimeMillis() < dueAt) {
                delay(POLL_TICK_MILLIS)
            }
            // Measured from the start of the work, so a slow cycle does not push the next
            // one progressively later. An overrun simply makes the next cycle due at once.
            val overdueBy = System.currentTimeMillis() - dueAt
            dueAt = System.currentTimeMillis() + intervalMillis
            if (overdueBy > intervalMillis) {
                // Worth saying out loud: it means the process was suspended, and it is the
                // signal that distinguishes "asleep" from "broken" in the log.
                println("[poller] cycle was overdue by ${overdueBy / 60_000}m — machine likely slept")
            }

            runCatching { ingest.runAll() }
                .onFailure { println("[poller] cycle failed: ${it.message}") }

            // After the searches, so a long manual list cannot starve discovery. Wrapped for the
            // same reason every other phase is: one failing item must not stop the cycle.
            runCatching { manualService.refreshAll() }
                .onFailure { println("[manual] refresh failed: ${it.message}") }

            // Every cycle, not every Nth: a watch is only as fresh as the observations it
            // scores, and unlike the browser-based shipping verifier this costs one query
            // per watch and no network at all. Wrapped because a broken watch must never
            // stop deals being fetched.
            runCatching { watchService.runAll() }
                .onFailure { println("[watch] evaluation failed: ${it.message}") }

            // Counted even when the fetch failed: a failed fetch does not make the
            // already-stored backlog any less worth verifying.
            cycle++
            if (everyN > 0 && cycle % everyN == 0) {
                verifyPeriodically(repo, app)
            }
        }
    }

    println("[web] http://localhost:$port")
    embeddedServer(Netty, port = port) {
        routes(repo, watches, ingest, manualService, app, categorizer, scope)
    }.start(wait = true)
}

/**
 * Re-applies config/categories.yaml to every stored deal.
 *
 * Needed after editing the rules, and to backfill rows ingested before category_group
 * existed. `--show-other` lists what stayed unmatched, which is the feedback loop for
 * deciding whether a new rule is worth adding.
 */
private fun recategorize(repo: DealRepository, categorizer: Categorizer, showOther: Boolean) {
    val rows = repo.allForCategorization()
    val assignments = rows.map { (id, category, title) ->
        id to categorizer.categorize(category, title)
    }
    val updated = repo.setCategoryGroups(assignments)
    println("recategorized $updated deal(s)")

    repo.categoryGroupCounts().forEach { (group, count) ->
        println("  ${group.padEnd(20)} $count")
    }

    if (showOther) {
        val other = rows.filterIndexed { i, _ -> assignments[i].second == Categorizer.OTHER }
        println("\nuncategorised (${other.size}) -- add patterns to categories.yaml for any worth splitting out:")
        other.take(40).forEach { (_, _, title) -> println("    - ${title.take(88)}") }
        if (other.size > 40) println("    … and ${other.size - 40} more")
    }
}

/**
 * Deletes already-stored deals the source published longer ago than `maxAgeDays`.
 *
 * `--include-undated` also removes rows with no posted_at at all. Those are rows ingested
 * before the column existed; since page depth is now lower they will never be re-fetched
 * and so can never acquire a date.
 */
private fun purgeOld(
    repo: DealRepository,
    app: AppConfig,
    dryRun: Boolean,
    includeUndated: Boolean,
    onlySource: String?,
) {
    var total = 0
    app.providers
        .filter { onlySource == null || it.id == onlySource }
        .forEach { provider ->
            val maxAgeDays = provider.int("maxAgeDays", 0)
            if (maxAgeDays <= 0) return@forEach

            val stale = repo.staleByPostedAt(provider.id, maxAgeDays, includeUndated)
            if (stale.isEmpty()) {
                println("[${provider.id}] nothing older than ${maxAgeDays}d")
                return@forEach
            }
            println(
                "[${provider.id}] ${stale.size} deal(s) older than ${maxAgeDays}d" +
                    (if (includeUndated) " (including undated)" else ""),
            )
            stale.take(8).forEach { (_, title) -> println("    - ${title.take(84)}") }
            if (stale.size > 8) println("    … and ${stale.size - 8} more")

            if (dryRun) {
                println("[${provider.id}] --dry-run: nothing deleted")
            } else {
                total += repo.deleteDeals(stale.map { it.first })
                println("[${provider.id}] deleted ${stale.size} deal(s)")
            }
        }
    println(if (dryRun) "dry run complete" else "purged $total deal(s)")
}

/**
 * Deletes already-stored deals matching a provider's `excludeTitlePatterns`.
 *
 * Uses the exact same compiled patterns as ingest filtering, so a title that would be
 * rejected on fetch is also the one removed here — the two cannot drift apart.
 * Pass `--dry-run` to preview.
 */
private fun purgeExcluded(
    repo: DealRepository,
    ingest: IngestService,
    app: AppConfig,
    dryRun: Boolean,
    onlySource: String?,
) {
    var total = 0
    app.providers
        .filter { onlySource == null || it.id == onlySource }
        .forEach { provider ->
            val patterns = ingest.excludePatterns(provider)
            if (patterns.isEmpty()) return@forEach

            val matches = repo.titlesFor(provider.id)
                .filter { (_, title) -> patterns.any { it.containsMatchIn(title) } }
            if (matches.isEmpty()) {
                println("[${provider.id}] nothing to purge")
                return@forEach
            }

            println("[${provider.id}] ${matches.size} deal(s) match exclusion patterns:")
            matches.take(10).forEach { (_, title) -> println("    - ${title.take(88)}") }
            if (matches.size > 10) println("    … and ${matches.size - 10} more")

            if (dryRun) {
                println("[${provider.id}] --dry-run: nothing deleted")
            } else {
                val deleted = repo.deleteDeals(matches.map { it.first })
                println("[${provider.id}] deleted $deleted deal(s) and their price history")
                total += deleted
            }
        }
    println(if (dryRun) "dry run complete" else "purged $total deal(s)")
}

/**
 * Builds the verifier config from `defaults:` in sources.yaml.
 *
 * Shared by the CLI and the periodic run so the two cannot drift: the country and the
 * politeness delay are properties of Amazon, not of how the run was triggered. Only the
 * batch size and the wall-wait differ, and those are passed in.
 */
internal fun verifyConfig(
    app: AppConfig,
    headless: Boolean = false,
    batchSize: Int = app.defaults.coShippingBatchSize,
    wallWaitMillis: Long = CoShippingConfig().wallWaitMillis,
) = CoShippingConfig(
    country = app.defaults.coShippingCountry,
    batchSize = batchSize,
    delayMillis = app.defaults.coShippingDelayMillis,
    maxPerRun = app.defaults.coShippingMaxPerRun,
    headless = headless,
    wallWaitMillis = wallWaitMillis,
)

private fun summarize(outcome: VerifyOutcome): String =
    "[verify] done: ${outcome.checked} checked — " +
        "${outcome.yes} ships, ${outcome.no} does not, ${outcome.unclear} unclear" +
        (if (outcome.protectedManual > 0) ", ${outcome.protectedManual} manual verdict(s) left untouched" else "") +
        (if (outcome.blocked) "  [STOPPED EARLY: Amazon was blocking]" else "")

/**
 * The small automatic verification drip that runs every Nth poll cycle.
 *
 * Three things this must not do, each learned the hard way:
 *
 *  - **Take ingest down with it.** Playwright is the most failure-prone part of this app
 *    (browser launch, profile locks, Amazon's walls), and none of that should stop deals
 *    from being fetched. Hence `runCatching` around the whole thing.
 *  - **Block a dispatcher thread.** Playwright's API is synchronous and a run lasts minutes,
 *    so it goes to `Dispatchers.IO` rather than stalling the poller's thread.
 *  - **Run headless.** Headless reliably walls after ~9 page loads where a headed session
 *    reaches 40-55, and a headless run appeared to cost the profile's accumulated trust.
 *    A visible window every Nth hour is the price of the session staying usable.
 *
 * It also cannot keep up with arrivals and is not meant to -- see `coShippingEveryNPolls`.
 */
private suspend fun verifyPeriodically(repo: DealRepository, app: AppConfig) {
    withContext(Dispatchers.IO) {
        runCatching {
            val cfg = verifyConfig(
                app,
                batchSize = app.defaults.coShippingPollBatch,
                wallWaitMillis = app.defaults.coShippingPollWallWaitMillis,
            )
            val outcome = VerifySlot.tryWith(
                VerifySlot.lockFileFor(cfg.profileDir),
                // A click or a CLI run already has the profile. Skipping is right: the next
                // cycle comes around in a few hours and the backlog is not going anywhere.
                onBusy = {
                    println("[verify] skipping scheduled run — a verification is already in progress")
                    null
                },
            ) { AmazonVerifier(repo, cfg).run() }
            if (outcome == null) return@runCatching
            // Only report when it actually did something; an idle cycle where everything is
            // decided or in backoff should stay quiet in the log.
            if (outcome.checked > 0 || outcome.blocked) println(summarize(outcome))
            if (outcome.blocked) {
                println("[verify] Amazon blocked this automatic run — backing off until the next scheduled one")
            }
        }.onFailure { println("[verify] automatic run failed: ${it.message}") }
    }
}
