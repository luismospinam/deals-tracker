package com.luis.dealstracker.sources.vtex

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.UnitSize
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The whole grocery catalogue of one physical store, priced as that store prices it.
 *
 * ## Why not [VtexSource]
 *
 * `catalog_system`, which [VtexSource] reads, answers with the NATIONAL catalogue, and for a
 * grocer that is the wrong price or no price at all. Measured on carulla.com: *Mix de brotes
 * 30 gr* comes back `Price 0, AvailableQuantity 0` nationally and `5,838 (list 8,340)` at Carulla
 * Laureles. Exito's pork cut is 13,350 nationally and 14,160 at Exito Laureles, and even the
 * top discounted items differ.
 *
 * The site's "¿Cómo quieres recibir tu pedido?" picker needs no cookie and no browser. Each store is
 * its own VTEX seller (`carulla729`, `exitocol037`), and VTEX Intelligent Search takes a
 * `regionId` of `base64("SW#<seller>")` that prices and stocks every product at exactly that
 * seller. `catalog_system` ignores `regionId`, which is why this is a separate adapter over a
 * different API rather than an option on the existing one.
 *
 * ## Three limits of Intelligent Search, all measured
 *
 *  1. **50 pages, hard.** Page 51 answers 400 `"Page should not exceed 50 pages."`, so one query
 *     reaches at most 2,500 products. A subcategory bigger than that (Exito's `despensa` holds
 *     2,965 in stock at Laureles) is split into its children via the facets endpoint.
 *  2. **Both endpoints answer HTTP 406 with a perfectly good body** -- every request, successful
 *     or not, whatever the Accept header or user agent. So status is not the test of success here;
 *     a parseable body is. (The first live run trusted the status and read 0 products.)
 *  3. **`hideUnavailableItems=true` means in stock at THIS store.** It takes Exito's `mercado` from
 *     66,214 products to 12,891, and is how "out of stock at Laureles" is kept off the tab.
 *
 * Prices are mapped by [VtexSource.toRawDeal] -- IS returns the same `items[].sellers[]` shape,
 * and quotes weighed goods per kg exactly as `catalog_system` does (checked on `Uva Verde`,
 * 20,240/kg x 0.5 kg on both APIs), so the multiplier handling applies unchanged.
 */
