package com.luis.dealstracker.sources.amazon

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * One search result, before a size has been resolved.
 *
 * The price here is the *parent's* advertised price, which is why it is only a starting
 * point: on Amazon each size is a separate child ASIN with its own price, so the number shown
 * on a search tile may belong to a size you cannot wear. It is carried anyway because it is
 * free, and because a parent with no price at all is a row worth dropping early.
 */
internal data class SearchHit(
    val asin: String,
    val title: String,
    val brand: String?,
    val url: String,
    val imageUrl: String?,
    /**
     * Non-null by construction: a tile with no parseable price is dropped in [AmazonParser].
     *
     * Made non-nullable after a nullable version let an unpriced tile reach `toRawDeal`, which
     * has to supply *something* for a non-null column and so would have stored `0`. A zero
     * price is not "unknown" — it sorts to the top of the feed, reads as the best deal ever
     * recorded, and poisons the price history that the promotion rules rest on. The type now
     * makes that unrepresentable rather than relying on the caller to remember.
     */
    val price: BigDecimal,
    val currency: String,
    /**
     * Amazon's own strikethrough "List:" price, when the tile shows one.
     *
     * Null on most tiles — only 17 of 48 carried one on a real capture — and null must stay a
     * normal outcome rather than reading as a parse failure. `RawDeal.discountPercent` is computed
     * from it, so a wrong value here would invent a discount.
     */
    val listPrice: BigDecimal? = null,
)

/**
 * Pure parsers for Amazon's search and product HTML.
 *
 * Separated from fetching for the reason every parser in this project is: the network half is
 * unpredictable and the parsing half is where the bugs that matter live, so the parsing half is a
 * pure function over captured HTML and is tested against real fixtures with no network.
 * `CoShippingExtractor` is the same shape.
 *
 * **Selectors are configurable** rather than constants, following `PrestashopSource`. Amazon's
 * markup is a rendered storefront, not a documented API, and it shifts -- so a selector that
 * matches nothing must be adjustable from `config/sources.yaml` without a rebuild, and must say so
 * loudly rather than returning an empty list that reads like an empty catalogue.
 */
internal object AmazonParser {

    /** Amazon's own id format. Same shape `SlickdealsSource` validates against. */
    private val ASIN_RE = Regex("^[A-Z0-9]{10}$")

    /**
     * Parses a `/s?k=` results page.
     *
     * Reads only tiles carrying `data-component-type="s-search-result"`, which is Amazon's own
     * marker for a real organic result — sponsored slots and layout scaffolding do not have it.
     * On the 2026-09-17 capture that was 48 tiles, 47 of them priced.
     *
     * A tile with no parseable price is dropped rather than defaulted: the one unpriced tile in
     * the fixture is a genuine "see options" listing with no single price to record.
     */
    fun parseSearch(html: String, selectors: AmazonSelectors, baseUrl: String): List<SearchHit> {
        val doc = Jsoup.parse(html, baseUrl)
        val tiles = doc.select(selectors.resultTile)
        if (tiles.isEmpty()) return emptyList()

        return tiles.mapNotNull { tile -> toSearchHit(tile, selectors, baseUrl) }
    }

    /**
     * Rejects link text that is plainly not a product name.
     *
     * A live run stored a deal titled **"Leave ad feedback"** — the text of a sponsored tile's
     * feedback link, picked up because the title selector matched it instead of the product. It
     * could not be reproduced on demand afterwards (every tile in three later captures carried a
     * proper `title-recipe` block), which is exactly why this guard is defensive rather than a
     * selector tweak: the layout that caused it is served sometimes, and a stored row with a
     * nonsense title is worse than a dropped one.
     *
     * Deliberately a short, specific deny-list plus a minimum length, not a cleverness contest.
     * Real Nike and adidas titles are long and descriptive, so a very short tail is never a
     * product; and anything that *is* a product but gets dropped here costs one row, whereas a
     * wrong title is tracked and priced forever.
     */
    internal fun isProductTitle(text: String): Boolean {
        if (text.length < MIN_TITLE_LENGTH) return false
        return NON_TITLES.none { text.contains(it, ignoreCase = true) }
    }

    private const val MIN_TITLE_LENGTH = 8

    /**
     * Longest plausible brand, in characters.
     *
     * 24 sits just above the longest real one observed ("Franchise Polo by Nike", 22) and far below
     * the shortest bogus one (43). Measured rather than guessed, and deliberately generous: this is
     * meant to reject prose, not to adjudicate brand names.
     */
    private const val MAX_BRAND_LENGTH = 24

    /** Interface affordances that have appeared where a product name was expected. */
    private val NON_TITLES = listOf(
        "leave ad feedback",
        "ad feedback",
        "sponsored",
        "see all buying options",
        "see options",
    )

    /** Exposed so the adapter can report "the selector matched nothing" distinctly. */
    fun countTiles(html: String, selectors: AmazonSelectors): Int =
        Jsoup.parse(html).select(selectors.resultTile).size

