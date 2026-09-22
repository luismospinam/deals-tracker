package com.luis.dealstracker.sources.amazon

import com.luis.dealstracker.notify.Notifier
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.verify.VerifySlot
import com.luis.dealstracker.verify.botWallMarkers
import com.luis.dealstracker.verify.trimProfileCache
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.Cookie
import java.io.File
import java.nio.file.Paths

/**
 * Fetches amazon.com HTML, cheaply when Amazon allows it and through the trusted browser
 * profile when it does not.
 *
 * ## Why two transports rather than one
 *
 * Measured on 2026-09-17 with the project's own user agent, no spoofing:
 *
 * | request | result |
 * |---|---|
 * | 1st | **200**, full markup — 48 result tiles, 240 price nodes |
 * | 2nd | **200**, same |
 * | 3rd | **503** "Sorry! Something went wrong!", carrying `api-services-support@amazon.com` |
 *
 * So plain HTTP genuinely works and returns everything this adapter needs — for a couple of
 * requests. It is far too cheap to give up on (no browser, no window, no profile lock, no
 * 15-second pacing) and far too fragile to rely on alone.
 *
 * The browser half already exists for the shipping verifier: a persistent Chrome profile in
 * `.amazon-profile` that has accumulated real trust and is driven headed. Reusing it means the
 * fallback is a known quantity rather than a second thing to get blocked.
 *
 * ## Sharing the profile safely
 *
 * Chrome takes an exclusive lock on its `--user-data-dir`, so two Chromes on one profile do
 * not both start — the second hands off to the first and Playwright dies on an opaque closed-
 * target error. `VerifySlot` already arbitrates exactly this between the poller's scheduled
 * verification, the UI's Verify button and `./dt verify`, so this adapter takes the same lock
 * rather than inventing a second mechanism.
 *
 * When the slot is busy the fetch is **abandoned, not queued**: a verification run lasts
 * minutes, and the next poll comes around within the hour. Skipping and logging beats blocking
 * an ingest cycle behind a browser.
 *
 * ## What this deliberately does not do
 *
 * No user-agent spoofing, no header games, no challenge solving. `robots.txt` permits `/s` and
 * `/dp/` for a generic agent — the blanket `Disallow: /` entries there apply to named AI
 * crawlers, which this is not — and the honest way to stay inside that permission is to
 * identify ourselves, go slowly, and stop when told to. A 503 is Amazon saying no; the
 * response is to back off, which is why a wall marker ends the attempt rather than triggering
 * a retry.
 */
