package com.luis.dealstracker.sources.algolia

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * Retailers whose storefront search is backed by Algolia.
 *
 * Written for alkomprar.com (Alkosto's sister brand). Its front end ships an Algolia app id
 * and a **search-only** API key in the page, which is how every Algolia site works — the key is
 * meant to be public. Verified before use: the key's ACL is exactly `["search"]`, and an
 * `addObject` attempt is refused with 403, so nothing here can write.
 *
 * A close second to Shopify for data quality: one POST returns a page of products with the
 * current price, the was-price, a stock flag, brand, category path, images, and — unusually —
 * **the EAN as the product code**, so these rows join existing GTIN clusters immediately.
 *
 * Browsed by category facet rather than keyword, deliberately. alkomprar.com's robots.txt
 * disallows `/search/` and `*?q=`, and while those rules govern the storefront rather than the
 * Algolia host, honouring the site's evident intent is the same call made for Homecenter.
 * `allcategories_string_mv` is the facet its own category pages filter on.
 */
class AlgoliaSource : DealSource {
    override val type = "algolia"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val appId = cfg.options["algoliaAppId"]
            ?: error("[${cfg.id}] algolia needs options.algoliaAppId")
        // Public search key, read from the site's own front-end bundle. Not a secret, so it
        // lives in committed config rather than local.properties -- unlike the MercadoLibre
        // client secret, this is the same value every visitor's browser receives.
        val apiKey = cfg.options["algoliaSearchKey"]
            ?: error("[${cfg.id}] algolia needs options.algoliaSearchKey")
        val index = cfg.options["algoliaIndex"]
            ?: error("[${cfg.id}] algolia needs options.algoliaIndex")
        val facet = cfg.options["categoryFacet"] ?: "allcategories_string_mv"

        val categories = cfg.list("categories")
        if (categories.isEmpty()) {
            println("[${cfg.id}] no categories configured; nothing to fetch")
            return emptyList()
        }

        val base = cfg.requireBaseUrl().trimEnd('/')
        val url = "https://$appId-dsn.algolia.net/1/indexes/$index/query"
        val headers = mapOf(
            "X-Algolia-Application-Id" to appId,
            "X-Algolia-API-Key" to apiKey,
        )
        val hitsPerPage = cfg.int("pageSize", 20).coerceIn(1, 100)
        val maxPages = cfg.int("maxPagesPerCategory", 2).coerceAtLeast(1)

        val all = LinkedHashMap<String, RawDeal>()
        for (category in categories) {
            var page = 0
            var fetched = 0
            while (page < maxPages) {
                val body = buildJsonObject {
                    // Empty query plus a facet filter is a category browse, not a search.
                    put("query", "")
                    put("filters", "$facet:$category")
                    put("hitsPerPage", hitsPerPage)
                    put("page", page)
                }.toString()

                val result = try {
                    http.postJson(url, body, headers)
                } catch (e: Exception) {
                    println("[${cfg.id}] $category page $page failed: ${e.message}")
                    break
                }
                val text = result.body
                if (text == null || !result.ok) {
                    println("[${cfg.id}] $category page $page -> HTTP ${result.status}")
                    break
                }

                val response = try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    println("[${cfg.id}] $category page $page unparseable: ${e.message}")
                    break
                }
                val hits = (response["hits"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                if (hits.isEmpty()) break

                hits.forEach { hit ->
                    toRawDeal(hit, base, cfg.currency)?.let { all.putIfAbsent(it.externalId, it) }
                }
                fetched += hits.size

                // nbPages is authoritative; stop rather than requesting past the end.
                val nbPages = (response["nbPages"] as? JsonPrimitive)?.content?.toIntOrNull()
                page++
                if (nbPages != null && page >= nbPages) break
            }
            println("[${cfg.id}] $category -> $fetched products")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products, ${available.size} in stock")
        return available
    }

    internal fun toRawDeal(hit: JsonObject, base: String, currency: String): RawDeal? {
        val code = hit.text("code_string") ?: return null
        val title = hit.text("name_text_es") ?: return null

        // discountprice is what you pay; baseprice is the "before". Both are plain JSON numbers
        // here, read from their literal text so nothing passes through a Double.
        val price = hit.number("discountprice_double")
            ?: hit.number("lowestprice_double")
            ?: hit.number("pricevalue_cop_double")
            ?: return null
        if (price.signum() <= 0) return null
        val listPrice = hit.number("baseprice_cop_string") ?: hit.number("pricevalue_cop_double")

        return RawDeal(
            externalId = code,
            title = title,
            url = hit.text("url_es_string")?.let { path ->
                if (path.startsWith("http")) path else "$base/${path.trimStart('/')}"
            } ?: "$base/p/$code",
            price = price.asMoney(),
            currency = currency,
            // Only above the sale price counts; equal carries no discount and must stay unknown
            // rather than becoming 0%.
            listPrice = listPrice?.asMoney()?.takeIf { it > price.asMoney() },
            merchant = base.removePrefix("https://").removePrefix("www."),
            brand = hit.firstOfArray("brand_string_mv"),
            // The product code IS the EAN on this retailer, which is why these rows cluster with
            // Exito, Easy and Grupo Mansion straight away. Passed through unvalidated, like every
            // other adapter's -- ProductIdentity owns the check digit.
            gtin = code,
            // "Electrodomésticos > Refrigeración > Neveras", the retailer's own path, which
            // Categorizer prefers over guessing from a title.
            category = (hit["categoryName_text_es_mv"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" > "),
            imageUrl = hit.text("img-750wx750h_string") ?: hit.text("img-310wx310h_string"),
            // Unlike most sources here, stock is published explicitly. Out-of-stock rows are
            // dropped by the caller: a price you cannot buy would also plant a phantom cheaper
            // rival in the cross-site comparison.
            available = (hit["instockflag_boolean"] as? JsonPrimitive)?.content?.toBoolean() ?: true,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonPrimitive.contentOrNull(): String? =
            if (this is JsonNull) null else content.takeIf { it.isNotBlank() }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull()

        /** Reads a JSON number from its literal text, never via Double. */
        private fun JsonObject.number(key: String): BigDecimal? =
            text(key)?.let { runCatching { BigDecimal(it) }.getOrNull() }

        /** Several fields are single-element arrays: `brand_string_mv: ["HACEB"]`. */
        private fun JsonObject.firstOfArray(key: String): String? =
            (this[key] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull() }
                ?: text(key)
    }
}
