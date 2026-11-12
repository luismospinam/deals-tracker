package com.luis.dealstracker.notify

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Posts one embed to a Discord channel through its webhook URL -- the same mechanism
 * daily-stock-analyzer's `DiscordService` uses: a JSON `embeds` payload and no bot or login, the URL
 * being the whole credential. Create one under Channel settings > Integrations > Webhooks.
 */
class DiscordWebhook(private val url: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Whether Discord accepted it. A failure is logged and returned, never thrown: a missed post must not stop the poller. */
    fun send(title: String, description: String, footer: String, color: Int = GREEN, username: String = "Deals Tracker"): Boolean =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(title, description, footer, color, username).toString()))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            (response.statusCode() in 200..299).also {
                if (!it) println("[discord] '$title' failed: HTTP ${response.statusCode()}")
            }
        }.getOrElse {
            println("[discord] '$title' failed: ${it.message}")
            false
        }

    /**
     * Posts [embeds] as cards, [MAX_EMBEDS] to a message. True when every message was accepted; a
     * failure is logged and returned, as in [send].
     */
    fun sendEmbeds(embeds: List<DiscordEmbed>, username: String = "Deals Tracker", heading: String? = null): Boolean =
        embeds.chunked(MAX_EMBEDS).withIndex().all { (i, chunk) ->
            runCatching {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(embedsPayload(chunk, username, heading.takeIf { i == 0 }).toString()))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                (response.statusCode() in 200..299).also {
                    if (!it) println("[discord] ${chunk.size} card(s) failed: HTTP ${response.statusCode()}")
                }
            }.getOrElse {
                println("[discord] ${chunk.size} card(s) failed: ${it.message}")
                false
            }
        }

    companion object {
        const val GREEN = 0x2ECC71

        /** Discord takes at most ten embeds in one message. */
        const val MAX_EMBEDS = 10

        /** Discord rejects an embed title over 256 characters. */
        const val MAX_TITLE = 256

        /** [heading] is plain message text above the cards: what the batch is, said once. */
        internal fun embedsPayload(embeds: List<DiscordEmbed>, username: String, heading: String? = null): JsonObject =
            buildJsonObject {
                put("username", username)
                heading?.let { put("content", it) }
                put(
                    "embeds",
                    JsonArray(
                        embeds.map { e ->
                            buildJsonObject {
                                put("title", e.title.take(MAX_TITLE))
                                e.url?.let { put("url", it) }
                                put("description", e.description.take(MAX_DESCRIPTION))
                                put("color", e.color)
                                e.imageUrl?.let { put(if (e.smallImage) "thumbnail" else "image", buildJsonObject { put("url", it) }) }
                                e.footer?.let { put("footer", buildJsonObject { put("text", it) }) }
                            }
                        },
                    ),
                )
            }

        /** Discord rejects an embed description over 4096 characters. */
        const val MAX_DESCRIPTION = 4096

        internal fun payload(title: String, description: String, footer: String, color: Int, username: String): JsonObject =
            buildJsonObject {
                put("username", username)
                put(
                    "embeds",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("title", title)
                                put("description", description)
                                put("color", color)
                                put("footer", buildJsonObject { put("text", footer) })
                            },
                        ),
                    ),
                )
            }
    }
}

/** One card of a Discord message: a linked title, a line of text and, when there is one, the picture. */
data class DiscordEmbed(
    val title: String,
    val description: String,
    val url: String? = null,
    val imageUrl: String? = null,
    val footer: String? = null,
    val color: Int = DiscordWebhook.GREEN,
    /** The picture beside the text rather than full width under it: for a long list of cards. */
    val smallImage: Boolean = false,
)

/**
 * [lines] joined for an embed description, cut to [limit] characters with a closing "...and N more"
 * rather than cut mid-line or refused by Discord. Lines are kept whole and in order.
 */
fun fitDescription(lines: List<String>, limit: Int = DiscordWebhook.MAX_DESCRIPTION): String {
    val kept = mutableListOf<String>()
    var used = 0
    for ((i, line) in lines.withIndex()) {
        val left = lines.size - i
        val tail = "\n…and $left more"
        val cost = line.length + (if (kept.isEmpty()) 0 else 1)
        // Keep room for the "and N more" line unless this is the last one and it fits outright.
        val fits = if (left == 1) used + cost <= limit else used + cost + tail.length <= limit
        if (!fits) return kept.joinToString("\n") + tail
        kept += line
        used += cost
    }
    return kept.joinToString("\n")
}
