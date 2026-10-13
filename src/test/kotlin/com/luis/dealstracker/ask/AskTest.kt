package com.luis.dealstracker.ask

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskTest {

    private val now: Instant = Instant.parse("2026-09-23T12:00:00Z")

    private fun deal(
        id: Long = 1,
        title: String = "Soporte De Pared Para TV 32-75 Doble Brazo Articulado",
        price: String = "75500.00",
        listPrice: String? = "145000.00",
        currency: String = "COP",
        co: CoShipping = CoShipping.YES,
        coCost: String? = null,
        isActive: Boolean = true,
        observations: Int = 3,
    ) = Deal(
        id = id, source = "exito", sourceDealId = "x-$id", title = title, url = "https://example.com/$id",
        merchant = null, asin = null, category = null, imageUrl = null, brand = "NIA", gtin = null,
        modelCode = null, matchKey = null, modelKey = null, currency = currency, firstSeenAt = now,
        lastSeenAt = now, isActive = isActive, state = DealState.NEW, coShipping = co, promoted = true,
        promotedAt = now, communityScore = null, postedAt = null, categoryGroup = "TV & Video",
        coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
        coShippingCost = coCost?.let(::BigDecimal), price = BigDecimal(price),
        listPrice = listPrice?.let(::BigDecimal), minPriceEver = BigDecimal(price),
        maxPriceEver = BigDecimal("145000.00"), observationCount = observations,
    )

    private fun ctx(vararg deals: Deal, omitted: Int = 0) = AskContext("test", emptyList(), deals.toList(), omitted)

    private fun rowOf(d: Deal): List<String> = ctx(d).rowsTable().lines()[1].split("|")

    private fun col(name: String): Int = ROW_HEADER.split("|").indexOf(name)

    @Test
    fun `every row has exactly the header's columns`() {
        // A title containing the separator must not shift every later cell one column right --
        // the model would then read the brand as the currency and the list price as the discount.
        val table = ctx(deal(title = "Soporte | Brazo | 55\""), deal(id = 2)).rowsTable().trim().lines()
        val width = ROW_HEADER.split("|").size
        table.forEach { assertEquals(width, it.split("|").size, "misaligned row: $it") }
    }

    @Test
    fun `a missing list price is blank, never zero`() {
        // The app's first rule: no list price means the discount is UNKNOWN. A 0 here would read
        // as "not discounted", which is a different and false claim.
        val row = rowOf(deal(listPrice = null))
        assertEquals("", row[col("list")])
        assertEquals("", row[col("off%")])
    }

    @Test
    fun `COP prices carry no fake cents, USD keeps them`() {
        assertEquals("75500", rowOf(deal())[col("price")])
        assertEquals("32.89", rowOf(deal(price = "32.89", listPrice = null, currency = "USD"))[col("price")])
    }

    @Test
    fun `shipping states the fee, and an expired offer says so`() {
        assertEquals("ships +60.66", rowOf(deal(coCost = "60.66"))[col("ship")])
        assertEquals("ships free", rowOf(deal(coCost = "0"))[col("ship")])
        assertEquals("?", rowOf(deal(co = CoShipping.UNKNOWN))[col("ship")])
        assertEquals("expired", rowOf(deal(isActive = false))[col("status")])
    }

    @Test
    fun `the prompt says when rows were cut`() {
        assertTrue(ctx(deal(), omitted = 40).firstPrompt("best?").contains("1 of 41 matching"))
        assertTrue(ctx(deal()).firstPrompt("best?").contains("all 1 matching"))
    }

    @Test
    fun `history lists only rows whose price moved, oldest first, and says how many were checked`() {
        val moved = deal(id = 1)
        val flat = deal(id = 2)
        val d = java.time.LocalDate.of(2026, 9, 1)
        val c = ctx(moved, flat).withHistory(
            mapOf(
                1L to listOf(d to java.math.BigDecimal("2199000"), d.plusDays(20) to java.math.BigDecimal("1899000")),
                2L to listOf(d to java.math.BigDecimal("500000")),
            ),
            checked = 2,
        )
        assertEquals("1|2026-09-01 2199000 2026-09-21 1899000", c.historyBlock().trim())
        val prompt = c.firstPrompt("q")
        assertTrue("first 2 rows were checked" in prompt)
        assertTrue(prompt.indexOf("Price history") < prompt.indexOf("Question:"))
    }

    @Test
    fun `no history means no history section`() {
        assertFalse("Price history" in ctx(deal(id = 1)).firstPrompt("q"))
    }

    @Test
    fun `the question comes last, after the rows`() {
        val prompt = ctx(deal()).firstPrompt("which is best for a 55 inch TV?")
        assertTrue(prompt.trimEnd().endsWith("Question: which is best for a 55 inch TV?"))
        assertTrue(prompt.indexOf(ROW_HEADER) < prompt.indexOf("Question:"))
    }

    @Test
    fun `mentioned products are found however the id was written, in order, once each`() {
        val c = ctx(deal(id = 430525), deal(id = 168866), deal(id = 157941))
        val answer = "Best is Perlegear [#430525]. Then #168866, and id 157941. Again [#430525]."
        assertEquals(listOf(430525L, 168866L, 157941L), c.mentionedDeals(answer).map { it.id })
    }

    @Test
    fun `a number that is not a sent row never becomes a link`() {
        // "#55" is a screen size and 75500 a price; neither is a row on screen.
        val c = ctx(deal(id = 168866))
        assertTrue(c.mentionedDeals("Fits a #55 TV, costs 75500, see [#999999].").isEmpty())
    }

    // --- conversations ---

    private fun service() = AskService(fakeCli(File("/bin/echo")), maxRows = 200)

    @Test
    fun `a follow-up on the same screen resumes`() {
        val s = service()
        val (id, resume) = s.sessionFor(null, "watch:4?")
        assertFalse(resume)
        s.record(id, "watch:4?")
        assertEquals(id to true, s.sessionFor(id, "watch:4?"))
    }

    @Test
    fun `a follow-up after the filters changed starts over`() {
        // Resuming would answer about the rows the session was first given, while the page now
        // shows different ones.
        val s = service()
        val (id, _) = s.sessionFor(null, "feed?q=tv")
        s.record(id, "feed?q=tv")
        val (next, resume) = s.sessionFor(id, "feed?q=nevera")
        assertFalse(resume)
        assertTrue(next != id)
    }

    @Test
    fun `an unknown or malformed conversation id starts over rather than failing`() {
        val s = service()
        assertFalse(s.sessionFor("not-a-uuid", "feed?").second)
        assertFalse(s.sessionFor("00000000-0000-0000-0000-000000000000", "feed?").second)
    }

    @Test
    fun `a long conversation starts over, since resuming it costs more than a fresh one`() {
        val s = service()
        val (id, _) = s.sessionFor(null, "feed?")
        repeat(AskService.MAX_TURNS) { s.record(id, "feed?") }
        assertFalse(s.sessionFor(id, "feed?").second)
    }

    // --- the CLI wrapper, against a fake `claude` ---

    private fun fakeCli(exe: File) = ClaudeCli(
        executable = exe.absolutePath,
        workDir = Files.createTempDirectory("ask-test").toFile(),
        model = "sonnet", effort = "low", maxBudgetUsd = 0.1, systemPrompt = "test",
    )

    /** A stand-in for `claude -p` that prints canned stream-json and records its argv and stdin. */
    private fun fakeClaude(stdout: String, exit: Int = 0): Pair<File, File> {
        val dir = Files.createTempDirectory("fake-claude").toFile()
        val out = File(dir, "out.jsonl").apply { writeText(stdout) }
        val log = File(dir, "log")
        val script = File(dir, "claude").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"$@\" > '${log.path}.args'\ncat > '${log.path}.stdin'\ncat '${out.path}'\nexit $exit\n")
            setExecutable(true)
        }
        return script to log
    }

    private val sid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `streams text deltas and reads the cost`() {
        val (exe, _) = fakeClaude(
            """
            {"type":"system","subtype":"init"}
            {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hmm"}}}
            {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Best: "}}}
            {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"[#168866]"}}}
            {"type":"result","subtype":"success","is_error":false,"result":"Best: [#168866]","total_cost_usd":0.0354}
            """.trimIndent(),
        )
        val parts = mutableListOf<String>()
        val outcome = fakeCli(exe).run("q", sid, resume = false) { parts += it }
        // Thinking is not the answer and must not leak into the panel.
        assertEquals(listOf("Best: ", "[#168866]"), parts)
        assertNull(outcome.error)
        assertEquals(0.0354, outcome.costUsd)
    }

    @Test
    fun `the question goes over stdin, and the flags that bound cost and tools are always passed`() {
        val (exe, log) = fakeClaude("""{"type":"result","subtype":"success","is_error":false,"result":"ok"}""")
        fakeCli(exe).run("--dangerous \"question\"", sid, resume = true) {}
        val args = File("${log.path}.args").readLines()
        assertEquals("--dangerous \"question\"", File("${log.path}.stdin").readText())
        assertFalse(args.any { it.contains("dangerous") }, "question leaked into argv: $args")
        // `--tools ""` is what keeps a seller-written title from steering the model into a command.
        assertEquals("", args[args.indexOf("--tools") + 1])
        assertTrue("--safe-mode" in args)
        assertTrue("--max-budget-usd" in args)
        assertEquals(sid, args[args.indexOf("--resume") + 1])
    }

    @Test
    fun `a CLI error is reported, not swallowed`() {
        val (exe, _) = fakeClaude(
            """{"type":"result","subtype":"error_max_budget_usd","is_error":true,"result":""}""",
            exit = 1,
        )
        val outcome = fakeCli(exe).run("q", sid, resume = false) {}
        assertEquals("error_max_budget_usd", outcome.error)
    }

    @Test
    fun `a missing executable is an error the panel can show`() {
        val outcome = fakeCli(File("/nonexistent/claude")).run("q", sid, resume = false) {}
        assertTrue(outcome.error!!.contains("could not start"))
    }
}
