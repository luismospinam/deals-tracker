package com.luis.dealstracker.sources.amazon

import com.luis.dealstracker.config.ProviderConfig

/**
 * Amazon's markup, as selectors that can be corrected from config.
 *
 * Same call `PrestashopSource.Selectors` makes and for the same reason: this reads a rendered
 * storefront rather than a documented API. VTEX, Shopify and Algolia publish JSON with field
 * names they are contractually stuck with; Amazon publishes HTML it reorganises whenever it
 * likes. Hardcoding these would mean a Kotlin change and a rebuild the first time a class name
 * moves, and — worse — the failure would look like "Amazon has no Nike shoes" rather than
 * "the selector is stale".
 *
 * Defaults verified against real captures on 2026-09-17 (`fixtures/amazon/`).
 */
internal data class AmazonSelectors(
    val resultTile: String,
    val resultTitleLink: String,
    val resultBrand: String,
    val resultPrice: String,
    val resultListPrice: String,
    val resultImage: String,
    val productTitle: String,
    val productPrice: String,
    val productAvailability: String,
) {
    companion object {
        fun from(cfg: ProviderConfig) = AmazonSelectors(
            // Amazon's own marker for an organic result. Sponsored slots and grid scaffolding
            // lack it, so this filters ads without a separate rule.
            resultTile = cfg.options["resultTileSelector"]
                ?: "div[data-component-type=s-search-result]",
            // The product NAME. Deliberately not the <h2> in the same block -- that holds the
            // brand ("Nike"), so reading it as the title gives every row an identical value.
            resultTitleLink = cfg.options["resultTitleSelector"]
                ?: "[data-cy=title-recipe] a.a-link-normal.s-line-clamp-3, [data-cy=title-recipe] a.a-link-normal",
            resultBrand = cfg.options["resultBrandSelector"]
                ?: "[data-cy=title-recipe] h2 span",
            // The screen-reader copy: one text node holding the whole amount. The visible price
            // is split across whole/decimal/fraction spans that concatenate unreliably.
            // `:not(.a-text-price)` is load-bearing, not tidiness. `a-text-price` marks Amazon's
            // strikethrough LIST price and carries `a-price` as well, so a bare `span.a-price`
            // matches both. It happens to be safe today -- on a 48-tile capture the sale price came
            // first every time -- but that is document order, not a guarantee, and reading a list
            // price as the sale price is a silent wrong number.
            resultPrice = cfg.options["resultPriceSelector"]
                ?: "span.a-price:not(.a-text-price) span.a-offscreen",
            // Amazon's strikethrough "List:" price on a search tile. `a-text-price` is its own
            // marker for a was-price, and it is a SUBSET of `a-price` — so the sale-price selector
            // above matches it too, and the list price must be excluded there rather than merely
            // read here. Present on 17 of 48 tiles on a real capture, which is why a null list
            // price has to stay normal rather than look like a parse failure.
            resultListPrice = cfg.options["resultListPriceSelector"]
                ?: "span.a-price.a-text-price span.a-offscreen",
            resultImage = cfg.options["resultImageSelector"] ?: "img.s-image",
            productTitle = cfg.options["productTitleSelector"] ?: "#productTitle",
            // Buy-box containers ONLY, in descending specificity, and deliberately WITHOUT a
            // bare `span.a-price` fallback.
            //
            // That fallback was the bug. `#corePrice_feature_div` does not exist on these pages
            // (the real id is `#corePrice_desktop`), so every price came from the loose last
            // alternative: the first `span.a-price` anywhere in the document. On a real capture
            // that was a price inside an `a-link-normal` — a link to a DIFFERENT product in a
            // carousel, 64KB earlier in the document than the buy box. It stored a Nike
            // Revolution 8 at $9,500.
            //
            // A price that cannot be located in the buy box is now no price at all, which the
            // caller turns into a dropped row. Silence beats a confident wrong number, the same
            // call `CoShippingExtractor` makes about a YES verdict.
            productPrice = cfg.options["productPriceSelector"]
                ?: "#corePrice_desktop span.a-offscreen, " +
                "#corePriceDisplay_desktop_feature_div span.a-offscreen, " +
                "#corePrice_feature_div span.a-offscreen, " +
                "#apex_desktop span.a-offscreen, " +
                "#buybox .priceToPay span.a-offscreen, " +
                "#price_inside_buybox, #newBuyBoxPrice",
            productAvailability = cfg.options["productAvailabilitySelector"] ?: "#availability",
        )
    }
}