    internal fun toSearchHit(
        tile: Element,
        selectors: AmazonSelectors,
        baseUrl: String,
    ): SearchHit? {
        val asin = tile.attr("data-asin").takeIf { it.matches(ASIN_RE) } ?: return null

        // The <h2> in a title-recipe holds the BRAND ("Nike"), not the product name -- the
        // name is the sibling <a>. Reading the h2 as a title gives every row the same value.
        val link = tile.selectFirst(selectors.resultTitleLink) ?: return null
        val title = link.text().trim().takeIf { it.isNotEmpty() && isProductTitle(it) } ?: return null
        // Rejected when it is plainly a product title rather than a manufacturer.
        //
        // On some tiles the brand element holds the whole product name: three stored rows had
        // "brands" of 43, 109 and 118 characters, e.g. "Men's Lightweight Hiking Pants Breathable
        // Quick Dry Fit Waterproof...". Those would each appear as their own entry in the brand
        // dropdown, once, burying Nike and adidas.
        //
        // The split in the live data is clean: every genuine brand was 22 characters or fewer
        // ("Franchise Polo by Nike" is the longest), and every bogus one was 43 or more. A brand
        // that is dropped costs one row's filterability; a title treated as a brand pollutes a
        // control that is supposed to have a handful of options.
        val brand = tile.selectFirst(selectors.resultBrand)?.text()?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_BRAND_LENGTH }

        // Dropped, not defaulted. One of the four captured tiles is a real "see options"
        // listing with no single price, and recording it as 0 would put a phantom best-ever
        // deal at the top of the feed and into the price history.
        //
        // The plausibility band applies here too: a tile price is scoped to its own tile, so it
        // cannot be another product's — but a markup change could still hand us a review count or
        // a shipping figure, and this is the cheap guard against that.
        val priced = AmazonMoney.parse(tile.selectFirst(selectors.resultPrice)?.text())
            ?.takeIf { (amount, currency) -> AmazonMoney.isPlausibleApparelPrice(amount, currency) }
            ?: return null

        // Amazon's own list price, and the basis for the discount percentage it advertises.
        //
        // Held to the SAME plausibility band as the sale price, and discarded unless it is strictly
        // above it. A capture contained a tile with sale $155.00 against list $74.99 — a list price
        // below the sale price is not a discount, and `RawDeal.discountPercent` would return null
        // for it anyway. Dropping it here keeps the stored value meaning what it says.
        val listPrice = AmazonMoney.parse(tile.selectFirst(selectors.resultListPrice)?.text())
            ?.takeIf { (amount, currency) ->
                AmazonMoney.isPlausibleApparelPrice(amount, currency) && amount > priced.first
            }?.first

        return SearchHit(
            asin = asin,
            title = title,
            brand = brand,
            // Built from the ASIN rather than taken from the tile's href, which carries a
            // long tracking payload (`ref=sr_1_1?dib=...`) that would churn the stored URL
            // on every poll and make a deal look edited when nothing changed.
            url = "$baseUrl/dp/$asin",
            imageUrl = tile.selectFirst(selectors.resultImage)?.absUrl("src")
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") },
            price = priced.first,
            currency = priced.second,
            listPrice = listPrice,
        )
    }

    /**
     * The buy-box price on a product page.
     *
     * `.a-offscreen` is the screen-reader copy of the price and is the most stable place to
     * read it: the visible price is split across `a-price-whole` / `a-price-decimal` /
     * `a-price-fraction` spans, so its `.text()` concatenates to `"COP117,125"`-with-artifacts
     * depending on which spans render. The offscreen span carries the whole string as one node.
     *
     * Takes the first match, which is the buy box; later ones are recommendations and other
     * listings on the same page.
     */
    fun parsePrice(html: String, selectors: AmazonSelectors): Pair<BigDecimal, String>? =
        Jsoup.parse(html).select(selectors.productPrice)
            .asSequence()
            // Never a price that lives inside a link. A buy-box price is not a link; a price that
            // is one belongs to a *different* product in a carousel or comparison table. That is
            // literally what produced the $9,500 Nike: the matched element sat inside an
            // `a-link-normal` 64KB earlier in the document than the buy box.
            .filter { it.closest("a") == null }
            .mapNotNull { AmazonMoney.parse(it.text()) }
            // Absurd values are rejected rather than stored -- see isPlausibleApparelPrice. A bad
            // price becomes the recorded peak and makes every later reading look like a crash.
            .firstOrNull { (amount, currency) -> AmazonMoney.isPlausibleApparelPrice(amount, currency) }

    /**
     * Whether this HTML is actually a rendered product page.
     *
     * The signal that separates "the size is not stocked" from "we were served a wall". Amazon's
     * interstitial replaces the listing rather than decorating it, so it has no `#productTitle`
     * — the same property `AmazonVerifier.wallMarkersOn` relies on when it short-circuits.
     *
     * Checked positively rather than by scanning for wall wording, because a truncated or
     * partly-rendered response has neither the listing nor the interstitial's phrasing, and
     * concluding anything from it is exactly the mistake this exists to prevent.
     */
    fun looksLikeProductPage(html: String): Boolean =
        html.contains("id=\"productTitle\"") || html.contains("id='productTitle'")

    /** The product title as the page states it, preferred over the search tile's truncation. */
    fun parseTitle(html: String, selectors: AmazonSelectors): String? =
        Jsoup.parse(html).selectFirst(selectors.productTitle)?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Whether the page says the item is buyable.
     *
     * Absence of a stock signal is treated as **available**, matching how every other adapter
     * here handles a source that does not say. The opposite default would silently empty the
     * feed the first time Amazon renamed the element.
     */
    fun parseAvailable(html: String, selectors: AmazonSelectors): Boolean {
        val text = Jsoup.parse(html).selectFirst(selectors.productAvailability)?.text()?.lowercase()
            ?: return true
        return OUT_OF_STOCK.none { text.contains(it) }
    }

    private val OUT_OF_STOCK = listOf(
        "currently unavailable",
        "out of stock",
        "we don't know when or if this item will be back",
    )
}
