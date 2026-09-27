package com.luis.dealstracker.sources.vtex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One announcement-bar config from a store's homepage, e.g. "30% con Mastercard en frutas y verduras
 * 6:00 am a 11:59 am". See `site_banners` in V18 for why a config is not proof it is on screen.
 */
data class SiteBanner(
    val text: String,
    val shown: Boolean?,
    val statedFrom: LocalDateTime?,
    val statedTo: LocalDateTime?,
    val link: String?,
)

/**
 * Reads the `SuperBar` blocks VTEX's store framework embeds in the homepage HTML.
 *
 * Plain HTML, no JavaScript: the configs are serialised into the page as JSON objects of the form
 * `{"name":"SuperBar","data":{... "textDesktop": ..., "initialDate": "Sep 19, 2025 6:0 AM" ...}}`.
 * The page is the storefront root, which both stores' robots.txt allow.
 */
internal object SiteBanners {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `"name":"SuperBar","data":{...}` -- the data object is flat, so the first `}` closes it. */
    private val BLOCK = Regex(""""name"\s*:\s*"SuperBar"\s*,\s*"data"\s*:\s*(\{[^{}]*\})""")

    /** "Sep 19, 2025 6:0 AM" -- minutes are not zero-padded, which is why this is not a stock pattern. */
    private val STATED = DateTimeFormatter.ofPattern("MMM d, yyyy h:m a", Locale.ENGLISH)

    fun parse(html: String): List<SiteBanner> =
        BLOCK.findAll(html).mapNotNull { m ->
            val data = runCatching { json.parseToJsonElement(m.groupValues[1]) as? JsonObject }.getOrNull()
                ?: return@mapNotNull null
            fun str(key: String) = data[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val text = (str("textDesktop") ?: str("textMobile"))?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
            SiteBanner(
                text = text,
                shown = data["showComponent"]?.jsonPrimitive?.booleanOrNull,
                statedFrom = str("initialDate")?.let(::stated),
                statedTo = str("endDate")?.let(::stated),
                link = str("buttonLink"),
            )
        }.distinctBy { it.text }.toList()

    private fun stated(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s, STATED) }.getOrNull()
}
