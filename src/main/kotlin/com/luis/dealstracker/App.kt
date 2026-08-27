package com.luis.dealstracker

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.Db
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.verify.AmazonVerifier
import com.luis.dealstracker.verify.CoShippingConfig
import com.luis.dealstracker.ingest.IngestService
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.SourceRegistry
import com.luis.dealstracker.sources.mercadolibre.MercadoLibreSource
import com.luis.dealstracker.sources.slickdeals.SlickdealsSource
import com.luis.dealstracker.sources.vtex.VtexSource
import com.luis.dealstracker.web.routes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val projectDir = File(System.getProperty("user.dir"))
    val app = AppConfig.load(projectDir)

    val registry = SourceRegistry(
        listOf(
            SlickdealsSource(),
            VtexSource(),
            MercadoLibreSource(),
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

    val repo = DealRepository(Db.dataSource)
    val http = PoliteHttp(app.defaults.userAgent, app.defaults.perHostDelayMillis)
    val categorizer = Categorizer.load(projectDir)
    val ingest = IngestService(repo, registry, app, http, categorizer)

    val fetchOnce = args.contains("--fetch-once")
    val onlySource = args.firstOrNull { it.startsWith("--source=") }?.substringAfter("=")

    if (args.contains("--verify-co-shipping")) {
        val limit = args.firstOrNull { it.startsWith("--limit=") }?.substringAfter("=")?.toIntOrNull()
        val cfg = CoShippingConfig(
            country = app.defaults.coShippingCountry,
            batchSize = app.defaults.coShippingBatchSize,
            delayMillis = app.defaults.coShippingDelayMillis,
            maxPerRun = app.defaults.coShippingMaxPerRun,
            headless = args.contains("--headless"),
        )
        val outcome = AmazonVerifier(repo, cfg).run(limit)
        println(
            "\n[verify] done: ${outcome.checked} checked — " +
                "${outcome.yes} ships, ${outcome.no} does not, ${outcome.unclear} unclear" +
                (if (outcome.protectedManual > 0) ", ${outcome.protectedManual} manual verdict(s) left untouched" else "") +
                (if (outcome.blocked) "  [STOPPED EARLY: Amazon was blocking]" else ""),
        )
        repo.coShippingStats().forEach { (k, v) -> println("  ${k.padEnd(18)} $v") }
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
        }
        http.close()
        return
    }

    val scope = CoroutineScope(SupervisorJob())

    // Hourly poll lives inside the app process rather than a separate cron entry
    // point, so the UI can also trigger a refresh on demand.
    scope.launch {
        val intervalMillis = app.defaults.pollIntervalMinutes * 60_000
        while (true) {
            runCatching { ingest.runAll() }
                .onFailure { println("[poller] cycle failed: ${it.message}") }
            delay(intervalMillis)
        }
    }

    println("[web] http://localhost:${app.defaults.httpPort}")
    embeddedServer(Netty, port = app.defaults.httpPort) {
        routes(repo, ingest, app, categorizer, scope)
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
