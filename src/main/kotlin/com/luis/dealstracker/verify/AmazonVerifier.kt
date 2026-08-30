package com.luis.dealstracker.verify

import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.model.CoShipping
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File
import java.nio.file.Paths

/**
 * Tunables for the Colombia shipping check. Config-driven so the pacing can be changed
 * without a rebuild — the limits exist to stay within what Amazon tolerates, and that
 * threshold is discovered empirically, not known up front.
 */
data class CoShippingConfig(
    val country: String = "Colombia",
    /** Deals per run. Low by default: this is a slow drip, not a sweep. */
    val batchSize: Int = 10,
    /** Politeness floor between page loads. */
    val delayMillis: Long = 15_000,
    /** Hard ceiling per run, regardless of batchSize. */
    val maxPerRun: Int = 100,
    /** Headed by default: Amazon periodically wants a human, and headless cannot ask. */
    val headless: Boolean = false,
    val profileDir: String = ".amazon-profile",
    /** How long to wait for the operator to clear a human check before giving up. */
    val wallWaitMillis: Long = 180_000,
    /** `#availability` and `#deliveryBlockMessage` are both AJAX-filled; they must be polled. */
    val signalTimeoutMillis: Long = 20_000,
)

data class VerifyOutcome(
    val checked: Int = 0,
    val yes: Int = 0,
    val no: Int = 0,
    val unclear: Int = 0,
    val protectedManual: Int = 0,
    val blocked: Boolean = false,
)

/**
 * Verifies Amazon → Colombia shipping eligibility for stored deals.
 *
 * Runs **assisted**: unattended requests from a datacenter IP were blocked after ~7 page
 * loads, and Amazon's wall is a single button a person can clear. So the browser is headed
 * by default, and when Amazon asks for a human the run pauses and says so.
 *
 * Boundaries this deliberately keeps:
 *  - Never solves a challenge, never spoofs a fingerprint, never rotates anything. A block
 *    is reported and the run stops.
 *  - Never handles credentials. If the operator signs in, they do it themselves in the
 *    window; the persistent profile then carries that session forward.
 *  - Never overwrites a MANUAL verdict — enforced in SQL, not by remembering to check.
 */
