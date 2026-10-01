package com.luis.dealstracker.verify

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page

/**
 * Reads the text of one page region, treating an absent region as `""` **quickly**.
 *
 * ## Why this is not a one-liner
 *
 * `innerText()` is a Playwright *action*, and actions are bounded by a timeout that
 * `Page.setDefaultNavigationTimeout` does not touch — so a caller that sets only the navigation
 * timeout still gets Playwright's 30-second default here, and pays it in full for any selector
 * that never appears.
 *
 * That fell entirely on the pages whose verdict is NO. Measured against the real saved product
 * pages in `build/amazon-probe`:
 *
 * | region | on a shippable page | on a refused page |
 * |---|---|---|
 * | `#availability` | 16ms, `"In Stock"` | **30,016ms**, absent |
 * | `#deliveryBlockMessage` | 7ms, names the country | 7ms, present but empty |
 * | `#amazonGlobal_feature_div` | 26ms | 4ms |
 * | `#buybox` | 12ms | 5ms |
 *
 * Amazon simply does not render some of these regions for an item it will not ship to the
 * destination, and a missing element is the slow case while an empty one is the fast case. So the
 * cost was not "non-shippable items are harder to read" — it was one unbounded wait per absent
 * region, on exactly the items that have absent regions.
 *
 * Kept separate from [CoShippingExtractor] for the same reason that class exists: the extractor
 * decides verdicts from text and is tested offline, while this is the browser-facing read. Shared
 * rather than private to `AmazonVerifier` so a test can pin the timing behaviour against a real
 * page, and so any future reader of these regions inherits the bound instead of rediscovering it.
 *
 * @param timeoutMillis how long a region may take to appear before it counts as absent. A region
 *   already in the DOM returns in milliseconds, so this only needs to cover an AJAX fill in
 *   flight, never a region that is not coming.
 */
internal fun regionText(page: Page, selector: String, timeoutMillis: Long): String =
    runCatching {
        page.locator(selector).first()
            .innerText(Locator.InnerTextOptions().setTimeout(timeoutMillis.toDouble()))
            .trim()
    }.getOrDefault("")
