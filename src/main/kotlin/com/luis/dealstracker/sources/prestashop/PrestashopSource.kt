package com.luis.dealstracker.sources.prestashop

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * PrestaShop storefronts, read from the product grid on a category page.
 *
 * Written for grupomansion.com, which a Google search for a model code turned up selling a
 * Samsung side-by-side well under every retailer this app already polls. Rather than scrape
 * Google — whose robots.txt disallows `/search` and whose data has no price history — the
 * merchants it names get added as first-class sources.
 *
 * **One request per category page, not one per product.** PrestaShop's JSON-LD is tempting and
 * wrong for this: the `Product` block on a *product* page carries `gtin13` and `offers.price`,
 * but a *category* page emits only an `ItemList` of names and URLs with no prices at all. Going
 * that route would mean 24 extra requests per page against a small retailer's server. The grid
 * markup has everything instead, and more cheaply.
 *
 * What the default theme puts in each `article.product-miniature`, all of it useful:
 *
 *  - `span.product-price[content]` — **a machine-readable integer**, so no un-formatting
 *  - `span.regular-price` — the was-price, dot-grouped
 *  - `.product-reference` — the manufacturer reference, i.e. the model code, as its own field
 *  - the href slug ends in the EAN: `…-rs57dg4000b4co-8806095746456.html`
 *
 * **Caveat worth knowing before adding a second PrestaShop store:** unlike `vtex` or `shopify`,
 * this reads a *theme's* markup rather than a documented API, and PrestaShop themes differ. The
 * selectors are therefore config, not constants. A new store may need them adjusted; if its
 * grid turns out to be too different, per-product JSON-LD is the reliable fallback at the cost
 * of one request each.
 */
class PrestashopSource : DealSource {
    override val type = "prestashop"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val categories = cfg.list("categories")
        if (categories.isEmpty()) {
            println("[${cfg.id}] no categories configured; nothing to fetch")
            return emptyList()
        }

        val maxPages = cfg.int("maxPagesPerCategory", 2).coerceAtLeast(1)
        val selectors = Selectors.from(cfg)

        val all = LinkedHashMap<String, RawDeal>()
        for (category in categories) {
            var page = 1
            var fetched = 0
            while (page <= maxPages) {
                val url = "$base/${category.trim('/')}?page=$page"
                val result = try {
                    http.get(url, mapOf("Accept" to "text/html"))
                } catch (e: Exception) {
                    println("[${cfg.id}] $category page $page failed: ${e.message}")
                    break
                }
                if (result.notModified) break
                val body = result.body
                if (body == null || !result.ok) {
                    println("[${cfg.id}] $category page $page -> HTTP ${result.status}")
                    break
                }

                val tiles = Jsoup.parse(body, url).select(selectors.tile)
                if (tiles.isEmpty()) {
                    // Loud, because an empty grid on a theme-dependent selector almost always
                    // means the theme changed rather than the category being empty.
                    println(
                        "[${cfg.id}] $category page $page: no '${selectors.tile}' tiles — " +
                            "the theme may have changed; check options.tileSelector",
                    )
                    break
                }

                tiles.forEach { tile ->
                    toRawDeal(tile, base, cfg.currency, selectors, category)
                        ?.let { all.putIfAbsent(it.externalId, it) }
                }
                fetched += tiles.size
                page++
            }
            println("[${cfg.id}] $category -> $fetched products")
        }

        println("[${cfg.id}] ${all.size} unique products")
        return all.values.toList()
    }

    /** Theme-dependent selectors, overridable per store in `options`. */
    internal data class Selectors(
        val tile: String,
        val title: String,
        val price: String,
        val listPrice: String,
        val reference: String,
    ) {
        companion object {
            fun from(cfg: ProviderConfig) = Selectors(
                tile = cfg.options["tileSelector"] ?: "article.product-miniature",
                title = cfg.options["titleSelector"] ?: "h3.product-title a, .product-title a",
                price = cfg.options["priceSelector"] ?: "span.product-price",
                listPrice = cfg.options["listPriceSelector"] ?: "span.regular-price",
                reference = cfg.options["referenceSelector"] ?: ".product-reference a, .product-reference",
            )
        }
    }

    internal fun toRawDeal(
        tile: Element,
        base: String,
        currency: String,
        selectors: Selectors,
        category: String? = null,
    ): RawDeal? {
        val link = tile.selectFirst("a[href*=.html]")?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?: return null
        val title = tile.selectFirst(selectors.title)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val priceEl = tile.selectFirst(selectors.price) ?: return null
        // The `content` attribute is an unformatted integer, which is why it is preferred over
        // the displayed text. Fall back to parsing the text only if the theme omits it.
        val price = priceEl.attr("content").takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: parseCopAmount(priceEl.text())
            ?: return null
        if (price.signum() <= 0) return null

        val listPrice = tile.selectFirst(selectors.listPrice)?.let { el ->
            el.attr("content").takeIf { it.isNotBlank() }
                ?.let { runCatching { BigDecimal(it) }.getOrNull() }
                ?: parseCopAmount(el.text())
        }

        return RawDeal(
            externalId = productId(link) ?: link,
            title = title,
            url = link,
            price = price.asMoney(),
            currency = currency,
            // Only above the sale price counts; equal or lower carries no discount.
            listPrice = listPrice?.asMoney()?.takeIf { it > price.asMoney() },
            merchant = base.removePrefix("https://").removePrefix("www."),
            // PrestaShop grids do not carry a brand field. Left null rather than guessed from
            // the title: a wrong brand would produce a wrong identity key, and a missing one
            // only costs a match. ProductIdentity needs brand AND model, so these rows join the
            // cross-site comparison on GTIN alone — which this store happily publishes.
            brand = null,
            gtin = gtinFromSlug(link),
            // The store's own manufacturer reference, so the model code is read rather than
            // guessed. Still canonicalised downstream like every other source's.
            model = tile.selectFirst(selectors.reference)?.text()?.trim()?.takeIf { it.isNotEmpty() },
            category = category?.substringAfter('-')?.replace('-', ' ')?.takeIf { it.isNotBlank() },
            // Lazy-loading themes leave a data: placeholder in src and the real URL in data-src.
            imageUrl = tile.selectFirst("img")?.let { img ->
                img.absUrl("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.absUrl("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            },
            // No stock field in the grid. Anything listed with a price is treated as buyable,
            // the same assumption the Falabella adapter makes for the same reason.
            available = true,
        )
    }

    companion object {
        /** `/refrigeracion/284-nevecon-…-8806095746456.html` -> `284`. */
        internal fun productId(url: String): String? =
            Regex("/(\\d+)-[^/]*\\.html").find(url)?.groupValues?.get(1)

        /**
         * The EAN, which this store appends to its own URL slug.
         *
         * Returned unvalidated, like every other adapter's `gtin`: `ProductIdentity` owns the
         * check-digit test, so a slug ending in some other long number is rejected there rather
         * than being second-guessed here.
         */
        internal fun gtinFromSlug(url: String): String? =
            Regex("-(\\d{8,14})\\.html").find(url)?.groupValues?.get(1)

        /**
         * Parses "$ 4.619.000" — dots group thousands in Colombian formatting.
         *
         * Note this is the *opposite* convention to `ShopifySource.amount`, where the dot is a
         * decimal point. Same-looking strings, factor-of-100 difference.
         */
        internal fun parseCopAmount(text: String): BigDecimal? {
            val cleaned = text.trim()
                .replace(" ", "")
                .removePrefix("$").trim()
                .replace(".", "")
                .replace(',', '.')
                .filter { it.isDigit() || it == '.' }
            return cleaned.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        }
    }
}
