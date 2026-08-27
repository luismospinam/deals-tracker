package com.luis.dealstracker.verify

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File
import java.nio.file.Paths

/**
 * Assisted Amazon → Colombia eligibility check: a **headed** browser the operator drives
 * when Amazon asks for a human, with the reading automated around it.
 *
 * Why assisted rather than automated: unattended requests from this network were blocked
 * after ~7 page loads. Amazon geolocates the corporate proxy egress to a datacenter, so
 * the block is IP-reputation driven and slowing down does not help. The wall it serves is
 * a single button, though, so a person can clear it — and with a **persistent profile**
 * the resulting trust cookies survive into later runs.
 *
 * Deliberate boundaries:
 *  - Never solves a challenge itself and never spoofs anything. When Amazon wants a human,
 *    it says so and waits.
 *  - Never touches credentials. If the operator chooses to sign in, they type it into their
 *    own browser window; nothing is read, stored or logged from it.
 *  - Needs no terminal input: it polls the session cookie for the country and proceeds on
 *    its own once Colombia is set, so the operator only ever interacts with the browser.
 */
private const val PROFILE_DIR = ".amazon-profile"
private const val SETUP_TIMEOUT_MS = 6 * 60 * 1000L
private const val DELIVERY_BLOCK_TIMEOUT_MS = 25_000L

/** ASINs to check. Small on purpose — this proves the mechanic, it is not the batch. */
private val DEFAULT_ASINS = listOf(
    "B09C5RG6KV", // Anker 65W charger — small, likely ships
    "B0CKN1N2YH", // MagSafe airplane phone mount — small
    "B0D4M581B3", // AA lithium batteries — batteries are often restricted
    "B0GKDL92X4", // 16'x7' garage door screens — bulky, likely does not ship
)

private data class Verdict(
    val asin: String,
    val title: String,
    val country: String?,
    val deliveryText: String,
    val signals: List<String>,
    val call: String,
)

fun main(args: Array<String>) {
    val asins = args.filter { !it.startsWith("--") }.ifEmpty { DEFAULT_ASINS }
    val out = File("build/amazon-assisted").apply { mkdirs() }
    val profile = File(PROFILE_DIR).apply { mkdirs() }

    println("=".repeat(78))
    println("Assisted Amazon → Colombia check")
    println("  profile : ${profile.absolutePath}  (persists trust between runs; gitignored)")
    println("  asins   : ${asins.joinToString(", ")}")
    println("=".repeat(78))

    Playwright.create().use { pw ->
        // A persistent context, not a fresh one: the whole point is that what the operator
        // does by hand carries over to later runs.
        val ctx = pw.chromium().launchPersistentContext(
            Paths.get(profile.absolutePath),
            BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome")
                .setHeadless(false)
                .setViewportSize(1400, 900),
        )
        val page = ctx.pages().firstOrNull() ?: ctx.newPage()

        try {
            if (!ensureColombia(ctx, page)) {
                println("\n[!] Colombia was never set within ${SETUP_TIMEOUT_MS / 60000} minutes — stopping.")
                println("    Nothing was forced. Re-run when you are ready to drive the window.")
                return@use
            }

            println("\n" + "=".repeat(78))
            println("Colombia confirmed. Reading eligibility for ${asins.size} item(s).")
            println("=".repeat(78))

            val verdicts = mutableListOf<Verdict>()
            asins.forEachIndexed { i, asin ->
                if (i > 0) {
                    println("\n[rate] waiting ${ProbeConfig.MIN_INTERVAL_MS / 1000}s before the next page")
                    page.waitForTimeout(ProbeConfig.MIN_INTERVAL_MS.toDouble())
                }
                verdicts += readOne(ctx, page, asin, out)
            }

            println("\n" + "=".repeat(78))
            println("SUMMARY — check these against what you see in your own browser")
            println("=".repeat(78))
            verdicts.forEach { v ->
                println("\n  ${v.asin}  →  ${v.call}")
                println("    title:    ${v.title.take(66)}")
                println("    country:  ${v.country ?: "?"}")
                println("    delivery: ${v.deliveryText.replace("\n", " / ").take(150).ifEmpty { "<empty>" }}")
                v.signals.forEach { println("    signal:   $it") }
            }
            println("\n  Raw page text and HTML per ASIN: ${out.path}/")
        } finally {
            ctx.close()
        }
    }
}

/**
 * Waits for the operator to put the session into a Colombia delivery state, clearing any
 * wall and optionally signing in. Detection is by cookie rather than by page text, because
 * the visible location element disappears on some page variants.
 */
