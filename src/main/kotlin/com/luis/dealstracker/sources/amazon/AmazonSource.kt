package com.luis.dealstracker.sources.amazon

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.encodeQueryValue

/**
 * Tracks specific Amazon products in a specific size.
 *
 * ## Why this source is different from every other one here
 *
 * Every other adapter answers "what is on offer?" and collapses a product's variants to the
 * cheapest purchasable one — `ShopifySource` and `VtexSource` both say so explicitly. That is
 * right for a fridge, where the variants are colours.
 *
 * It is wrong for clothing. On Amazon **a size is a distinct child ASIN with its own price and
 * its own stock**, so "the price of this hoodie" is not a fact; only "the price of this hoodie
 * in Medium" is. Collapsing to the cheapest variant would faithfully report the price of a
 * garment that does not fit. So this adapter emits **one deal per resolved child ASIN**, which
 * is also the call `FalabellaNextSource` makes when it keys on `skuId` rather than `productId`:
 * the purchasable thing is the thing worth tracking.
 *
 * ## How a poll proceeds
 *
 *  1. `GET /s?k=<term>` — the unfiltered search, which also carries Amazon's size-refinement rail.
 *  2. Read the rail for the configured size; that costs no request, the page is already in hand.
 *  3. `GET` that search with `rh=<size filter>`, then `&page=2`, `&page=3`… up to
 *     `maxPagesPerSearch`. Every tile is an item available in that size, already priced.
 *
 * Roughly `1 + maxPagesPerSearch` requests per search, and **none per item**. An earlier version resolved sizes itself by
 * fetching each product page for its variation payload; that cost one or two `/dp/` requests per
 * item and was the measured bottleneck — one run lost 14 of 15 correctly-resolved shoes because
 * those requests were refused. Letting Amazon filter server-side removes them entirely, and makes
 * the size its guarantee rather than our inference. See `SizeRefinement`.
 *
 * ## The request budget is the binding constraint
 *
 * Amazon served this machine two good responses and then a 503 on the third (2026-09-17, own user
 * agent, no spoofing). `AmazonFetcher` escalates to the trusted headed Chrome profile the shipping
 * verifier maintains, but runs are still partly refused. That degrades to fewer items rather than
 * wrong ones, and repeated polls accumulate coverage the way Slickdeals already does.
 *
 * ## Sizes are Amazon's own words — its SHORT ones
 *
 * The config says `8`, `M`, `S`: the labels Amazon prints in its filter rail. Note these are *not*
 * the spelled-out labels its product pages use (`Medium`, `XX-Large`) — both are Amazon's wording
 * for one size, in different places, and this path reads the rail. No aliasing is invented, so a
 * size the rail does not offer is reported by name at the end of the run rather than guessed at.
 */