class VtexGrocerySource(
    private val vtex: VtexSource = VtexSource(),
) : DealSource {
    override val type = "vtex-grocery"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val seller = requireNotNull(cfg.options["regionSeller"]?.takeIf { it.isNotBlank() }) {
            "provider '${cfg.id}' needs options.regionSeller -- the VTEX seller id of the store to price at"
        }
        val region = regionId(seller)
        val groups = groupsOf(cfg)
        if (groups.isEmpty()) {
            println("[${cfg.id}] no grocery categories configured; nothing to fetch")
            return emptyList()
        }

        val all = LinkedHashMap<String, RawDeal>()
        for ((path, group) in groups) {
            val queries = split(cfg, base, region, path, http)
            var fetched = 0
            for (query in queries) {
                fetched += walk(cfg, base, region, query, http) { product ->
                    toGroceryDeal(product, base, cfg.currency, group)?.let { all.putIfAbsent(it.externalId, it) }
                }
            }
            println("[${cfg.id}] $path ($group) -> $fetched products" +
                if (queries.size > 1) " across ${queries.size} subcategories" else "")
        }

        // hideUnavailableItems already filtered by stock at this store; this catches the odd
        // product whose only in-stock offer carried no price.
        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products at seller $seller, ${available.size} in stock")
        return available
    }

    /**
     * Configured category path -> the tab's subcategory label.
     *
     * `listOptions.categories` holds entries as `path = Label`, e.g.
     * `mercado/pollo-carne-y-pescado = Meat & fish`. One label for the same aisle at both stores is
     * the point: Exito's `pollo-carne-y-pescado` and Carulla's `pollo-carnes-y-pescado` must filter
     * together. An entry without a label uses its last path segment.
     */
    internal fun groupsOf(cfg: ProviderConfig): List<Pair<String, String>> =
        cfg.list("categories").mapNotNull { entry ->
            val path = entry.substringBefore('=').trim().trim('/')
            if (path.isEmpty()) return@mapNotNull null
            val label = entry.substringAfter('=', "").trim().ifEmpty { path.substringAfterLast('/') }
            path to label
        }

    /**
     * The category paths to page through for one configured path: itself if it fits under the page
     * cap, otherwise its children, recursively.
     *
     * Falls back to the path itself when the facets cannot be read -- a capped walk of 2,500 is a
     * worse result than a split one, but a far better one than nothing.
     */
    internal suspend fun split(
        cfg: ProviderConfig,
        base: String,
        region: String,
        path: String,
        http: PoliteHttp,
        depth: Int = 0,
    ): List<String> {
        val facets = facets(cfg, base, region, path, http) ?: return listOf(path)
        if (facets.total <= MAX_RESULTS || depth >= 2 || facets.children.isEmpty()) {
            if (facets.total > MAX_RESULTS) {
                println("[${cfg.id}] WARNING: $path has ${facets.total} products and cannot be split " +
                    "further; only the first $MAX_RESULTS will be read")
            }
            return listOf(path)
        }
        return facets.children.flatMap { child -> split(cfg, base, region, "$path/$child", http, depth + 1) }
    }

    private data class Facets(val total: Int, val children: List<String>)

    private suspend fun facets(cfg: ProviderConfig, base: String, region: String, path: String, http: PoliteHttp): Facets? {
        val result = try {
            http.get(facetsUrl(base, region, path), mapOf("Accept" to "application/json"))
        } catch (e: Exception) {
            println("[${cfg.id}] facets for $path failed: ${e.message}")
            return null
        }
        // Not `result.ok`: this endpoint answers 406 with a valid body -- see the class comment.
        val body = result.body ?: return null
        val parsed = runCatching { json.decodeFromString<IsFacetsResponse>(body) }.getOrNull() ?: run {
            println("[${cfg.id}] facets for $path unreadable (HTTP ${result.status})")
            return null
        }
        val childKey = "category-${facetDepth(path) + 1}"
        val children = parsed.facets.firstOrNull { f -> f.values.firstOrNull()?.key == childKey }
            ?.values.orEmpty()
        return Facets(
            total = children.sumOf { it.quantity },
            children = children.map { it.value },
        )
    }

    /** Pages one path, handing each product to [each]. Returns how many products were read. */
    private suspend fun walk(
        cfg: ProviderConfig,
        base: String,
        region: String,
        path: String,
        http: PoliteHttp,
        each: (VtexProduct) -> Unit,
    ): Int {
        var fetched = 0
        for (page in 1..MAX_PAGES) {
            // One retry on an unreadable page. A ~1 MB response occasionally arrives cut short --
            // Carulla's drinks page 1 failed to parse at offset 1,018,275 and read cleanly on the next
            // request -- and without a retry that one page silently costs a whole aisle.
            val products = readPage(cfg, base, region, path, page, http)
                ?: readPage(cfg, base, region, path, page, http)
                ?: break
            products.forEach(each)
            fetched += products.size
            if (products.size < PAGE_SIZE) break
        }
        return fetched
    }

    /** One page's products, or null when the page could not be read. */
    private suspend fun readPage(
        cfg: ProviderConfig,
        base: String,
        region: String,
        path: String,
        page: Int,
        http: PoliteHttp,
    ): List<VtexProduct>? {
        val result = try {
            http.get(searchUrl(base, region, path, page), mapOf("Accept" to "application/json"))
        } catch (e: Exception) {
            println("[${cfg.id}] $path page $page failed: ${e.message}")
            return null
        }
        // Not `result.ok`: every Intelligent Search response here is HTTP 406, successful ones
        // included, so the body is the only test -- see the class comment. A real refusal (page 51
        // is a 400 with an error object) parses to no products and ends the walk the same way.
        val body = result.body ?: run {
            println("[${cfg.id}] $path page $page -> HTTP ${result.status}, no body")
            return null
        }
        return try {
            json.decodeFromString<IsSearchResponse>(body).products
        } catch (e: Exception) {
            println("[${cfg.id}] $path page $page unparseable (HTTP ${result.status}): ${e.message?.take(160)}")
            null
        }
    }

    /** The shelf price at this store, plus the pack size and the tab's subcategory. */
    internal fun toGroceryDeal(product: VtexProduct, base: String, currency: String, group: String): RawDeal? {
        val raw = vtex.toRawDeal(product, base, currency) ?: return null
        // The size of the SKU that was priced. Grocery products are single-SKU in practice, so the
        // first item is that SKU; were one not, the size would be a neighbour's and the title
        // fallback in UnitSize is what would still be right.
        val item = product.items.firstOrNull()
        val size = UnitSize.of(raw.title, item?.measurementUnit, item?.unitMultiplier)
        return raw.copy(
            unitMeasure = size?.measure,
            unitQuantity = size?.quantity,
            groceryGroup = group,
        )
    }

    companion object {
        /** IS's own maximum page size. */
        internal const val PAGE_SIZE = 50

        /** Page 51 is refused outright -- see the class comment. */
        internal const val MAX_PAGES = 50
        internal const val MAX_RESULTS = PAGE_SIZE * MAX_PAGES

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `carulla729` -> `U1cjY2FydWxsYTcyOQ==`, the value the store picker writes. */
        internal fun regionId(seller: String): String =
            Base64.getEncoder().encodeToString("SW#$seller".toByteArray())

        /** `mercado/despensa` -> `category-1/mercado/category-2/despensa`. */
        internal fun facetPath(path: String): String =
            path.split('/').filter { it.isNotBlank() }
                .mapIndexed { i, segment -> "category-${i + 1}/$segment" }
                .joinToString("/")

        private fun facetDepth(path: String) = path.split('/').count { it.isNotBlank() }

        private fun query(region: String) =
            "regionId=${java.net.URLEncoder.encode(region, Charsets.UTF_8)}&hideUnavailableItems=true"

        internal fun searchUrl(base: String, region: String, path: String, page: Int): String =
            "$base/api/io/_v/api/intelligent-search/product_search/${facetPath(path)}" +
                "?count=$PAGE_SIZE&page=$page&${query(region)}"

        internal fun facetsUrl(base: String, region: String, path: String): String =
            "$base/api/io/_v/api/intelligent-search/facets/${facetPath(path)}?${query(region)}"
    }
}

@Serializable
internal data class IsSearchResponse(val products: List<VtexProduct> = emptyList())

@Serializable
internal data class IsFacetsResponse(val facets: List<IsFacet> = emptyList())

@Serializable
internal data class IsFacet(val name: String? = null, val values: List<IsFacetValue> = emptyList())

/** `key` is the facet level (`category-2`); `value` the slug (`despensa`). */
@Serializable
internal data class IsFacetValue(val key: String? = null, val value: String = "", val quantity: Int = 0)
