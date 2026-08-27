package com.luis.dealstracker.verify

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File
import java.nio.file.Paths

/**
 * Deep re-check of a single ASIN, written to resolve a disagreement: the automated read
 * said an item could not ship to Colombia, while the operator saw it as shippable on their
 * phone.
 *
 * The hypothesis this tests is that **eligibility is per-offer, not per-ASIN**. Evidence
 * from the saved page: `merchantID` was empty (no buy-box seller at all) and `#availability`
 * contained `<div id="all-offers-display">`, i.e. Amazon had no default offer for Colombia
 * and was pointing at the offer list instead. If a *different* seller ships to Colombia,
 * reading only the default buy box under-reports eligibility — which would be a systematic
 * flaw, not a one-off.
 *
 * Reuses the persistent profile so no human check or address setup is needed.
 */
fun main(args: Array<String>) {
    val asin = args.firstOrNull { !it.startsWith("--") } ?: "B09C5RG6KV"
    val out = File("build/amazon-recheck").apply { mkdirs() }
    val profile = File(".amazon-profile")
    require(profile.exists()) { "no profile at ${profile.path} — run the `assisted` task first" }

    Playwright.create().use { pw ->
        val ctx = pw.chromium().launchPersistentContext(
            Paths.get(profile.absolutePath),
            BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome").setHeadless(false).setViewportSize(1400, 950),
        )
        val page = ctx.pages().firstOrNull() ?: ctx.newPage()
        try {
            println("[recheck] session country = ${country(ctx) ?: "?"}")

            // --- 1. the product page again, in case the first read caught a transient state
            println("\n=== product page: /dp/$asin ===")
            page.navigate("https://www.amazon.com/dp/$asin")
            page.waitForLoadState()
            wallCheck(page, "product page")

            val availability = pollText(page, "#availability", 15_000)
            val delivery = pollText(page, "#deliveryBlockMessage", 15_000)
            println("[recheck] #availability : ${availability.replace("\n", " / ").take(160).ifEmpty { "<empty>" }}")
            println("[recheck] #delivery     : ${delivery.replace("\n", " / ").take(160).ifEmpty { "<empty>" }}")
            println("[recheck] buybox seller : ${pollText(page, "#sellerProfileTriggerId", 3_000).ifEmpty { "<none>" }}")
            File(out, "$asin-dp.txt").writeText(page.locator("body").innerText())

            // --- 2. the offer-listing page: what "See All Buying Options" opens
            println("\n[rate] waiting ${ProbeConfig.MIN_INTERVAL_MS / 1000}s")
            page.waitForTimeout(ProbeConfig.MIN_INTERVAL_MS.toDouble())

            println("\n=== offer listing: /gp/offer-listing/$asin ===")
            page.navigate("https://www.amazon.com/gp/offer-listing/$asin")
            page.waitForLoadState()
            wallCheck(page, "offer listing")

            val offersText = page.locator("body").innerText()
            File(out, "$asin-offers.txt").writeText(offersText)
            File(out, "$asin-offers.html").writeText(page.content())

            val pinned = page.locator("#aod-offer, .aod-offer, [id^='aod-offer']").count()
            println("[recheck] offer blocks found: $pinned")
            println("[recheck] offer-page bytes: ${offersText.length}")

            listOf(
                "to Colombia", "cannot be shipped", "does not ship",
                "Import Fees", "No Import Charges", "Ships from", "Sold by",
            ).forEach { phrase ->
                val hits = Regex(Regex.escape(phrase), RegexOption.IGNORE_CASE).findAll(offersText).count()
                if (hits > 0) {
                    val i = offersText.indexOf(phrase, ignoreCase = true)
                    println("[recheck] '$phrase' x$hits → ${offersText.substring(i, minOf(i + 110, offersText.length)).replace("\n", " / ")}")
                }
            }
        } finally {
            ctx.close()
        }
    }
}

private fun country(ctx: BrowserContext): String? =
    ctx.cookies().firstOrNull { it.name == "sp-cdn" }?.value?.substringAfterLast(':')?.trim('"', ' ')

/** Async widgets must be polled; reading once returns "" and looks like "no information". */
private fun pollText(page: Page, selector: String, timeoutMs: Long): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val t = runCatching { page.locator(selector).first().innerText().trim() }.getOrDefault("")
        if (t.isNotEmpty()) return t
        page.waitForTimeout(750.0)
    }
    return ""
}

private fun wallCheck(page: Page, where: String) {
    val walls = runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList())
    if (walls.isEmpty()) return
    println("[recheck] human check on $where (${walls.first()}) — please clear it; waiting up to 3 min")
    val until = System.currentTimeMillis() + 180_000
    while (System.currentTimeMillis() < until) {
        page.waitForTimeout(3000.0)
        if (runCatching { botWallMarkers(page.content()) }.getOrDefault(emptyList()).isEmpty()) {
            println("[recheck] cleared")
            return
        }
    }
    println("[recheck] still blocked — stopping rather than pushing through")
}
