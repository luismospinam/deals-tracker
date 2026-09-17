package com.luis.dealstracker.sources.vtex

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.encodeQueryValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * VTEX catalog adapter. Serves Exito, Carulla, and any other VTEX-hosted retailer
 * purely through config.
 *
 * Two traps this works around, both found by probing the live APIs:
 *
 *  1. **Responses are enormous** — roughly 19MB per 50 products, most of which is
 *     marketing metadata. Pages are kept small and only the handful of fields
 *     below are deserialized.
 *  2. **Unknown categories do not 404.** Requesting a slug the store does not have
 *     returns HTTP 206 with an unfiltered product list, so a copy-pasted slug
 *     produces plausible but wrong data. [validateCategories] checks configured
 *     slugs against the store's own category tree and warns loudly.
 */
class VtexSource : DealSource {
    override val type = "vtex"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val queries = buildQueries(cfg)
        if (queries.isEmpty()) {
            println("[${cfg.id}] no categories or searches configured; nothing to fetch")
            return emptyList()
        }

        val categories = queries.filter { it.kind == VtexQuery.Kind.CATEGORY }.map { it.value }
        if (categories.isNotEmpty()) validateCategories(cfg, base, categories, http)

        val pageSize = cfg.int("pageSize", 24).coerceIn(1, 50)

        val all = LinkedHashMap<String, RawDeal>()
        for (query in queries) {
            // Searches are capped shallower than category browses by default. A category is
            // a catalog we want broad coverage of; a search exists to answer "is the thing
            // I want on sale", and relevance drops off fast -- 850 hits for "nevera no
            // frost" are mostly not fridges by the third page.
            val maxPages = when (query.kind) {
                VtexQuery.Kind.CATEGORY -> cfg.int("maxPagesPerCategory", 3)
                VtexQuery.Kind.SEARCH -> cfg.int("maxPagesPerSearch", 2)
            }.coerceAtLeast(1)

            var page = 0
            var fetched = 0
            while (page < maxPages) {
                val from = page * pageSize
                val url = query.url(base, from, from + pageSize - 1)

                val result = try {
                    http.get(url, mapOf("Accept" to "application/json"))
                } catch (e: Exception) {
                    println("[${cfg.id}] ${query.label} page $page failed: ${e.message}")
                    break
                }
                if (result.notModified) break
                // VTEX answers 206 Partial Content for _from/_to ranges, so a plain
                // `status == 200` check here would reject every successful page.
                val body = result.body
                if (body == null || !result.ok) {
                    println("[${cfg.id}] ${query.label} page $page -> HTTP ${result.status}")
                    break
                }

                val products = try {
                    json.decodeFromString<List<VtexProduct>>(body)
                } catch (e: Exception) {
                    println("[${cfg.id}] ${query.label} page $page unparseable: ${e.message}")
                    break
                }
                if (products.isEmpty()) break

                products.forEach { product ->
                    toRawDeal(product, base, cfg.currency)?.let { all.putIfAbsent(it.externalId, it) }
                }
                fetched += products.size
                if (products.size < pageSize) break
                page++
            }
            println("[${cfg.id}] ${query.label} -> $fetched products")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products, ${available.size} in stock")
        return available
    }

    /**
     * Expands config into the paged requests to make.
     *
     * `searches` is populated by [com.luis.dealstracker.ingest.IngestService] from the
     * active watches as well as from config, which is what lets a watch go looking for a
     * fridge that no configured category would ever have surfaced. The key is the same one
     * `SlickdealsSource` reads, so one mechanism drives keyword hunting on every source
     * that supports it.
     */
    internal fun buildQueries(cfg: ProviderConfig): List<VtexQuery> =
        cfg.list("categories").map { VtexQuery(VtexQuery.Kind.CATEGORY, it) } +
            cfg.list("searches").map { VtexQuery(VtexQuery.Kind.SEARCH, it) }

    /**
     * Warns when a configured category slug is absent from the store's own tree.
     *
     * Not fatal: VTEX taxonomies change, and a stale slug should degrade to a
     * warning rather than take the whole poll down. But it must not pass silently,
     * because the API's response for a bad slug looks perfectly valid.
     */
    internal suspend fun validateCategories(
        cfg: ProviderConfig,
        base: String,
        categories: List<String>,
        http: PoliteHttp,
    ) {
        val result = try {
            http.get("$base/api/catalog_system/pub/category/tree/1", mapOf("Accept" to "application/json"))
        } catch (e: Exception) {
            println("[${cfg.id}] could not verify category slugs: ${e.message}")
            return
        }
        val body = result.body ?: return
        val tree = try {
            json.decodeFromString<List<VtexCategory>>(body)
        } catch (e: Exception) {
            println("[${cfg.id}] could not parse category tree: ${e.message}")
            return
        }

        val known = tree.mapNotNull { it.url?.trimEnd('/')?.substringAfterLast('/') }.toSet()
        val unknown = categories.filter { it !in known }
        if (unknown.isNotEmpty()) {
            println(
                "[${cfg.id}] WARNING: categories $unknown are not in this store's tree. " +
                    "VTEX returns unfiltered results for unknown slugs, so these will " +
                    "produce arbitrary products. Known top-level slugs: ${known.sorted()}",
            )
        }
    }

