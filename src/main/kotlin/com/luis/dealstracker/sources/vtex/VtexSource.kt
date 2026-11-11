package com.luis.dealstracker.sources.vtex

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import com.luis.dealstracker.sources.SpecText
import com.luis.dealstracker.sources.encodeQueryValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** VTEX's sort parameter for "largest advertised discount first". */
internal const val BEST_DISCOUNT_ORDER = "OrderByBestDiscountDESC"

/**
 * `banos` -> `category-1/banos`; `mercado/despensa` -> `category-1/mercado/category-2/despensa`.
 *
 * How Intelligent Search names a category, and shared by both adapters over that API — one copy,
 * so they cannot disagree about what a category path looks like.
 */
internal fun facetPath(path: String): String =
    path.split('/').filter { it.isNotBlank() }
        .mapIndexed { i, segment -> "category-${i + 1}/$segment" }
        .joinToString("/")

/**
 * One thing to fetch: a category to walk, or a keyword to search.
 *
 * The order is per category rather than per provider because the right choice depends on
 * how broad the category is — see [VtexSource.targetsOf].
 *
 * Search shares this type rather than having its own, because everything downstream — paging,
 * parsing, dedupe, logging — is identical; only the URL differs. `term` and `path` are mutually
 * exclusive: a search has no category and vice versa.
 */
internal data class CategoryTarget(val path: String, val order: String?, val term: String? = null) {
    val isSearch: Boolean get() = term != null

