package com.luis.dealstracker.sources.falabella

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.CardOffer
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.encodeQueryValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Falabella Group storefronts — falabella.com.co and homecenter.com.co (Sodimac).
 *
 * One adapter for both because they are literally the same front end: the same
 * `falabella-b2c-ui` Next.js build, emitting the same product record shape. Only two things
 * differ, and both are config: where the results sit inside `__NEXT_DATA__`, and how a
 * product URL is formed. That is the same "a protocol, not a site" split that lets one
 * `VtexSource` serve Exito, Carulla and Easy.
 *
 * **This is server-rendered HTML with an embedded JSON island, not a public API.** It is more
 * stable than scraping markup — the JSON is the app's own data contract with its own client —
 * but it will break on a frontend rewrite, and each page is a 1.6-2.0MB fetch. Hence small
 * page budgets and `PoliteHttp`'s conditional requests.
 *
 * The underlying BFF is deliberately not used: `/s/browse/v1/search/co` exists and even leaks
 * its schema, but every well-formed call answers 404. See `BLOCKERS.md`.
 */
class FalabellaNextSource : DealSource {
    override val type = "falabella-next"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val storePath = cfg.options["storePath"]?.trim('/')
            ?: error("[${cfg.id}] falabella-next needs options.storePath, e.g. homecenter-co")

        val queries = buildQueries(cfg)
        if (queries.isEmpty()) {
            println("[${cfg.id}] no categories or searches configured; nothing to fetch")
            return emptyList()
        }

        val all = LinkedHashMap<String, RawDeal>()
        for (query in queries) {
            val maxPages = when (query.kind) {
                NextQuery.Kind.CATEGORY -> cfg.int("maxPagesPerCategory", 2)
                NextQuery.Kind.SEARCH -> cfg.int("maxPagesPerSearch", 1)
            }.coerceAtLeast(1)

            var page = 1
            var fetched = 0
            while (page <= maxPages) {
                val url = query.url(base, storePath, page)
                val result = try {
                    http.get(url, mapOf("Accept" to "text/html"))
                } catch (e: Exception) {
                    println("[${cfg.id}] ${query.label} page $page failed: ${e.message}")
                    break
                }
                if (result.notModified) break
                val body = result.body
                if (body == null || !result.ok) {
                    println("[${cfg.id}] ${query.label} page $page -> HTTP ${result.status}")
                    break
                }

                val island = extractNextData(body)
                if (island == null) {
                    // Worth saying loudly rather than returning nothing: an empty result here
                    // means the page shape changed, which is a different problem from a
                    // category that genuinely has no products.
                    println("[${cfg.id}] ${query.label} page $page: no __NEXT_DATA__ — page shape may have changed")
                    break
                }

                val products = try {
                    readResults(island, cfg.options["resultsPath"])
                } catch (e: Exception) {
                    println("[${cfg.id}] ${query.label} page $page unparseable: ${e.message}")
                    break
                }
                if (products.isEmpty()) break

                products.forEach { product ->
                    toRawDeal(product, base, storePath, cfg.currency, query.category)
                        ?.let { all.putIfAbsent(it.externalId, it) }
                }
                fetched += products.size
                page++
            }
            println("[${cfg.id}] ${query.label} -> $fetched products")
        }

