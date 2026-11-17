package com.luis.dealstracker.exito

import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.verify.VerifySlot
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Paths
import java.time.Instant

/**
 * Reads the Exito LOGIN cookie out of a real browser, so the daily paste is not needed.
 *
 * The same idea as the Amazon verifier: a **persistent Chrome profile** the operator signs in to once,
 * which later runs reuse. Nothing is typed or solved for them -- the operator signs in to their own
 * window, and only the login token is read afterwards.
 *
 * What it deliberately does NOT take is the cart id. Measured 2026-10-14: this profile, signed in as the
 * same account, shows an EMPTY cart while the shopper's own Chrome holds items, so carts are per browser
 * rather than per account and no other browser can discover the shopper's. The cart id changes only when an
 * order is placed, so it stays a manual paste; the login token expires daily, so that is what is automated.
 *
 * What makes the login automatable: besides the 24-hour token, the profile keeps `vid_rt`, a 30-day refresh
 * token, so a later visit is re-issued a token without signing in again.
 *
 * It prints no value -- only names, domains and lifetimes.
 */
internal const val EXITO_PROFILE_DIR = ".exito-profile"
private const val LOGIN_TIMEOUT_MS = 6 * 60 * 1000L

/** One cookie as read from the browser. */
data class CookieRow(val name: String, val domain: String, val value: String)

/**
 * The login token to use, from every cookie of that name the browser holds.
 *
 * The browser keeps the token under two scopes: `.tienda.exito.com` (where VTEX ID signs in) and
 * `.www.exito.com` (where the shop and its cart API live). The cart API is called on www.exito.com, so
 * that scope's copy is the one that is sent there and the one to take; any other is a fallback.
 */
internal fun loginTokenFrom(cookies: List<CookieRow>): String? =
    cookies.filter { it.name == EXITO_COOKIE_NAME && it.value.isNotBlank() }
        // The freshest wins: a renewal lands under .tienda.exito.com while a stale copy can linger under www.
        // Between equally fresh copies, the one the cart API is called with.
        .sortedWith(
            compareByDescending<CookieRow> { cookieExpiry(cleanCookie(it.value)) ?: Instant.MIN }
                .thenByDescending { it.domain.removePrefix(".") == "www.exito.com" },
        )
        .firstOrNull()?.value?.let(::cleanCookie)?.takeIf { it.isNotEmpty() }

/**
 * Whether the daily renewal should run now: from [startHour] local time on, once the saved login was not issued
 * today (a token's `exp` is 24 hours after it was issued), and not within [retryAfter] of the last attempt.
 * Stateless apart from [lastAttempt], so a Mac that slept through the morning renews as soon as it wakes.
 */
internal fun renewalDue(
    now: Instant,
    token: String?,
    lastAttempt: Instant?,
    zone: java.time.ZoneId,
    startHour: Int = 6,
    retryAfter: java.time.Duration = java.time.Duration.ofHours(1),
): Boolean {
    val local = now.atZone(zone)
    if (local.hour < startHour) return false
    if (lastAttempt != null && java.time.Duration.between(lastAttempt, now) < retryAfter) return false
    val issued = token?.let(::cookieExpiry)?.minus(java.time.Duration.ofHours(24))
    return issued == null || issued.atZone(zone).toLocalDate() != local.toLocalDate()
}

/**
 * Whether [token] was just issued: a renewal's token runs 24 hours from the moment it is made, so one with
 * under [margin] left is the old token, still in the profile because the refresh did nothing.
 */
internal fun isFullDay(token: String, now: Instant, margin: java.time.Duration = java.time.Duration.ofHours(23)): Boolean =
    cookieExpiry(token)?.let { java.time.Duration.between(now, it) >= margin } ?: false

/**
 * Whether the account API takes [token]: one small read of the order history on the checkout host, sent the
 * way the tracker sends it -- a plain request with the cookie, not through the browser, whose headless
 * requests to the shop host Cloudflare refuses whatever the token.
 */
