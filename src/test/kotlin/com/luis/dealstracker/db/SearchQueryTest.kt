package com.luis.dealstracker.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchQueryTest {
    @Test
    fun `plain and piped terms are inclusions`() {
        assertEquals(SearchQuery(listOf("leche", "queso"), emptyList()), SearchQuery.parse("leche | queso"))
    }

    @Test
    fun `bang excludes and may stand alone`() {
        assertEquals(SearchQuery(emptyList(), listOf("aceite")), SearchQuery.parse("!aceite"))
        assertEquals(SearchQuery(listOf("leche"), listOf("deslactosada")), SearchQuery.parse("leche | ! deslactosada"))
    }

    @Test
    fun `a lone bang or blank is nothing`() {
        assertTrue(SearchQuery.parse("!").isEmpty)
        assertTrue(SearchQuery.parse(" | ! | ").isEmpty)
        assertTrue(SearchQuery.parse(null).isEmpty)
    }

    @Test
    fun `sql ands exclusions and ors inclusions`() {
        val (clauses, params) = SearchQuery.parse("a | b | !c_").toSql()
        assertEquals(2, clauses.size)
        assertTrue(clauses[1].contains("NOT ILIKE"))
        assertEquals(listOf("%a%", "a", "%b%", "b", "%c\\_%"), params)
    }

    @Test
    fun `exclusion only has no inclusion clause`() {
        val (clauses, _) = SearchQuery.parse("!aceite").toSql()
        assertEquals(1, clauses.size)
    }
}
