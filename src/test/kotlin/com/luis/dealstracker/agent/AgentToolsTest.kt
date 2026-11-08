package com.luis.dealstracker.agent

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.DbConfig
import com.luis.dealstracker.config.Defaults
import com.luis.dealstracker.db.ChainRival
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.ingest.UnitSize
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolsTest {
    private val zone = ZoneId.of("America/Bogota")
    private val today = LocalDate.parse("2026-11-03")
    private fun day(s: String) = LocalDate.parse(s)
    private fun at(d: String) = day(d).atTime(12, 0).atZone(zone).toInstant()
    private fun bought(d: String, q: Int = 1) = Purchase(1, "exito-grocery", "p1", "Arroz Diana 5 kg", q, BigDecimal("21900"), "COP", at(d))

    private fun deal(id: Long, title: String, price: String) = Deal(
        id = id, source = "exito-grocery", sourceDealId = "$id", title = title, url = "https://x/$id",
        merchant = "exito", asin = null, category = null, imageUrl = null, brand = "Diana", gtin = null,
        modelCode = null, matchKey = null, modelKey = null, currency = "COP",
        firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
        state = DealState.NEW, coShipping = CoShipping.YES, promoted = false, promotedAt = null,
        communityScore = null, postedAt = null, categoryGroup = null,
        coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null, coShippingCost = null,
        price = BigDecimal(price), listPrice = null, minPriceEver = null, maxPriceEver = null, observationCount = 5,
    )

    private fun row(id: Long, price: String, usual: String?, size: UnitSize? = null, pinned: Boolean = false) = GroceryRow(
        deal = deal(id, "Arroz\tDiana $id", price), size = size, group = "Arroz", pinnedAt = if (pinned) Instant.EPOCH else null,
        usual = usual?.let(::BigDecimal), low = usual?.let(::BigDecimal), days = 10, onShelf = true, store = "Exito Laureles",
    )

    @Test
    fun `cadence is the median gap between shopping days, and due once that long has passed`() {
        val c = cadence(listOf(bought("2026-10-01"), bought("2026-10-08", 2), bought("2026-10-08"), bought("2026-10-22")), today, zone)!!
        assertEquals(3, c.times, "two adds on the same day are one purchase")
        assertEquals(5, c.units)
        assertEquals(14, c.medianDaysBetween, "gaps 7 and 14: the upper median")
        assertEquals(12, c.daysSinceLast)
        assertEquals(false, c.due)
        assertNull(cadence(listOf(bought("2026-10-30")), today, zone)!!.due, "one purchase: no rhythm yet")
        assertNull(cadence(emptyList(), today, zone))
    }

    @Test
    fun `history is reported as the days the price changed, plus today`() {
        fun p(d: String, v: String, c: String? = null) = DayPrice(day(d), BigDecimal(v), c?.let(::BigDecimal))
        val series = listOf(p("2026-10-03", "100"), p("2026-10-01", "100"), p("2026-10-02", "100"), p("2026-10-04", "90"), p("2026-10-05", "90", "80"), p("2026-10-06", "90", "80"))
        assertEquals(listOf("2026-10-01", "2026-10-04", "2026-10-05", "2026-10-06"), changePoints(series).map { it.day.toString() })
        assertTrue(changePoints(emptyList()).isEmpty())
        val r = range(series)!!
        assertEquals(BigDecimal("90"), r.low)
        assertEquals(day("2026-10-06"), r.lowOn, "the most recent day at the low")
        assertEquals(BigDecimal("100"), r.high)
    }

    @Test
    fun `a grocery line carries unit price, usual, the other chain and no stray tabs`() {
        val r = row(7, "21900", "25000", UnitSize("g", BigDecimal(5000)), pinned = true)
            .copy(rival = ChainRival("Carulla", "carulla", BigDecimal("20500"), "COP", 99))
        val cols = groceryLine(r).split("\t")
        assertEquals(GROCERY_HEADER.split("\t").size, cols.size)
        val byName = GROCERY_HEADER.split("\t").zip(cols).toMap()
        assertEquals("Arroz Diana 7", byName["title"])
        assertEquals("5000 g", byName["size"])
        assertEquals("438/100 g", byName["unit_price"])
        assertEquals("12.4", byName["vs_usual_pct"])
        assertEquals("Carulla 20500 #99", byName["rival"])
        assertEquals("yes", byName["pinned"])
    }

    @Test
    fun `sorting puts the biggest drop first and unknowns last`() {
        val rows = listOf(row(1, "95", "100"), row(2, "50", "100"), row(3, "80", null))
        assertEquals(listOf(2L, 1L, 3L), sortGroceries(rows, GrocerySort.BELOW_USUAL).map { it.deal.id })
        assertEquals(listOf(2L, 3L, 1L), sortGroceries(rows, GrocerySort.PRICE).map { it.deal.id })
    }

    @Test
    fun `only suggest writes, and every tool has a description`() {
        val ds = HikariDataSource() // never connected: building the tool list touches no database
        val file = AppConfig(Defaults(), emptyList(), DbConfig("jdbc:x", "u", "p"), null, null)
        val tools = AgentTools(ds, { null }, GroceryRepository(ds), { file }).tools()
        assertEquals(listOf("suggest"), tools.filter { it.writes }.map { it.name })
        assertTrue(tools.all { it.description.length > 40 })
        assertTrue("sql" in tools.map { it.name } && "grocery_pinned" in tools.map { it.name })
    }

    @Test
    fun `a search with no query, aisle or deals filter is refused with a pointer`() {
        val ds = HikariDataSource()
        val file = AppConfig(Defaults(), emptyList(), DbConfig("jdbc:x", "u", "p"), null, null)
        val server = McpServer(AgentTools(ds, { null }, GroceryRepository(ds), { file }).tools())
        val out = server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"grocery_search","arguments":{}}}""", McpCaller())!!
        assertTrue(out.contains("\"isError\":true") && out.contains("grocery_pinned"), out)
        val sql = server.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"sql","arguments":{"query":"select 1"}}}""", McpCaller())!!
        assertTrue(sql.contains("not available"), "no read-only pool, no sql: never falls back to the owner connection")
    }
}