internal fun accountAccepts(token: String): Boolean = try {
    val request = java.net.http.HttpRequest.newBuilder(java.net.URI("$EXITO_ORDERS_URL?page=1&per_page=1"))
        .timeout(java.time.Duration.ofSeconds(20))
        .header("Cookie", "$EXITO_COOKIE_NAME=$token")
        .header("Accept", "application/json")
        .GET().build()
    java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200
} catch (_: Exception) {
    false
}

/** Stores the login token where the cart button reads it, leaving the saved cart id alone. */
internal fun saveLogin(settings: SettingsRepository, token: String) {
    settings.set(mapOf(SettingsRepository.EXITO_COOKIE to token))
}

/** What the page's own `orderForm` call said: the cart the browser would show, and whether it is signed in. */
internal data class BrowserCart(val id: String?, val loggedIn: Boolean, val items: Int)

internal fun parseBrowserCart(json: String): BrowserCart? = try {
    val o = cartJson.parseToJsonElement(json).jsonObject
    BrowserCart(
        id = o["id"]?.jsonPrimitive?.contentOrNull,
        loggedIn = o["loggedIn"]?.jsonPrimitive?.booleanOrNull == true,
        items = o["items"]?.jsonArray?.size ?: 0,
    )
} catch (_: Exception) {
    null
}

/** Whether [token] is still good at [now] and runs longer than the [saved] login: worth saving in its place. */
internal fun outlastsSaved(token: String, saved: String?, now: Instant): Boolean {
    val expiry = cookieExpiry(token) ?: return false
    val savedExpiry = saved?.let(::cookieExpiry)
    return expiry.isAfter(now) && (savedExpiry == null || expiry.isAfter(savedExpiry))
}

/**
 * Whether a renewal that left the old login in place is worth an alert: only once the saved login is gone or
 * has under [margin] left. A login with hours on it still works, and the hourly retry will renew it when
 * VTEX ID next agrees to (see `refreshAfter`), so alerting earlier cries wolf.
 */
internal fun loginRunningOut(token: String?, now: Instant, margin: java.time.Duration = java.time.Duration.ofHours(2)): Boolean {
    val expiry = token?.let(::cookieExpiry) ?: return true
    return java.time.Duration.between(now, expiry) < margin
}

/** The by-hand command that opens a Chrome window to sign in to Exito again and save the login. */
internal const val EXITO_SIGNIN_COMMAND = "./dt run --args=\"--exito-session --renew --save\""

/**
 * The alert sent when the daily renewal left the old login in place, worded by its cause. With no refresh token
 * in the profile ([signInGone]), the 30-day sign-in has ended and only signing in again helps; the old wording
 * ("renew by hand") read as a glitch to retry, on 2026-11-16 and 17, when it was the sign-in running out.
 */
internal fun renewalFailedAlert(signInGone: Boolean): Pair<String, String> =
    if (signInGone) {
        "Exito sign-in ended" to
            "Exito's 30-day sign-in has run out, so the daily renewal has nothing to renew from. The cart button and the order sync stop until you sign in again.\n" +
            "At the Mac: $EXITO_SIGNIN_COMMAND -- a Chrome window opens; sign in to Exito there."
    } else {
        "Exito login was not renewed" to
            "The daily renewal ran and left the old login in place. The cart button and the order sync stop when it expires.\n" +
            "Renew by hand: $EXITO_SIGNIN_COMMAND (a Chrome window opens; sign in if asked)."
    }

/**
 * Whether to warn that the sign-in [ends] soon, so it can be renewed before the cart button stops rather than
 * after: within [within] of [now], and not already over (that is [renewalFailedAlert]'s case).
 */
internal fun signInEndingSoon(ends: Instant?, now: Instant, within: java.time.Duration = java.time.Duration.ofDays(3)): Boolean =
    ends != null && ends.isAfter(now) && java.time.Duration.between(now, ends) <= within

