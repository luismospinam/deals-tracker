package com.luis.dealstracker.sources.vtex

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
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

/** VTEX's sort parameter for "largest advertised discount first". */
internal const val BEST_DISCOUNT_ORDER = "OrderByBestDiscountDESC"

/**
 * One configured category, plus the order its products are walked in.
 *
 * The order is per category rather than per provider because the right choice depends on
 * how broad the category is — see [VtexSource.targetsOf].
 */
internal data class CategoryTarget(val path: String, val order: String?) {
    /** For the fetch log, so a discount-ordered walk is distinguishable from a default one. */
    val label: String get() = if (order == null) path else "$path (by discount)"
}

/**
 * VTEX catalog adapter. Serves Exito, Carulla, and any other VTEX-hosted retailer
 * purely through config.
 *
 * Three traps this works around, all found by probing the live APIs:
 *
 *  1. **Responses are enormous** — roughly 19MB per 50 products, most of which is
 *     marketing metadata. Pages are kept small and only the handful of fields
 *     below are deserialized.
 *  2. **Unknown categories do not 404.** Requesting a slug the store does not have
 *     returns HTTP 206 with an unfiltered product list, so a copy-pasted slug
 *     produces plausible but wrong data. [validateCategories] checks configured
 *     slugs against the store's own category tree and warns loudly.
 *  3. **The default product order is best-sellers, not deals.** Measured against Exito:
 *     an unordered request returns results identical to `O=OrderByTopSaleDESC`, and that
 *     order is stable — so polling one hourly re-fetched the same products indefinitely.
 *     Since best-sellers are mostly at full price, this filled the feed with popular
 *     groceries at 0% off while an actual 40%-off meat promotion sat unreachable. See
 *     `bestDiscountCategories` in `config/sources.yaml`.
 */
class VtexSource : DealSource {
    override val type = "vtex"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val targets = targetsOf(cfg)
        if (targets.isEmpty()) {
            println("[${cfg.id}] no categories configured; nothing to fetch")
            return emptyList()
        }

        validateCategories(cfg, base, targets.map { it.path }, http)

        val pageSize = cfg.int("pageSize", 24).coerceIn(1, 50)
        val maxPages = cfg.int("maxPagesPerCategory", 3).coerceAtLeast(1)

