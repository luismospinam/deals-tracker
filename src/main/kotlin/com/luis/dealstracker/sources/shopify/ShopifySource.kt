package com.luis.dealstracker.sources.shopify

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/**
 * Shopify storefronts, via each store's public `products.json`.
 *
 * The nicest source in this app by some distance. Every Shopify store exposes
 * `/collections/<handle>/products.json` as documented, stable, first-party JSON — no HTML to
 * parse, no embedded island to locate, no private BFF to reverse-engineer. It is also a
 * *platform* adapter, so the marginal cost of the next Shopify retailer is a config block,
 * exactly as `vtex` gave us Éxito, Carulla, Easy and Olímpica.
 *
 * Found the right way: a Google search for a model code surfaced merchants this app does not
 * poll (Fullhogar among them). Rather than scrape Google — whose robots.txt disallows
 * `/search`, and whose data carries no price history — the merchants it names get added here.
 */
class ShopifySource : DealSource {
    override val type = "shopify"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val collections = cfg.list("collections")
        if (collections.isEmpty()) {
            println("[${cfg.id}] no collections configured; nothing to fetch")
            return emptyList()
        }

        // Shopify caps this at 250. Kept well below, since a page is one request either way
        // and a smaller window is gentler on a small retailer's server.
        val pageSize = cfg.int("pageSize", 50).coerceIn(1, 250)
        val maxPages = cfg.int("maxPagesPerCollection", 2).coerceAtLeast(1)

        val all = LinkedHashMap<String, RawDeal>()
        for (handle in collections) {
            var page = 1
            var fetched = 0
            while (page <= maxPages) {
                val url = "$base/collections/$handle/products.json?limit=$pageSize&page=$page"
                val result = try {
                    http.get(url, mapOf("Accept" to "application/json"))
                } catch (e: Exception) {
                    println("[${cfg.id}] $handle page $page failed: ${e.message}")
                    break
                }
                if (result.notModified) break
                val body = result.body
                if (body == null || !result.ok) {
                    println("[${cfg.id}] $handle page $page -> HTTP ${result.status}")
                    break
                }

                val products = try {
                    json.decodeFromString<ShopifyFeed>(body).products
                } catch (e: Exception) {
                    println("[${cfg.id}] $handle page $page unparseable: ${e.message}")
                    break
                }
                if (products.isEmpty()) break

                products.forEach { product ->
                    toRawDeal(product, base, cfg.currency, handle)
                        ?.let { all.putIfAbsent(it.externalId, it) }
                }
                fetched += products.size
                if (products.size < pageSize) break
                page++
            }
            println("[${cfg.id}] $handle -> $fetched products")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products, ${available.size} in stock")
        return available
    }

    /**
     * Maps one product, collapsing its variants to the cheapest purchasable one.
     *
     * Same choice `VtexSource` makes: one row per product keeps the feed readable, and the
     * cheapest in-stock variant is the price the store advertises. Falls back to the cheapest
     * variant overall when none is in stock, so the row still carries a price and is simply
     * filtered out by `available` afterwards.
     */
    internal fun toRawDeal(
        product: ShopifyProduct,
        base: String,
        currency: String,
        collection: String? = null,
    ): RawDeal? {
        val title = product.title.takeIf { it.isNotBlank() } ?: return null
        val priced = product.variants.mapNotNull { variant ->
            amount(variant.price)?.let { variant to it }
        }
        if (priced.isEmpty()) return null

        val inStock = priced.filter { it.first.available }
        val (best, price) = (inStock.ifEmpty { priced }).minByOrNull { it.second } ?: return null

        return RawDeal(
            externalId = product.id.toString(),
            title = title,
            url = "$base/products/${product.handle}",
            price = price.asMoney(),
            currency = currency,
            // Shopify's own "was" price. Null or not above the sale price means no discount,
            // which must stay unknown rather than becoming 0%.
            listPrice = amount(best.compareAtPrice)?.asMoney()?.takeIf { it > price.asMoney() },
            merchant = base.removePrefix("https://").removePrefix("www."),
            brand = product.vendor?.takeIf { it.isNotBlank() },
            // Present in the schema but empty on every row observed on fullhogar.com.co, so
            // identity falls back to brand plus the model code in the title.
            gtin = best.barcode?.takeIf { it.isNotBlank() },
            // product_type is the store's own field but is frequently blank, so the collection
            // that returned the product stands in. Categorizer prefers a retailer's taxonomy
            // over guessing from a title, and a blank category forfeits that.
            category = product.productType?.takeIf { it.isNotBlank() } ?: collection,
            imageUrl = product.images.firstOrNull()?.src,
            available = inStock.isNotEmpty(),
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Reads a Shopify money string.
         *
         * **`"1645900.00"` — the dot is a decimal point, not a thousands separator.** This is
         * the opposite convention to the Falabella adapter's `"1.829.900"`, where dots group
         * thousands. The two strings look alike and mean different things, so stripping dots
         * here would turn 1,645,900 COP into 164,590,000. Hence a plain BigDecimal parse and a
         * test that pins it.
         *
         * Never via Double, matching the NUMERIC(14,2) columns.
         */
        internal fun amount(text: String?): BigDecimal? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { BigDecimal(trimmed) }.getOrNull()?.takeIf { it.signum() > 0 }
        }
    }
}

@Serializable
data class ShopifyFeed(val products: List<ShopifyProduct> = emptyList())

@Serializable
data class ShopifyProduct(
    val id: Long,
    val title: String = "",
    val handle: String = "",
    val vendor: String? = null,
    @SerialName("product_type") val productType: String? = null,
    val variants: List<ShopifyVariant> = emptyList(),
    val images: List<ShopifyImage> = emptyList(),
)

@Serializable
data class ShopifyVariant(
    val id: Long? = null,
    /** Decimal string, e.g. "1645900.00". See [ShopifySource.amount]. */
    val price: String? = null,
    @SerialName("compare_at_price") val compareAtPrice: String? = null,
    val sku: String? = null,
    /** Usually absent from the public feed, but read where a store fills it in. */
    val barcode: String? = null,
    val available: Boolean = false,
)

@Serializable
data class ShopifyImage(val src: String? = null)
