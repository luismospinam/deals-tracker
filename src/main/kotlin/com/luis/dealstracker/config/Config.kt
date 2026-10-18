package com.luis.dealstracker.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.io.File
import java.math.BigDecimal
import java.util.Properties

/**
 * A single site to poll, bound to a protocol by [type].
 *
 * Adding a site that speaks a protocol we already support (another VTEX
 * retailer, another Slickdeals feed, another MercadoLibre country) is a config
 * entry and nothing else. A genuinely new platform needs a new [type] plus an
 * adapter registered in SourceRegistry.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    /** Feed URL for RSS providers. */
    val url: String? = null,
    /** Site root for API providers, e.g. https://www.exito.com */
    val baseUrl: String? = null,
    /** MercadoLibre site id, e.g. MCO for Colombia. */
    val siteId: String? = null,
    val currency: String = "COP",
    val coShippingDefault: String = "UNKNOWN",
    /** Overrides the global discount floor for this provider. */
    val minDiscountPercent: Double? = null,
    val options: Map<String, String> = emptyMap(),
    /** List-valued options (categories, allowedMerchants) kept separate for YAML clarity. */
    val listOptions: Map<String, List<String>> = emptyMap(),
) {
    fun requireBaseUrl(): String =
        baseUrl ?: error("provider '$id' (type=$type) requires baseUrl in config/sources.yaml")

    fun requireUrl(): String =
        url ?: error("provider '$id' (type=$type) requires url in config/sources.yaml")

    fun list(key: String): List<String> = listOptions[key] ?: emptyList()

    fun int(key: String, default: Int): Int = options[key]?.toIntOrNull() ?: default
}