internal class AmazonFetcher(
    private val http: PoliteHttp,
    private val allowBrowser: Boolean,
    private val profileDir: String = ".amazon-profile",
    private val logPrefix: String = "amazon",
) {

    /**
     * Set only once the **browser itself** has been walled.
     *
     * Deliberately not "a browser fetch was attempted". The first version set this before
     * escalating, so a *successful* browser read disabled every later fallback in the run: the
     * live log showed `read ... via the browser profile` followed by `already blocked in the
     * browser this run` for every subsequent request, and a run that had a working browser
     * session fell back to nothing and tracked 2 items instead of 12. Giving up is a response
     * to being refused, not to having tried.
     */
    private var browserWalled = false

    /**
     * Returns the page HTML, or null when it could not be read — always having logged why.
     *
     * Null covers three different failures on purpose (transport error, throttle, wall);
     * the caller's response to all three is the same, which is to drop this item from the
     * batch and let a later poll retry.
     *
     * @param expect a selector that must be present for the page to count as rendered. The
     *   browser path waits for it, which is what makes a server-rendered search page reliable
     *   rather than a race against Amazon's own scripts.
     */
    suspend fun html(url: String, expect: String? = null): String? {
        val direct = fetchDirect(url)
        if (direct != null) return direct

        if (!allowBrowser) {
            println("[$logPrefix] blocked and browser fallback is off; skipping $url")
            return null
        }
        if (browserWalled) {
            // The trusted profile has already been refused this run. Hammering it spends the
            // session's remaining goodwill for nothing.
            println("[$logPrefix] the browser was already walled this run; skipping $url")
            return null
        }
        return fetchViaBrowser(url, expect)
    }

    /**
     * The cheap path. Null means "do not trust this response", not necessarily an error.
     *
     * `Accept-Language` is overridden because `PoliteHttp` hardcodes `es-CO,es;q=0.9,en;q=0.8`
     * for the Colombian retailers, and amazon.com honours it by serving Spanish — which would
     * change the very strings the extractor matches on.
     */
    private suspend fun fetchDirect(url: String): String? {
        pace()

        val result = try {
            http.get(
                url,
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    // Overrides PoliteHttp's es-CO default. Two jobs: it keeps size labels in
                    // English ("8.5 Women/7.5 Men" rather than "8,5 mujer/7,5 hombre", which once
                    // cost 33 false "size unavailable" verdicts), and it asks for the US locale
                    // whose currency is USD.
                    "Accept-Language" to "en-US,en;q=0.9",
                    // PoliteHttp keeps no cookie jar, so the currency cannot be carried in one --
                    // it is sent explicitly on every request instead. Without this the HTTP path,
                    // which serves most fetches, was free to come back in COP while the browser
                    // path returned USD, putting both units in one item's price history.
                    "Cookie" to "i18n-prefs=$CURRENCY; lc-main=en_US",
                ),
            )
        } catch (e: Exception) {
            println("[$logPrefix] $url failed: ${e.message}")
            return null
        }

        // ETag caching is per-URL and these URLs are stable, so an unchanged page is a real
        // outcome. Treated as "nothing new to parse" rather than an error.
        if (result.notModified) return null

        val body = result.body
        if (body == null || !result.ok) {
            println("[$logPrefix] $url -> HTTP ${result.status}")
            return null
        }

        val walls = botWallMarkers(body)
        if (walls.isNotEmpty()) {
            println("[$logPrefix] throttled by Amazon (${walls.first()}) on $url")
            return null
        }
        return body
    }

    /**
     * The trusted path: the same persistent Chrome the shipping verifier uses.
     *
     * Headed, because a headless session on this profile walls after ~9 page loads where a
     * headed one reaches 40-55 — and because a headless run appeared to spend the profile's
     * accumulated trust. That measurement is recorded against `verifyPeriodically` in `App.kt`
     * and applies unchanged here.
     */
    private fun fetchViaBrowser(url: String, expect: String?): String? = VerifySlot.tryWith(
        VerifySlot.lockFileFor(profileDir),
        onBusy = {
            println(
                "[$logPrefix] a verification run holds the Chrome profile; skipping the " +
                    "browser fetch for $url — the next poll will retry",
            )
            null
        },
    ) {
        runCatching {
            var page = openPage()
            page.navigate(url)
            page.waitForLoadState()

            // A stale Amazon session cookie, not a blocked IP, is what usually gets us refused.
            //
            // Measured 2026-09-21, headed, same machine and same minute: this profile received
            // Amazon's 503 refusal (2,316 bytes, 0 results) while a FRESH profile got 48 results.
            // `clearCookies()` on the very same context then returned 48 results immediately. The
            // profile was not burnt and the IP was not blocked — one `session-id` had simply gone
            // sour, and it persisted because the profile persists.
            //
            // So a refusal earns exactly one retry with the Amazon cookies dropped. Deliberately
            // once: if a clean session is refused too, that is a real block and retrying would
            // just spend the profile's remaining goodwill.
            if (botWallMarkers(page.content()).isNotEmpty()) {
                println("[$logPrefix] refused — dropping the stale session and retrying once")
                resetSession()
                page = openPage()
                page.navigate(url)
                page.waitForLoadState()
            }

            // Still walled after a clean session: this is the "Click the button below to continue
            // shopping" screen, and it is a single button a person can clear in one click.
            //
            // Worth waiting for rather than abandoning. The window is already open and visible, so
            // giving up leaves it sitting on that page looking broken while the run reports items
            // as unreadable — 31 in one measured run. `AmazonVerifier` has always paused here; this
            // fetcher did not, which is the whole difference between "the browser is blocked" and
            // "nobody told the operator there was a button".
            //
            // Bounded, and it only asks when a window is genuinely on screen.
            if (botWallMarkers(page.content()).isNotEmpty()) {
                if (waitForHumanToClear(page, url)) {
                    page.navigate(url)
                    page.waitForLoadState()
                }
            }

            // Wait for the content that proves the page rendered, rather than trusting
            // `waitForLoadState`. Amazon's search results are server-rendered but the document
            // reports loaded before the result grid is in the DOM, so reading immediately
            // returned markup with **zero** tiles -- which the adapter then reported as "the
            // markup may have changed". A short explicit wait for the real selector is the
            // difference between a usable page and a false selector alarm.
            if (expect != null) {
                runCatching {
                    page.waitForSelector(
                        expect,
                        Page.WaitForSelectorOptions().setTimeout(15_000.0),
                    )
                }
            }

            val html = page.content()
            val walls = botWallMarkers(html)
            if (walls.isNotEmpty()) {
                // Only here is the browser genuinely refused, and only here do we stop trying.
                browserWalled = true
                println(
                    "[$logPrefix] the browser was walled too (${walls.first()}) — " +
                        "backing off for the rest of this run",
                )
                null
            } else {
                println("[$logPrefix] read $url via the browser profile")
                html
            }
        }.onFailure { println("[$logPrefix] browser fetch failed: ${it.message}") }.getOrNull()
    }

    /**
     * One browser, one page, reused for the whole run.
     *
     * Relaunching per request was costing ~8 seconds of Chrome startup each time and opening a
     * fresh window in the operator's face; `AmazonVerifier` reuses a single context across its
     * whole batch for the same reason. Kept lazily so a run that is never blocked never starts
     * a browser at all.
     */
    private fun openPage(): Page {
        playwright?.let { return page!! }

        // Before launch, for the reason the verifier documents: Chrome holds these files open
        // while it lives, so the point is to hand it a profile it can work with.
        //
        // Not optional. The shared profile reached 467MB (405MB of it `Default/Cache`) and every
        // navigation then failed with `Timeout 30000ms exceeded` while curl fetched the same URL
        // in seconds — 33 items unread in one run. `AmazonVerifier` has trimmed this since it hit
        // the same wall at 1.5GB; this fetcher drives the same profile and simply never did.
        trimProfileCache(profileDir, MAX_PROFILE_CACHE_MB)

        val pw = Playwright.create()
        playwright = pw
        val ctx = pw.chromium().launchPersistentContext(
            Paths.get(File(profileDir).absolutePath),
            BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome")
                .setHeadless(false)
                .setViewportSize(1400, 950)
                // Pinned for the same reason as the HTTP path -- see pinCurrency.
                .setLocale("en-US"),
        )
        context = ctx
        pinCurrency(ctx)
        return (ctx.pages().firstOrNull() ?: ctx.newPage()).also { page = it }
    }

    /**
     * Spaces requests to amazon.com far further apart than `PoliteHttp`'s per-host default.
     *
     * `perHostDelayMillis` is 1200, tuned for the Colombian retailers, and this adapter inherited
     * it. Amazon is a different proposition: the shipping verifier that drives the same host
     * deliberately waits **15 seconds** between product pages, a figure arrived at empirically and
     * recorded in `config/sources.yaml`.
     *
     * The measured consequence of ignoring that: a run fetching shoe pages 1.2s apart had 14 of 15
     * correctly-resolved items refused, while hoodies later in the same run — by then further from
     * the burst — came back fine. Pacing is the one lever that costs nothing but wall-clock, and a
     * tracked item only needs one request per hour, so there is no reason to hurry.
     *
     * 6 seconds rather than the verifier's 15: this fetches more items per run than a verification
     * batch does, and a full pass still finishes inside a few minutes. Deliberately conservative
     * relative to 1.2s, deliberately not as slow as a browser-driven batch.
     */
    private suspend fun pace() {
        val since = System.currentTimeMillis() - lastRequestAt
        if (lastRequestAt > 0 && since < AMAZON_DELAY_MILLIS) {
            kotlinx.coroutines.delay(AMAZON_DELAY_MILLIS - since)
        }
        lastRequestAt = System.currentTimeMillis()
    }

    private var lastRequestAt = 0L

    /**
     * Waits for the operator to clear Amazon's one-button interstitial.
     *
     * Deliberately does **not** click it. The button is Amazon asking whether a human is present,
     * and answering that question mechanically would be exactly the kind of evasion this project
     * has consistently refused — the same reasoning recorded for the shipping verifier. It puts a
     * banner on the page, says so in the log, and polls.
     *
     * Returns true when the wall cleared, false on timeout. A timeout also sets [browserWalled],
     * so the rest of the run stops asking rather than opening a window per item and waiting again.
     */
    private fun waitForHumanToClear(page: Page, url: String): Boolean {
        println(
            "[$logPrefix] Amazon is showing its 'Continue shopping' check. Please click it in the " +
                "open window — waiting up to ${WALL_WAIT_MILLIS / 1000}s. It will not be clicked " +
                "automatically.",
        )
        showBanner(page)

        // Also say so on the desktop. The in-page banner is only visible if the operator happens
        // to be looking at that window, and this fetcher runs inside an hourly poll -- so without
        // this it waits 90 seconds for a click nobody knew was wanted. Same title and group as the
        // shipping verifier's prompt, because it is the same ask about the same window.
        Notifier.notify(
            title = "Deals Tracker: Amazon needs a human",
            message = "Click 'Continue shopping' in the Chrome window",
            group = "amazon-wall",
        )

        val deadline = System.currentTimeMillis() + WALL_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(2_000.0)
            if (botWallMarkers(page.content()).isEmpty()) {
                println("[$logPrefix] cleared — continuing")
                return true
            }
        }

        browserWalled = true
        println("[$logPrefix] not cleared within ${WALL_WAIT_MILLIS / 1000}s — backing off for the rest of this run")
        return false
    }

    /**
     * Amber banner explaining why the window is sitting there.
     *
     * Without it the operator sees an Amazon page and no indication that anything is waiting on
     * them. Excised from wall detection by `AmazonVerifier.wallMarkersOn`, which strips
     * `#dt-help-banner` before matching — an earlier version of that check matched its own banner
     * text and could never report the wall as cleared.
     */
    private fun showBanner(page: Page) {
        runCatching {
            page.evaluate(
                """
                () => {
                  let b = document.getElementById('dt-help-banner');
                  if (!b) {
                    b = document.createElement('div');
                    b.id = 'dt-help-banner';
                    b.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
                      'background:#f2761b;color:#1b1b1b;font:600 15px system-ui;padding:12px 16px;' +
                      'text-align:center';
                    document.body.appendChild(b);
                  }
                  b.textContent = 'deals-tracker: please click the button on this page to continue. ' +
                    'Nothing is clicked automatically.';
                }
                """.trimIndent(),
            )
        }
    }

    /**
     * Forces Amazon to quote in one currency, rather than letting it decide per request.
     *
     * Amazon picks a currency from the session, and for an anonymous profile with a Colombian
     * delivery country it flip-flops. Measured on real stored history, same ASIN across three
     * consecutive hourly runs:
     *
     * ```
     * B0D2JGVVJN  COP 126,952  ->  USD 49.00  ->  USD 49.00
     * B0CKZ98F9H  COP 282,790  ->  USD 71.97  ->  USD 71.97
     * ```
     *
     * That is not a price change, it is the same price in two units — but `price_observations`
     * stores one number per observation, so a series like that makes "lowest ever" and the change
     * percentage meaningless: 49.00 reads as a 99.96% crash from 126,952. The tracked tab exists
     * to measure movement, so an unpinned currency defeats its entire purpose.
     *
     * USD is chosen because it is what the operator sees in their own signed-in browser, what the
     * existing Slickdeals Amazon rows already use, and a true list price rather than one carrying
     * Amazon's FX conversion. `i18n-prefs` is the cookie that holds it.
     */
    private fun pinCurrency(ctx: BrowserContext) {
        runCatching {
            ctx.addCookies(
                listOf(
                    Cookie("i18n-prefs", CURRENCY).apply { domain = ".amazon.com"; path = "/" },
                    Cookie("lc-main", "en_US").apply { domain = ".amazon.com"; path = "/" },
                ),
            )
        }.onFailure { println("[$logPrefix] could not pin currency to $CURRENCY: ${it.message}") }
    }

    /**
     * Drops the soured session cookies while **preserving the delivery country**.
     *
     * A blanket `clearCookies()` fixes the refusal but takes `sp-cdn` with it — and `sp-cdn` is
     * exactly how `AmazonVerifier.countryOf` knows the profile is set to Colombia. Without it the
     * verifier does not fail loudly; it opens amazon.com, prints "destination is not Colombia
     * yet", and **waits for a human to set it**, timing out after `wallWaitMillis` and reporting
     * itself blocked. So an unattended shipping run would quietly stop resolving deals because a
     * price fetch had cleaned up after itself too thoroughly.
     *
     * This session and the country are different things that happen to live in the same jar. Only
     * the session identity is dropped; everything the other tools depend on is restored.
     *
     * Playwright has no per-cookie delete, so the jar is read, filtered and re-added.
     */
    private fun resetSession() {
        val ctx = context ?: return
        val keep = runCatching {
            ctx.cookies().filter { it.name in PRESERVED_COOKIES }
        }.getOrDefault(emptyList())

        ctx.clearCookies()
        if (keep.isNotEmpty()) {
            runCatching { ctx.addCookies(keep) }
                .onFailure { println("[$logPrefix] could not restore ${keep.map { c -> c.name }}: ${it.message}") }
        }
    }

    /**
     * Shuts the browser down if one was started. Must be called when the fetch finishes, or the
     * Chrome window outlives the run and keeps its lock on the profile — which would then block
     * the shipping verifier rather than merely being untidy.
     */
    fun close() {
        runCatching { context?.close() }
        runCatching { playwright?.close() }
        context = null
        playwright = null
        page = null
    }

    private var playwright: Playwright? = null
    private var context: BrowserContext? = null
    private var page: Page? = null

    private companion object {
        /**
         * The currency Amazon is told to quote in.
         *
         * A constant rather than config: mixing currencies within one item's price history is not a
         * preference, it is a defect, and the whole point is that every observation agrees. If this
         * ever needs to vary per provider it should become an option — but it must still be one
         * value for the lifetime of a tracked item's series.
         */
        const val CURRENCY = "USD"

        /**
         * Cache ceiling for the shared Chrome profile, in MB.
         *
         * Matches `CoShippingConfig.maxProfileCacheMb` so the two tools that drive this profile
         * agree about when it has grown too big to use.
         */
        const val MAX_PROFILE_CACHE_MB = 400L

        /**
         * How long to wait for the operator to clear Amazon's one-button check.
         *
         * 90s: long enough to notice a window and click, short enough that an unattended overnight
         * poll is not held open for minutes. The verifier uses 180s for a manual run and 45s for a
         * scheduled one; this sits between, because an ingest cycle may or may not be watched.
         */
        const val WALL_WAIT_MILLIS = 90_000L

        /**
         * Minimum gap between amazon.com requests, in ms. See `pace`.
         *
         * Five times PoliteHttp's per-host default and well under the verifier's 15s, because this
         * fetches more items per run than a verification batch but is in no hurry either.
         */
        const val AMAZON_DELAY_MILLIS = 6_000L

        /**
         * Cookies another tool depends on, which a session reset must not take with it.
         *
         * `sp-cdn` holds the delivery country (`"L5Z9:CO"`) that `AmazonVerifier` reads to decide
         * whether the profile is pointed at Colombia. `i18n-prefs` and `lc-main` hold the currency
         * and locale, which is what keeps size labels arriving in English rather than as
         * `8,5 mujer/7,5 hombre` — a Spanish response already cost 33 false "size unavailable"
         * verdicts once.
         */
        val PRESERVED_COOKIES = setOf("sp-cdn", "i18n-prefs", "lc-main")
    }
}