        println("[${cfg.id}] ${all.size} unique products")
        return all.values.toList()
    }

    /**
     * Expands config into the pages to fetch.
     *
     * **Search is opt-in per store, and that is a robots.txt requirement rather than a
     * preference.** Homecenter disallows its search URLs for generic agents while allowing
     * category pages. Since `IngestService` injects the active watches' terms into `searches`
     * for every provider, a store that did not gate this would silently start requesting
     * disallowed URLs the moment a watch existed. Default off, so forgetting the flag fails
     * safe.
     */
    internal fun buildQueries(cfg: ProviderConfig): List<NextQuery> {
        val categories = cfg.list("categories").map { NextQuery(NextQuery.Kind.CATEGORY, it) }
        val searchAllowed = cfg.options["searchEnabled"]?.toBoolean() ?: false
        val searches = if (searchAllowed) {
            cfg.list("searches").map { NextQuery(NextQuery.Kind.SEARCH, it) }
        } else {
            emptyList()
        }
        return categories + searches
    }

    /**
     * Pulls the JSON island out of the page.
     *
     * Jsoup rather than a regex because the payload contains `</script>` inside string values
     * often enough to break naive matching, and Jsoup already handles the escaping rules. It
     * is also already a dependency, used for Slickdeals' RSS.
     */
    internal fun extractNextData(html: String): JsonObject? {
        val script = Jsoup.parse(html).getElementById("__NEXT_DATA__")?.data()?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { json.parseToJsonElement(script).jsonObject }.getOrNull()
    }

    /**
     * Navigates to the results array, which sits at a different depth per store.
     *
     * Falabella:  `props.pageProps.results`
     * Homecenter: `props.pageProps.searchProps.searchData.results`
     *
     * `resultsPath` is the dotted remainder after `props.pageProps`, empty for Falabella.
     */
    internal fun readResults(island: JsonObject, resultsPath: String?): List<JsonObject> {
        var node = island["props"]?.jsonObject?.get("pageProps")?.jsonObject ?: return emptyList()
        resultsPath?.split('.')?.filter { it.isNotBlank() }?.forEach { segment ->
            node = node[segment]?.jsonObject ?: return emptyList()
        }
        val results = node["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { it as? JsonObject }
    }

    /**
     * Maps one product record, or null when it carries no usable price.
     */
    internal fun toRawDeal(
        product: JsonObject,
        base: String,
        storePath: String,
        currency: String,
        category: String? = null,
    ): RawDeal? {
        val title = product.text("displayName")?.takeIf { it.isNotBlank() } ?: return null
        // skuId, not productId: it identifies the purchasable variant, and it is what the
        // product URL is built from. The two genuinely differ (productId 136275849 /
        // skuId 136275851 on one real Falabella row).
        val id = product.text("skuId") ?: product.text("productId") ?: return null
        val offer = resolveOffer(product["prices"] as? JsonArray) ?: return null

        return RawDeal(
            externalId = id,
            title = title,
            // Falabella publishes an absolute URL; Homecenter publishes none, but
            // /<store>/product/<skuId>/ 302s to the canonical slug.
            url = product.text("url")?.takeIf { it.startsWith("http") }
                ?: "$base/$storePath/product/$id/",
            price = offer.price,
            currency = currency,
            listPrice = offer.listPrice,
            // sellerName matters here in a way it does not for VTEX: both storefronts carry
            // marketplace sellers, so the retailer in the URL is not necessarily who sells it.
            merchant = product.text("sellerName")
                ?: base.removePrefix("https://").removePrefix("www."),
            brand = product.text("brand")?.takeIf { it.isNotBlank() },
            // Homecenter publishes this; Falabella does not, and falls back to the title.
            model = product.text("model")?.takeIf { it.isNotBlank() },
            // The CMR price, kept beside the cash price rather than in place of it. See
            // [cardOfferOf] for why reading it AS the price would be a systematic error.
            cardOffer = cardOfferOf(product["prices"] as? JsonArray, offer.price),
            // Not published per product; supplied by the category listing that returned it.
            category = category,
            imageUrl = (product["mediaUrls"] as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNullSafe() },
            // Neither storefront publishes a stock field on a listing, and both appear to omit
            // unbuyable items. A usable price is the only availability signal on offer, so
            // saying "available" here means "listed with a price", not "confirmed in stock".
            available = true,
        )
    }

    internal data class Offer(val price: BigDecimal, val listPrice: BigDecimal?)

    /**
     * Picks the price a person would actually pay, and the "before" price to compare it to.
     *
     * **`cmrPrice` is excluded, and this is the single most important line in the file.** It is
     * the price with Falabella's own store credit card, it is often the lowest in the array,
     * and it is present on 32 of 56 real rows. Reading it as the sale price would understate
     * Falabella by tens of thousands of pesos on most items — which would not merely be wrong,
     * it would systematically make Falabella win every cross-site comparison it appears in.
     *
     * Type names differ per store and both are accepted:
     *   Falabella  `eventPrice` / `internetPrice`, list `normalPrice` (`crossed: true`)
     *   Homecenter `INTERNET`,                     list `NORMAL`
     *
     * When only a list price exists — 5 of 47 real Homecenter rows — that *is* the selling
     * price, and there is no discount to report. Returning it as both would invent a 0%
     * discount, and the app's rule is that blank means unknown, never zero.
     */
    internal fun resolveOffer(prices: JsonArray?): Offer? {
        if (prices == null) return null
        val parsed = prices.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj.text("type")?.lowercase() ?: return@mapNotNull null
            if (type in CMR_TYPES) return@mapNotNull null
            amountOf(obj)?.let { type to it }
        }

        val sale = SALE_TYPES.firstNotNullOfOrNull { wanted ->
            parsed.firstOrNull { it.first == wanted }?.second
        }
        val list = parsed.firstOrNull { it.first in LIST_TYPES }?.second

        val price = sale ?: list ?: return null
        return Offer(
            price = price.asMoney(),
            // Only above the sale price counts; equal or lower carries no discount and would
            // compute as 0% or negative.
            listPrice = list?.asMoney()?.takeIf { it > price.asMoney() },
        )
    }

    /**
     * The CMR price, kept beside the cash price rather than instead of it.
     *
     * [resolveOffer] throws `cmrPrice` away, and must keep doing so — reading it as the sale price
     * would make Falabella win every cross-site comparison it takes part in. But throwing it away
     * also loses a real saving: the Sensi Dacqua toilet `487197` is 799,900 cash and **589,900 with
     * CMR** at both Homecenter and Falabella, and that 210,000 was invisible. So it is captured
     * here, as a [CardOffer] on the observation, exactly as Exito's "Mastercard −20%" already is
     * (`V17__card_price`). Nothing downstream compares it: the alert rules stay on the cash price,
     * which is what anyone can pay.
     *
     * Two things about this data are easy to get wrong, and both were measured on the live pages:
     *
     *  - **Two type names for one thing.** Falabella sends `cmrPrice`, Homecenter sends `CMR`.
     *    Accepting only one silently halves the coverage.
     *  - **The percentage is derived, and against the CASH price.** This is the mirror image of
     *    VTEX, where the teaser publishes a percentage and the price must be computed. Here the
     *    price is published and the percentage is not, so it is computed — against the price we
     *    actually store, never against `normalPrice`. On Falabella's first nevera the CMR saving is
     *    3.8% against cash and would read as 57.9% against `normalPrice`; that 57.9% is the shelf
     *    discount the "Off" column already reports, not what the card saves.
     *
     * Null is the common answer and not a failure: a category listing at Homecenter carries a CMR
     * price on 6 of 288 rows (measured 2026-09-29 across the seven configured categories), against
     * 37 of 56 on a Falabella search. A row with no CMR entry simply has no card price today.
     */
    internal fun cardOfferOf(prices: JsonArray?, cashPrice: BigDecimal): CardOffer? {
        if (prices == null || cashPrice.signum() <= 0) return null
        val cmr = prices.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.text("type")?.lowercase() in CMR_TYPES }
            .mapNotNull { amountOf(it)?.asMoney() }
            // Cheapest, on the same "best card offer wins" rule as `VtexSource.cardOfferOf`.
            .minOrNull() ?: return null

        // Strict: a card price that merely equals the cash price saves nothing, and one above it is
        // not a discount at all. The rendered pages show no card price in either case.
        if (cmr >= cashPrice) return null

        val percent = cashPrice.subtract(cmr)
            .divide(cashPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(2, RoundingMode.HALF_UP)
        // `card_percent` is CHECK (> 0 AND < 100), so a saving too small to survive two decimals
        // would fail the INSERT rather than the row. Dropped here instead, where the cause is
        // visible; the card price without a percentage is not worth a broken observation.
        if (percent.signum() <= 0 || percent >= BigDecimal(100)) return null

        return CardOffer(price = cmr, percent = percent, scope = CardScope.CMR)
    }

    /**
     * Reads one price entry as an exact amount.
     *
     * Never via `Double`, matching the NUMERIC(14,2) columns: a COP price reaches eight digits
     * and float rounding is precisely the drift the schema exists to prevent.
     *
     * Two shapes, because the two stores disagree even inside a shared component:
     *  - Homecenter: `priceWithoutFormatting: 3859900` — a JSON number, read from its literal
     *    text so it never touches a binary float.
     *  - Falabella: `price: ["6.269.900"]` — a one-element array of a dot-grouped string.
     */
    internal fun amountOf(entry: JsonObject): BigDecimal? {
        // Preferred when present: already numeric, so nothing has to be un-formatted.
        val numeric = (entry["priceWithoutFormatting"] as? JsonPrimitive)?.contentOrNullSafe()
        if (numeric != null) {
            runCatching { BigDecimal(numeric) }.getOrNull()?.let { return it }
        }
        val raw = when (val p = entry["price"]) {
            is JsonPrimitive -> p.contentOrNullSafe()
            is JsonArray -> p.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
            else -> null
        } ?: return null
        return parseCopAmount(raw)
    }

    companion object {
        /**
         * The store credit-card price, under both names the two storefronts give it: Falabella
         * sends `cmrPrice`, Homecenter sends `CMR`. Excluded from the sale price by
         * [resolveOffer] and captured separately by [cardOfferOf].
         */
        private val CMR_TYPES = setOf("cmrprice", "cmr")

        /** In preference order: an event price supersedes the standing internet price. */
        private val SALE_TYPES = listOf("eventprice", "internetprice", "internet")
        private val LIST_TYPES = setOf("normalprice", "normal")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parses "1.829.900" — dots group thousands in Colombian formatting, they are not
         * decimal points. A stray comma is treated as the decimal separator, which is the
         * other half of the same convention.
         */
        internal fun parseCopAmount(text: String): BigDecimal? {
            val cleaned = text.trim()
                .removePrefix("$").trim()
                .replace(".", "")
                .replace(',', '.')
                .filter { it.isDigit() || it == '.' }
            return cleaned.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNullSafe()

        /**
         * `JsonPrimitive.content` yields the literal string "null" for a JSON null, which
         * would sail through every `isNotBlank` check and end up stored as a title or a
         * brand. Both storefronts send nulls freely, so this is routine, not defensive.
         */
        private fun JsonPrimitive.contentOrNullSafe(): String? =
            if (this is JsonNull) null else content.takeIf { it.isNotBlank() }
    }
}