/** [signInEndingSoon]'s message, with the day and time the sign-in ends in [zone]. */
internal fun signInEndingAlert(ends: Instant, zone: java.time.ZoneId): Pair<String, String> {
    val at = ends.atZone(zone)
    val day = at.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM 'at' HH:mm", java.util.Locale.ENGLISH))
    return "Exito sign-in ends soon" to
        "Exito's 30-day sign-in ends $day. After that the daily renewal cannot keep the cart button working.\n" +
        "Sign in again before then, at the Mac: $EXITO_SIGNIN_COMMAND -- a Chrome window opens; sign in to Exito there."
}

/** What a refresh answer with no verdict looked like, for the log: field names only, since values can be tokens. */
internal fun refreshAnswerShape(contentType: String?, fields: Set<String>?): String =
    if (fields == null) "not JSON (${contentType?.substringBefore(';') ?: "no content type"}): a check page, not VTEX ID"
    else "JSON with fields ${fields.sorted()}"

/** Cloudflare's interstitial, which a headless run is likelier to be served than a headed one. */
internal fun looksLikeBotWall(title: String): Boolean =
    title.contains("just a moment", ignoreCase = true) || title.contains("attention required", ignoreCase = true)

/**
 * The flags Playwright adds that tell a site the browser is automated (`--enable-automation`, and the
 * `navigator.webdriver` flag it turns on), left out. With them, Cloudflare's "I'm human" box looped forever
 * on 2026-11-17: ticked, then shown again a few seconds later, so a sign-in could not be done in the window.
 */
internal val EXITO_HIDDEN_DEFAULT_ARGS = listOf("--enable-automation")
internal val EXITO_EXTRA_ARGS = listOf("--disable-blink-features=AutomationControlled")

/**
 * How the Exito profile is opened. Always through Playwright's Chrome, never a plain Chrome window: Playwright
 * keeps cookies under its own key (a mock keychain), so a login made in plain Chrome cannot be read here and
 * is dropped the next time this opens the profile (seen on 2026-11-17).
 */
internal fun exitoLaunchOptions(headless: Boolean): BrowserType.LaunchPersistentContextOptions =
    BrowserType.LaunchPersistentContextOptions()
        .setChannel("chrome")
        .setHeadless(headless)
        .setViewportSize(1280, 900)
        .setIgnoreDefaultArgs(EXITO_HIDDEN_DEFAULT_ARGS)
        .setArgs(EXITO_EXTRA_ARGS)