        val all = LinkedHashMap<String, RawDeal>()
        for (target in targets) {
            var page = 0
            var fetched = 0
            while (page < maxPages) {
                val from = page * pageSize
                val to = from + pageSize - 1
                val url = searchUrl(base, target, from, to)

                val result = try {
                    http.get(url, mapOf("Accept" to "application/json"))
                } catch (e: Exception) {
                    println("[${cfg.id}] ${target.label} page $page failed: ${e.message}")
                    break
                }
                if (result.notModified) break
                // VTEX answers 206 Partial Content for _from/_to ranges, so a plain
                // `status == 200` check here would reject every successful page.
                val body = result.body
                if (body == null || !result.ok) {
                    println("[${cfg.id}] ${target.label} page $page -> HTTP ${result.status}")
                    break
                }

                val products = try {
                    json.decodeFromString<List<VtexProduct>>(body)
                } catch (e: Exception) {
                    println("[${cfg.id}] ${target.label} page $page unparseable: ${e.message}")
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
            println("[${cfg.id}] ${target.label} -> $fetched products")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products, ${available.size} in stock")
        return available
    }

    /**
     * The categories to walk, and the order to walk each one in.
     *
     * Two lists rather than one provider-wide `order` setting, because the right ordering
     * depends on how broad the category is and both readings are needed in the same store:
     *
     *  - `categories` keeps VTEX's default (best-seller) order. Correct for a broad
     *    category, where sorting by discount surfaces marketplace listings with inflated
     *    list prices — Exito's `tecnologia` leads with a 95%-off webcam, and the top-level
     *    `mercado` with 92%-off sweets.
     *  - `bestDiscountCategories` sorts by advertised discount. Correct for a narrow,
     *    single-purpose category, where the same sort returns the store's genuine
     *    promotions: `mercado/pollo-carne-y-pescado` leads with picanha and New York strip
     *    at 40% off, which is exactly what the default order could never reach.
     *
     * Deduplication happens on `externalId` in [fetch], so listing a category in both is
     * harmless rather than a source of duplicate rows.
     */
    internal fun targetsOf(cfg: ProviderConfig): List<CategoryTarget> =
        cfg.list("categories").map { CategoryTarget(it, null) } +
            cfg.list("bestDiscountCategories").map { CategoryTarget(it, BEST_DISCOUNT_ORDER) }

    /**
     * The product-search URL for one page of one category.
     *
     * Split out so the ordering parameter is testable without a network call — an omitted
     * `O=` is what silently pinned every poll to the same best-selling products.
     */
    internal fun searchUrl(base: String, target: CategoryTarget, from: Int, to: Int): String =
        "$base/api/catalog_system/pub/products/search/${normalizePath(target.path)}" +
            "?_from=$from&_to=$to" +
            (target.order?.let { "&O=$it" } ?: "")

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
        // Depth has to reach the deepest configured slug. This was hardcoded to 1, which
        // was right while every configured category was top-level -- but Exito nests its
        // grocery categories under `mercado/...`, and a depth-1 tree carries no children,
        // so every nested slug would be reported as unknown. A warning that fires on
        // correct config gets ignored, which is worse than having no warning at all.
        val depth = categories.maxOfOrNull { segments(it).size }?.coerceAtLeast(1) ?: return
        val result = try {
            http.get("$base/api/catalog_system/pub/category/tree/$depth", mapOf("Accept" to "application/json"))
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

        val known = knownPaths(tree)
        // An empty tree means the request degraded, not that every slug is wrong. Warning
        // about all of them would be noise that trains the reader to skip the warning.
        if (known.isEmpty()) {
            println("[${cfg.id}] category tree came back empty; skipping slug validation")
            return
        }

        val unknown = categories.filter { normalizePath(it) !in known }
        if (unknown.isEmpty()) return

        println(
            "[${cfg.id}] WARNING: categories $unknown are not in this store's tree. " +
                "VTEX returns unfiltered results for unknown slugs, so these will " +
                "produce arbitrary products.",
        )
        // Names the siblings rather than dumping the whole tree, which runs to hundreds of
        // paths once nested categories are configured. Near-misses are what this catches in
        // practice: Exito's meat category is `pollo-carne-y-pescado` and Carulla's is
        // `pollo-carnes-y-pescado`, and that one letter yields plausible junk.
        unknown.forEach { slug ->
            val parent = normalizePath(slug).substringBeforeLast('/', "")
            val siblings = known.filter { it.substringBeforeLast('/', "") == parent }.sorted()
            println("[${cfg.id}]   '$slug' — available under '${parent.ifEmpty { "/" }}': ${siblings.take(25)}")
        }
    }

    /**
     * Every category path in the tree, as `mercado/pollo-carne-y-pescado`.
     *
     * Recursive rather than top-level-only, because a nested slug is valid only if its
     * whole parent chain is in the tree, and because the requested depth now varies with
     * what is configured.
     */
    internal fun knownPaths(tree: List<VtexCategory>): Set<String> = buildSet {
        fun visit(nodes: List<VtexCategory>) {
            nodes.forEach { node ->
                node.url?.let { categoryPath(it) }?.let { add(it) }
                visit(node.children.orEmpty())
            }
        }
        visit(tree)
    }

    /**
     * The category path from a tree node's `url`.
     *
     * The host has to be discarded rather than trusted: these urls carry the store's
     * INTERNAL VTEX hostname (`tienda.exito.com`, `secure.carulla.com`), exactly as
     * `VtexProduct.link` does, so only the path is meaningful. Comparing whole urls
     * against configured slugs would therefore never match.
     */
    internal fun categoryPath(url: String): String? {
        val path = if (url.contains("://")) url.substringAfter("://").substringAfter('/', "") else url
        return normalizePath(path).takeIf { it.isNotBlank() }
    }

    private fun normalizePath(path: String): String = path.trim('/')

    private fun segments(path: String): List<String> =
        normalizePath(path).split('/').filter { it.isNotBlank() }

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

@Serializable
data class VtexCategory(
    val id: Int? = null,
    val name: String? = null,
    val url: String? = null,
    /**
     * Populated only when the tree is requested at a depth above 1, which is why
     * `VtexSource.validateCategories` derives the depth from the configured slugs.
     */
    val children: List<VtexCategory>? = null,
)

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