/** One page request: a category listing or a keyword search. */
internal data class NextQuery(val kind: Kind, val value: String) {
    enum class Kind { CATEGORY, SEARCH }

    val label: String get() = if (kind == Kind.SEARCH) "q=$value" else value

    /**
     * The retailer's own category for anything this query returns, or null for a search.
     *
     * Neither storefront puts a category on the product record, but a category listing tells
     * us one by construction — these products are the ones filed under this slug. That
     * matters more than it looks: `Categorizer` prefers a retailer's own taxonomy over
     * guessing from a title, and without it every Homecenter row landed in `Other`, which in
     * turn made them invisible to any watch pinned to a category.
     *
     * "category/cat10850/neveras-y-nevecones" -> "neveras y nevecones"
     */
    val category: String?
        get() = if (kind == Kind.SEARCH) {
            null
        } else {
            value.trim('/').substringAfterLast('/').replace('-', ' ').takeIf { it.isNotBlank() }
        }

    fun url(base: String, storePath: String, page: Int): String = when (kind) {
        // `value` is the whole category path, e.g. "category/cat10850/neveras-y-nevecones",
        // because these ids are opaque and pairing them with their slug in one config string
        // keeps the two from drifting apart.
        Kind.CATEGORY -> "$base/$storePath/${value.trim('/')}?page=$page"
        Kind.SEARCH -> "$base/$storePath/search?Ntt=${encodeQueryValue(value)}&page=$page"
    }
}
