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
     * 360 a day, which drains that backlog in about four days of uptime and then keeps pace.
     *
     * Still bounded by [coShippingMaxPerRun], and still leaves the session's remaining
     * tolerance for the Verify button and manual runs aimed at deals worth buying.
     */
    val coShippingPollBatch: Int = 15,
    /**
     * How long an automatic run waits for a human to clear an Amazon wall before giving up.
     *
     * Much shorter than a manual run's 180s. Nobody is necessarily watching, and a blocked
     * automatic run should end and let the next cycle retry rather than hold a Chrome window
     * open for three minutes waiting for a click that may never come.
     */
    val coShippingPollWallWaitMillis: Long = 45_000,
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
