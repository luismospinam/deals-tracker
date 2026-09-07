package com.luis.dealstracker.verify

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.File

/**
 * Throwaway explorer for Phase 1: works out how amazon.com exposes shipping eligibility
 * and how the delivery country can be set. This is NOT the verifier — it only observes and
 * dumps, so the extraction logic can then be written against saved HTML offline.
 *
 * Deliberate choices:
 *  - Drives the *installed Google Chrome* (`setChannel("chrome")`) rather than a bundled
 *    headless shell. A real browser sends a real user agent; the alternative would be
 *    disguising a headless one, which is both dishonest and the thing we said we would
 *    not do.
 *  - Navigates at most once per [MIN_INTERVAL_MS], the agreed politeness floor.
 *  - Writes everything to disk so analysis happens offline and no page is fetched twice.
 */
object ProbeConfig {
    /** One page per 30s. A hard floor, not a target. */
    const val MIN_INTERVAL_MS = 30_000L
    val outDir = File("build/amazon-probe")
}

/**
 * Markers for Amazon's anti-automation pages. A hit is a **stop condition**: back off and
 * report it, never work around it.
 *
 * The "continue shopping" entry is the one that actually fired in practice, and its absence
 * from the original list is instructive — the first version of this checked only for CAPTCHA
 * phrasings, so the real wall sailed through undetected and the page merely looked empty.
 * Left unattended, a batch would have recorded hundreds of deals as "no verdict" while
 * reporting success.
 */
internal fun botWallMarkers(html: String): List<String> = listOf(
    "Click the button below to continue shopping",
    // NOT a bare "Continue shopping". That phrase occurs in the hidden markup of ordinary
    // product pages, so it flagged pages that were loading perfectly well -- and because
    // the help banner this tool injects contains the phrase itself, the clear-check then
    // matched its own banner and could never succeed. Deals that plainly shipped to
    // Colombia were recorded as blocked and backed off. The full sentence above is the
    // actual interstitial's wording and is specific enough to stand alone.
    "Enter the characters you see below",
    "Type the characters you see in this image",
    "api-services-support@amazon.com",
    "Robot Check",
    "/errors/validateCaptcha",
    "Sorry, we just need to make sure you're not a robot",
).filter { html.contains(it, ignoreCase = true) }