class AmazonSource(
    /** Terms switched off in the UI for a provider, read at fetch time so a toggle needs no restart. */
    private val disabledTerms: (providerId: String) -> Set<String> = { emptySet() },
) : DealSource {

    override val type = "amazon"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')

        // Read from `trackSearches`, NOT `searches`, and that is the whole point of the name.
        //
        // `IngestService.withWatchTerms` merges every active watch's terms into every
        // provider's `searches` key. That is right for a general retailer and wrong here: this
        // provider is a size-aware tracker for specific clothing, and the first live run
        // dutifully searched amazon.com for `inodoro`, `televisor` and `nevera`, because
        // watches for a toilet, a TV and a fridge happened to be enabled. Nine of fifteen
        // search requests went on items this provider cannot size and was never meant to
        // track — against a source that starts refusing on the third request — and every one
        // of the 33 TVs and toilets it stored was labelled `Fashion`.
        //
        // Using a key injection does not know about means opting out is structural rather than
        // a flag someone has to remember, and a watch can still reach Amazon the way it always
        // did: through the `slickdeals` provider.
        val configured = cfg.list("trackSearches")
        val off = if (configured.isEmpty()) emptySet() else disabledTerms(cfg.id)
        val searches = configured.filterNot { it in off }
        if (off.isNotEmpty()) println("[${cfg.id}] skipping ${configured.size - searches.size} disabled search(es)")
        if (searches.isEmpty()) {
            println("[${cfg.id}] no enabled trackSearches; nothing to fetch")
            return emptyList()
        }
        val injected = cfg.list("searches").size
        if (injected > 0) {
            println("[${cfg.id}] ignoring $injected watch term(s) — this provider tracks trackSearches only")
        }

        val selectors = AmazonSelectors.from(cfg)
        // 20, up from 8. A size-filtered page yields ~45 usable tiles, so 8 was the tighter
        // constraint of the two and cost nothing to relax -- the page is fetched either way.
        val maxItems = cfg.int("maxItemsPerSearch", 20).coerceIn(1, 100)

        // Pages to walk per search. Verified against the live site: pages 1-3 each returned 48
        // tiles with the size filter still applied and ZERO overlap between them.
        //
        // 2 by default, not 3. Each extra page is one more request against a host that refuses
        // intermittently, and refusals -- not code -- are the binding constraint here. Two pages
        // already offer ~90 usable tiles per search, well past maxItems.
        val maxPages = cfg.int("maxPagesPerSearch", 2).coerceIn(1, 7)
        val fetcher = AmazonFetcher(
            http = http,
            allowBrowser = cfg.options["allowBrowserFallback"]?.toBoolean() ?: true,
            logPrefix = cfg.id,
        )

        val deals = LinkedHashMap<String, RawDeal>()

        /** Searches whose size could not be filtered — reported by name so they can be fixed. */
        val unsizedTerms = mutableListOf<String>()

        // try/finally, because a Chrome left running holds the exclusive lock on
        // .amazon-profile -- which would then block the shipping verifier, not merely leave a
        // window open.
        try {
            for (term in searches) {
                val garment = Garment.of(term)
                val wantedSize = sizeFor(cfg, garment)

                val searchUrl = "$base/s?k=${encodeQueryValue(term)}"
                // Wait for the result grid itself. The document reports loaded before the tiles are
                // in the DOM, so without this the browser path read a page with zero tiles and the
                // adapter blamed its own selector.
                val firstPage = fetcher.html(searchUrl, expect = selectors.resultTile) ?: continue

                // Ask Amazon to filter by size, rather than resolving each item ourselves. This
                // is why the per-item `/dp/` fetches are gone: a filtered page returns only items
                // available in the wanted size, each tile already priced.
                //
                // The URL every page of this search is built from: unfiltered, or with Amazon's own
                // size refinement appended. Resolved once per term, because the refinement id is
                // read from the rail on the page already in hand.
                val pagedUrl = if (garment == Garment.UNSIZED || wantedSize == null) {
                    searchUrl
                } else {
                    refinedUrl(firstPage, term, wantedSize, searchUrl, cfg)
                        ?: run { unsizedTerms += term; null }
                } ?: continue

                val size = if (garment == Garment.UNSIZED) null else wantedSize
                var kept = 0

                // Walk pages until the budget runs out or a page brings nothing new.
                //
                // Paging is a plain `&page=N` parameter, verified against the live site: pages 1-3
                // each returned 48 tiles, the `rh` size filter stayed applied throughout, and the
                // overlap between pages was ZERO (130 distinct items across three). So depth
                // genuinely multiplies coverage rather than re-reading the same rows.
                //
                // The results UI also loads on scroll, which makes it look like there is no URL to
                // fetch — but Amazon still ships a numbered pager whose links are ordinary URLs, and
                // those are what this follows. No scrolling, no JS.
                for (pageNo in 1..maxPages) {
                    // Page 1 is the bare URL: adding `&page=1` works but needlessly differs from
                    // the URL whose rail we just read.
                    val url = if (pageNo == 1) pagedUrl else "$pagedUrl&page=$pageNo"

                    // Page 1 of an unfiltered search is already in hand -- do not re-fetch it.
                    val pageHtml = if (pageNo == 1 && pagedUrl == searchUrl) {
                        firstPage
                    } else {
                        fetcher.html(url, expect = selectors.resultTile)
                    } ?: break

                    val hits = AmazonParser.parseSearch(pageHtml, selectors, base)
                    if (hits.isEmpty()) {
                        // Only worth remarking on for page 1. A later empty page is simply the end
                        // of the results, which is the normal way this loop terminates.
                        if (pageNo == 1) {
                            val tiles = AmazonParser.countTiles(pageHtml, selectors)
                            println(
                                "[${cfg.id}] '$term' -> 0 usable results from $tiles tile(s); " +
                                    if (tiles == 0) {
                                        "Amazon's markup may have changed — check options.resultTileSelector"
                                    } else {
                                        "tiles were found but none carried a title and price"
                                    },
                            )
                        }
                        break
                    }

                    // Every tile on a size-filtered page is already an item available in that size,
                    // priced, so there is nothing left to resolve and no further request to make.
                    //
                    // The ASIN is the listing Amazon showed for that size. It may be a parent whose
                    // default variant differs, which is why the size is recorded from the FILTER --
                    // Amazon's own guarantee -- rather than re-derived from the page.
                    var addedHere = 0
                    for (hit in hits) {
                        if (kept >= maxItems) break
                        // putIfAbsent is what makes the budget count DISTINCT items: the same ASIN
                        // can appear under two terms ("nike men shoes" and "nike men running shoes"),
                        // and it should consume one slot, not two.
                        if (deals.putIfAbsent(hit.asin, toRawDeal(hit, garment, size)) == null) {
                            kept++
                            addedHere++
                        }
                    }

                    // A page whose every row was already known means later pages are unlikely to
                    // differ either -- stop rather than spend the request budget confirming it.
                    if (addedHere == 0) break
                    if (kept >= maxItems) break
                }

                println("[${cfg.id}] '$term' (${garment.name.lowercase()}, size ${size ?: "n/a"}) -> $kept tracked")
            }
        } finally {
            fetcher.close()
        }

        // Named, not just counted. A search whose size could not be filtered is the one case the
        // operator asked to be told about, because they can find the URL by hand — so the terms are
        // listed rather than reduced to a number.
        if (unsizedTerms.isNotEmpty()) {
            println(
                "[${cfg.id}] could not apply a size filter for ${unsizedTerms.size} search(es): " +
                    "$unsizedTerms — these were skipped rather than tracked at an unguaranteed size",
            )
        }
        println("[${cfg.id}] ${deals.size} item(s) tracked in the configured sizes")

        return deals.values.toList()
    }

    /**
     * The search URL with Amazon's own size filter appended, or null when the size is not offered.
     *
     * Costs **no extra request**: the refinement rail is on the unfiltered page already fetched, so
     * discovery is pure parsing. The returned URL is then paged with `&page=N`.
     *
     * Returns null rather than falling back to the unfiltered URL, deliberately. Unfiltered tiles
     * are not size-guaranteed and their prices belong to whichever variant Amazon chose to display,
     * so tracking them would reintroduce the wrong-size-at-a-real-price failure this adapter exists
     * to prevent. The caller records the term and reports it by name at the end of the run.
     */
    private fun refinedUrl(
        unfiltered: String,
        term: String,
        wantedSize: String,
        searchUrl: String,
        cfg: ProviderConfig,
    ): String? {
        val options = SizeRefinement.parse(unfiltered)
        val picked = SizeRefinement.pick(options, wantedSize)

        if (picked == null) {
            val offered = SizeRefinement.offered(options)
            println(
                "[${cfg.id}] '$term': Amazon offers no size '$wantedSize' filter here" +
                    if (offered.isEmpty()) {
                        " (no size rail found on the page at all)"
                    } else {
                        " — it offers ${offered.take(12)}"
                    },
            )
            return null
        }

        println("[${cfg.id}] '$term': filtering by ${picked.parameter}=${picked.label}")
        return "$searchUrl&rh=${encodeQueryValue(picked.rh)}"
    }

    internal fun toRawDeal(hit: SearchHit, garment: Garment, size: String?): RawDeal = RawDeal(
        externalId = hit.asin,
        title = titleWithSize(hit.title, size),
        url = hit.url,
        price = hit.price,
        // The currency Amazon actually quoted, not the provider's nominal one -- it served COP
        // to this machine and USD to others. See AmazonMoney.
        currency = hit.currency,
        merchant = "amazon",
        asin = hit.asin,
        brand = hit.brand,
        imageUrl = hit.imageUrl,
        // Amazon's own "List:" price when the tile shows one, which is what its advertised
        // discount percentage is computed from. Null on most tiles and that stays normal -- the UI
        // renders a blank rather than a 0% discount, the rule this project holds to everywhere.
        //
        // Already checked to be strictly above the sale price in the parser, so `discountPercent`
        // cannot produce a negative or zero "discount".
        listPrice = hit.listPrice,
        // Null for an unsized item rather than a guessed clothing category -- see Garment.
        category = garment.category,
    )

    /**
     * Puts the size in the title, which is the only place it can be seen without a schema
     * change.
     *
     * `deals` has no variant or size column and no JSON column, and adding one would touch the
     * upsert, the feed views, the export and the watch queries. The title is already the thing
     * every one of those surfaces displays, and two sizes of one garment are two rows whose
     * titles must differ or they read as duplicates. Worth revisiting if size ever needs to be
     * filtered on rather than merely read.
     */
    internal fun titleWithSize(title: String, size: String?): String =
        if (size.isNullOrBlank() || title.contains(" — $size")) title else "$title — $size"

    /** The configured size for a garment type, or null when none is set. */
    private fun sizeFor(cfg: ProviderConfig, garment: Garment): String? =
        garment.configKey.takeIf { it.isNotEmpty() }
            ?.let { cfg.options[it] }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

}
