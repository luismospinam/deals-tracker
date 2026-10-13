package com.luis.dealstracker.sources.amazon

import com.luis.dealstracker.model.RawDeal

/**
 * Turns a pasted Amazon URL into a [RawDeal], reading everything from the product page itself.
 *
 * ## Why this route is more trustworthy than the search path
 *
 * The `amazon` provider finds items by keyword and lets Amazon filter by size. That is good for
 * discovery and imprecise by nature: Amazon's size filter returns a **representative of the product
 * family**, not strictly the matching child. A size-8 search returned the 8.5 ASIN at $130.26 while
 * size 8 sold for $107.41 — so a searched row can carry the wrong variant at the wrong price, and
 * its size label is Amazon's claim rather than a verified fact.
 *
 * A pasted URL names an exact ASIN. The price is therefore the price of the thing actually wanted,
 * and the size comes from that ASIN's **own** variation entry. Nothing is inferred from a filter.
 *
 * ## One request, everything read from it
 *
 * Brand, category, size, title, price and stock all come off the single page fetch, which is why an
 * add is cheap and why the operator normally types nothing but the URL. Overrides exist for the
 * cases where the page says nothing useful, and they win when supplied.
 */
internal class ManualItemFetcher(
    private val fetcher: AmazonFetcher,
    private val selectors: AmazonSelectors,
    private val baseUrl: String = "https://www.amazon.com",
) {

    /** Why an add or refresh produced no deal. Distinct cases, because the remedies differ. */
    internal sealed interface Failure {
        /** The URL names no ASIN — a search page, a shortened link, a typo. */
        data object NotAProductUrl : Failure

        /** Amazon refused the request or served an interstitial. Worth retrying next poll. */
        data object Unreadable : Failure

        /** The page rendered but carries no buy-box price: out of stock, or no featured offer. */
        data object NoPrice : Failure
    }

    internal sealed interface Result {
        data class Ok(val deal: RawDeal, val asin: String) : Result
        data class Failed(val reason: Failure, val asin: String?) : Result
    }

    /**
     * Fetches one URL and builds its deal.
     *
     * @param brandOverride used in place of the page's byline when supplied.
     * @param typeOverride recorded as the size-less category hint when supplied; the page's
     *   breadcrumb is used otherwise.
     */
    suspend fun fetch(url: String, brandOverride: String? = null, typeOverride: String? = null): Result {
        val asin = AmazonParser.asinFromUrl(url)
            ?: return Result.Failed(Failure.NotAProductUrl, null)

        // Canonical URL, not the pasted one: a pasted link carries a `ref=`/`dib=` tracking payload
        // that changes between visits, and storing it would make the deal's URL churn every poll.
        val canonical = "$baseUrl/dp/$asin"

        val html = fetcher.html(canonical, expect = selectors.productTitle)
            ?: return Result.Failed(Failure.Unreadable, asin)

        // Same guard the search path applies: Amazon's interstitial replaces the listing, so it
        // carries no #productTitle. Without this a refusal reads as "a product with no price".
        if (!AmazonParser.looksLikeProductPage(html)) {
            return Result.Failed(Failure.Unreadable, asin)
        }

        return fromPage(html, asin, canonical, selectors, brandOverride, typeOverride)
    }

    internal companion object {
        /**
         * Builds the deal from a product page already in hand -- the part of [fetch] after the request, so
         * a caller that has the page open (the verifier, finding a size's sibling) reads it the same way.
         */
        fun fromPage(
            html: String,
            asin: String,
            canonical: String,
            selectors: AmazonSelectors,
            brandOverride: String? = null,
            typeOverride: String? = null,
        ): Result {
            val priced = AmazonParser.parsePrice(html, selectors)
                ?: return Result.Failed(Failure.NoPrice, asin)

            val title = AmazonParser.parseTitle(html, selectors) ?: "Amazon item $asin"
            // The size Amazon records for THIS ASIN -- definitive, unlike a filter-derived one.
            val size = AmazonParser.parseOwnSize(html)

            return Result.Ok(
                RawDeal(
                    externalId = asin,
                    title = if (size.isNullOrBlank()) title else "$title — $size",
                    url = canonical,
                    price = priced.first,
                    currency = priced.second,
                    merchant = "amazon",
                    asin = asin,
                    // Override first, then the page's byline. Null stays null rather than being guessed
                    // from the title: a wrong brand produces a wrong identity key.
                    brand = brandOverride?.trim()?.takeIf { it.isNotEmpty() } ?: AmazonParser.parseBrand(html),
                    imageUrl = AmazonParser.parseImage(html),
                    listPrice = null,
                    // A type override doubles as the category hint; otherwise the breadcrumb, which
                    // Categorizer prefers to guessing from a title.
                    category = typeOverride?.trim()?.takeIf { it.isNotEmpty() }
                        ?: AmazonParser.parseBreadcrumb(html),
                    available = AmazonParser.parseAvailable(html, selectors),
                ),
                asin,
            )
        }

        /** Explains a failure in the terms the operator can act on. */
        fun describe(reason: Failure): String = when (reason) {
            Failure.NotAProductUrl ->
                "that URL names no product — paste an amazon.com/dp/<ASIN> link"
            Failure.Unreadable ->
                "Amazon would not serve the page just now; it will be retried on the next poll"
            Failure.NoPrice ->
                "the page has no buy-box price (out of stock, or no featured offer)"
        }
    }
}