@Serializable
data class Defaults(
    /** Discount floor for promoting a deal to the feed. */
    val minDiscountPercent: Double = 25.0,
    val pollIntervalMinutes: Long = 60,
    /** Identifies this tool to the sites it polls. */
    val userAgent: String = "deals-tracker/0.1 (personal price monitor; contact: local user)",
    /** Politeness delay between requests to the same host. */
    val perHostDelayMillis: Long = 1_200,
    val httpPort: Int = 8080,

    // --- Amazon -> Colombia shipping verification ---
    /** Destination country whose eligibility is being checked. */
    val coShippingCountry: String = "Colombia",
    /** Deals per run. Low on purpose: a slow drip is what Amazon tolerates. */
    val coShippingBatchSize: Int = 10,
    /** Politeness floor between product pages, in milliseconds. */
    val coShippingDelayMillis: Long = 30_000,
    /** Hard ceiling per run regardless of batch size or --limit. */
    val coShippingMaxPerRun: Int = 40,

    /**
     * Verify after every Nth poll cycle. 0 disables the automatic run entirely.
     *
     * Still a slow drip rather than an attempt to keep up: new Amazon deals arrive far
     * faster than [coShippingPollBatch] can clear them, so the backlog grows regardless.
     *
     * This was 3, to spare the session's trust. That rationale leaned on a page-load
     * ceiling measured while a false wall detection was recording healthy pages as blocked,
     * so the number it was protecting was never real. What settled it is the counter: it is
     * process state, and every restart resets it. At 3 with hourly polls the app needed two
     * unbroken hours to verify anything, and never got them -- the automatic run fired zero
     * times across days of ordinary use. At 1 the first poll after startup verifies, which
     * is the only cadence that outlives a restart.
     *
     * The real fix is to derive this from the last recorded check rather than from process
     * uptime, since `co_shipping_checked_at` already knows. Deliberately not done: 1 makes
     * the mechanism moot in practice.
     */
    val coShippingEveryNPolls: Int = 1,
    /**
     * Deals per automatic run.
     *
     * Sized against measured arrivals rather than caution alone. Slickdeals brings 220-313
     * new deals a day, so the original 5 per hour (120 a day) fell behind by roughly 110 a
     * day — the backlog reached 503 while the log made it look like progress. 15 an hour is
     * 360 a day, which was meant to drain that backlog in about four days of uptime and then
     * keep pace. It did not: measured 2026-09-23, 1,179 of 1,688 active Amazon rows were
     * still UNKNOWN against 336 YES and 173 NO. Hence 20, i.e. 480 a day.
     *
     * Note what this buys the feed, since "shipping check" sounds peripheral: an unverified
     * row scores as though freight were free, and [com.luis.dealstracker.db.DealScore]'s
     * freight term is the largest single penalty in the ranking at 60 points. A thousand rows
     * with that term pinned at zero is the "costs more to ship than to buy, ranked among the
     * best deals on the page" failure, still live.
     *
     * Not raised further because Amazon page loads per cycle are now ~68 — 45 for
     * `AmazonSource` (15 terms × 3 pages), 20 here, 3 for the manual refresh — against a
     * documented headed-session ceiling of 40-55. That ceiling was measured while a false
     * wall detection was recording healthy pages as blocked, so the true figure is unknown
     * and this is already past the written one with no refusal in the logs. Unknown headroom
     * gets spent in steps, not in one go.
     *
     * Still bounded by [coShippingMaxPerRun], and still leaves the session's remaining
     * tolerance for the Verify button and manual runs aimed at deals worth buying. Also
     * diminishing by construction: `coShippingCandidates` orders by `DealScore`, so the 20th
     * deal in a run is less attractive than the 15th was.
     */
    val coShippingPollBatch: Int = 20,
    /**
     * How long an automatic run waits for a human to clear an Amazon wall before giving up.
     *
     * Much shorter than a manual run's 180s. Nobody is necessarily watching, and a blocked
     * automatic run should end and let the next cycle retry rather than hold a Chrome window
     * open for three minutes waiting for a click that may never come.
     */
    val coShippingPollWallWaitMillis: Long = 45_000,
    /**
     * Rows per run of the page-check cleanup, which reads each search-found item's own size and price
     * (see V23). Runs on its own timer, between and in addition to the poll's verification, for as long
     * as unchecked rows remain, then goes quiet. 0 turns it off.
     *
     * 40 is the known-good batch (a 40-item run came back clean); the headed-session ceiling is
     * 40-55 loads before Amazon walls.
     */
    val pageCleanupBatch: Int = 40,
    /**
     * Product pages read per poll cycle to capture Falabella and Homecenter specifications, which live
     * only on each page (~1 MB). Only products an enabled watch is hunting are read. 0 turns it off.
     */
    val specPageBatch: Int = 30,
    /**
     * Days after which a confirmed row's own page is read again, for a page-true price and a fresh look
     * at stock. A size never changes, so this is about price, not size. The cleanup loop therefore never
     * goes fully idle: roughly one row in [pageRecheckDays] comes due each day. 0 turns it off.
     */
    val pageRecheckDays: Int = 7,
    /** Days after which a `YES` shipping verdict's fee is read again from the page. 0 turns it off. */
    val shippingRecheckDays: Int = 7,
    /** Minutes between cleanup runs while there is a backlog. A block backs off for an hour instead. */
    val pageCleanupEveryMinutes: Int = 20,

    /**
     * Price readings older than this many days are thinned: within an unchanged stretch only the first
     * is kept (see `HistoryPruner`). Anything that changed -- price, list price, card price, or the
     * promotions in force -- is kept. Pinned items are never touched. 0 turns pruning off.
     */
    val pruneHistoryAfterDays: Int = 14,
    /** Days between automatic prunes. The last run is remembered in `app_settings`, so a restart does not reset it. */
    val pruneHistoryEveryDays: Int = 7,

    /**
     * Local times of day the pinned-items-on-sale posts go to Discord. 08:00 is the morning read; 19:00
     * sits after the last grocery crawl window ends (18:45). Move the second if a window in sources.yaml
     * moves. See docs/pinned-notifications.md.
     */
    val pinnedAlertTimes: List<String> = listOf("08:00", "19:00"),

    /**
     * Warn when this percentage of active deals has landed in the `Other` category. 0 disables.
     *
     * Category rules need gardening as the sources' catalogs shift, and there was a feedback
     * loop for it — `--recategorize --show-other` — but nothing that said *when* to run it. The
     * tuning that took `Other` from 73 down to 33 of 612 has since drifted back to 78 of 618
     * (12.6%) without a single line of output remarking on it, because the per-ingest count is
     * a share of one 25-item batch and reads as noise.
     *
     * 10% is set just under the observed drift so the condition is live rather than theoretical:
     * an over-broad or missing rule should announce itself, the same way an excluded title or a
     * failed fetch already does.
     */
    val categoryOtherWarnPercent: Int = 10,

    /**
     * Pesos per US dollar when Colombia's official TRM cannot be fetched.
     *
     * Watches compare in COP, and an Amazon TV is quoted in USD, so some rate is always needed.
     * The live one comes from datos.gov.co once a day (see `ExchangeRates`); this is only what is
     * used before that first succeeds or while it keeps failing, and the alert text says so.
     */
    val usdCopRate: Double = 3200.0,

    /**
     * Any non-zero value reshuffles the feed's default ordering on every page load; 0 turns it off
     * and makes the ordering a pure function of the score. (It was once a bucket length in hours.
     * The name is kept so existing `sources.yaml` files still parse.)
     *
     * Exists because a deterministic score gives an identical page on every visit: most of its
     * inputs never change. See `DealScore.rotating` for the bound on how far a row can move.
     */
    val feedRotationHours: Int = 3,

    // --- Ask Claude panel ---
    /**
     * Model for the Ask panel, as `claude --model` takes it.
     *
     * Sonnet rather than Haiku, on a measurement rather than a price list: asked for the best
     * full-motion mount among 139 watch candidates, Haiku 4.5 spent 9.4K output tokens reasoning
     * and cost $0.054 in 65s, where Sonnet 5 answered in 1.8K tokens for $0.035 in 22s — and was
     * the one that flagged the USD row and the missing height-adjustment spec. Cheaper per token
     * is not cheaper per answer.
     */
    val askModel: String = "sonnet",
    /** `claude --effort`. Lower means shorter reasoning, which is most of an answer's cost. */
    val askEffort: String = "low",
    /**
     * Hard ceiling per question, passed as `--max-budget-usd`. Sized against the row cap below: a
     * full 500-row screen is ~25K tokens, measured at roughly $0.10 for the first question and a
     * little more for each follow-up (a resume replays the transcript). So this only bites on a
     * runaway answer, which is the case it exists for.
     */
    val askMaxBudgetUsd: Double = 0.30,
    /**
     * Most rows sent with a question. ~50 tokens each, so 500 is ~25K tokens.
     *
     * Was 200, which cut the "Soporte TV" watch — 335 candidates — to its 200 cheapest, and the
     * answer then had to say it could not see the rest. The whole point of asking a watch is "which
     * of everything I'm hunting is best", so the cap is set to cover any real watch whole. It still
     * binds on the unfiltered feed (1,000+ rows), where the prompt says how many were left out.
     */
    val askMaxRows: Int = 500,
    /**
     * How many of those rows also get their recent price changes (~25 tokens each where the price
     * moved). The top of the screen's order, since that is what a question is mostly about; 0 off.
     */
    val askHistoryRows: Int = 100,
    /**
     * The `claude` executable. Blank means "find it": PATH first, then `~/.local/bin/claude`,
     * where the installer puts it — launchd starts the app with a bare PATH that lacks that
     * directory, so relying on PATH alone would work from a terminal and fail in the background.
     */
    val askClaudePath: String = "",
)

