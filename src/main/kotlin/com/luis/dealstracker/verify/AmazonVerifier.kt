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
    /**
     * Discard the profile's HTTP cache once it exceeds this many megabytes. 0 disables.
     *
     * A persistent profile grows without bound, and past roughly a gigabyte Chrome starts
     * failing to reach `load` at all: every navigation timed out at 30s and the run died with
     * "pipe closed", while plain curl fetched the same URL in 3.3s. The profile had reached
     * 1.5GB across 50,670 files, 1.3GB of it cache.
     *
     * 400 is well under where the failure appeared but high enough that the cache still does
     * its job between runs.
     */
    val maxProfileCacheMb: Long = 400,
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

    /**
     * @param only verify just this one deal, for the UI's Verify button. Bypasses the
     *   backoff window that batch runs respect — backoff throttles unattended retries,
     *   whereas a click is a person asking for this deal now.
     */
    fun run(limit: Int? = null, only: Long? = null): VerifyOutcome {
        val candidates = if (only != null) {
            listOfNotNull(repo.coShippingCandidate(only))
        } else {
            val take = (limit ?: config.batchSize).coerceAtMost(config.maxPerRun)
            repo.coShippingCandidates(take)
        }

        // Never a silent skip: deals with no ASIN cannot be looked up by this route and
        // that fact has to surface, or the totals quietly stop adding up. Only for batch
        // runs -- for a single click the count of *other* unreachable deals is noise.
        if (only == null) {
            val uncheckable = repo.coShippingUncheckableCount()
            if (uncheckable > 0) {
                println("[verify] $uncheckable active UNKNOWN deal(s) have no ASIN and cannot be checked this way")
            }
        }
        if (candidates.isEmpty()) {
            println("[verify] nothing to check (all decided, MANUAL, or still in backoff)")
            return VerifyOutcome()
        }
        println("[verify] checking ${candidates.size} deal(s), ${config.delayMillis / 1000}s apart, country=${config.country}")

        var outcome = VerifyOutcome()
        trimProfileCache(config.profileDir, config.maxProfileCacheMb)

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
                progressTotal = candidates.size
                var lastHitAmazon = false
                for ((i, candidate) in candidates.withIndex()) {
                    val (dealId, asin, title) = candidate
                    progressDone = i
                    // Only pause when the previous iteration actually hit Amazon; deals
                    // resolved from the title cost no request and need no cooling off.
                    if (i > 0 && lastHitAmazon) {
                        showStatus(page, i, candidates.size, "pausing ${config.delayMillis / 1000}s to stay polite")
                        page.waitForTimeout(config.delayMillis.toDouble())
                    }
                    lastHitAmazon = false

                    // Digital items are identifiable from the stored title alone, so resolve
                    // them WITHOUT loading a page. Amazon requests are the scarce resource
                    // here, and there is nothing on the page to learn: a Kindle book renders
                    // no shipping widget at all.
                    val titleOnly = PageSignals(title = title)
                    if (extractor.isDigital(titleOnly)) {
                        val v = extractor.extract(titleOnly)
                        repo.recordCoShippingCheck(
                            dealId, v.value, v.unclear, v.evidence, v.importChargesIncluded, v.shippingCost,
                        )
                        outcome = outcome.copy(checked = outcome.checked + 1, yes = outcome.yes + 1)
                        println("[verify] $asin → ${v.value.name}  (digital, no page load needed)")
                        continue
                    }
                    lastHitAmazon = true

                    val signals = readSignals(page, asin)?.copy(title = title)
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

    /**
     * Shows what the run is doing, always — not only when it needs help.
     *
     * Each product involves ~30s of intentional waiting (a 15s politeness delay plus AJAX
     * polling), which is indistinguishable from a hang. Stating the progress and the reason
     * for the pause removes the ambiguity.
     */
    private fun showStatus(page: Page, done: Int, total: Int, note: String) {
        // Always create-or-update. Navigation destroys the DOM, so an update-only path
        // meant the banner vanished after the first page and never came back.
        val label = if (done >= 0) "verified $done of $total · $note" else note
        runCatching {
            page.evaluate(
                """
                ([done, total, note]) => {
                  let b = document.getElementById('dt-status-banner');
                  if (!b) {
                    b = document.createElement('div');
                    b.id = 'dt-status-banner';
                    b.style.cssText = 'position:fixed;bottom:0;left:0;right:0;z-index:2147483646;' +
                      'background:#171a21;color:#9aa4b6;font:600 13px/1.4 -apple-system,sans-serif;' +
                      'padding:9px 16px;text-align:center;border-top:2px solid #5b9dff';
                    document.body.appendChild(b);
                  }
                  b.textContent = 'Deals Tracker — verified ' + done + ' of ' + total +
                    ' · ' + note + ' · no action needed unless an amber banner appears';
                }
                """.trimIndent(),
                listOf(done, total, note),
            )
        }
    }

    /**
     * Makes the "I need a human" state impossible to miss.
     *
     * Previously this was only printed to the terminal, so the window looked frozen while
     * it silently waited — the operator had no way to know a click was wanted. Now it
     * overlays a banner on the page itself and fires a desktop notification.
     */
    private fun askForHelp(page: Page, asin: String) {
        runCatching {
            page.evaluate(
                """
                (asin) => {
                  if (document.getElementById('dt-help-banner')) return;
                  const b = document.createElement('div');
                  b.id = 'dt-help-banner';
                  b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
                    'background:#f5a623;color:#1a1a1a;font:600 16px/1.5 -apple-system,sans-serif;' +
                    'padding:14px 18px;text-align:center;box-shadow:0 2px 12px rgba(0,0,0,.4)';
                  b.textContent = 'Deals Tracker needs you: click Amazon\'s "Continue shopping" ' +
                    'button below to carry on verifying (' + asin + '). Waiting…';
                  document.body.appendChild(b);
                }
                """.trimIndent(),
                asin,
            )
        }
        // Desktop notification, so it is noticed even when the window is not focused.
        runCatching {
            ProcessBuilder(
                "osascript", "-e",
                """display notification "Click 'Continue shopping' in the Chrome window" """ +
                    """with title "Deals Tracker: Amazon needs a human" sound name "Ping"""",
            ).start()
        }
    }

    /**
     * Bot-wall markers on the current page, ignoring anything this tool put there itself.
     *
     * Two independent guards, because the single substring scan this replaced produced a
     * false positive that could not be cleared:
     *
     *  - **A product title means a product page.** Amazon's wall *replaces* the listing; it
     *    does not decorate it. So if `#productTitle` renders, we are looking at the product
     *    regardless of what strings appear elsewhere in the markup. This is the guard that
     *    matters, since it does not depend on knowing every phrase Amazon might use.
     *  - **Our own overlays are excised before matching.** The help banner names the button
     *    it is asking for, so scanning raw HTML made the banner satisfy the very condition
     *    it was announcing — a wall that sustained itself until the timeout expired.
     */
    private fun wallMarkersOn(page: Page): List<String> {
        val onProductPage = runCatching { page.locator("#productTitle").count() > 0 }
            .getOrDefault(false)
        if (onProductPage) return emptyList()

        val html = runCatching {
            page.evaluate(
                """
                () => {
                  const root = document.documentElement.cloneNode(true);
                  root.querySelectorAll('#dt-help-banner, #dt-status-banner')
                      .forEach(e => e.remove());
                  return root.outerHTML;
                }
                """.trimIndent(),
            ) as? String
        }.getOrNull()
            ?: runCatching { page.content() }.getOrDefault("")

        return botWallMarkers(html)
    }

    /** Returns null when Amazon is blocking and the operator did not clear it. */
    private var progressDone = 0
    private var progressTotal = 0

    private fun readSignals(page: Page, asin: String): PageSignals? {
        page.navigate("https://www.amazon.com/dp/$asin")
        page.waitForLoadState()
        // Re-assert after navigation: the previous page's banner went with the old DOM.
        showStatus(page, progressDone, progressTotal, "reading shipping info for $asin")

        var walls = wallMarkersOn(page)
        if (walls.isNotEmpty()) {
            println("[verify] human check shown (${walls.first()}) — please clear it in the window")
            askForHelp(page, asin)
            val until = System.currentTimeMillis() + config.wallWaitMillis
            while (System.currentTimeMillis() < until && walls.isNotEmpty()) {
                page.waitForTimeout(3000.0)
                walls = wallMarkersOn(page)
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
            buybox = text(page, "#buybox"),
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

/**
 * Deletes the profile's HTTP cache when it grows past [maxMb], leaving the session alone.
 *
 * Only the cache directories, which Chrome rebuilds on demand. Cookies, `Login Data` and
 * `Preferences` are separate files and are never touched -- which is what makes this safe to
 * run unattended, since the whole value of this profile is the trusted, signed-in session with
 * a Colombian delivery address that Amazon has come to accept. Re-establishing that costs a
 * human; a cold cache costs a few seconds.
 *
 * Runs before launch rather than after: Chrome holds these files open while it lives, and the
 * point is to hand it a profile it can actually work with.
 */
internal fun trimProfileCache(profileDir: String, maxMb: Long) {
    if (maxMb <= 0) return
    val default = File(profileDir, "Default")
    if (!default.isDirectory) return

    // "Cache" alone was 1.3GB of the 1.5GB, but the others regrow too and are equally
    // disposable. Names are Chrome's own, and a missing one is simply skipped.
    val caches = listOf("Cache", "Code Cache", "GPUCache", "DawnWebGPUCache", "DawnGraphiteCache")
        .map { File(default, it) }
        .filter { it.exists() }
    if (caches.isEmpty()) return

    val bytes = caches.sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    val mb = bytes / (1024 * 1024)
    if (mb < maxMb) return

    println("[verify] profile cache is ${mb}MB (limit ${maxMb}MB) — clearing it; the signed-in session is kept")
    caches.forEach { dir ->
        // Best-effort: a cache we fail to delete is not worth aborting a run over, and the
        // next run tries again.
        runCatching { dir.deleteRecursively() }
            .onFailure { println("[verify] could not clear ${dir.name}: ${it.message}") }
    }
}
