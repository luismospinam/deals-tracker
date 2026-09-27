package com.luis.dealstracker.sources.pricesmart

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.UnitSize
import com.luis.dealstracker.model.RawDeal
import com.luis.dealstracker.model.asMoney
import com.luis.dealstracker.sources.DealSource
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * PriceSmart Colombia's food catalogue, priced at one warehouse club -- the grocery tab's third chain.
 *
 * ## How the club is chosen
 *
 * PriceSmart runs its catalogue through Bloomreach Discovery, behind its own endpoint
 * `POST /api/br_discovery/getProductsByKeyword`. The club is not a cookie or a session: every field
 * that varies by club is published per club, suffixed with its id -- `price_CO_6106`,
 * `inventory_CO_6106`, `sold_by_weight_CO_6106` -- and a request asks for the suffixed fields it wants.
 * So pricing at Medellín Las Américas (6106) is asking for `_CO_6106` fields, and nothing else changes.
 * Measured 2026-09-27: 16 of 144 Alimentos items differed in price between Las Américas and El Poblado
 * (6110), so the club is not cosmetic.
 *
 * The request carries the site's own public Bloomreach identifiers (account 7024, domain key and a
 * search-only auth key), the same values every visitor's browser sends; they are config, not secrets.
 *
 * ## Money is in two units
 *
 * `price_CO_<club>` is in CENTS -- `4850000` is $48,500 -- while the instant-saving fields are in PESOS:
 * Silk almond milk reads price 4,850,000, saving -11,400, original 59,900, i.e. 48,500 + 11,400 =
 * 59,900. Mixing the two is a factor-of-100 error either way; [pesos] is the one conversion.
 *
 * ## Weighed items
 *
 * Meat trays are sold by weight: `price_CO_<club>` is the tray's price, `sign_price_CO_<club>` the
 * per-kg shelf price and `weight_CO_<club>` the tray weight in kg -- checked on 35 trays, price = sign
 * price x weight every time. The tray price is the one stored, as for any other pack; the weight is
 * its pack size, so the unit price comes out per 100 g like everywhere else.
 *
 * ## robots.txt
 *
 * PriceSmart's robots.txt allows `/api/` for `User-agent: *`, disallowing only cart, checkout, account
 * and `?fq=` paths -- this adapter sends none of those and identifies as `deals-tracker`. The same file
 * blocks named AI agents (ClaudeBot, Claude-User), `curl` and `python-requests` outright. Luis chose to
 * proceed on 2026-09-27 knowing that; see README "PriceSmart".
 */
class PriceSmartSource : DealSource {
    override val type = "pricesmart"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        val base = cfg.requireBaseUrl().trimEnd('/')
        val club = requireNotNull(cfg.options["club"]?.takeIf { it.isNotBlank() }) {
            "provider '${cfg.id}' needs options.club -- the PriceSmart club id to price at, e.g. 6106"
        }
        val categories = cfg.list("categories").mapNotNull { entry ->
            val id = entry.substringBefore('=').trim()
            if (id.isEmpty()) null else id to entry.substringAfter('=', "").trim().ifEmpty { id }
        }
        if (categories.isEmpty()) {
            println("[${cfg.id}] no categories configured; nothing to fetch")
            return emptyList()
        }
        val rows = cfg.int("pageSize", DEFAULT_ROWS).coerceIn(1, 200)
        val maxPages = cfg.int("maxPages", 60)

        // Aisle by aisle, as the VTEX grocery crawl does: a category page's docs do not say which child
        // they sit in, so the aisle is known from the query rather than read off each product. One
        // configured entry per PriceSmart subcategory, with the label the grocery tab filters by.
        val all = LinkedHashMap<String, RawDeal>()
        for ((category, aisle) in categories) {
            var start = 0
            var total = 0
            var pages = 0
            while (pages < maxPages) {
                val page = readPage(cfg, base, club, category, start, rows, http) ?: break
                total = page.total
                page.docs.forEach { doc -> toRawDeal(doc, base, club, cfg.currency, aisle)?.let { all.putIfAbsent(it.externalId, it) } }
                pages++
                start += rows
                if (page.docs.size < rows || start >= total) break
            }
            println("[${cfg.id}] $category ($aisle) -> $total products in $pages page(s)")
        }

