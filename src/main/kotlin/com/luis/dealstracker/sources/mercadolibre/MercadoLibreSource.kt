package com.luis.dealstracker.sources.mercadolibre

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
 * MercadoLibre adapter.
 *
 * ## Status: blocked by MercadoLibre, not by this code
 *
 * Verified live on 2026-08-23 with a valid app token obtained via
 * [MlAuth.accessToken] (see that class — auth itself works fine):
 *
 * | Endpoint                      | Result                        |
 * |-------------------------------|-------------------------------|
 * | `/users/me`                   | 200                           |
 * | `/categories/{id}`            | 200                           |
 * | `/sites/{site}/search`        | 403 PolicyAgent               |
 * | `/products/search`            | 403 PolicyAgent               |
 * | `/highlights/{site}/category` | 403 PolicyAgent               |
 * | `/sites`, `/currencies/{id}`  | 403 PolicyAgent               |
 *
 * Every search and browse endpoint returns
 * `PA_UNAUTHORIZED_RESULT_FROM_POLICIES`. Registering an app is not sufficient —
 * MercadoLibre gates catalog search behind separate approval. Category *metadata*
 * is readable, but metadata alone cannot produce deals.
 *
 * So this provider is disabled in config. The two ways forward are a policy
 * decision rather than an implementation detail (request search access from ML, or
 * read the server-rendered `/ofertas` pages instead), so the listing strategy is
 * deliberately left unimplemented rather than guessed at.
 */
class MercadoLibreSource(private val authFactory: (String, String) -> MlAuth = ::MlAuth) : DealSource {
    override val type = "mercadolibre"

    override suspend fun fetch(cfg: ProviderConfig, app: AppConfig, http: PoliteHttp): List<RawDeal> {
        // From gitignored local.properties, so no app credentials sit in a committed file.
        val clientId = app.mlClientId ?: cfg.options["clientId"]
            ?: error("provider '${cfg.id}' needs ml.clientId in local.properties")
        val secret = app.mlClientSecret
            ?: error("provider '${cfg.id}' needs ml.clientSecret in local.properties")
        val siteId = cfg.siteId ?: error("provider '${cfg.id}' needs siteId (e.g. MCO)")

        val auth = authFactory(clientId, secret)
        // Fail fast and loudly on auth, so a credential problem is never mistaken
        // for "the search endpoint is blocked".
        val token = auth.accessToken()

        val categories = cfg.list("categories")
        val results = mutableListOf<RawDeal>()

        for (category in categories) {
            val url = "https://api.mercadolibre.com/sites/$siteId/search" +
                "?category=$category&limit=${cfg.int("pageSize", 50)}"
            val result = http.get(
                url,
                mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            )

            if (result.status == 403) {
                error(
                    "MercadoLibre search is policy-blocked for this app (HTTP 403 " +
                        "PA_UNAUTHORIZED_RESULT_FROM_POLICIES). Auth is working — /users/me " +
                        "and /categories/{id} both return 200 with the same token — but " +
                        "MercadoLibre gates /sites/$siteId/search behind separate approval. " +
                        "Either request search access for app $clientId, or switch this " +
                        "provider to read the server-rendered /ofertas pages instead.",
                )
            }
            val body = result.body ?: continue
            val page = runCatching { json.decodeFromString<MlSearchResponse>(body) }.getOrNull() ?: continue
            page.results.mapNotNullTo(results) { it.toRawDeal(cfg.currency) }
        }

        return results
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

@Serializable
data class MlSearchResponse(val results: List<MlItem> = emptyList())

@Serializable
data class MlItem(
    val id: String,
    val title: String = "",
    val permalink: String? = null,
    val price: Double? = null,
    @SerialName("original_price") val originalPrice: Double? = null,
    @SerialName("available_quantity") val availableQuantity: Int? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val thumbnail: String? = null,
) {
    fun toRawDeal(currency: String): RawDeal? {
        val p = price?.takeIf { it > 0 } ?: return null
        val salePrice = BigDecimal.valueOf(p).asMoney()
        return RawDeal(
            externalId = id,
            title = title.ifBlank { return null },
            url = permalink ?: "https://articulo.mercadolibre.com.co/$id",
            price = salePrice,
            currency = currency,
            listPrice = originalPrice?.let { BigDecimal.valueOf(it).asMoney() }?.takeIf { it > salePrice },
            merchant = "mercadolibre",
            category = categoryId,
            imageUrl = thumbnail,
            available = (availableQuantity ?: 0) > 0,
        )
    }
}
