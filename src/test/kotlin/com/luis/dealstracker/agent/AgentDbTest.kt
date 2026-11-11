package com.luis.dealstracker.agent

import com.luis.dealstracker.exito.CartContents
import com.luis.dealstracker.exito.CartLine
import com.luis.dealstracker.exito.cartContents
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDbTest {
    @Test
    fun `the agent role can log in and read, and nothing more`() {
        val sql = AgentDb.roleStatements("abc_DEF-123").joinToString("\n")
        assertTrue(sql.contains("NOSUPERUSER") && sql.contains("NOCREATEROLE") && sql.contains("NOBYPASSRLS"))
        assertTrue(sql.contains("default_transaction_read_only = on") && sql.contains("statement_timeout"))
        assertTrue(sql.contains("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM deals_agent"), "grants are rebuilt from nothing each boot")
        val grant = AgentDb.roleStatements("x").single { it.startsWith("GRANT SELECT") }
        assertFalse(grant.contains("INSERT") || grant.contains("UPDATE"))
        assertTrue(grant.contains("price_observations") && grant.contains("purchases"))
    }

    @Test
    fun `the settings table, which holds the Exito login, is never readable`() {
        assertFalse("app_settings" in AGENT_READABLE_TABLES)
        assertTrue("exito_orders" in AGENT_READABLE_TABLES && "exito_order_items" in AGENT_READABLE_TABLES)
        assertFalse(AgentDb.roleStatements("x").any { it.contains("app_settings") })
    }

    @Test
    fun `a password that could break out of the statement is refused`() {
        assertFailsWith<IllegalArgumentException> { AgentDb.roleStatements("x'; DROP ROLE deals; --") }
        assertTrue(AgentDb.randomToken(24).matches(Regex("[A-Za-z0-9_-]{32}")))
    }

    @Test
    fun `query results are tab-separated, cleaned of tabs and newlines, and say when cut`() {
        val r = QueryResult(listOf("id", "title"), listOf(listOf("1", "a\tb\nc"), listOf("2", null)), truncated = true)
        val tsv = r.toTsv()
        assertTrue(tsv.startsWith("id\ttitle\n1\ta b c\n2\t\n"), tsv)
        assertTrue(tsv.contains("cut at 2 rows"))
        assertTrue(QueryResult(listOf("x"), emptyList(), false).toTsv().contains("(no rows)"))
    }

    // --- the purchase log ------------------------------------------------------------------------

    private fun cart(id: String, vararg lines: Pair<String, Int>) =
        CartContents(id, lines.map { (pid, q) -> CartLine(pid, "Item $pid", q, BigDecimal("1000")) })

    @Test
    fun `a product that appears or grows in the cart is a purchase of the difference`() {
        val first = cartChanges(null, cart("c1", "a" to 2))
        assertEquals(listOf("a" to 2), first.map { it.line.productId to it.delta })
        val before = CartSnapshot("c1", mapOf("a" to 2, "b" to 1))
        val next = cartChanges(before, cart("c1", "a" to 3, "b" to 1, "c" to 1))
        assertEquals(setOf("a" to 1, "c" to 1), next.map { it.line.productId to it.delta }.toSet())
    }

    @Test
    fun `taking one out un-logs it, and a new cart after an order starts from nothing`() {
        val before = CartSnapshot("c1", mapOf("a" to 2, "b" to 1))
        assertEquals(setOf("a" to -1, "b" to -1), cartChanges(before, cart("c1", "a" to 1)).map { it.line.productId to it.delta }.toSet())
        assertTrue(cartChanges(before, cart("c2")).isEmpty(), "the order closed c1: its lines were bought, nothing to undo")
        assertEquals(listOf("a" to 1), cartChanges(before, cart("c2", "a" to 1)).map { it.line.productId to it.delta })
    }

    @Test
    fun `an order form reads as product quantities, prices from cents`() {
        val body = """{"orderFormId":"of1","items":[
            {"productId":"p1","id":"s1","name":"Arroz Diana","quantity":2,"sellingPrice":2190000},
            {"productId":"p1","id":"s1","name":"Arroz Diana","quantity":1,"sellingPrice":2190000},
            {"productId":"p2","name":"Leche","quantity":1,"price":450000}]}"""
        val c = cartContents(body)!!
        assertEquals("of1", c.cartId)
        assertEquals(3, c.lines.single { it.productId == "p1" }.quantity, "two lines of one product count together")
        assertEquals(BigDecimal("21900.00"), c.lines.first().unitPrice)
        assertEquals(BigDecimal("4500.00"), c.lines.single { it.productId == "p2" }.unitPrice)
        assertEquals(null, cartContents("<html>"))
    }
}
