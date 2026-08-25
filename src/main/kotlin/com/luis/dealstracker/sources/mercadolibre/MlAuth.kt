package com.luis.dealstracker.sources.mercadolibre

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

/**
 * MercadoLibre OAuth.
 *
 * Verified against the live API on 2026-08-23: `client_credentials` succeeds and,
 * unusually, returns a token already bound to the app owner's user id — so no
 * `authorization_code` redirect flow is needed for anything this tool does.
 *
 * The token lasts 6 hours and is cached in memory until shortly before expiry.
 */
class MlAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    @Volatile private var cached: Token? = null

    private data class Token(val accessToken: String, val expiresAt: Instant, val userId: Long?)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("user_id") val userId: Long? = null,
        val scope: String? = null,
        val message: String? = null,
        val error: String? = null,
    )

    class AuthException(message: String) : IllegalStateException(message)

    fun accessToken(): String {
        cached?.let { if (Instant.now().isBefore(it.expiresAt)) return it.accessToken }
        return refresh().accessToken
    }

    private fun refresh(): Token {
        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Accept", "application/json")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString<TokenResponse>(text) }.getOrNull()
            val token = parsed?.accessToken
                ?: throw AuthException(
                    "MercadoLibre token request failed (HTTP ${response.code}): " +
                        "${parsed?.message ?: text.take(200)}. " +
                        "If this says invalid_client, re-check ml.clientSecret in local.properties.",
                )

            // Refresh a minute early so a long poll can't straddle expiry.
            val expiresAt = Instant.now().plusSeconds((parsed.expiresIn ?: 21_600L) - 60)
            return Token(token, expiresAt, parsed.userId).also { cached = it }
        }
    }

    companion object {
        private const val TOKEN_URL = "https://api.mercadolibre.com/oauth/token"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