    /** For the fetch log, so each kind of walk is distinguishable in the output. */
    val label: String get() = when {
        term != null -> "ft=$term"
        order == null -> path
        else -> "$path (by discount)"
    }
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
            println("[${cfg.id}] no categories or searches configured; nothing to fetch")
            return emptyList()
        }

        // Only real categories are validated against the store's tree; a search term is not a
        // slug and would always look "unknown".
        val categories = targets.filterNot { it.isSearch }.map { it.path }
        if (categories.isNotEmpty()) validateCategories(cfg, base, categories, http)

        val pageSize = cfg.int("pageSize", 24).coerceIn(1, 50)

        // Intelligent Search instead of `catalog_system`, per provider. Off by default. It is the
        // only API that fills in `teasers`, where Easy's and Exito's card prices live -- see
        // `VtexOffer.teasers` and `cardOfferOf`. (The 2026-09-29 note that it answers Exito's
        // keyword searches with nothing was the trailing-slash 301 in [intelligentSearchUrl].)
        val intelligentSearch = cfg.options["useIntelligentSearch"]?.toBoolean() ?: false

        val all = LinkedHashMap<String, RawDeal>()
        for (target in targets) {
            // Searches are capped shallower than category browses by default. A category is a
            // catalog we want broad coverage of; a search exists to answer "is the thing I want
            // on sale", and relevance drops off fast -- 850 hits for "nevera no frost" are
            // mostly not fridges by the third page.
            val maxPages = when {
                target.isSearch -> cfg.int("maxPagesPerSearch", 2)
                else -> cfg.int("maxPagesPerCategory", 3)
            }.coerceAtLeast(1)


            suspend fun walk(intelligentSearch: Boolean): Int {
                var page = 0
                var fetched = 0
                while (page < maxPages) {
                    val from = page * pageSize
                    val to = from + pageSize - 1
                    val url = if (intelligentSearch) {
                        intelligentSearchUrl(base, target, page + 1, pageSize)
                    } else {
                        searchUrl(base, target, from, to)
                    }

                    val result = try {
                        http.get(url, mapOf("Accept" to "application/json"))
                    } catch (e: Exception) {
                        println("[${cfg.id}] ${target.label} page $page failed: ${e.message}")
                        break
                    }
                    // VTEX answers 206 Partial Content for _from/_to ranges, so a plain
                    // `status == 200` check here would reject every successful page.
                    val body = result.body
                    // Intelligent Search answers 406 even when it succeeds, so for that API the body
                    // is the only test -- the same call `VtexGrocerySource.readPage` makes. The status
                    // check stays in force for `catalog_system`, where it does detect real failures.
                    if (body == null || (!result.ok && !intelligentSearch)) {
                        println("[${cfg.id}] ${target.label} page $page -> HTTP ${result.status}")
                        break
                    }

                    val products = try {
                        if (intelligentSearch) {
                            json.decodeFromString<IsSearchResponse>(body).products
                        } else {
                            withSpecs(body)
                        }
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

                return fetched
            }
            // Intelligent Search first: it is the only API that carries card prices, and it ranks
            // by relevance. But it is stricter on keywords than `catalog_system` -- measured
            // 2026-11-02 at Exito, `sofacama` finds nothing there and 24 sofa-beds here, and
            // `sound bar` misses the JBL SB510. So a SEARCH also asks `catalog_system`, and keeps
            // only what Intelligent Search did not return (`putIfAbsent`, so the card-priced copy
            // wins). Those extra rows carry no card price. Categories do not need it.
            var fetched = walk(intelligentSearch)
            if (intelligentSearch && target.isSearch && cfg.options["searchFallbackToCatalog"]?.toBoolean() != false) {
                val before = all.size
                val extra = walk(false)
                println("[${cfg.id}] ${target.label} catalog_system fallback: $extra read, ${all.size - before} not in Intelligent Search")
                fetched += extra
            }
            println("[${cfg.id}] ${target.label} -> $fetched products")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} unique products, ${available.size} in stock")
        return available
    }

    /**
     * What to fetch: the categories to walk, the order to walk each in, and the terms to search.
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
     *
     * `searches` is a third kind of target, and is populated by
     * [com.luis.dealstracker.ingest.IngestService] from the active watches as well as from
     * config — which is what lets a watch go looking for a fridge that no configured category
     * would ever have surfaced. It reads the same key `SlickdealsSource` does, so one mechanism
     * drives keyword hunting on every source that supports it.
     */
    internal fun targetsOf(cfg: ProviderConfig): List<CategoryTarget> =
        cfg.list("categories").map { CategoryTarget(it, null) } +
            cfg.list("bestDiscountCategories").map { CategoryTarget(it, BEST_DISCOUNT_ORDER) } +
            cfg.list("searches").map { CategoryTarget(path = "", order = null, term = it) }

    /**
     * The product-search URL for one page of one target.
     *
     * Split out so the ordering parameter is testable without a network call — an omitted
     * `O=` is what silently pinned every poll to the same best-selling products.
     *
     * A category is a path segment; a keyword goes in `ft`. Note `encodeQueryValue` rather than
     * `URLEncoder` directly: olimpica.com answers HTTP 400 to `ft=smart+tv` and 206 to
     * `ft=smart%20tv`, and watch terms are routinely multi-word.
     */
    internal fun searchUrl(base: String, target: CategoryTarget, from: Int, to: Int): String {
        val root = "$base/api/catalog_system/pub/products/search"
        val range = "_from=$from&_to=$to"
        return if (target.isSearch) {
            "$root?ft=${encodeQueryValue(target.term!!)}&$range"
        } else {
            "$root/${normalizePath(target.path)}?$range" + (target.order?.let { "&O=$it" } ?: "")
        }
    }

    /**
     * The same targets against Intelligent Search, which is the only API that fills in `teasers`.
     *
     * Three differences from [searchUrl], all forced by the API: paging is `count`/`page` (1-based)
     * rather than `_from`/`_to`, a category is a `category-N/<slug>` facet path rather than a bare
     * slug, and a keyword is `query=` rather than `ft=`.
     *
     * Deliberately NOT sent, unlike the grocery adapter:
     *  - `regionId`, which prices at one physical store. Easy needs none — its categories answer
     *    HTTP 200 without it (measured 2026-09-29), and there is no seller id for that account.
     *  - `hideUnavailableItems`, which makes the server drop out-of-stock rows. This adapter
     *    fetches them and sets `available` from the offer ([toRawDeal]), so an item going out of
     *    stock is marked rather than vanishing from the feed.
     *  - `O=`, the best-discount sort, which this API does not accept in that form. A provider
     *    using `bestDiscountCategories` should stay on `catalog_system`.
     */
    internal fun intelligentSearchUrl(base: String, target: CategoryTarget, page: Int, count: Int): String {
        val root = "$base/api/io/_v/api/intelligent-search/product_search"
        val paging = "count=$count&page=$page"
        return if (target.isSearch) {
            // No slash before the `?`: Exito answers `product_search/?query=` with a 301 to plain http,
            // whose body is HTML, so every keyword search parsed as nothing (measured 2026-11-02).
            "$root?query=${encodeQueryValue(target.term!!)}&$paging"
        } else {
            "$root/${facetPath(normalizePath(target.path))}?$paging"
        }
    }

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
        data class Offer(
            val price: BigDecimal,
            val listPrice: BigDecimal?,
            val qty: Int,
            val cards: List<com.luis.dealstracker.model.CardOffer> = emptyList(),
            val promotions: List<com.luis.dealstracker.model.PromotionTag> = emptyList(),
        )

        val offers = product.items.flatMap { item ->
            // VTEX quotes weighed goods PER UNIT, and `unitMultiplier` is the pack size. A 2kg
            // pork belly came back as Price 25050 against a shelf price of $50,100, and its
            // ListPrice 33400 against $66,800 -- both exactly half, because the API figure is
            // per kg. It cuts the other way too: 0.5kg cuts would be stored at double.
            //
            // Applied to the list price as well as the price. Scaling one and not the other
            // would invent a discount out of the pack size, which is worse than either error
            // alone -- and the discount floor decides what gets promoted.
            val multiplier = item.unitMultiplier
                ?.takeIf { it > 0 && it.isFinite() }
                ?.toBigDecimal()
                ?: BigDecimal.ONE

            item.sellers.mapNotNull { seller ->
                val o = seller.commertialOffer ?: return@mapNotNull null
                val price = o.Price?.takeIf { it.signum() > 0 }
                    ?.multiply(multiplier)?.asMoney() ?: return@mapNotNull null
                Offer(
                    price = price,
                    // Ignore a list price that isn't above the sale price: it carries
                    // no discount information and would compute as 0%. Compared after
                    // scaling, since both sides must be in the same units.
                    listPrice = o.ListPrice?.multiply(multiplier)?.asMoney()?.takeIf { it > price },
                    qty = o.AvailableQuantity ?: 0,
                    // The card price of THIS offer, so it travels with the offer chosen below rather
                    // than being read off a different seller's teasers.
                    cards = cardOffersOf(o, multiplier, price),
                    promotions = promotionsOf(o),
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
            cardOffer = best.cards.firstOrNull(),
            cardOffers = best.cards,
            promotions = best.promotions,
            stockQty = best.qty,
            specs = product.specs.ifEmpty { specsOf(product.properties) },
            description = SpecText.description(product.description),
        )
    }

    companion object {
        // VTEX payloads carry hundreds of fields we do not use.
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Decodes a `catalog_system` response and attaches each product's specification table.
         *
         * That API lists the table's names in `allSpecifications` and then repeats each as its own
         * top-level key (`"Lado Chaise": ["Izquierdo"]`), so the names are only known per product and
         * the values must be read off the raw object.
         */
        internal fun withSpecs(body: String): List<VtexProduct> =
            json.parseToJsonElement(body).jsonArray.map { el ->
                val product = json.decodeFromJsonElement<VtexProduct>(el)
                product.copy(specs = specsOf(el.jsonObject))
            }

        internal fun specsOf(product: JsonObject): Map<String, String> =
            (product["allSpecifications"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .mapNotNull { name ->
                    val value = (product[name] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .joinToString("; ")
                    SpecText.pair(name, value)
                }
                .toMap()

        /** Intelligent Search's own shape of the same table. */
        internal fun specsOf(properties: List<VtexProperty>?): Map<String, String> =
            properties.orEmpty().mapNotNull { SpecText.pair(it.name, it.values.orEmpty().joinToString("; ")) }.toMap()
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
    val description: String? = null,
    /** Intelligent Search's spec table: `[{name, values}]`. `catalog_system` uses [specs] instead. */
    val properties: List<VtexProperty>? = null,
    /**
     * Filled by [VtexSource.withSpecs] after decoding, never by the decoder: `catalog_system` spreads
     * the table across the product as one key per name, which no fixed class can describe.
     */
    @kotlinx.serialization.Transient val specs: Map<String, String> = emptyMap(),
)

@Serializable
data class VtexProperty(val name: String? = null, val values: List<String>? = null)

@Serializable
data class VtexItem(
    val itemId: String? = null,
    /** SKU barcode. Real GS1 codes and internal refs both arrive here; see ProductIdentity. */
    val ean: String? = null,
    val images: List<VtexImage>? = null,
    val sellers: List<VtexSeller> = emptyList(),
    /**
     * How many [measurementUnit] the pack contains. The offer's `Price` is **per unit**, so
     * the shelf price is `Price * unitMultiplier`.
     *
     * Only meaningfully different from 1 for weighed goods, but it goes both ways: a 2kg pork
     * belly priced per kg was understated by half, while 0.5kg cuts are overstated by double.
     * Defaults to 1 so any item without the field behaves as before.
     */
    val unitMultiplier: Double? = null,
    val measurementUnit: String? = null,
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
    /** The price before any shelf promotion; what a card percentage is taken off. See [cardOfferOf]. */
    @Serializable(with = BigDecimalSerializer::class) val PriceWithoutDiscount: BigDecimal? = null,
    /**
     * Conditional promotions -- card-only prices and multi-buys. Intelligent Search fills this in;
     * `catalog_system` leaves it empty. See [cardOfferOf].
     */
    val teasers: List<VtexTeaser>? = null,
    /**
     * Shelf promotions, by name (`cmp_20_prov_mercado_...`). Already reflected in [Price], so they are
     * not a second discount -- they are recorded for analysis only. See `V18__promotions`.
     */
    val discountHighlights: List<VtexHighlight>? = null,
)

@Serializable
data class VtexHighlight(val name: String? = null)

/**
 * One conditional promotion, e.g. `mastercard_20_aliado_..._sep25_sep25`: condition
 * `PaymentMethodId = "4,501"`, effect `PercentualDiscount = "20"`.
 */
@Serializable
data class VtexTeaser(
    val name: String? = null,
    val conditions: VtexTeaserConditions? = null,
    val effects: VtexTeaserEffects? = null,
)

@Serializable
data class VtexTeaserConditions(val parameters: List<VtexParam> = emptyList(), val minimumQuantity: Int? = null)

@Serializable
data class VtexTeaserEffects(val parameters: List<VtexParam> = emptyList())

@Serializable
data class VtexParam(val name: String? = null, val value: String? = null)

/**
 * Every promotion on an offer, shelf and conditional, decoded as far as its name allows. Distinct by
 * name, since a store occasionally lists the same highlight twice.
 */
internal fun promotionsOf(offer: VtexOffer): List<com.luis.dealstracker.model.PromotionTag> =
    (offer.discountHighlights.orEmpty().mapNotNull { h -> h.name?.takeIf { it.isNotBlank() }?.let(PromotionNames::shelf) } +
        offer.teasers.orEmpty().mapNotNull(PromotionNames::teaser))
        .distinctBy { it.name }

/** VTEX payment system ids, as `Installments[].PaymentSystemName` names them at both stores. */
private const val MASTERCARD_ID = "4"

/** "Tarjeta Éxito y Carulla" at Exito, "Carulla Mastercard" at Carulla -- group customPrivate_501. */
private const val STORE_CARD_ID = "501"

/**
 * The ids Exito's `*_aliado_tabladeprecios_*` teasers name, alongside the two above. Measured
 * 2026-11-03 on exito.com electro: `visa_aliado_...` names 2 and 209, `mastercard_aliado_...` 4 and 210,
 * `addi_aliado_...` 102 -- and Easy's `SW T. INT ADDI ...` teasers name 102 too. Before V35 every one of
 * them was recorded as an unknown "Card".
 */
private val MASTERCARD_IDS = setOf(MASTERCARD_ID, "210")
private val VISA_IDS = setOf("2", "209")
private const val ADDI_ID = "102"

/**
 * Exito's own-card price table: `exito1_aliado_tabladeprecios_*`, restricted by a list of Tuya BINs
 * rather than by payment id. Measured 2026-11-05: 113 of 127 card teasers over eight searches, and
 * the rendered page (Televisor CAIXUN 55" ...-3255839) shows its price, 1,279,903, beside the
 * "Tarjeta Éxito" logo. Before this it was recorded as an unknown "Card".
 *
 * Its grocery percentages carry the percentage in the name too: `exito1_35_aliado_mercado_fruver_...`
 * (2026-11-10, 149 of 150 fruit & veg items at Exito Laureles). Carulla runs the same promotion as
 * `carulla1_35_aliado_mercado_fruver_...` -- same id 40794, same 22 BINs -- so it is the same card. See [cardOffersOf].
 */
private val STORE_CARD_TABLE = Regex("""^(?:exito|carulla)\d*(?:_\d+)?_aliado_""", RegexOption.IGNORE_CASE)

/** Who can pay a BIN-restricted price table, from its name: the store card, or a card not named. */
internal fun scopeOfBinTable(name: String?): com.luis.dealstracker.model.CardScope =
    if (name != null && STORE_CARD_TABLE.containsMatchIn(name)) com.luis.dealstracker.model.CardScope.STORE_CARD
    else com.luis.dealstracker.model.CardScope.OTHER

/** Who can pay a teaser restricted to these payment ids. */
internal fun scopeOfPaymentIds(ids: Set<String>): com.luis.dealstracker.model.CardScope = when {
    ids.any { it in MASTERCARD_IDS } -> com.luis.dealstracker.model.CardScope.MASTERCARD
    ids == setOf(STORE_CARD_ID) -> com.luis.dealstracker.model.CardScope.STORE_CARD
    ids.isNotEmpty() && ids.all { it in VISA_IDS } -> com.luis.dealstracker.model.CardScope.VISA
    ids == setOf(ADDI_ID) -> com.luis.dealstracker.model.CardScope.ADDI
    else -> com.luis.dealstracker.model.CardScope.OTHER
}

/**
 * Easy restricts by card BIN instead of by payment id, and the BINs it uses fall into exactly two
 * disjoint halves plus their union.
 *
 * Measured 2026-09-29 over 185 teasers across five categories: these six always accompany a name
 * containing `TMC`, the [CODENSA_BINS] six always one containing `COD`, and all twelve together
 * always one reading `COD Y TMC`. Name and BIN set agreed in 185 of 185, so this is the store's own
 * split rather than an inference from the numbers.
 */
private val TMC_BINS = setOf("409744", "411759", "414489", "483160", "483161", "606374")

private val CODENSA_BINS = setOf("423629", "425819", "425949", "529448", "590712", "870055")

/**
 * The best card-only price among an offer's teasers, or null when none applies.
 *
 * Only teasers that are purely a payment condition count: `PaymentMethodId` and nothing else, a
 * minimum quantity of 0, and a `PercentualDiscount`. That excludes `paguexllevey` multi-buys
 * (condition `SkuIdsList`, minimum 3, "100% off" the free unit), which are not a card price.
 *
 * The percentage comes off `PriceWithoutDiscount` -- the price before any shelf promotion -- not the
 * shelf price, so the two discounts do not stack. Each case below was checked on the rendered page:
 *
 *  - Arroz premium 1 kg: shelf 5,193, PriceWithoutDiscount 6,110, 25% -> page $4,583, not 3,895.
 *  - Gatorade blue ice: shelf 4,100, no shelf promotion, 20% -> page $3,280.
 *  - Gatorade manzana: shelf 4,100, `ListPrice` 4,220 but PriceWithoutDiscount 4,100, 20% -> page
 *    $3,280. Taken off ListPrice it would be 3,376, so ListPrice is only the fallback.
 *  - Gatorade frutas tropicales: shelf already 3,280, 20% of 4,100 is 3,280 -> the page shows no card
 *    price at all. A card price that does not beat the shelf price is dropped.
 *
 * Rounded to the whole unit HALF_UP, which is what the page shows (6,110 x 0.75 = 4,582.5 -> $4,583).
 * [multiplier] scales weighed goods exactly as the shelf price is scaled.
 */
internal fun cardOfferOf(
    offer: VtexOffer,
    multiplier: BigDecimal,
    shelfPrice: BigDecimal,
): com.luis.dealstracker.model.CardOffer? = cardOffersOf(offer, multiplier, shelfPrice).firstOrNull()

/**
 * Every card offer on [offer], the best per card, cheapest first. [cardOfferOf] is its head: the one
 * card price the history keeps. The rest are what lets an operator holding Addi see Addi's price when
 * Visa's is lower (the 423 L Electrolux: 1,899,000 with Visa, 1,999,000 with Addi).
 */
internal fun cardOffersOf(
    offer: VtexOffer,
    multiplier: BigDecimal,
    shelfPrice: BigDecimal,
): List<com.luis.dealstracker.model.CardOffer> {
    val base = listOf(offer.PriceWithoutDiscount, offer.ListPrice, offer.Price)
        .firstOrNull { it != null && it.signum() > 0 }?.multiply(multiplier) ?: return emptyList()
    return offer.teasers.orEmpty().flatMap { teaser ->
        val conditions = teaser.conditions ?: return@flatMap emptyList()
        if ((conditions.minimumQuantity ?: 0) > 0) return@flatMap emptyList()
        val params = conditions.parameters
        if (params.isEmpty()) return@flatMap emptyList()
        val values = params.flatMap { it.value.orEmpty().split(',') }
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (values.isEmpty()) return@flatMap emptyList()

        when {
            // Exito and Carulla: the teaser names a payment method and publishes a percentage,
            // and the price must be computed off the pre-promotion base.
            params.all { it.name == "PaymentMethodId" } ->
                listOfNotNull(
                    byPaymentMethod(teaser, values, base, shelfPrice)
                        ?: byPriceTable(teaser, base, shelfPrice, scopeOfPaymentIds(values)),
                )
            // Easy: the teaser restricts by card BIN and publishes the price to pay outright. One
            // good on both of Easy's cards ("COD Y TMC") is an offer for each, so a Codensa holder
            // sees it too; with a single card price per reading it had to be recorded as TMC alone.
            params.all { it.name == "RestrictionsBins" } ->
                byCardBin(teaser, values, shelfPrice)?.let { card ->
                    if (card.scope == com.luis.dealstracker.model.CardScope.TMC && values.any { it in CODENSA_BINS }) {
                        listOf(card, card.copy(scope = com.luis.dealstracker.model.CardScope.CODENSA))
                    } else {
                        listOf(card)
                    }
                } ?: listOfNotNull(
                    byPriceTable(teaser, base, shelfPrice, scopeOfBinTable(teaser.name))
                        // Exito's own card, as a percentage: `exito1_35_aliado_mercado_fruver_...`, 35%
                        // with Tarjeta Éxito on the grocery aisles since the Mastercard ones ended
                        // (2026-11-06). Read exactly like the PaymentMethodId shape -- off the pre-promotion
                        // base, and only when it beats the shelf -- which is what Exito's pages showed for
                        // its card percentages. Only for the store card: the reason Easy's BIN percentages are
                        // ignored (often already in the shelf price) is caught here by that same beat-the-shelf
                        // test, since a baked-in percentage reproduces the shelf price exactly.
                        ?: scopeOfBinTable(teaser.name).takeIf { it == com.luis.dealstracker.model.CardScope.STORE_CARD }
                            ?.let { byPercent(teaser, it, base, shelfPrice) },
                )
            // A mixed or unknown condition is not a plain card offer -- a SkuIdsList makes it a
            // multi-buy. Dropped rather than guessed at.
            else -> emptyList()
        }
    }
        // Best per card; ties keep the store's own teaser order, which is what the single card price
        // always followed (minByOrNull takes the first of equals).
        .withIndex()
        .groupBy { it.value.scope }
        .map { (_, same) -> same.minWith(compareBy({ it.value.price }, { it.index })) }
        .sortedWith(compareBy({ it.value.price }, { it.index }))
        .map { it.value }
}

/** The Exito/Carulla shape: `PaymentMethodId` + `PercentualDiscount`, off the pre-promotion base. */
private fun byPaymentMethod(
    teaser: VtexTeaser,
    ids: Set<String>,
    base: BigDecimal,
    shelfPrice: BigDecimal,
): com.luis.dealstracker.model.CardOffer? = byPercent(teaser, scopeOfPaymentIds(ids), base, shelfPrice)

/** A teaser's `PercentualDiscount` off [base], for [scope]; null unless it beats [shelfPrice]. */
private fun byPercent(
    teaser: VtexTeaser,
    scope: com.luis.dealstracker.model.CardScope,
    base: BigDecimal,
    shelfPrice: BigDecimal,
): com.luis.dealstracker.model.CardOffer? {
    val percent = teaser.effects?.parameters
        ?.firstOrNull { it.name == "PercentualDiscount" }?.value?.trim()?.toBigDecimalOrNull()
        ?.takeIf { it > BigDecimal.ZERO && it < BigDecimal(100) }
        ?: return null
    val price = base.multiply(BigDecimal.ONE - percent.movePointLeft(2))
        .setScale(0, java.math.RoundingMode.HALF_UP).asMoney()
    return com.luis.dealstracker.model.CardOffer(price, percent.setScale(2, java.math.RoundingMode.HALF_UP), scope)
        .takeIf { price < shelfPrice }
}

/**
 * The Easy shape: restricted by card BIN, with the price to pay given outright.
 *
 * `MaximumUnitPriceDiscount` is a PRICE in cents, not a discount -- `PRECMAX` in the teaser name is
 * *precio máximo*. The toilet `1505557` is 299,900 cash with a cap of 26990000 → **269,900 with
 * TMC**, which is what the store's own page shows. So the percentage is derived here, against the
 * cash price, exactly as `FalabellaNextSource.cardOfferOf` does for CMR.
 *
 * **`PercentualDiscount` on a BIN-restricted teaser is deliberately ignored.** Measured on the live
 * pages, those percentages are frequently ALREADY APPLIED in the shelf price -- Combo Tairona is
 * 411,920 with `PriceWithoutDiscount` 514,900 and a 20% teaser, and 514,900 × 0.80 reproduces the
 * shelf price exactly. 7 of 32 such rows did that, and others compute to MORE than the cash price
 * (Klipen Ecohome: 449,900 × 0.80 = 359,920 against a 314,930 shelf). There is no reliable way to
 * tell a live one from a spent one, so none is taken: a card price we cannot stand behind is worse
 * than no card price.
 */
private fun byCardBin(
    teaser: VtexTeaser,
    bins: Set<String>,
    shelfPrice: BigDecimal,
): com.luis.dealstracker.model.CardOffer? {
    val cents = teaser.effects?.parameters
        ?.firstOrNull { it.name == "MaximumUnitPriceDiscount" }?.value?.trim()?.toBigDecimalOrNull()
        ?.takeIf { it.signum() > 0 }
        ?: return null
    val price = cents.movePointLeft(2).asMoney()
    // Strict: a cap equal to the cash price saves nothing. The toilet carries exactly such a
    // teaser (299,900) alongside the real one, so this is load-bearing rather than defensive.
    if (price >= shelfPrice || shelfPrice.signum() <= 0) return null

    val scope = when {
        // A teaser good on both cards is recorded as the one in hand; the name says "COD Y TMC".
        bins.any { it in TMC_BINS } -> com.luis.dealstracker.model.CardScope.TMC
        bins.all { it in CODENSA_BINS } -> com.luis.dealstracker.model.CardScope.CODENSA
        else -> com.luis.dealstracker.model.CardScope.OTHER
    }
    val percent = shelfPrice.subtract(price)
        .divide(shelfPrice, 6, java.math.RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(2, java.math.RoundingMode.HALF_UP)
    // `card_percent` is CHECK (> 0 AND < 100); a saving too small to survive two decimals would
    // fail the INSERT and lose the whole observation, so it is dropped here instead.
    if (percent.signum() <= 0 || percent >= BigDecimal(100)) return null

    return com.luis.dealstracker.model.CardOffer(price, percent, scope)
}

/**
 * The Exito "price table" shape (`*_aliado_tabladeprecios_*`): a payment condition (card BINs or a
 * payment method such as Addi, id 102) with a `PromotionalPriceTableItemsDiscount` effect, an
 * ABSOLUTE amount off the pre-promotion base.
 *
 * Measured 2026-11-01 on Nevecon Electrolux 423 L (exito.com ...-3231539): list 3,199,900, shelf
 * 2,399,925; discounts 1,300,900 (card BINs) and 1,200,900 (Addi) give 1,899,000 and 1,999,000 --
 * round numbers, which is what a price set by hand looks like, so the amount is taken off the base
 * rather than the shelf price. Unlike a BIN percentage, an absolute amount cannot already be baked
 * into the shelf price; a result that does not beat the shelf price is dropped all the same.
 * [scope] comes from the payment ids when the teaser names them (Visa, Mastercard, Addi); a BIN list is
 * not mapped to a bank, so it stays [com.luis.dealstracker.model.CardScope.OTHER].
 */
private fun byPriceTable(
    teaser: VtexTeaser,
    base: BigDecimal,
    shelfPrice: BigDecimal,
    scope: com.luis.dealstracker.model.CardScope = com.luis.dealstracker.model.CardScope.OTHER,
): com.luis.dealstracker.model.CardOffer? {
    val discount = teaser.effects?.parameters
        ?.firstOrNull { it.name == "PromotionalPriceTableItemsDiscount" }?.value?.trim()?.toBigDecimalOrNull()
        ?.takeIf { it.signum() > 0 } ?: return null
    val price = base.subtract(discount).setScale(0, java.math.RoundingMode.HALF_UP).asMoney()
    if (price.signum() <= 0 || price >= shelfPrice || base.signum() <= 0) return null
    val percent = base.subtract(price).divide(base, 6, java.math.RoundingMode.HALF_UP)
        .multiply(BigDecimal(100)).setScale(2, java.math.RoundingMode.HALF_UP)
    if (percent.signum() <= 0 || percent >= BigDecimal(100)) return null
    return com.luis.dealstracker.model.CardOffer(price, percent, scope)
}

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
