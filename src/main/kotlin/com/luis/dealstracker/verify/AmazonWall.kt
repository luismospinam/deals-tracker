package com.luis.dealstracker.verify

/**
 * Detection for Amazon's anti-automation pages, shared by everything that touches amazon.com.
 *
 * Moved here from `AmazonProbe.kt`, which is a throwaway explorer with a `main()`: four
 * production call sites in `verify/` plus the `sources/amazon` adapter depended on a function
 * living in a file whose stated purpose is to be deleted. Nothing about the logic changed.
 */

/**
 * Markers for Amazon's anti-automation pages. A hit is a **stop condition**: back off and
 * report it, never work around it.
 *
 * The "continue shopping" entry is the one that actually fired in practice, and its absence
 * from the original list is instructive — the first version of this checked only for CAPTCHA
 * phrasings, so the real wall sailed through undetected and the page merely looked empty.
 * Left unattended, a batch would have recorded hundreds of deals as "no verdict" while
 * reporting success.
 *
 * `api-services-support@amazon.com` also covers the plain-HTTP throttle: a `503` served to a
 * non-browser request carries that address in an HTML comment, with no CAPTCHA wording at all.
 * Confirmed on 2026-09-17, on the third consecutive request from this machine.
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
    // Amazon's 503 refusal page -- the "SORRY something went wrong on our end" one with the dog.
    //
    // Both markers are needed because the SAME page reaches us in two forms. Fetched over HTTP it
    // carries `api-services-support@amazon.com` in an HTML comment, which the entry above catches.
    // Rendered in a browser, `page.content()` returns the DOM **after parsing**, and that comment
    // is gone -- so not one marker above matched, and a 2.3KB refusal was accepted as a valid
    // search results page with zero results. That is what made the adapter blame its own selector
    // ("the markup may have changed") while Amazon was simply saying no.
    //
    // `cs_503` is the ref tag on that page's own links and `dogsofamazon` is its illustration;
    // both survive DOM parsing and neither appears on a real listing page.
    "cs_503",
    "dogsofamazon",
).filter { html.contains(it, ignoreCase = true) }
