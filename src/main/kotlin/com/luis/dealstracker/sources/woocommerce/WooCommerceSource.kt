package com.luis.dealstracker.sources.woocommerce

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.SpecText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/**
 * WooCommerce storefronts, via the public Store API at `/wp-json/wc/store/v1/products`.
 *
 * The WordPress counterpart of [com.luis.dealstracker.sources.shopify.ShopifySource]: first-party
 * JSON that the store's own front end uses, so there is no theme to scrape and no selector to
 * break when a template changes. Added for Kaiu Home, a Colombian furniture maker the sofa watch
 * kept missing; the next WooCommerce retailer is a config block.
 *
 * Categories are given by slug and the API takes the slug directly (`?category=salas`), which also
 * includes everything in that category's children.
 */
class WooCommerceSource : DealSource {
    override val type = "woocommerce"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val categories = cfg.list("categories")
        if (categories.isEmpty()) {
            println("[${cfg.id}] no categories configured; nothing to fetch")
            return emptyList()
        }

        // The API allows 100. One page is one request either way, and a furniture catalogue of a
        // couple of hundred rows is two or three of them.
        val pageSize = cfg.int("pageSize", 100).coerceIn(1, 100)
        val maxPages = cfg.int("maxPagesPerCategory", 3).coerceAtLeast(1)

        val all = LinkedHashMap<String, RawDeal>()
        for (slug in categories) {
            var page = 1
            var fetched = 0
            while (page <= maxPages) {
                val url = "$base/wp-json/wc/store/v1/products?category=$slug&per_page=$pageSize&page=$page"
                val result = try {
                    http.get(url, mapOf("Accept" to "application/json"))
                } catch (e: Exception) {
                    println("[${cfg.id}] $slug page $page failed: ${e.message}")
                    break
                }
                if (result.notModified) break
                val body = result.body
                if (body == null || !result.ok) {
                    println("[${cfg.id}] $slug page $page -> HTTP ${result.status}")
                    break
                }
                val products = try {
                    json.decodeFromString<List<WooProduct>>(body)
                } catch (e: Exception) {
                    println("[${cfg.id}] $slug page $page unparseable: ${e.message}")
                    break
                }
                if (products.isEmpty()) break

                products.forEach { product ->
                    toRawDeal(product, base, cfg.currency, slug)?.let { all.putIfAbsent(it.externalId, it) }
                }
                fetched += products.size
                if (products.size < pageSize) break
                page++
            }
            println("[${cfg.id}] $slug -> $fetched products")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products, ${available.size} in stock")
        return available
    }

    internal fun toRawDeal(product: WooProduct, base: String, currency: String, category: String? = null): RawDeal? {
        val title = decodeEntities(product.name).takeIf { it.isNotBlank() } ?: return null
        val price = amount(product.prices.price, product.prices.currencyMinorUnit) ?: return null
        val regular = amount(product.prices.regularPrice, product.prices.currencyMinorUnit)

        // The most specific category the store files it under: its children come after the parent
        // ("Salas", "Sofá Camas"), and that is the label worth keeping. The configured slug stands
        // in when the product lists none.
        val label = product.categories.lastOrNull()?.name?.let(::decodeEntities) ?: category

        return RawDeal(
            externalId = product.id.toString(),
            title = title,
            url = product.permalink,
            price = price.asMoney(),
            currency = currency,
            // The store's own "was" price. Null, or not above the price, means no discount, which
            // must stay unknown rather than becoming 0%.
            listPrice = regular?.asMoney()?.takeIf { it > price.asMoney() },
            merchant = base.removePrefix("https://").removePrefix("www."),
            brand = product.brands.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let(::decodeEntities),
            // SKUs are the maker's internal codes when filled in, and blank on most rows seen, so
            // no GTIN is claimed: identity falls back to brand plus the model code in the title.
            category = label,
            imageUrl = product.images.firstOrNull()?.src,
            available = product.isInStock,
            specs = product.attributes
                .mapNotNull { a -> SpecText.pair(a.name, a.terms.joinToString("; ") { it.name }) }.toMap() +
                dimensionsOf(product.dimensions),
            // Where Kaiu's dimensions and bed size actually are: the table carries only colour. The
            // short text is the maker's own summary and leads, the long one adds the measurements.
            description = SpecText.description(
                listOfNotNull(product.shortDescription, product.description).joinToString(" "),
            ),
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Reads the Store API's money string, which is in **minor units**: `"1799900"` with
         * `currency_minor_unit` 0 is 1,799,900 pesos, while a store that sets 2 would send
         * `"179990000"` for the same amount. Dividing by the advertised unit is what keeps this
         * right on a store that changes its decimals, rather than assuming COP's none.
         */
        internal fun amount(text: String?, minorUnit: Int = 0): BigDecimal? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { BigDecimal(trimmed).movePointLeft(minorUnit) }.getOrNull()
                ?.takeIf { it.signum() > 0 }
        }

        /** WooCommerce HTML-escapes titles in this API: `Puff &#8211; Gris`, `Tom &amp; Jerry`. */
        internal fun decodeEntities(text: String): String = SpecText.decodeEntities(text).trim()

        /** The store's own length/width/height fields, which are usually blank and only worth keeping when set. */
        internal fun dimensionsOf(d: WooDimensions): Map<String, String> =
            listOf("Largo" to d.length, "Ancho" to d.width, "Alto" to d.height)
                .filter { it.second.isNotBlank() }.toMap()
    }
}

@Serializable
data class WooProduct(
    val id: Long,
    val name: String = "",
    val permalink: String = "",
    val prices: WooPrices = WooPrices(),
    val images: List<WooImage> = emptyList(),
    val categories: List<WooCategory> = emptyList(),
    val brands: List<WooCategory> = emptyList(),
    @kotlinx.serialization.SerialName("is_in_stock") val isInStock: Boolean = true,
    val description: String? = null,
    @kotlinx.serialization.SerialName("short_description") val shortDescription: String? = null,
    val attributes: List<WooAttribute> = emptyList(),
    val dimensions: WooDimensions = WooDimensions(),
)

@Serializable
data class WooAttribute(val name: String = "", val terms: List<WooCategory> = emptyList())

@Serializable
data class WooDimensions(val length: String = "", val width: String = "", val height: String = "")

@Serializable
data class WooPrices(
    val price: String? = null,
    @kotlinx.serialization.SerialName("regular_price") val regularPrice: String? = null,
    @kotlinx.serialization.SerialName("currency_minor_unit") val currencyMinorUnit: Int = 0,
)

@Serializable
data class WooImage(val src: String? = null)

@Serializable
data class WooCategory(val name: String = "")