class ExitoSessionProbe(
    private val profileDir: String = EXITO_PROFILE_DIR,
    private val headless: Boolean,
    private val save: SettingsRepository?,
    /** Deletes the login token first, to see whether the profile is re-issued one without a sign-in. */
    private val dropLoginFirst: Boolean = false,
    /** When set, tests whether a link can open THAT cart in a browser that has never seen it. */
    private val openCartId: String? = null,
    /** Asks for a new token even when the profile still holds an unexpired one: the daily run wants a full day. */
    private val renew: Boolean = false,
    private val now: () -> Instant = Instant::now,
) {
    /** True once a run found no refresh token in the profile: the sign-in has ended, see [renewalFailedAlert]. */
    var signInGone: Boolean = false
        private set

    /** False when another run holds the profile and nothing was done, so the caller can try again soon. */
    fun run(): Boolean {
        val profile = File(profileDir).apply { mkdirs() }
        return VerifySlot.tryWith(
            VerifySlot.lockFileFor(profile.path),
            onBusy = { println("[exito] another run already holds ${profile.path}, which Chrome locks. Retry in a minute."); false },
        ) { if (openCartId != null) openCart(profile, openCartId) else read(profile); true }
    }

    /**
     * Opens `checkout/?orderFormId=<id>` in this profile -- a browser whose own cart is a different one --
     * and says whether the cart it then shows is the one named. Prints ids' last characters and a count only.
     */
    private fun openCart(profile: File, cartId: String) {
        println("[exito] opening the cart ending …${cartId.takeLast(6)} by link, in a browser that has its own different cart")
        Playwright.create().use { pw ->
            val ctx = pw.chromium().launchPersistentContext(
                Paths.get(profile.absolutePath),
                exitoLaunchOptions(headless),
            )
            try {
                val page = ctx.pages().firstOrNull() ?: ctx.newPage()
                page.navigate("https://www.exito.com/")
                page.waitForLoadState()
                val before = browserCart(page)
                println("  before: this browser's cart ends …${before?.id?.takeLast(6)}, ${before?.items} item(s)")
                page.navigate("https://www.exito.com/checkout/?orderFormId=$cartId#/cart")
                page.waitForLoadState()
                Thread.sleep(6000)
                println("  page after the link: \"${page.title()}\"  at ${page.url().substringBefore('#').substringBefore('?')}")
                // The link lands on the checkout host (tienda.exito.com), whose cookies are its own, so the cart
                // is read there -- on the page the link opened -- as well as back on the shop host.
                val onCheckout = browserCart(page)
                println("  on the checkout page: cart ends …${onCheckout?.id?.takeLast(6)}, ${onCheckout?.items} item(s)")
                val settled = ctx.newPage()
                settled.navigate("https://www.exito.com/")
                settled.waitForLoadState()
                val after = browserCart(settled)
                println("  back on the shop:     cart ends …${after?.id?.takeLast(6)}, ${after?.items} item(s)")
                println("  the link opened the named cart: ${onCheckout?.id == cartId || after?.id == cartId}")
            } finally {
                ctx.close()
            }
        }
    }

    private fun read(profile: File) {
        println("[exito] profile ${profile.absolutePath} (${if (headless) "headless" else "headed"})")
        Playwright.create().use { pw ->
            val ctx = pw.chromium().launchPersistentContext(
                Paths.get(profile.absolutePath),
                exitoLaunchOptions(headless),
            )
            try {
                if (dropLoginFirst) {
                    ctx.clearCookies(BrowserContext.ClearCookiesOptions().setName(EXITO_COOKIE_NAME))
                    println("[exito] login token deleted from the profile; the refresh token (vid_rt) is left. Visiting to see if it is re-issued.")
                }
                // The daily renewal first, with no page loaded: Cloudflare stops a headless visit to the shop
                // (both runs on 2026-11-12 ended at its check page), but the renewal is one API call to the
                // sign-in host, which it lets through. The page route below stays as the fallback.
                if (renew && !dropLoginFirst && renewWithoutPage(ctx)) return
                val page = ctx.pages().firstOrNull() ?: ctx.newPage()
                page.navigate("https://www.exito.com/")
                page.waitForLoadState()
                if (looksLikeBotWall(page.title())) {
                    if (headless) {
                        println("[exito] Cloudflare is showing its check page (\"${page.title()}\"). " +
                            "Run headed (without --headless) so you can clear it; the profile remembers.")
                        return
                    }
                    // Headed, the check usually clears by itself in a few seconds, or after one click on its
                    // box. Returning at once (as this did until 2026-11-15) made the advice above impossible
                    // to follow: the window closed before anyone could click.
                    println("[exito] Cloudflare is showing its check page. Waiting for it to clear; tick its box in the window if it asks.")
                    val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                    while (runCatching { looksLikeBotWall(page.title()) }.getOrDefault(true)) {
                        if (System.currentTimeMillis() > deadline) {
                            println("[exito] the Cloudflare check did not clear in time; nothing renewed.")
                            return
                        }
                        page.waitForTimeout(1_000.0)
                    }
                    println("[exito] the Cloudflare check cleared.")
                    runCatching { page.waitForLoadState() }
                }
                if (renew || ctx.cookies().none { it.name == EXITO_COOKIE_NAME }) refreshLogin(ctx)
                if (!waitForLogin(ctx)) return
                // The sign-in redirects through several pages, and the one opened first may be mid-navigation
                // or gone. A fresh tab, after a pause, reads a settled page.
                Thread.sleep(4000)
                val settled = ctx.newPage()
                settled.navigate("https://www.exito.com/")
                settled.waitForLoadState()
                report(ctx, settled)
            } finally {
                ctx.close()
            }
        }
    }

    /**
     * Renews from the refresh token without opening the shop, and saves the login when it is a new one that
     * the cart API accepts. False when it did not get that far, so the caller falls back to the page.
     */
    private fun renewWithoutPage(ctx: BrowserContext): Boolean {
        refreshLogin(ctx)
        val token = loginTokenFrom(ctx.cookies().map { CookieRow(it.name, it.domain, it.value) })
        if (token == null || !isFullDay(token, now())) {
            // VTEX ID answers "Success" without a new token until `refreshAfter`, about 20 hours after the last
            // one it issued. The profile may still hold a login newer than the saved one (2026-11-15: the saved
            // login had expired at 06:11 while the profile's ran to 22:07), and that one is worth keeping.
            val saved = save?.get(SettingsRepository.EXITO_COOKIE)
            if (token != null && outlastsSaved(token, saved, now()) && accountAccepts(token)) {
                println("[exito] no new login was issued, but the profile holds one that outlasts the saved one; it expires ${cookieExpiry(token)}")
                save?.let { saveLogin(it, token); println("[exito] SAVED the login token (the saved cart id is untouched).") }
                return true
            }
            println("[exito] the refresh left no new login token; trying through the shop's page instead")
            return false
        }
        if (!accountAccepts(token)) {
            println("[exito] the renewed token was refused by the account API; trying through the shop's page instead")
            return false
        }
        println("[exito] renewed without loading the shop; the new login expires ${cookieExpiry(token)}")
        if (save != null) {
            saveLogin(save, token)
            println("[exito] SAVED the login token (the saved cart id is untouched).")
        }
        return true
    }

    /**
     * Asks VTEX ID to exchange the refresh token (`vid_rt`) for a new login token -- the call a VTEX storefront
     * makes for itself. `vid_rt` is scoped to the sign-in host, so the call goes there; the request shares the
     * browser's cookies and its answer's `Set-Cookie` lands back in them. Only the status is printed.
     */
    private fun refreshLogin(ctx: BrowserContext) {
        val refresh = ctx.cookies().filter { it.name == "vid_rt" }.maxByOrNull { it.expires ?: 0.0 }
        if (refresh == null) {
            signInGone = true
            println("[exito] no refresh token (vid_rt) in this profile, so there is nothing to renew from: the 30-day sign-in has ended. Sign in again.")
            return
        }
        // Its expiry is when the sign-in ends; kept so the app can warn days ahead. Only the date, never the value.
        refresh.expires?.takeIf { it > 0 }?.let { secs ->
            val ends = Instant.ofEpochSecond(secs.toLong())
            println("[exito] the sign-in (refresh token) runs until $ends")
            save?.let { runCatching { it.set(mapOf(SettingsRepository.EXITO_SIGNIN_ENDS to ends.toString())) } }
        }
        // GET answered 405 (2026-10-14): the endpoint takes a POST.
        val resp = ctx.request().post("https://tienda.exito.com/api/vtexid/refreshtoken/webstore")
        // Only the verdict field is printed, never the body: it can carry tokens.
        val body = runCatching { resp.text() }.getOrDefault("")
        val fields = try { cartJson.parseToJsonElement(body).jsonObject } catch (_: Exception) { null }
        val verdict = fields?.get("authStatus")?.jsonPrimitive?.contentOrNull
        println("[exito] asked VTEX ID to renew the login from the refresh token: HTTP ${resp.status()}, authStatus=$verdict")
        // Why a 200 renewed nothing: the answer's shape, never its values. A page instead of JSON is Cloudflare.
        if (verdict == null) println("[exito]   the answer was ${refreshAnswerShape(resp.headers()["content-type"], fields?.keys)}")
        // Neither is a secret: a status word, and when VTEX ID will next agree to issue a token.
        fields?.let { f ->
            val status = runCatching { f["status"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val after = runCatching { f["refreshAfter"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (status != null || after != null) println("[exito]   status=$status, refreshAfter=$after")
        }
    }

    /**
     * Waits for the login cookie to appear. Polled on the context, not through a page: the operator may close
     * or navigate the page being watched, and a dead page would end the wait for the wrong reason.
     */
    private fun waitForLogin(ctx: BrowserContext): Boolean {
        val deadline = System.currentTimeMillis() + if (headless) 8_000 else LOGIN_TIMEOUT_MS
        var told = false
        while (true) {
            if (ctx.cookies().any { it.name == EXITO_COOKIE_NAME }) return true
            if (System.currentTimeMillis() >= deadline) {
                println(
                    if (headless) "[exito] no login token appeared: this profile is not signed in, or the refresh did not happen. Run headed (without --headless) and sign in."
                    else "[exito] not signed in within ${LOGIN_TIMEOUT_MS / 60000} minutes -- stopping. Nothing was changed.",
                )
                return false
            }
            if (!told && !headless) {
                println("[exito] not signed in. Sign in in the browser window (your own login, typed there); I will carry on by myself.")
                told = true
            }
            Thread.sleep(1000)
        }
    }

    private fun browserCart(page: Page): BrowserCart? = try {
        parseBrowserCart(
            page.evaluate(
                """() => fetch('/api/checkout/pub/orderForm', {credentials: 'include'})
                    .then(r => r.json())
                    .then(j => JSON.stringify({id: j.orderFormId, loggedIn: j.loggedIn,
                                               items: (j.items || []).map(i => i.id)}))""",
            ) as String,
        )
    } catch (_: Exception) {
        null
    }

    private fun report(ctx: BrowserContext, page: Page) {
        // Every Exito or VTEX cookie, wherever it is scoped. Names and lifetimes only -- never a value.
        val all = ctx.cookies().filter { "exito" in it.domain || "vtex" in it.domain }
        println("\n=== login-related cookies this browser holds (names and lifetimes only) ===")
        all.filter { it.name.startsWith("VtexIdclient") || it.name == "vid_rt" }
            .sortedBy { it.name }.forEach { println("  %-34s %-22s %s".format(it.name.take(34), it.domain, lifetime(it.expires))) }

        val token = loginTokenFrom(all.map { CookieRow(it.name, it.domain, it.value) })
        token?.let { println("  login token declares expiry: ${cookieExpiry(it) ?: "unreadable"}") }
        val cart = browserCart(page)
        println("\n=== this browser's own cart (not the shopper's: see the class note) ===")
        println("  signed in: ${cart?.loggedIn}   items in it: ${cart?.items}")
        // A renewed token lands under .tienda.exito.com only, which the browser does not send to www.exito.com, so
        // the page can read "signed in: false" for a token that works. Try it the way the tracker will: as a header.
        val works = cart?.loggedIn == true || (token != null && tokenWorks(ctx, token))
        if (cart?.loggedIn != true && token != null) println("  token tried as the tracker sends it: ${if (works) "accepted" else "refused"}")

        if (token == null || !works) {
            println("\n[exito] not signed in after the visit -- nothing to save.")
            return
        }
        if (save != null) {
            saveLogin(save, token)
            println("\n[exito] SAVED the login token (the saved cart id is untouched).")
        } else {
            println("\n[exito] signed in, token read. Not saved (add --save to store it).")
        }
    }

    private fun tokenWorks(ctx: BrowserContext, token: String): Boolean = try {
        val resp = ctx.request().get(
            "https://www.exito.com/api/checkout/pub/orderForm",
            com.microsoft.playwright.options.RequestOptions.create().setHeader("Cookie", "$EXITO_COOKIE_NAME=$token"),
        )
        isLoggedIn(cartJson.parseToJsonElement(resp.text()))
    } catch (_: Exception) {
        false
    }

    private fun lifetime(expires: Double): String =
        if (expires <= 0) "session cookie"
        else Instant.ofEpochSecond(expires.toLong()).let { exp ->
            val hours = java.time.Duration.between(now(), exp).toHours()
            "expires $exp (in ${hours}h)"
        }
}