private fun ensureColombia(ctx: BrowserContext, page: Page): Boolean {
    println("\n[setup] opening amazon.com …")
    page.navigate("https://www.amazon.com/")
    page.waitForLoadState()

    if (countryOf(ctx) == "CO") {
        println("[setup] this profile already has Colombia set — nothing for you to do.")
        return true
    }

    println(
        """
        |
        |──────────────────────────────────────────────────────────────────────────────
        | OVER TO YOU — in the Chrome window that just opened:
        |
        |   1. If Amazon shows "Click the button below to continue shopping", click it.
        |   2. (Optional) Sign in, if you want your real address used. Type it yourself —
        |      nothing about your credentials is read, stored or logged here.
        |   3. Set the delivery country to Colombia:
        |        click "Delivering to …" at the top left
        |        → under "or ship outside the US" pick Colombia
        |        → Done
        |
        | Nothing else is needed. This is watching the session cookie and will carry on by
        | itself the moment it sees Colombia. Waiting up to ${SETUP_TIMEOUT_MS / 60000} minutes.
        |──────────────────────────────────────────────────────────────────────────────
        """.trimMargin(),
    )

    val deadline = System.currentTimeMillis() + SETUP_TIMEOUT_MS
    var lastReport = 0L
    var wallAnnounced = false

    while (System.currentTimeMillis() < deadline) {
        val country = countryOf(ctx)
        if (country == "CO") {
            println("\n[setup] ✓ Colombia detected (sp-cdn cookie). Thank you — taking it from here.")
            return true
        }

        // Surface a wall once, as information. Never acted on.
        val walls = runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList())
        if (walls.isNotEmpty() && !wallAnnounced) {
            println("[setup] Amazon is showing a human check (${walls.first()}) — please clear it in the window.")
            wallAnnounced = true
        } else if (walls.isEmpty()) {
            wallAnnounced = false
        }

        val now = System.currentTimeMillis()
        if (now - lastReport > 20_000) {
            val remaining = (deadline - now) / 1000
            println("[setup] still waiting … country=${country ?: "none yet"}  (${remaining}s left)")
            lastReport = now
        }
        page.waitForTimeout(2500.0)
    }
    return false
}

/** Country code Amazon has stored for the session, read from the `sp-cdn` cookie ("L5Z9:CO"). */
private fun countryOf(ctx: BrowserContext): String? =
    ctx.cookies().firstOrNull { it.name == "sp-cdn" }
        ?.value?.substringAfterLast(':')?.trim('"', ' ')?.takeIf { it.isNotEmpty() }

private fun readOne(ctx: BrowserContext, page: Page, asin: String, out: File): Verdict {
    println("\n─── $asin ───")
    page.navigate("https://www.amazon.com/dp/$asin")
    page.waitForLoadState()

    // A wall mid-run is not fatal here: ask, wait, continue. Still never solved for them.
    var walls = runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList())
    if (walls.isNotEmpty()) {
        println("[$asin] human check shown (${walls.first()}) — please clear it; waiting up to 3 min")
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until && walls.isNotEmpty()) {
            page.waitForTimeout(3000.0)
            walls = runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList())
        }
        if (walls.isEmpty()) {
            println("[$asin] cleared, continuing")
            page.navigate("https://www.amazon.com/dp/$asin")
            page.waitForLoadState()
        }
    }

    // #deliveryBlockMessage is empty on load and filled by AJAX, so it must be polled.
    // Reading it once always yields "" and would silently look like "no information".
    var delivery = ""
    val deadline = System.currentTimeMillis() + DELIVERY_BLOCK_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        delivery = runCatching { page.locator("#deliveryBlockMessage").first().innerText().trim() }
            .getOrDefault("")
        if (delivery.isNotEmpty()) break
        page.waitForTimeout(1000.0)
    }

    val body = runCatching { page.locator("body").innerText() }.getOrDefault("")
    File(out, "$asin.txt").writeText(body)
    File(out, "$asin.html").writeText(page.content())

    val signals = buildList {
        listOf(
            "#availability", "#outOfStock", "#amazonGlobal_feature_div",
            "#importFeesDepositMessage", "#exports_desktop_qualifiedBuyBox_truncateCopy",
            "#contextualIngressPtMessage",
        ).forEach { sel ->
            if (page.locator(sel).count() > 0) {
                val t = runCatching { page.locator(sel).first().innerText().trim() }.getOrDefault("")
                if (t.isNotEmpty()) add("$sel → ${t.replace("\n", " / ").take(110)}")
            }
        }
        listOf(
            "cannot be shipped", "does not ship to", "not available for shipping",
            "currently unavailable", "Import Fees Deposit", "ships to Colombia",
            "Delivering to Colombia", "This item cannot be shipped to your selected",
        ).forEach { phrase ->
            val idx = body.indexOf(phrase, ignoreCase = true)
            if (idx >= 0) add("text '$phrase' → ${body.substring(idx, minOf(idx + 92, body.length)).replace("\n", " / ")}")
        }
    }

    // Deliberately conservative: only claim YES/NO on explicit evidence. Anything else is
    // UNCLEAR, so a wrong verdict is never recorded as fact.
    val lower = (delivery + " " + body).lowercase()
    val call = when {
        lower.contains("cannot be shipped") || lower.contains("does not ship to") ||
            lower.contains("not available for shipping") -> "NO — explicit refusal on page"
        delivery.isNotEmpty() && lower.contains("colombia") -> "YES — delivery quoted to Colombia"
        delivery.isNotEmpty() -> "UNCLEAR — delivery block present but no country named"
        else -> "UNCLEAR — no delivery block rendered"
    }
    println("[$asin] $call")
    println("[$asin] delivery: ${delivery.replace("\n", " / ").take(130).ifEmpty { "<empty>" }}")

    return Verdict(asin, page.title(), countryOf(ctx), delivery, signals, call)
}
