package com.luis.dealstracker.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    private val echo = McpTool("echo", "Says back what it is given, for the test.", listOf(Param("text", "string", "what to say", required = true))) { a, c ->
        "${a.require("text")} from ${c.agent}"
    }
    private val server = McpServer(listOf(echo))

    @Test
    fun `initialize agrees a protocol version and names the server`() {
        val out = server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""", McpCaller())!!
        assertTrue(out.contains("\"protocolVersion\":\"2025-03-26\"") && out.contains("deals-tracker"), out)
        val unknown = server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""", McpCaller())!!
        assertTrue(unknown.contains(McpServer.PROTOCOL_VERSIONS.first()))
    }

    @Test
    fun `tools are listed with their schema and read-only hint`() {
        val out = server.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", McpCaller())!!
        assertTrue(out.contains("\"name\":\"echo\"") && out.contains("\"required\":[\"text\"]") && out.contains("\"readOnlyHint\":true"), out)
    }

    @Test
    fun `a call answers as text, a bad argument as a tool error the model can fix`() {
        val ok = server.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""", McpCaller(4, "grocery"))!!
        assertTrue(ok.contains("hi from grocery") && ok.contains("\"isError\":false"), ok)
        val bad = server.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{}}}""", McpCaller())!!
        assertTrue(bad.contains("`text` is required") && bad.contains("\"isError\":true"), bad)
        assertTrue(server.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""", McpCaller())!!.contains("-32602"))
    }

    @Test
    fun `each tool call is logged with who called it, the arguments and the answer's size`() {
        val lines = mutableListOf<String>()
        McpServer(listOf(echo), log = { lines += it }).handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""", McpCaller(4, "grocery"))
        assertTrue(lines.single().startsWith("[mcp] grocery#4 echo {\"text\":\"hi\"} -> 15 chars"), lines.single())
    }

    @Test
    fun `notifications get no answer, garbage gets a parse error`() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", McpCaller()))
        assertTrue(server.handle("not json", McpCaller())!!.contains("-32700"))
        assertTrue(server.handle("""{"jsonrpc":"2.0","id":9,"method":"resources/list"}""", McpCaller())!!.contains("-32601"))
    }

    @Test
    fun `only this machine, never through the tunnel or from a web page, and only with the token`() {
        val token = "t".repeat(43)
        fun refuse(remote: String = "127.0.0.1", vararg headers: Pair<String, String>) =
            mcpRefusal(remote, { h -> headers.toMap()[h] }, token)
        val auth = "Authorization" to "Bearer $token"
        assertNull(refuse("127.0.0.1", auth))
        assertNull(refuse("0:0:0:0:0:0:0:1", auth))
        assertEquals("not from this machine", refuse("192.168.31.20", auth))
        assertEquals("came through the tunnel", refuse("127.0.0.1", auth, "Cf-Connecting-Ip" to "1.2.3.4"))
        assertEquals("foreign origin", refuse("127.0.0.1", auth, "Origin" to "https://evil.example"))
        assertNull(refuse("127.0.0.1", auth, "Origin" to "http://localhost:6274"), "a local inspector is fine")
        assertEquals("bad token", refuse("127.0.0.1"))
        assertEquals("bad token", refuse("127.0.0.1", "Authorization" to "Bearer wrong"))
    }

    @Test
    fun `the caller is the run the runner named, else a person`() {
        assertEquals(McpCaller(12, "grocery"), mcpCaller { mapOf("X-Agent-Run" to "12", "X-Agent-Name" to "grocery")[it] })
        assertEquals(McpCaller(null, "manual"), mcpCaller { null })
        assertEquals("agent", mcpCaller { mapOf("X-Agent-Run" to "3", "X-Agent-Name" to "Bad Name!")[it] }.agent)
    }

    @Test
    fun `the token is made once, kept owner-only, and reused`() {
        val dir = Files.createTempDirectory("mcp").toFile()
        try {
            val f = File(dir, ".mcp-token")
            val t = mcpToken(f)
            assertTrue(t.length >= 40)
            assertEquals(t, mcpToken(f))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
            f.writeText("short")
            assertFalse(mcpToken(f) == "short", "a truncated token is replaced, not trusted")
        } finally {
            dir.deleteRecursively()
        }
    }
}