    /**
     * Picks the cheapest in-stock offer across the product's SKUs.
     *
     * A VTEX product can carry many SKUs (sizes, colours) at different prices.
     * The cheapest available one is what these stores advertise ("desde $X"), and
     * collapsing to one row per product keeps the feed readable.
     */
    internal fun toRawDeal(product: VtexProduct, base: String, currency: String): RawDeal? {
        data class Offer(val price: BigDecimal, val listPrice: BigDecimal?, val qty: Int)

        val offers = product.items.flatMap { item ->
            item.sellers.mapNotNull { seller ->
                val o = seller.commertialOffer ?: return@mapNotNull null
                val price = o.Price?.takeIf { it.signum() > 0 }?.asMoney() ?: return@mapNotNull null
                Offer(
                    price = price,
                    // Ignore a list price that isn't above the sale price: it carries
                    // no discount information and would compute as 0%.
                    listPrice = o.ListPrice?.asMoney()?.takeIf { it > price },
                    qty = o.AvailableQuantity ?: 0,
                )
            }
        }
        if (offers.isEmpty()) return null
        val inStock = offers.filter { it.qty > 0 }
        val best = (inStock.ifEmpty { offers }).minByOrNull { it.price } ?: return null

        val imageUrl = product.items.firstNotNullOfOrNull { it.images?.firstOrNull()?.imageUrl }

        return RawDeal(
            externalId = product.productId,
            title = product.productName.ifBlank { return null },
            // Built from the configured public host, NOT from VTEX's own `link` field.
            // `link` returns the store's internal VTEX hostname (tienda.exito.com,
            // secure.carulla.com). Those hosts answer 200 but serve a bare SPA shell
            // with no canonical/og:url/title, and a real browser bounces to the
            // homepage -- so the links look fine to curl and are broken for a human.
            url = "$base/${product.linkText}/p",
            price = best.price,
            currency = currency,
            listPrice = best.listPrice,
            merchant = base.removePrefix("https://").removePrefix("www."),
            asin = null,
            brand = product.brand?.takeIf { it.isNotBlank() },
            // Taken from the cheapest SKU's own barcode rather than the product, since a
            // VTEX product groups variants and each has its own. Passed through unvalidated:
            // Exito publishes internal refs here too, and ProductIdentity's check digit is
            // what separates them.
            gtin = product.items.firstNotNullOfOrNull { it.ean?.takeIf { e -> e.isNotBlank() } },
            // "/Tecnología/Televisores/" -> "Tecnología > Televisores"
            category = product.categories?.firstOrNull()
                ?.trim('/')?.split('/')?.joinToString(" > ")?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl,
            available = inStock.isNotEmpty(),
        )
    }

    companion object {
        // VTEX payloads carry hundreds of fields we do not use.
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * One paged catalog request — a category browse or a keyword search.
 *
 * Both hit the same `products/search` endpoint, which is why they share the paging loop:
 * a category is a path segment, a keyword is the `ft` parameter. Verified against
 * exito.com on 2026-09-07 — `?ft=nevera+no+frost` answers 206 and pages cleanly with
 * `_from`/`_to`, with zero overlap between consecutive pages.
 */
internal data class VtexQuery(val kind: Kind, val value: String) {
    enum class Kind { CATEGORY, SEARCH }

    val label: String get() = if (kind == Kind.SEARCH) "ft=$value" else value

    fun url(base: String, from: Int, to: Int): String {
        val root = "$base/api/catalog_system/pub/products/search"
        val range = "_from=$from&_to=$to"
        return when (kind) {
            Kind.CATEGORY -> "$root/$value?$range"
            Kind.SEARCH -> "$root?ft=${encodeQueryValue(value)}&$range"
        }
    }
}

@Serializable
data class VtexCategory(val id: Int? = null, val name: String? = null, val url: String? = null)

@Serializable
data class VtexProduct(
    val productId: String,
    val productName: String = "",
    val brand: String? = null,
    val linkText: String = "",
    val link: String? = null,
    val categories: List<String>? = null,
    val items: List<VtexItem> = emptyList(),
)

@Serializable
data class VtexItem(
    val itemId: String? = null,
    /** SKU barcode. Real GS1 codes and internal refs both arrive here; see ProductIdentity. */
    val ean: String? = null,
    val images: List<VtexImage>? = null,
    val sellers: List<VtexSeller> = emptyList(),
)

@Serializable
data class VtexImage(val imageUrl: String? = null)

/** Note: VTEX's own field name is misspelled "commertialOffer" — it must match. */
@Serializable
data class VtexSeller(val sellerId: String? = null, val commertialOffer: VtexOffer? = null)

@Suppress("PropertyName")
@Serializable
data class VtexOffer(
    // Deserialized straight to BigDecimal, never via Double. Routing a COP price
    // like 9700900 through a binary float is the precision loss the NUMERIC schema
    // columns exist to prevent -- it should not be reintroduced at the JSON boundary.
    @Serializable(with = BigDecimalSerializer::class) val Price: BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class) val ListPrice: BigDecimal? = null,
    val AvailableQuantity: Int? = null,
)

/** Reads a JSON number as BigDecimal from its literal text, bypassing Double entirely. */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return BigDecimal(element.jsonPrimitive.content)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }
}
