package com.luis.dealstracker.verify

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.io.File

/**
 * Phase 1, step 3: read shipping eligibility using a session that already has Colombia
 * set, and wait for the delivery block to arrive.
 *
 * The two mechanics discovered by [main] in AmazonProbe:
 *  1. The delivery country is set via a native `<select id="GLUXCountryList">` inside the
 *     location popover; applying it writes `sp-cdn="L5Z9:CO"` into the session cookies.
 *     Reusing a saved `storageState` therefore skips the whole address dance.
 *  2. **`#deliveryBlockMessage` is empty on load and filled in by AJAX.** The page ships
 *     `delivery-block-ajax-params` with the merchantId/asin and fetches the copy after.
 *     Reading it synchronously always returns blank, which is why the first attempt saw
 *     an element with no text.
 */
private val SESSION = File("build/amazon-probe/amazon-session.json")

fun main(args: Array<String>) {
    val asins = args.filter { !it.startsWith("--") }.ifEmpty { listOf("B09C5RG6KV") }
    val out = File("build/amazon-probe"); out.mkdirs()
    require(SESSION.exists()) { "no saved session at ${SESSION.path} — run the probe task first" }

    Playwright.create().use { pw ->
        val browser = pw.chromium().launch(
            BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true),
        )
        val ctx = browser.newContext(
            Browser.NewContextOptions().setStorageStatePath(SESSION.toPath()),
        )
        val page = ctx.newPage()

        asins.forEachIndexed { i, asin ->
            if (i > 0) {
                println("[v] rate floor: sleeping ${ProbeConfig.MIN_INTERVAL_MS / 1000}s")
                page.waitForTimeout(ProbeConfig.MIN_INTERVAL_MS.toDouble())
            }
            println("\n=== $asin ===")
            page.navigate("https://www.amazon.com/dp/$asin")
            page.waitForLoadState()

            // The delivery block arrives asynchronously; poll for it rather than reading once.
            var deliveryText = ""
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                deliveryText = runCatching {
                    page.locator("#deliveryBlockMessage").first().innerText().trim()
                }.getOrDefault("")
                if (deliveryText.isNotEmpty()) break
                page.waitForTimeout(1000.0)
            }

            val body = page.locator("body").innerText()
            File(out, "$asin-verdict.txt").writeText(body)

            println("[v] title:    ${page.title().take(72)}")
            println("[v] location: ${readFirst(page, "#glow-ingress-line2", "#nav-global-location-slot")}")
            println("[v] delivery block (${deliveryText.length} chars): ${deliveryText.replace("\n", " / ").take(150)}")

            // Everything that might carry an eligibility signal, so the phrasing is
            // learned from the page rather than assumed.
            listOf(
                "#availability", "#outOfStock", "#exports_desktop_qualifiedBuyBox_truncateCopy",
                "#contextualIngressPtMessage", "#deliveryPromiseBadgeMessage_feature_div",
                "#amazonGlobal_feature_div", "#importFeesDepositMessage",
            ).forEach { sel ->
                val n = page.locator(sel).count()
                if (n > 0) {
                    val t = runCatching { page.locator(sel).first().innerText().trim() }.getOrDefault("")
                    if (t.isNotEmpty()) println("[v]   $sel -> ${t.replace("\n", " / ").take(120)}")
                }
            }
            listOf(
                "cannot be shipped", "does not ship", "not available for", "unavailable",
                "ships to Colombia", "Import Fees Deposit", "import fees",
                "Deliver to Colombia", "Delivering to Colombia", "Colombia",
            ).forEach { phrase ->
                val idx = body.indexOf(phrase, ignoreCase = true)
                if (idx >= 0) {
                    println("[v]   FOUND '$phrase': ${body.substring(idx, minOf(idx + 96, body.length)).replace("\n", " / ")}")
                }
            }
        }
        browser.close()
    }
}

private fun readFirst(page: com.microsoft.playwright.Page, vararg selectors: String): String {
    selectors.forEach { sel ->
        if (page.locator(sel).count() > 0) {
            val t = runCatching { page.locator(sel).first().innerText().trim() }.getOrDefault("")
            if (t.isNotEmpty()) return "$sel = ${t.replace("\n", " / ").take(60)}"
        }
    }
    return "<no location element found>"
}
