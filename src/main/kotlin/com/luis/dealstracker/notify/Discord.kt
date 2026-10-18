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

    companion object {
        const val GREEN = 0x2ECC71

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
