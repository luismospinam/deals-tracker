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

/** Cloudflare's interstitial, which a headless run is likelier to be served than a headed one. */
internal fun looksLikeBotWall(title: String): Boolean =
    title.contains("just a moment", ignoreCase = true) || title.contains("attention required", ignoreCase = true)

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
    fun run() {
        val profile = File(profileDir).apply { mkdirs() }
        VerifySlot.tryWith(
            VerifySlot.lockFileFor(profile.path),
            onBusy = { println("[exito] another run already holds ${profile.path}, which Chrome locks. Retry in a minute.") },
        ) { if (openCartId != null) openCart(profile, openCartId) else read(profile) }
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
                BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(headless).setViewportSize(1280, 900),
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
                BrowserType.LaunchPersistentContextOptions()
                    .setChannel("chrome")
                    .setHeadless(headless)
                    .setViewportSize(1280, 900),
            )
            try {
                if (dropLoginFirst) {
                    ctx.clearCookies(BrowserContext.ClearCookiesOptions().setName(EXITO_COOKIE_NAME))
                    println("[exito] login token deleted from the profile; the refresh token (vid_rt) is left. Visiting to see if it is re-issued.")
                }
                val page = ctx.pages().firstOrNull() ?: ctx.newPage()
                page.navigate("https://www.exito.com/")
                page.waitForLoadState()
                if (looksLikeBotWall(page.title())) {
                    println("[exito] Cloudflare is showing its check page (\"${page.title()}\"). " +
                        "Run headed (without --headless) so you can clear it; the profile remembers.")
                    return
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
     * Asks VTEX ID to exchange the refresh token (`vid_rt`) for a new login token -- the call a VTEX storefront
     * makes for itself. `vid_rt` is scoped to the sign-in host, so the call goes there; the request shares the
     * browser's cookies and its answer's `Set-Cookie` lands back in them. Only the status is printed.
     */
    private fun refreshLogin(ctx: BrowserContext) {
        val hasRefresh = ctx.cookies().any { it.name == "vid_rt" }
        if (!hasRefresh) {
            println("[exito] no refresh token (vid_rt) in this profile, so there is nothing to renew from.")
            return
        }
        // GET answered 405 (2026-10-14): the endpoint takes a POST.
        val resp = ctx.request().post("https://tienda.exito.com/api/vtexid/refreshtoken/webstore")
        // Only the verdict field is printed, never the body: it can carry tokens.
        val verdict = try {
            cartJson.parseToJsonElement(resp.text()).jsonObject["authStatus"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
        println("[exito] asked VTEX ID to renew the login from the refresh token: HTTP ${resp.status()}, authStatus=$verdict")
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