class AmazonVerifier(
    private val repo: DealRepository,
    private val config: CoShippingConfig = CoShippingConfig(),
) {
    private val extractor = CoShippingExtractor(config.country)

    fun run(limit: Int? = null): VerifyOutcome {
        val take = (limit ?: config.batchSize).coerceAtMost(config.maxPerRun)
        val candidates = repo.coShippingCandidates(take)
        val uncheckable = repo.coShippingUncheckableCount()

        // Never a silent skip: deals with no ASIN cannot be looked up by this route and
        // that fact has to surface, or the totals quietly stop adding up.
        if (uncheckable > 0) {
            println("[verify] $uncheckable active UNKNOWN deal(s) have no ASIN and cannot be checked this way")
        }
        if (candidates.isEmpty()) {
            println("[verify] nothing to check (all decided, MANUAL, or still in backoff)")
            return VerifyOutcome()
        }
        println("[verify] checking ${candidates.size} deal(s), ${config.delayMillis / 1000}s apart, country=${config.country}")

        var outcome = VerifyOutcome()
        Playwright.create().use { pw ->
            val ctx = pw.chromium().launchPersistentContext(
                Paths.get(File(config.profileDir).absolutePath),
                BrowserType.LaunchPersistentContextOptions()
                    .setChannel("chrome")
                    .setHeadless(config.headless)
                    .setViewportSize(1400, 950),
            )
            val page = ctx.pages().firstOrNull() ?: ctx.newPage()
            try {
                if (!ensureCountry(ctx, page)) {
                    println("[verify] destination country is not set — stopping before any check")
                    return VerifyOutcome(blocked = true)
                }

                // A plain loop, not forEachIndexed: `return@forEachIndexed` is *continue*,
                // not break, so the previous version printed "stopping the run" and then
                // carried on hitting a site that had just objected.
                for ((i, candidate) in candidates.withIndex()) {
                    val (dealId, asin) = candidate
                    if (i > 0) page.waitForTimeout(config.delayMillis.toDouble())

                    val signals = readSignals(page, asin)
                    if (signals == null) {
                        // Record the failed attempt before leaving. Without this the deal
                        // keeps checked_at=NULL and attempts=0, so it stays a fresh
                        // candidate forever with no backoff -- the exact retry loop the V5
                        // state model exists to prevent, reintroduced via this path.
                        repo.recordCoShippingCheck(
                            dealId, CoShipping.UNKNOWN, unclear = true,
                            evidence = "blocked by Amazon human check", importFree = null,
                            shippingCost = null,
                        )
                        println("[verify] blocked at $asin — stopping the run (attempt recorded, will back off)")
                        outcome = outcome.copy(blocked = true, unclear = outcome.unclear + 1)
                        break
                    }

                    val verdict = extractor.extract(signals)
                    val written = repo.recordCoShippingCheck(
                        dealId, verdict.value, verdict.unclear, verdict.evidence,
                        verdict.importChargesIncluded, verdict.shippingCost,
                    )
                    outcome = when {
                        !written -> outcome.copy(protectedManual = outcome.protectedManual + 1)
                        verdict.unclear -> outcome.copy(checked = outcome.checked + 1, unclear = outcome.unclear + 1)
                        verdict.value.name == "YES" -> outcome.copy(checked = outcome.checked + 1, yes = outcome.yes + 1)
                        else -> outcome.copy(checked = outcome.checked + 1, no = outcome.no + 1)
                    }
                    val label = if (verdict.unclear) "UNCLEAR" else verdict.value.name
                    val ship = verdict.shippingCost?.let {
                        if (it.signum() == 0) "  free shipping" else "  shipping +\$$it"
                    } ?: ""
                    println("[verify] $asin → $label$ship${if (!written) " (skipped: manual verdict protected)" else ""}")
                    println("[verify]   evidence: ${verdict.evidence.take(110)}")
                }
            } finally {
                ctx.close()
            }
        }
        return outcome
    }

    /**
     * Confirms the session ships to [CoShippingConfig.country], waiting for the operator to
     * set it if not. Detected via the `sp-cdn` cookie rather than page text, because the
     * visible location element is absent on some page variants.
     */
    private fun ensureCountry(ctx: BrowserContext, page: Page): Boolean {
        if (countryOf(ctx) != null && countryOf(ctx) == COUNTRY_CODES[config.country]) return true

        println("[verify] destination is not ${config.country} yet — opening amazon.com")
        page.navigate("https://www.amazon.com/")
        page.waitForLoadState()
        println(
            """
            |
            | ACTION NEEDED in the browser window:
            |   • clear any "continue shopping" check
            |   • optionally sign in (type it yourself; nothing is read or stored here)
            |   • set delivery to ${config.country}: "Delivering to …" → "or ship outside the US"
            |
            | Waiting up to ${config.wallWaitMillis / 1000}s; this continues by itself once the cookie changes.
            """.trimMargin(),
        )
        val deadline = System.currentTimeMillis() + config.wallWaitMillis
        while (System.currentTimeMillis() < deadline) {
            if (countryOf(ctx) == COUNTRY_CODES[config.country]) {
                println("[verify] ✓ ${config.country} confirmed")
                return true
            }
            page.waitForTimeout(2500.0)
        }
        return false
    }

    /** Returns null when Amazon is blocking and the operator did not clear it. */
    private fun readSignals(page: Page, asin: String): PageSignals? {
        page.navigate("https://www.amazon.com/dp/$asin")
        page.waitForLoadState()

        var walls = runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList())
        if (walls.isNotEmpty()) {
            println("[verify] human check shown (${walls.first()}) — please clear it in the window")
            val until = System.currentTimeMillis() + config.wallWaitMillis
            while (System.currentTimeMillis() < until && walls.isNotEmpty()) {
                page.waitForTimeout(3000.0)
                walls = runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList())
            }
            if (walls.isNotEmpty()) return null
            println("[verify] cleared — continuing")
            page.navigate("https://www.amazon.com/dp/$asin")
            page.waitForLoadState()
        }

        // Poll: both regions are empty on load and filled by AJAX. Reading once would make
        // every deal look like "no information".
        val deadline = System.currentTimeMillis() + config.signalTimeoutMillis
        var availability = ""
        var delivery = ""
        while (System.currentTimeMillis() < deadline) {
            availability = text(page, "#availability")
            delivery = text(page, "#deliveryBlockMessage")
            if (availability.isNotEmpty() || delivery.isNotEmpty()) break
            page.waitForTimeout(750.0)
        }
        return PageSignals(
            availability = availability,
            deliveryBlock = delivery,
            amazonGlobal = text(page, "#amazonGlobal_feature_div"),
        )
    }

    private fun text(page: Page, selector: String): String =
        runCatching { page.locator(selector).first().innerText().trim() }.getOrDefault("")

    private fun countryOf(ctx: BrowserContext): String? =
        ctx.cookies().firstOrNull { it.name == "sp-cdn" }
            ?.value?.substringAfterLast(':')?.trim('"', ' ')?.takeIf { it.isNotEmpty() }

    companion object {
        /** Only what is needed; extend when another destination is actually used. */
        private val COUNTRY_CODES = mapOf("Colombia" to "CO", "Mexico" to "MX")
    }
}