fun main(args: Array<String>) {
    val asin = args.firstOrNull { !it.startsWith("--") } ?: "B09C5RG6KV"
    val headed = args.contains("--headed")
    ProbeConfig.outDir.mkdirs()

    Playwright.create().use { pw ->
        val browser = pw.chromium().launch(
            BrowserType.LaunchOptions().setChannel("chrome").setHeadless(!headed),
        )
        val page = browser.newContext().newPage()

        println("[probe] chrome ${browser.version()}  asin=$asin  headed=$headed")
        page.navigate("https://www.amazon.com/dp/$asin")
        page.waitForLoadState()

        val html = page.content()
        File(ProbeConfig.outDir, "$asin.html").writeText(html)
        page.screenshot(
            Page.ScreenshotOptions().setPath(File(ProbeConfig.outDir, "$asin.png").toPath()),
        )
        println("[probe] title: ${page.title().take(80)}")
        println("[probe] html:  ${html.length} bytes")

        val walls = botWallMarkers(html)
        if (walls.isNotEmpty()) {
            // A bot wall is a valid finding and a stop condition, never something to work around.
            println("[probe] STOP: bot wall detected -> $walls")
            browser.close()
            return@use
        }
        println("[probe] no bot wall markers")

        // The earlier fetch found no "Deliver to" text, so enumerate candidate controls
        // rather than assuming a selector.
        println("\n[probe] location controls present?")
        listOf(
            "#glow-ingress-line1" to "glow label",
            "#glow-ingress-line2" to "glow value",
            "#nav-global-location-popover-link" to "popover link",
            "#nav-global-location-slot" to "location slot",
            "#contextualIngressPtLabel" to "contextual ingress",
            "#deliveryBlockMessage" to "delivery block",
            "#exports_desktop_qualifiedBuyBox_truncateCopy" to "intl shipping copy",
        ).forEach { (sel, label) ->
            val loc = page.locator(sel)
            val n = loc.count()
            val txt = if (n > 0) runCatching { loc.first().innerText().trim().replace("\n", " / ") }
                .getOrDefault("<hidden>") else "-"
            println("   %-52s %-20s n=%d  %s".format(sel, label, n, txt.take(44)))
        }

        // Learn Amazon's real phrasing instead of guessing at it.
        val text = page.locator("body").innerText()
        File(ProbeConfig.outDir, "$asin.txt").writeText(text)
        println("\n[probe] delivery / shipping phrases in page text:")
        listOf(
            "cannot be shipped", "not eligible", "does not ship", "ships to",
            "Deliver to", "Delivering to", "Ships from", "Sold by",
            "International", "import fees", "FREE delivery", "Delivery",
        ).forEach { phrase ->
            val idx = text.indexOf(phrase, ignoreCase = true)
            if (idx >= 0) {
                println("   [$phrase] ${text.substring(idx, minOf(idx + 88, text.length)).replace("\n", " / ")}")
            }
        }

        // --- Step 2: how is the delivery country changed? ---
        // The label is "Delivering to", not "Deliver to" -- which is why an earlier text
        // search for the latter found nothing. Amazon geolocates us to a US zip via the
        // proxy egress, so the country must be changed explicitly.
        if (args.contains("--popover")) {
            println("\n[probe] opening the location popover")
            page.click("#nav-global-location-popover-link")
            page.waitForTimeout(2500.0)

            // The country control is a JS "a-dropdown", not a native select, so its
            // options do not exist until it is clicked. Click it, then dump the WHOLE
            // document -- guessing at the injected container was what returned 138 bytes.
            runCatching {
                page.click("#GLUXCountryListDropdown")
                page.waitForTimeout(1500.0)
            }.onFailure { println("[probe] country dropdown click failed: ${it.message}") }

            val afterHtml = page.content()
            File(ProbeConfig.outDir, "popover-full.html").writeText(afterHtml)
            println("[probe] full DOM after opening dropdown: ${afterHtml.length} bytes")
            println("[probe] 'Colombia' occurrences in DOM: ${Regex("Colombia").findAll(afterHtml).count()}")

            println("\n[probe] controls inside the popover:")
            listOf(
                "#GLUXCountryListDropdown" to "country dropdown",
                "#GLUXCountryList" to "country list",
                "#GLUXZipUpdateInput" to "zip input",
                "#GLUXZipUpdate" to "zip apply",
                "select[name='dropdown-selection']" to "generic dropdown",
                "input[name='glowDoneButton']" to "done button",
                "#GLUXConfirmClose" to "confirm close",
                "a:has-text('outside the')" to "ship outside US link",
            ).forEach { (sel, label) ->
                val n = page.locator(sel).count()
                println("   %-46s %-22s n=%d".format(sel, label, n))
            }

            // #GLUXCountryList is a native <select> under the heading "or ship outside
            // the US", containing <option value="CO">Colombia</option>. An earlier query
            // returned 0 options because it looked for `#GLUXCountryList a` instead.
            val opts = page.locator("#GLUXCountryList option")
            println("\n[probe] country options: ${opts.count()}")
            println("[probe] has CO option: ${page.locator("#GLUXCountryList option[value='CO']").count() > 0}")

            println("\n[probe] selecting Colombia (CO)")
            page.selectOption("#GLUXCountryList", "CO")
            page.waitForTimeout(3000.0)
            runCatching { page.click("#GLUXConfirmClose") }
                .onFailure { println("[probe] no confirm button to click (may auto-apply)") }
            page.waitForTimeout(1000.0)

            // Amazon reloads to apply the location. Respect the politeness floor before
            // the resulting navigation settles into another page fetch.
            println("[probe] waiting ${ProbeConfig.MIN_INTERVAL_MS / 1000}s before re-reading (rate floor)")
            page.waitForTimeout(ProbeConfig.MIN_INTERVAL_MS.toDouble())
            page.reload()
            page.waitForLoadState()

            val nowLocation = runCatching { page.locator("#glow-ingress-line2").first().innerText().trim() }
                .getOrDefault("?")
            println("[probe] location now reads: '$nowLocation'")

            val after = page.locator("body").innerText()
            File(ProbeConfig.outDir, "$asin-CO.txt").writeText(after)
            File(ProbeConfig.outDir, "$asin-CO.html").writeText(page.content())

            // Persist the session so later pages skip the whole address dance.
            page.context().storageState(
                com.microsoft.playwright.BrowserContext.StorageStateOptions()
                    .setPath(File(ProbeConfig.outDir, "amazon-session.json").toPath()),
            )
            println("[probe] session saved -> build/amazon-probe/amazon-session.json")

            println("\n[probe] eligibility signals with Colombia selected:")
            listOf(
                "#deliveryBlockMessage", "#exports_desktop_qualifiedBuyBox_truncateCopy",
                "#availability", "#outOfStock", "#buybox-see-all-buying-choices",
            ).forEach { sel ->
                val n = page.locator(sel).count()
                val t = if (n > 0) runCatching { page.locator(sel).first().innerText().trim().replace("\n", " / ") }
                    .getOrDefault("<hidden>") else "-"
                println("   %-50s n=%d  %s".format(sel, n, t.take(90)))
            }
            listOf(
                "cannot be shipped", "not available for shipping", "does not ship",
                "ships to Colombia", "Import Fees", "import charges", "unavailable",
                "Delivery to", "Ships to Colombia", "This item cannot be shipped",
            ).forEach { phrase ->
                val idx = after.indexOf(phrase, ignoreCase = true)
                if (idx >= 0) println("   FOUND [$phrase] -> ${after.substring(idx, minOf(idx + 100, after.length)).replace("\n", " / ")}")
            }
        }

        browser.close()
    }
    println("\n[probe] done — analyse build/amazon-probe/ offline; nothing fetched twice.")
}
