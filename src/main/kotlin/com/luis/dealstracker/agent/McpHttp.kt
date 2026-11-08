package com.luis.dealstracker.agent

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the MCP server listens. Its own port, so the Cloudflare tunnel (which forwards 8080) never reaches it. */
const val MCP_HOST = "127.0.0.1"
const val MCP_PORT = 8091

/**
 * Why a request to the MCP endpoint is refused, or null to serve it. Four walls, each enough alone
 * for what it guards against:
 *
 *  - **not from this machine**: the socket is bound to 127.0.0.1, so this only fires if that changes;
 *  - **came through Cloudflare**: cloudflared runs on this Mac, so tunnelled traffic arrives from
 *    localhost too -- its `Cf-*` headers give it away should the tunnel ever point at this port;
 *  - **sent by a web page**: a browser always sends `Origin` on a cross-site POST, and the MCP spec asks
 *    servers to refuse foreign ones (DNS rebinding); Claude Code sends none;
 *  - **no token**: the bearer token in `.mcp-token`, compared in constant time.
 */
internal fun mcpRefusal(remoteHost: String, header: (String) -> String?, token: String): String? {
    if (remoteHost !in LOOPBACK) return "not from this machine"
    if (header("Cf-Connecting-Ip") != null || header("Cf-Ray") != null) return "came through the tunnel"
    header("Origin")?.let { o -> if (!Regex("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?$").matches(o)) return "foreign origin" }
    val presented = header("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
    if (!MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) return "bad token"
    return null
}

private val LOOPBACK = setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost")

/** The run a request belongs to, from the headers [AgentRunner] puts in its MCP config. */
internal fun mcpCaller(header: (String) -> String?): McpCaller {
    val run = header("X-Agent-Run")?.toLongOrNull()
    val agent = header("X-Agent-Name")?.takeIf { it.matches(Regex("[a-z0-9_-]{1,40}")) }
    return McpCaller(run, agent ?: if (run != null) "agent" else "manual")
}

/**
 * The token, created on first use in [file] (owner-readable only) and gitignored. Rotate it by deleting
 * the file and restarting; then re-add the server in Claude Code.
 */
fun mcpToken(file: File): String {
    file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 32 }?.let { return it }
    val token = AgentDb.randomToken(32)
    file.writeText(token + "\n")
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    return token
}

/** Starts the MCP server on [MCP_HOST]:[MCP_PORT]; separate from the UI's server so it can bind elsewhere. */
fun startMcpServer(server: McpServer, token: String): ApplicationEngine =
    embeddedServer(Netty, host = MCP_HOST, port = MCP_PORT) {
        routing {
            post("/mcp") {
                mcpRefusal(call.request.origin.remoteAddress, { call.request.header(it) }, token)?.let { why ->
                    println("[mcp] refused: $why")
                    return@post call.respondText("""{"error":"forbidden"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                val body = call.receiveText()
                val caller = mcpCaller { call.request.header(it) }
                val answer = withContext(Dispatchers.IO) { server.handle(body, caller) }
                if (answer == null) call.respond(HttpStatusCode.Accepted, "") else call.respondText(answer, ContentType.Application.Json)
            }
            // No server-to-client event stream: every answer comes back on the POST.
            get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed, "") }
        }
    }.start(wait = false)
