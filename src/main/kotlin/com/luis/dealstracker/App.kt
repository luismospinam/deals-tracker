package com.luis.dealstracker

import com.luis.dealstracker.ask.AskService
import com.luis.dealstracker.ask.ClaudeCli
import com.luis.dealstracker.ask.SYSTEM_PROMPT
import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ConfigOverrides
import com.luis.dealstracker.config.LiveConfig
import com.luis.dealstracker.model.HeldCards
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.db.HistoryPruner
import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.db.pruneDue
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
import com.luis.dealstracker.sources.amazon.AmazonWatchSource
import com.luis.dealstracker.ingest.ExchangeRates
import com.luis.dealstracker.sources.falabella.FalabellaNextSource
import com.luis.dealstracker.sources.mercadolibre.MercadoLibreSource
import com.luis.dealstracker.sources.prestashop.PrestashopSource
import com.luis.dealstracker.sources.shopify.ShopifySource
import com.luis.dealstracker.ingest.SpecPageJob
import com.luis.dealstracker.sources.woocommerce.WooCommerceSource
import com.luis.dealstracker.sources.slickdeals.SlickdealsSource
import com.luis.dealstracker.sources.vtex.VtexGrocerySource
import com.luis.dealstracker.sources.pricesmart.PriceSmartSource
import com.luis.dealstracker.sources.vtex.VtexSource
import com.luis.dealstracker.web.routes
import com.luis.dealstracker.web.trackingSources
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
import java.time.Instant

/**
 * How often the poller checks whether its next cycle is due.
 *
 * Short on purpose. This is not the poll interval -- it is the granularity with which an
 * overdue poll is noticed, which is what makes the loop survive the machine sleeping.
 */
private const val POLL_TICK_MILLIS = 60_000L

/** How often the scheduled grocery crawls check whether a window's minute has come. */
private const val SCHEDULE_TICK_MILLIS = 5 * 60_000L