@Serializable
data class SourcesFile(
    val defaults: Defaults = Defaults(),
    val providers: List<ProviderConfig> = emptyList(),
)

data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
)

class AppConfig(
    val defaults: Defaults,
    val providers: List<ProviderConfig>,
    val db: DbConfig,
    /**
     * MercadoLibre app credentials, both read from gitignored local.properties.
     *
     * The client id lives there rather than in the committed sources.yaml. It is a public
     * identifier by OAuth's design, not a secret, but it still identifies a specific
     * personal app -- and keeping the pair together is simpler to reason about than
     * splitting them across a committed and an ignored file.
     */
    val mlClientId: String?,
    val mlClientSecret: String?,
    /** Discord webhook URLs, one channel per section; null leaves that section's post off. From `.env`. */
    val discordGroceriesWebhook: String? = null,
    val discordTrackedWebhook: String? = null,
) {
    val enabledProviders: List<ProviderConfig> get() = providers.filter { it.enabled }

    /** Effective discount floor for a provider, falling back to the global default. */
    fun minDiscount(provider: ProviderConfig): BigDecimal =
        BigDecimal.valueOf(provider.minDiscountPercent ?: defaults.minDiscountPercent)

    companion object {
        private val yaml = Yaml(
            configuration = YamlConfiguration(strictMode = false),
        )

        fun load(projectDir: File = File(".")): AppConfig {
            val sourcesFile = File(projectDir, "config/sources.yaml")
            require(sourcesFile.exists()) { "missing ${sourcesFile.path}" }
            val parsed = yaml.decodeFromString(SourcesFile.serializer(), sourcesFile.readText())

            val duplicates = parsed.providers.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate provider ids in sources.yaml: $duplicates" }

            // Secrets come from gitignored files, never from sources.yaml.
            val local = Properties().apply {
                File(projectDir, "local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            val env = readDotEnv(File(projectDir, ".env"))

            fun setting(envKey: String, default: String): String =
                System.getenv(envKey) ?: env[envKey] ?: default

            val db = DbConfig(
                jdbcUrl = System.getenv("DATABASE_URL")
                    ?: "jdbc:postgresql://localhost:5432/${setting("POSTGRES_DB", "deals")}",
                user = setting("POSTGRES_USER", "deals"),
                password = setting("POSTGRES_PASSWORD", "")
                    .ifBlank { error("POSTGRES_PASSWORD not set. Copy .env.example to .env and set it.") },
            )

            return AppConfig(
                defaults = parsed.defaults,
                providers = parsed.providers,
                db = db,
                mlClientId = System.getenv("ML_CLIENT_ID")
                    ?: local.getProperty("ml.clientId")?.takeIf { it.isNotBlank() },
                mlClientSecret = System.getenv("ML_CLIENT_SECRET")
                    ?: local.getProperty("ml.clientSecret")?.takeIf { it.isNotBlank() },
                discordGroceriesWebhook = setting("DISCORD_GROCERIES_WEBHOOK_URL", "").ifBlank { null },
                discordTrackedWebhook = setting("DISCORD_TRACKED_WEBHOOK_URL", "").ifBlank { null },
            )
        }

        private fun readDotEnv(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()
            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .associate { line ->
                    val (k, v) = line.split("=", limit = 2)
                    k.trim() to v.trim().removeSurrounding("\"").removeSurrounding("'")
                }
        }
    }
}