        val available = all.values.filter { it.available }
        println("[${cfg.id}] ${all.size} products priced at club $club, ${available.size} in stock")
        return available
    }

    internal data class Page(val total: Int, val docs: List<JsonObject>)

    private suspend fun readPage(
        cfg: ProviderConfig,
        base: String,
        club: String,
        category: String,
        start: Int,
        rows: Int,
        http: PoliteHttp,
    ): Page? {
        val body = requestBody(cfg, base, club, category, start, rows)
        val result = try {
            http.postJson(
                "$base/api/br_discovery/getProductsByKeyword",
                body,
                mapOf(
                    "Accept" to "application/json",
                    // The site's own API answers only its own origin; these say where the request is from,
                    // as the browser would, and nothing more.
                    "Origin" to base,
                    "Referer" to categoryUrl(cfg, base, category),
                ),
            )
        } catch (e: Exception) {
            println("[${cfg.id}] $category start=$start failed: ${e.message}")
            return null
        }
        val text = result.body
        if (text == null || !result.ok) {
            println("[${cfg.id}] $category start=$start -> HTTP ${result.status}")
            return null
        }
        return parsePage(text) ?: run {
            println("[${cfg.id}] $category start=$start unparseable")
            null
        }
    }

    internal fun parsePage(text: String): Page? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val response = root["response"]!!.jsonObject
        Page(
            total = response["numFound"]!!.jsonPrimitive.content.toInt(),
            docs = response["docs"]!!.jsonArray.map { it.jsonObject },
        )
    }.getOrNull()

    /**
     * The request the category page's own script builds: the fields it asks for, suffixed per club.
     *
     * Fields ending `_` get `CO_<club>` appended (`price_per_uom_` -> `price_per_uom_CO_6106`), the per-club
     * list gets the club suffix, and the per-country list gets `_CO` -- the same rule as the site's
     * `useProduct` composable, so the response carries exactly the club's values.
     */
    internal fun requestBody(cfg: ProviderConfig, base: String, club: String, category: String, start: Int, rows: Int): String {
        val clubSuffix = "CO_$club"
        val fields = FIELDS.map { if (it.endsWith("_")) it + clubSuffix else it } +
            CLUB_FIELDS.map { "${it}_$clubSuffix" } +
            COUNTRY_FIELDS.map { "${it}_CO" }
        return buildJsonObject {
            put("q", category)
            put("rows", rows)
            put("start", start)
            put("fl", fields.joinToString(","))
            put("view_id", cfg.options["viewId"] ?: "CO")
            put("account_id", cfg.options["accountId"] ?: "7024")
            put("auth_key", cfg.options["authKey"] ?: "ev7libhybjg5h1d1")
            put("domain_key", cfg.options["domainKey"] ?: "pricesmart_bloomreach_io_es")
            put("request_id", System.currentTimeMillis())
            put("_br_uid_2", "deals-tracker")
            put("request_type", "search")
            put("search_type", "category")
            put("url", categoryUrl(cfg, base, category))
        }.toString()
    }

    private fun categoryUrl(cfg: ProviderConfig, base: String, category: String) =
        "$base/${cfg.options["locale"] ?: "es-co"}/categoria/$category/$category"

    /** A price field in cents to pesos. */
    private fun pesos(cents: BigDecimal): BigDecimal = cents.movePointLeft(2).asMoney()

    internal fun toRawDeal(
        doc: JsonObject,
        base: String,
        club: String,
        currency: String,
        aisle: String,
    ): RawDeal? {
        val suffix = "CO_$club"
        val pid = doc.str("pid") ?: return null
        val title = doc.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A product with no price at THIS club is not sold here. The national `price_CO` is not a
        // stand-in: it is a different number, and a row priced from it would be the national-catalogue
        // mistake the Exito and Carulla adapters exist to avoid.
        val cents = doc.num("price_$suffix")?.takeIf { it.signum() > 0 } ?: return null
        val price = pesos(cents)

        // Instant savings are in pesos (see the class comment). The original is the pre-saving price,
        // so it is this row's list price -- a claim by the store, like any list price, shown as Store %.
        val original = doc.num("original_price_without_saving_$suffix")?.takeIf { it > price }?.asMoney()

        val inStock = doc.str("inventory_$suffix") == "in stock" && doc.str("availability_$suffix") != "false"

        val weighed = doc.str("sold_by_weight_$suffix") == "true"
        val weight = doc.num("weight_$suffix")?.takeIf { it.signum() > 0 }
        val weightUnit = doc.str("weight_uom_description_$suffix")?.lowercase()
        // A weighed tray's own weight is its pack size. For fixed packs the title states the size
        // ("Arándanos 508 g / 1.12 lb"), and UnitSize reads the LAST size, which here is the imperial one
        // -- so the metric size is read first, from the part before " / ".
        val size = if (weighed && weight != null && weightUnit == "kg") {
            UnitSize(UnitSize.GRAMS, weight.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP))
        } else {
            UnitSize.of(title.substringBefore(" / "), null, null) ?: UnitSize.of(title, null, null)
        }

        val slug = doc.str("slug")
        return RawDeal(
            externalId = pid,
            title = title,
            url = if (slug != null) "$base/es-co/producto/$slug/$pid" else "$base/es-co",
            price = price,
            currency = doc.str("currency") ?: currency,
            listPrice = original,
            merchant = "pricesmart.com",
            brand = doc.str("brand")?.takeIf { it.isNotBlank() },
            category = "Alimentos > $aisle",
            imageUrl = doc.str("thumb_image"),
            available = inStock,
            unitMeasure = size?.measure,
            unitQuantity = size?.quantity,
            groceryGroup = aisle,
        )
    }

    companion object {
        /** Bloomreach's documented page limit is 200; 48 is what the site itself asks for. */
        internal const val DEFAULT_ROWS = 48

        /** `brDiscoveryFlParam`, as the site's config publishes it (2026-09-27). */
        private val FIELDS = listOf(
            "pid", "title", "price", "thumb_image", "brand", "slug", "skuid", "currency", "fractionDigits", "master_sku",
            "sold_by_weight_", "weight_", "weight_uom_description_", "sign_price_", "price_per_uom_", "uom_description_",
            "saving_amount_", "original_price_without_saving_",
        )

        /** `brDiscoveryCountryClubParams`. */
        private val CLUB_FIELDS = listOf("availability", "price", "inventory")

        /** `brDiscoveryCountryParams`. */
        private val COUNTRY_FIELDS = listOf("inventory", "promoid")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

        private fun JsonObject.num(key: String): BigDecimal? = str(key)?.toBigDecimalOrNull()
    }
}
