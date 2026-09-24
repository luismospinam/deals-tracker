package com.luis.dealstracker.sources.amazon

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.WatchSearch
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.encodeQueryValue

/**
 * Searches amazon.com for what the watches ask for — a TV, a fridge — rather than for clothing.
 *
 * ## Why this is not the `amazon` adapter with watch terms switched on
 *
 * `AmazonSource` is a size-aware tracker: every row it stores is one child ASIN in the operator's
 * own size, and its rows are the Tracked tab. A television has no size, belongs to a watch rather
 * than to that tab, and should not spend the clothing searches' request budget. So this is a
 * sibling that shares the fetcher, the parser and the Chrome profile lock, and differs in three
 * ways:
 *
 *  - **Its terms come from the watches** — each watch's `amazonTerms`, English and chosen for
 *    this site — never from the Spanish terms `IngestService` merges into every provider's
 *    `searches`, which amazon.com answers poorly. See [Watch.amazonTerms].
 *  - **No size filter.** Every tile is tracked whole, at the price Amazon shows for it.
 *  - **Each row says which watch asked for it** ([RawDeal.watchIds]), so it becomes that watch's
 *    candidate even though an English title matches none of its terms, and it carries the watch's
 *    category so it lands in the bucket the watch is pinned to.
 *
 * Same request budget as the tracker: `1 + maxPagesPerSearch` page loads per term at most, and
 * none per item.
 */
class AmazonWatchSource(
    /** The watches' Amazon searches, read at fetch time so a new watch needs no restart. */
    private val searches: () -> List<WatchSearch>,
) : DealSource {

    override val type = "amazon-watch"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val wanted = searches()
        if (wanted.isEmpty()) {
            println("[${cfg.id}] no watch has Amazon terms; nothing to fetch")
            return emptyList()
        }

        val selectors = AmazonSelectors.from(cfg)
        val maxItems = cfg.int("maxItemsPerSearch", 20).coerceIn(1, 100)
        val maxPages = cfg.int("maxPagesPerSearch", 2).coerceIn(1, 7)
        val fetcher = AmazonFetcher(
            http = http,
            allowBrowser = cfg.options["allowBrowserFallback"]?.toBoolean() ?: true,
            logPrefix = cfg.id,
        )

        // One term can belong to two watches ("4k tv" on a TV watch and a bedroom-TV watch), and
        // then it is searched once and its rows linked to both — the request is the scarce thing.
        val byTerm = wanted.groupBy { it.term.lowercase() }
        val deals = LinkedHashMap<String, RawDeal>()

        // try/finally, because a Chrome left running holds the exclusive lock on .amazon-profile,
        // which would then block the shipping verifier and the tracker.
        try {
            for ((_, asks) in byTerm) {
                val term = asks.first().term
                val watchIds = asks.map { it.watchId }.toSet()
                // First watch wins when two disagree; they share the term, so they rarely will.
                val category = asks.firstNotNullOfOrNull { it.categoryGroup }
                val searchUrl = "$base/s?k=${encodeQueryValue(term)}"
                var kept = 0

                for (pageNo in 1..maxPages) {
                    val url = if (pageNo == 1) searchUrl else "$searchUrl&page=$pageNo"
                    val html = fetcher.html(url, expect = selectors.resultTile) ?: break
                    val hits = AmazonParser.parseSearch(html, selectors, base)
                    if (hits.isEmpty()) {
                        if (pageNo == 1) {
                            println(
                                "[${cfg.id}] '$term' -> 0 usable results from " +
                                    "${AmazonParser.countTiles(html, selectors)} tile(s)",
                            )
                        }
                        break
                    }

                    var addedHere = 0
                    for (hit in hits) {
                        if (kept >= maxItems) break
                        val existing = deals[hit.asin]
                        if (existing != null) {
                            // Already found under another term: it now also belongs to these
                            // watches, but costs no second slot.
                            deals[hit.asin] = existing.copy(watchIds = existing.watchIds + watchIds)
                            continue
                        }
                        deals[hit.asin] = toRawDeal(hit, watchIds, category)
                        kept++
                        addedHere++
                    }
                    // A page of rows already known means later pages are unlikely to differ.
                    if (addedHere == 0 || kept >= maxItems) break
                }

                println("[${cfg.id}] '$term' for watch(es) $watchIds -> $kept item(s)")
            }
        } finally {
            fetcher.close()
        }

        println("[${cfg.id}] ${deals.size} item(s) for ${wanted.map { it.watchId }.distinct().size} watch(es)")
        return deals.values.toList()
    }

    internal fun toRawDeal(hit: SearchHit, watchIds: Set<Long>, category: String?): RawDeal = RawDeal(
        externalId = hit.asin,
        title = hit.title,
        url = hit.url,
        price = hit.price,
        // As quoted, not the provider's nominal currency. See AmazonMoney.
        currency = hit.currency,
        merchant = "amazon",
        asin = hit.asin,
        brand = hit.brand,
        imageUrl = hit.imageUrl,
        listPrice = hit.listPrice,
        // The watch's own bucket name. `Categorizer` takes a source category that already IS a
        // bucket name as-is, so a TV found for a "TV & Video" watch lands there whatever its
        // English title says -- and a watch pinned to that category does not then drop it.
        category = category,
        watchIds = watchIds,
    )
}
