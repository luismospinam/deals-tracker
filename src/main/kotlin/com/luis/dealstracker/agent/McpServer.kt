package com.luis.dealstracker.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Who is calling a tool: an agent run (its id, from the runner's header) or a person in Claude Code. */
data class McpCaller(val runId: Long? = null, val agent: String = "manual")

/** A tool's arguments, read leniently: the model sends "40" as often as 40. */
class ToolArgs(private val o: JsonObject) {
    fun str(k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    fun long(k: String): Long? = (o[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }
    fun int(k: String, default: Int, range: IntRange): Int =
        ((o[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() } ?: default).coerceIn(range)
    fun bool(k: String): Boolean = (o[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
    fun require(k: String): String = str(k) ?: throw ToolError("`$k` is required")
}

/** A mistake in the call, reported back to the model as a tool error it can correct, not a crash. */
class ToolError(message: String) : Exception(message)

/** One property of a tool's input. */
data class Param(val name: String, val type: String, val description: String, val required: Boolean = false, val enum: List<String>? = null)

class McpTool(
    val name: String,
    val description: String,
    val params: List<Param>,
    /** Whether the tool changes anything. Only `suggest` does; the rest only read. */
    val writes: Boolean = false,
    val call: (ToolArgs, McpCaller) -> String,
) {
    fun schema(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                params.forEach { p ->
                    putJsonObject(p.name) {
                        put("type", p.type)
                        put("description", p.description)
                        p.enum?.let { e -> put("enum", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
                    }
                }
            }
            put("required", buildJsonArray { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
        }
        putJsonObject("annotations") {
            put("readOnlyHint", !writes)
        }
    }
}

/**
 * The Model Context Protocol, the part this app needs: `initialize`, `tools/list` and `tools/call`,
 * answered as plain JSON over HTTP (the "Streamable HTTP" transport, without the optional event
 * stream). Pure: a request in, a response out, so it is tested without a server.
 */
class McpServer(
    private val tools: List<McpTool>,
    private val serverName: String = "deals-tracker",
    private val version: String = "1",
    /** Each tool call, for the app log: who called what, and how big the answer was. */
    private val log: (String) -> Unit = {},
) {
    private val byName = tools.associateBy { it.name }

    /** The JSON-RPC response to [body], or null for a notification, which gets none. */
    fun handle(body: String, caller: McpCaller): String? {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return error(JsonNull, -32700, "parse error")
        val id = msg["id"]
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull ?: return error(id ?: JsonNull, -32600, "no method")
        if (id == null || id is JsonNull) return null
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
                put("protocolVersion", asked?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                putJsonObject("serverInfo") { put("name", serverName); put("version", version) }
                put("instructions", INSTRUCTIONS)
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", buildJsonArray { tools.forEach { add(it.schema()) } }) })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull
                val tool = name?.let(byName::get) ?: return error(id, -32602, "unknown tool: $name")
                val args = ToolArgs(params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
                val started = System.currentTimeMillis()
                val (text, isError) = try {
                    tool.call(args, caller) to false
                } catch (e: ToolError) {
                    (e.message ?: "invalid arguments") to true
                } catch (e: Exception) {
                    // A SQL error is the model's to fix (a wrong column, a timeout), so it gets the message.
                    "${e::class.simpleName}: ${e.message?.take(400)}" to true
                }
                log(toolCallLine(caller, name, params["arguments"], text.length, isError, System.currentTimeMillis() - started))
                result(id, buildJsonObject {
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                    put("isError", isError)
                })
            }
            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun result(id: JsonElement, r: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", r)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        /** Newest first; the first is offered to a client asking for one this server does not know. */
        val PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        const val INSTRUCTIONS =
            "Deals Tracker: the operator's local price monitor for Colombian groceries (Exito, Carulla, PriceSmart) " +
                "and online stores. Prices are COP unless a row says otherwise. Use the grocery_* tools first; they " +
                "already apply unit sizes, usual prices and cross-chain matching. Use sql only for what they do not answer. " +
                "Every tool reads, except suggest, which only adds an idea for the operator to accept or dismiss."
    }
}

/** "[mcp] grocery#3 product {"id":713320} -> 1207 chars, 85 ms": the audit line for one tool call. */
internal fun toolCallLine(caller: McpCaller, tool: String, args: JsonElement?, chars: Int, isError: Boolean, ms: Long): String {
    val who = caller.agent + (caller.runId?.let { "#$it" } ?: "")
    val a = args?.toString()?.takeIf { it != "{}" }?.let { " " + it.take(160) }.orEmpty()
    return "[mcp] $who $tool$a -> ${if (isError) "ERROR " else ""}$chars chars, $ms ms"
}