fun main(args: Array<String>) {
    val projectDir = File(System.getProperty("user.dir"))
    // Before anything can create a Playwright: see pinPlaywrightDriverDir for why not the system temp.
    com.luis.dealstracker.verify.pinPlaywrightDriverDir(projectDir)
    // The file as written. `app`, below, is this with the Config tab's overrides laid over it.
    val fileApp = AppConfig.load(projectDir)

    // Lazy because the registry below needs it before the database is connected: the watches'
    // Amazon adapter reads their searches at fetch time, long after `Db.connect`.
    val watches by lazy { WatchRepository(Db.dataSource) }
    val disabledSearches by lazy { com.luis.dealstracker.db.DisabledSearchRepository(Db.dataSource) }

    val registry = SourceRegistry(
        listOf(
            SlickdealsSource(),
            VtexSource(),
            // The same stores' grocery aisles, priced at one physical store (Laureles) through
            // Intelligent Search rather than at the national catalogue price.
            VtexGrocerySource(recordBanners = { host, banners -> DealRepository(Db.dataSource).recordBanners(host, banners) }),
            // PriceSmart's food catalogue, priced at one warehouse club. Not VTEX -- Bloomreach Discovery
            // behind the site's own API -- but its rows go through the same grocery tab.
            PriceSmartSource(),
            // Serves homecenter.com.co and falabella.com.co: same Next.js build, same product
            // record shape, so the two stores differ only in config.
            FalabellaNextSource(),
            // Both are platform adapters, so the next retailer on either is config only.
            ShopifySource(),
            WooCommerceSource(),
            PrestashopSource(),
            AlgoliaSource(),
            MercadoLibreSource(),
            // Queries amazon.com directly for specific items in a specific size, which the
            // Slickdeals feed cannot do -- it carries whatever the community posted.
            AmazonSource { disabledSearches.disabled(it) },
            // The same site searched for what the watches ask for -- a TV, a fridge -- rather than
            // for clothing in one size. Its terms are each watch's own `amazonTerms`.
            AmazonWatchSource { watches.amazonSearches() },
            // Rappi has no adapter: services.rappi.com.co does not resolve and its
            // robots.txt disallows /api, /api-services and /base-api. The provider is
            // present in sources.yaml with enabled:false so this is a config change,
            // not a code change, once an approach exists.
        ),
    )

    // Fail at boot on a typo'd provider type rather than producing an empty feed.
    registry.validate(fileApp.providers).forEach { println("[config] $it") }

    Db.connect(fileApp.db)
    try {
        Db.migrate()
    } catch (e: Exception) {
        System.err.println(
            "[db] migration failed: ${e.message}\n" +
                "Is Postgres running? Start it with:  docker compose up -d",
        )
        throw e
    }

    // The Config tab's overrides (`cfg.*` in app_settings) over sources.yaml. Boot-time readers see them
    // through `app`; the job loops read `LiveConfig` on every tick, so a schedule change applies at once.
    val bootSettings = SettingsRepository(Db.dataSource)
    LiveConfig.set(fileApp, bootSettings.withPrefix(ConfigOverrides.PREFIX))
    HeldCards.current = SettingsRepository.heldCards(bootSettings.get(SettingsRepository.HELD_CARDS))
    val app = LiveConfig.currentOr(fileApp)
    fun live() = LiveConfig.currentOr(app)

    val repo = DealRepository(Db.dataSource, app.defaults.feedRotationHours)
    val http = PoliteHttp(app.defaults.userAgent, app.defaults.perHostDelayMillis)
    // The day's USD->COP rate for comparing watch candidates. Refreshed once per poll; read from
    // cache everywhere else, so rendering a watch page never waits on datos.gov.co.
    val rates = ExchangeRates(http, java.math.BigDecimal.valueOf(app.defaults.usdCopRate))
    val categorizer = Categorizer.load(projectDir)
    // Watch terms are injected rather than fetched by ingest, so a watch can steer what the
    // adapters go looking for without ingest knowing where watches are stored.
    val ingest = IngestService(
        repo, registry, app, http, categorizer,
        liveApp = ::live,
        watchTerms = { watches.activeSearchTerms() },
        linkWatchDeals = { watches.linkSourced(it) },
    )
    // Env override on top of defaults.httpPort, so a second copy can run alongside the usual
    // one -- a worktree or a branch under test -- without editing committed config and having
    // to remember to put it back. Resolved here rather than at the server, because a
    // notification needs the same value to link back to a watch.
    val port = System.getenv("DEALS_HTTP_PORT")?.toIntOrNull() ?: app.defaults.httpPort
    val promoRepo = com.luis.dealstracker.promo.PromoRepository(Db.dataSource)
    runCatching { promoRepo.refresh() }.onFailure { println("[promo] load failed: ${it.message}") }
    // Something is wrong and needs a person: a desktop notice and a post to the health channel.
    val opsAlert: (String, String) -> Unit = { title, body ->
        com.luis.dealstracker.notify.Notifier.notify(title, body, openUrl = "http://localhost:$port/config#health", group = "ops")
        (live().discordHealthWebhook ?: live().discordWatchesWebhook)?.let {
            com.luis.dealstracker.notify.DiscordWebhook(it).send(title, com.luis.dealstracker.notify.fitDescription(body.lines()), "Deals Tracker · /config", color = 0xE67E22)
        }
    }
    val ops = com.luis.dealstracker.ops.OpsJobs(projectDir, repo, SettingsRepository(Db.dataSource), ::live, alert = opsAlert)
    val watchService = WatchService(
        watches, baseUrl = "http://localhost:$port", fx = rates::current,
        digest = { live().defaults.watchDigestTimes.isNotEmpty() },
        // Read per run, so ticking a card on the Config tab applies from the next cycle.
        heldCards = { HeldCards.current },
    )
    val watchDigest = com.luis.dealstracker.notify.WatchDigest(
        watches, SettingsRepository(Db.dataSource), { live().defaults.watchDigestTimes }, baseUrl = "http://localhost:$port",
        discord = app.discordWatchesWebhook?.let { com.luis.dealstracker.notify.DiscordWebhook(it) },
    )
    // Items tracked by pasted URL. Refreshed every poll alongside the searches: an un-refreshed row
    // is retired by deactivateStale after 48h and silently vanishes from the tab, which reads as
    // "the item stopped existing" rather than "the app stopped looking".
    val manualItems = ManualItemRepository(Db.dataSource)
    initAppLanguage()
    val manualService = ManualItemService(repo, manualItems, app, http, categorizer)

    val fetchOnce = args.contains("--fetch-once")
    val onlySource = args.firstOrNull { it.startsWith("--source=") }?.substringAfter("=")

    // Reads the Exito cart credentials from a signed-in browser profile. Without --save it only reports.
    if (args.contains("--exito-session")) {
        com.luis.dealstracker.exito.ExitoSessionProbe(
            headless = args.contains("--headless"),
            save = if (args.contains("--save")) SettingsRepository(Db.dataSource) else null,
            dropLoginFirst = args.contains("--drop-login"),
            openCartId = if (args.contains("--open-saved-cart")) SettingsRepository(Db.dataSource).get(SettingsRepository.EXITO_CART_ID) else null,
            renew = args.contains("--renew"),
        ).run()
        http.close()
        return
    }

    // Reads the Exito account's orders now instead of waiting for the next scheduled read.
    if (args.contains("--sync-exito-orders")) {
        println(kotlinx.coroutines.runBlocking { exitoOrderSync(app, http).sync() })
        http.close()
        return
    }

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
        runBlocking { rates.refresh() }
        val reports = watchService.runAll(dryRun = args.contains("--dry-run"))
        if (reports.isEmpty()) println("no enabled watches. Add one at http://localhost:$port/watches")
        reports.forEach { println(it.summary()) }
        http.close()
        return
    }

    // Sends the watch digest now, ignoring its daily slots. `--dry-run` prints the banner and the ranking.
    if (args.contains("--watch-digest")) {
        println(watchDigest.runNow(dryRun = args.contains("--dry-run")))
        http.close()
        return
    }

    // Builds the pinned-on-sale posts now, ignoring the daily slots. `--dry-run` prints them instead of
    // sending. See docs/pinned-notifications.md.
    if (args.contains("--notify-pinned")) {
        val alerts = com.luis.dealstracker.notify.PinnedAlerts(
            app, repo, GroceryRepository(Db.dataSource), manualItems, SettingsRepository(Db.dataSource),
        )
        alerts.runNow(dryRun = args.contains("--dry-run")).forEach(::println)
        http.close()
        return
    }

    // Reads specification tables off Falabella/Homecenter product pages for watched products. The poll
    // does a small batch each cycle; this drains the backlog on demand, optionally for one watch.
    if (args.contains("--fetch-specs")) {
        val limit = args.firstOrNull { it.startsWith("--limit=") }?.substringAfter("=")?.toIntOrNull() ?: 100
        val watchId = args.firstOrNull { it.startsWith("--watch=") }?.substringAfter("=")?.toLongOrNull()
        val job = SpecPageJob(Db.dataSource, http, app.providers.filter { it.enabled && it.type == "falabella-next" }.map { it.id })
        println(runBlocking { job.run(limit, watchId) }.summary())
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

    // Thins old price readings that repeat their predecessor. `--dry-run` counts without deleting;
    // `--days=N` overrides `defaults.pruneHistoryAfterDays`. Back the table up before a first real run.
    if (args.contains("--prune-history")) {
        val days = args.firstOrNull { it.startsWith("--days=") }?.substringAfter("=")?.toIntOrNull()
            ?: app.defaults.pruneHistoryAfterDays
        if (days <= 0) {
            println("pruning is off (pruneHistoryAfterDays = ${app.defaults.pruneHistoryAfterDays}); pass --days=N")
        } else {
            println(HistoryPruner(Db.dataSource).run(days, dryRun = args.contains("--dry-run")).summary())
        }
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
    // A second copy under test shares the live database; if it polled too, every store would be
    // crawled twice. DEALS_NO_POLLER=1 serves the UI and nothing else.
    val pollerEnabled = System.getenv("DEALS_NO_POLLER") != "1"
    if (!pollerEnabled) println("[poller] disabled by DEALS_NO_POLLER=1 -- serving the UI only")
    val falabellaSources = app.providers.filter { it.enabled && it.type == "falabella-next" }.map { it.id }

    // Hourly poll lives inside the app process rather than a separate cron entry
    // point, so the UI can also trigger a refresh on demand.
    if (pollerEnabled) scope.launch {
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
            // Read each cycle, so an interval changed on the Config tab applies from the next one.
            val intervalMillis = live().defaults.pollIntervalMinutes * 60_000
            val everyN = live().defaults.coShippingEveryNPolls
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
            // Skips anything a search already fetched this cycle -- otherwise an item that is both
            // manually tracked and returned by a search gets two observations seconds apart, which
            // reads as a price oscillating when nothing moved.
            runCatching {
                manualService.refreshAll(repo.recentlyObservedAsins("amazon"))
            }.onFailure { println("[manual] refresh failed: ${it.message}") }

            // Every cycle, not every Nth: a watch is only as fresh as the observations it
            // scores, and unlike the browser-based shipping verifier this costs one query
            // per watch and no network at all. Wrapped because a broken watch must never
            // stop deals being fetched.
            rates.refresh()
            runCatching { watchService.runAll() }
                .onFailure { println("[watch] evaluation failed: ${it.message}") }

            // A small batch of product pages per cycle, for watched Falabella/Homecenter items whose
            // specs are on the page and nowhere else. Quiet when there is nothing to read.
            if (app.defaults.specPageBatch > 0) {
                runCatching {
                    val report = SpecPageJob(Db.dataSource, http, falabellaSources).run(app.defaults.specPageBatch)
                    if (report.tried > 0) println(report.summary())
                }.onFailure { println("[specs] cycle failed: ${it.message}") }
            }

            // Counted even when the fetch failed: a failed fetch does not make the
            // already-stored backlog any less worth verifying.
            cycle++
            if (everyN > 0 && cycle % everyN == 0) {
                verifyPeriodically(repo, live())
            }
        }
    }

    // Weekly thinning of old price history. Due-ness comes from a timestamp in app_settings rather than
    // a timer, so a restart neither skips a week nor runs it again at once, and a Mac that slept through
    // the week catches up on its next tick. Delayed after boot so it never competes with startup.
    if (pollerEnabled) {
        scope.launch {
            val settings = SettingsRepository(Db.dataSource)
            delay(10 * 60_000L)
            while (true) {
                runCatching {
                    val d = live().defaults
                    val last = settings.get(SettingsRepository.HISTORY_PRUNED_AT)?.let { Instant.parse(it) }
                    // 0 days turns pruning off, checked per tick so the Config tab can turn it back on.
                    if (d.pruneHistoryAfterDays > 0 && pruneDue(last, Instant.now(), d.pruneHistoryEveryDays)) {
                        val report = withContext(Dispatchers.IO) {
                            HistoryPruner(Db.dataSource).run(d.pruneHistoryAfterDays, dryRun = false)
                        }
                        settings.set(mapOf(SettingsRepository.HISTORY_PRUNED_AT to Instant.now().toString()))
                        println("[prune] ${report.summary()}")
                    }
                }.onFailure { println("[prune] failed: ${it.message}") }
                delay(30 * 60_000L)
            }
        }
    }

    // The app watching itself: the nightly database dump, and an alert when a source stops returning
    // anything. Both on the 5-minute tick, with their state in app_settings (see ops.OpsJobs).
    if (pollerEnabled) scope.launch {
        // Let the first poll cycle land before judging any source.
        delay(10 * 60_000L)
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching { ops.backupIfDue()?.let(::println) }.onFailure { println("[backup] failed: ${it.message}") }
                runCatching { ops.checkHealth()?.let(::println) }.onFailure { println("[health] failed: ${it.message}") }
            }
            delay(SCHEDULE_TICK_MILLIS)
        }
    }

    // Payment-promo reminders (V37): a banner and a Discord post on the promo day and the day before,
    // listing the pinned and tracked items that qualify. Remembered in app_settings like the digest.
    if (pollerEnabled) scope.launch {
        val promoNotifier = com.luis.dealstracker.promo.PromoNotifier(
            Db.dataSource, promoRepo, SettingsRepository(Db.dataSource), baseUrl = "http://localhost:$port",
            send = { title, lines ->
                val banner = com.luis.dealstracker.notify.Notifier.notify(
                    title, lines.first(), openUrl = "http://localhost:$port/config#promos", group = "promo",
                )
                val hook = live().discordPromosWebhook ?: live().discordWatchesWebhook
                val posted = hook?.let {
                    com.luis.dealstracker.notify.DiscordWebhook(it).send(title, com.luis.dealstracker.notify.fitDescription(lines), "Deals Tracker · /config", color = 0xF1C40F)
                } ?: false
                banner || posted
            },
        )
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching {
                    com.luis.dealstracker.i18n.I18n.with(com.luis.dealstracker.i18n.AppLanguage.current) { promoNotifier.runDue() }.forEach(::println)
                }.onFailure { println("[promo] failed: ${it.message}") }
            }
            delay(SCHEDULE_TICK_MILLIS)
        }
    }

    // The scheduled grocery crawls, on their own short tick. Separate from the hourly loop above so a
    // 40-minute run window cannot fall between two hourly polls, and so a 6-minute crawl never delays
    // the rest of the cycle. `runScheduled` does nothing unless a window's chosen minute has passed.
    if (pollerEnabled) scope.launch {
        val groceries = GroceryRepository(Db.dataSource)
        while (true) {
            runCatching { ingest.runScheduled() }.onFailure { println("[schedule] failed: ${it.message}") }
            // Rebuilds the stored usual prices when the date has turned (V28); a no-op the rest of the day.
            runCatching { groceries.refreshUsualPrices().takeIf { it > 0 }?.let { println("[groceries] usual prices refreshed for $it rows") } }
                .onFailure { println("[groceries] usual-price refresh failed: ${it.message}") }
            delay(SCHEDULE_TICK_MILLIS)
        }
    }

    // The watch digest: one banner for every watch's alerts at defaults.watchDigestTimes. Due-ness is
    // remembered in app_settings like the pinned posts', so a restart neither repeats nor skips one.
    // Always ticking: with no times set `runDue` sends nothing, and the Config tab can set some at any time.
    if (pollerEnabled) scope.launch {
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching { watchDigest.runDue()?.let(::println) }.onFailure { println("[watch-digest] failed: ${it.message}") }
            }
            delay(SCHEDULE_TICK_MILLIS)
        }
    }

    // The Discord posts of pinned items on sale. Groceries go at the configured times: due-ness is the latest
    // time already passed and not yet recorded in app_settings, so a restart neither repeats nor skips a post.
    // Tracked items are announced on the tick after they are first seen on sale (tracked_alerts, V44).
    if (pollerEnabled) scope.launch {
        val alerts = com.luis.dealstracker.notify.PinnedAlerts(
            app, repo, GroceryRepository(Db.dataSource), manualItems, SettingsRepository(Db.dataSource),
            timesFn = { live().defaults.pinnedAlertTimes },
            trackedAlerts = com.luis.dealstracker.notify.TrackedAlertRepository(Db.dataSource),
        )
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching { alerts.runDue().forEach(::println) }.onFailure { println("[pinned-alerts] failed: ${it.message}") }
            }
            delay(SCHEDULE_TICK_MILLIS)
        }
    }

    // Renews the Exito login at the start of the day, headless, from the profile's 30-day refresh token, so the
    // cart button keeps working without a paste. Shares the Chrome profile lock; a busy slot just waits a turn.
    if (pollerEnabled) scope.launch {
        val exitoSettings = SettingsRepository(Db.dataSource)
        var lastAttempt: java.time.Instant? = null
        var alertedOn: java.time.LocalDate? = null
        while (true) {
            val due = runCatching {
                com.luis.dealstracker.exito.renewalDue(
                    java.time.Instant.now(), exitoSettings.get(SettingsRepository.EXITO_COOKIE), lastAttempt, java.time.ZoneId.systemDefault(),
                )
            }.getOrDefault(false)
            if (due) {
                val ran = withContext(Dispatchers.IO) {
                    runCatching {
                        println("[exito] renewing the login for the day")
                        com.luis.dealstracker.exito.ExitoSessionProbe(headless = true, save = exitoSettings, renew = true).run()
                    }.onFailure { println("[exito] daily login renewal failed: ${it.message}") }.getOrDefault(true)
                }
                // A run turned away by a busy profile did nothing, so it is not an attempt: the next tick
                // (15 minutes) tries again, not the next hour.
                if (ran) {
                    lastAttempt = java.time.Instant.now()
                    // Said once a day, on the first run that leaves yesterday's login in place: found here, not
                    // at the cart button. The hourly retries carry on quietly.
                    val today = java.time.LocalDate.now()
                    val stillOld = runCatching {
                        com.luis.dealstracker.exito.renewalDue(java.time.Instant.now(), exitoSettings.get(SettingsRepository.EXITO_COOKIE), null, java.time.ZoneId.systemDefault())
                    }.getOrDefault(false)
                    if (stillOld && alertedOn != today) {
                        alertedOn = today
                        runCatching {
                            opsAlert(
                                "Exito login was not renewed",
                                "The daily renewal ran and left the old login in place. The cart button and the order sync stop when it expires.\n" +
                                    "Renew by hand: ./dt run --args=\"--exito-session --renew --save\" (a Chrome window opens; sign in if asked).",
                            )
                        }
                    }
                }
            }
            delay(15 * 60_000L)
        }
    }

    // Reads each search-found item's own page for its size and price, faster than the poll's drip while
    // a backlog exists. Shares the verifier's Chrome profile through VerifySlot, so it simply skips a
    // turn when the poll's run (or a click) has it. Quiet once nothing is left to check.
    if (pollerEnabled && app.defaults.pageCleanupBatch > 0) {
        scope.launch {
            delay(5 * 60_000L)
            while (true) {
                val blocked = pageCleanupOnce(repo, app)
                delay(if (blocked) 60 * 60_000L else app.defaults.pageCleanupEveryMinutes.coerceAtLeast(5) * 60_000L)
            }
        }
    }

    // Questions go to the local `claude` CLI on the operator's own login -- see ClaudeCli for why
    // not the API. Its working directory is an empty folder under build/, so its session files are
    // filed apart from this repo's own Claude Code history.
    val claudePath = ClaudeCli.resolveExecutable(app.defaults.askClaudePath)
    val ask = AskService(
        ClaudeCli(
            executable = claudePath,
            workDir = File(projectDir, "build/ask"),
            model = app.defaults.askModel,
            effort = app.defaults.askEffort,
            maxBudgetUsd = app.defaults.askMaxBudgetUsd,
            systemPrompt = SYSTEM_PROMPT,
        ),
        maxRows = app.defaults.askMaxRows,
        historyRows = app.defaults.askHistoryRows,
    )
    println("[ask] using $claudePath, model ${app.defaults.askModel}, effort ${app.defaults.askEffort}")

    // The cart button is Exito Laureles only; its seller is that store's own, read from config so the
    // two cannot drift apart.
    val laureles = app.providers.firstOrNull { it.id == com.luis.dealstracker.exito.EXITO_CART_SOURCE }
    val exitoCart = com.luis.dealstracker.exito.ExitoCart(
        http,
        SettingsRepository(Db.dataSource),
        baseUrl = laureles?.baseUrl ?: "https://www.exito.com",
        seller = laureles?.options?.get("regionSeller") ?: "exitocol037",
    )

    // What was bought: the tracker's Exito cart, read every 30 minutes and right after a cart-button add.
    val purchaseLog = com.luis.dealstracker.agent.PurchaseLog(Db.dataSource, exitoCart, SettingsRepository(Db.dataSource))
    if (pollerEnabled) scope.launch {
        while (true) {
            runCatching { purchaseLog.syncExitoCart()?.let(::println) }.onFailure { println("[purchases] failed: ${it.message}") }
            delay(30 * 60_000L)
        }
    }

    // What was ordered: the account's own order history, which also sees orders placed from the phone
    // app's cart. Starts a few minutes after boot, so a restart loop does not hammer the endpoint.
    val exitoOrders = exitoOrderSync(app, http)
    if (pollerEnabled) scope.launch {
        delay(3 * 60_000L)
        while (true) {
            val hours = live().defaults.exitoOrdersEveryHours
            if (hours > 0) runCatching { println(exitoOrders.sync()) }.onFailure { println("[exito-orders] failed: ${it.message}") }
            delay((if (hours > 0) hours * 60L else 60L) * 60_000L)
        }
    }

    // The agents' window on the data: an MCP server on 127.0.0.1:8091 only, a port the Cloudflare tunnel
    // (which forwards 8080) does not reach, behind the token in .mcp-token. See agent.McpHttp.
    // The read-only role is set up only once the port is ours: a second copy (DEALS_HTTP_PORT=8081) finds
    // 8091 taken and must not reset the role's password under the first copy's pool.
    var agentDb: com.luis.dealstracker.agent.AgentDb? = null
    var mcpStarted = false
    val agentTools = com.luis.dealstracker.agent.AgentTools(Db.dataSource, { agentDb }, GroceryRepository(Db.dataSource), ::live)
    runCatching {
        val token = com.luis.dealstracker.agent.mcpToken(File(projectDir, ".mcp-token"))
        com.luis.dealstracker.agent.startMcpServer(com.luis.dealstracker.agent.McpServer(agentTools.tools(), log = ::println), token)
        agentDb = runCatching { com.luis.dealstracker.agent.AgentDb.ensure(Db.dataSource, fileApp.db) }
            .onFailure { println("[mcp] read-only role not set up, the sql tool is off: ${it.message}") }.getOrNull()
        println("[mcp] http://${com.luis.dealstracker.agent.MCP_HOST}:${com.luis.dealstracker.agent.MCP_PORT}/mcp (token in .mcp-token)")
        mcpStarted = true
    }.onFailure { println("[mcp] not started (another copy has the port?): ${it.message}") }

    // The grocery agent: `claude -p` on the Claude Code login, every morning after the crawl, with only
    // the MCP tools above. It runs from a folder outside the repo (see agent.AgentRunner). Only the copy
    // that owns the MCP server runs it, so a second copy never doubles the morning's suggestions.
    val agentRuns = com.luis.dealstracker.agent.AgentRunRepository(Db.dataSource)
    runCatching { agentRuns.failAbandoned() }
    val agentJobs = com.luis.dealstracker.agent.AgentJobs(
        com.luis.dealstracker.agent.AgentRunner(
            ClaudeCli.resolveExecutable(app.defaults.askClaudePath),
            File(System.getProperty("user.home"), "Library/Application Support/deals-tracker/agents"),
            token = { com.luis.dealstracker.agent.mcpToken(File(projectDir, ".mcp-token")) },
            runs = agentRuns,
        ),
        agentRuns, ::live, lang = { com.luis.dealstracker.i18n.AppLanguage.current },
        notify = { title, lines ->
            com.luis.dealstracker.notify.Notifier.notify(title, lines.firstOrNull().orEmpty(), openUrl = "http://localhost:$port/groceries#suggestions", group = "agent")
            (live().discordAgentsWebhook ?: live().discordGroceriesWebhook ?: live().discordWatchesWebhook)?.let {
                com.luis.dealstracker.notify.DiscordWebhook(it).send(title, com.luis.dealstracker.notify.fitDescription(lines), "Deals Tracker · grocery agent", color = 0x2ECC71)
            }
        },
        baseUrl = "http://localhost:$port",
    )
    if (pollerEnabled && mcpStarted) scope.launch {
        delay(2 * 60_000L)
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching { agentJobs.tick()?.let(::println) }.onFailure { println("[agent] failed: ${it.message}") }
            }
            delay(SCHEDULE_TICK_MILLIS)
        }
    }

    // Bound to this Mac only. The phone reaches the app through the Cloudflare tunnel, which connects
    // from here, so nothing on the Wi-Fi needs it. DEALS_HTTP_HOST=0.0.0.0 opens it again.
    val host = System.getenv("DEALS_HTTP_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    println("[web] http://localhost:$port (listening on $host)")
    embeddedServer(Netty, host = host, port = port) {
        routes(repo, watches, ingest, manualService, manualItems, app, categorizer, scope, ask, rates, GroceryRepository(Db.dataSource), disabledSearches, com.luis.dealstracker.db.DailyChangeRepository(Db.dataSource), exitoCart, ops = ops, purchaseLog = purchaseLog, agents = if (mcpStarted) agentJobs else null)
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
    delayJitterMillis = app.defaults.coShippingDelayJitterMillis,
    maxPerRun = app.defaults.coShippingMaxPerRun,
    // The Tracked tab's own providers, so the tab and the queue's priority cannot drift apart.
    prioritySources = trackingSources(app),
    headless = headless,
    wallWaitMillis = wallWaitMillis,
    // The Amazon provider's selectors, so a corrected price selector reaches the page-price read too.
    amazonOptions = app.providers.firstOrNull { it.type == "amazon" }?.options.orEmpty(),
    pageRecheckDays = app.defaults.pageRecheckDays,
    pinnedPageRecheckHours = app.defaults.pinnedPageRecheckHours,
    shippingRecheckDays = app.defaults.shippingRecheckDays,
)

private fun summarize(outcome: VerifyOutcome): String =
    "[verify] done: ${outcome.checked} checked — " +
        "${outcome.yes} ships, ${outcome.no} does not, ${outcome.unclear} unclear" +
        (if (outcome.protectedManual > 0) ", ${outcome.protectedManual} manual verdict(s) left untouched" else "") +
        (if (outcome.sizeRejected > 0) ", ${outcome.sizeRejected} retired for the wrong size" else "") +
        (if (outcome.siblingsAdded > 0) ", ${outcome.siblingsAdded} right-size replacement(s) added" else "") +
        (if (outcome.priceCorrected > 0) ", ${outcome.priceCorrected} price(s) corrected from the page" else "") +
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

/** One cleanup run. Returns true when Amazon blocked it, so the caller backs off. */
private suspend fun pageCleanupOnce(repo: DealRepository, app: AppConfig): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val sources = trackingSources(app)
            if (repo.pageCheckCandidates(
                    1, sources, recheckDays = app.defaults.pageRecheckDays,
                    pinnedRecheckHours = app.defaults.pinnedPageRecheckHours,
                    retireAfterHours = com.luis.dealstracker.verify.retireAfterHours(
                        app.providers.firstOrNull { it.type == "amazon" }?.options.orEmpty(),
                    ),
                ).isEmpty() &&
                repo.siblingRecoveryCandidates(1, sources).isEmpty()
            ) {
                return@runCatching false
            }
            val cfg = verifyConfig(
                app,
                batchSize = app.defaults.pageCleanupBatch,
                wallWaitMillis = app.defaults.coShippingPollWallWaitMillis,
            )
            val outcome = VerifySlot.tryWith(
                VerifySlot.lockFileFor(cfg.profileDir),
                onBusy = { null },
            ) { AmazonVerifier(repo, cfg).run(limit = app.defaults.pageCleanupBatch) }
                ?: return@runCatching false
            if (outcome.checked > 0 || outcome.blocked) println("[cleanup] " + summarize(outcome))
            outcome.blocked
        }.getOrElse {
            println("[cleanup] run failed: ${it.message}")
            false
        }
    }

/**
 * Loads the notification language from `app_settings` and saves it there when it changes. A
 * missing table or row leaves English, so a failure here costs Spanish banners, never the boot.
 */
private fun initAppLanguage() {
    val settings = com.luis.dealstracker.db.SettingsRepository(Db.dataSource)
    val saved = runCatching { settings.get(com.luis.dealstracker.db.SettingsRepository.LANGUAGE) }.getOrNull()
    val chosen = runCatching { settings.get(com.luis.dealstracker.db.SettingsRepository.LANGUAGE_CHOSEN) }.getOrNull()
    com.luis.dealstracker.i18n.AppLanguage.init(
        com.luis.dealstracker.i18n.Lang.fromCode(saved),
        chosen == "true",
    ) { lang, isChosen ->
        settings.set(
            mapOf(
                com.luis.dealstracker.db.SettingsRepository.LANGUAGE to lang.code,
                com.luis.dealstracker.db.SettingsRepository.LANGUAGE_CHOSEN to isChosen.toString(),
            ),
        )
    }
}

/** The order reader, with every provider that sells from exito.com and the store each is priced at. */
private fun exitoOrderSync(app: com.luis.dealstracker.config.AppConfig, http: com.luis.dealstracker.sources.PoliteHttp) =
    com.luis.dealstracker.exito.ExitoOrderSync(
        http,
        SettingsRepository(Db.dataSource),
        com.luis.dealstracker.exito.ExitoOrderRepository(Db.dataSource),
        com.luis.dealstracker.exito.exitoStores(app.providers.map { Triple(it.id, it.baseUrl, it.options["regionSeller"]) }),
    )
