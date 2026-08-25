package com.luis.dealstracker.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTermsTest {

    @Test
    fun `splits on pipe and trims whitespace`() {
        assertEquals(listOf("airpods", "airtag", "ssd"), parseSearchTerms("airpods | airtag | ssd"))
        assertEquals(listOf("airpods", "airtag"), parseSearchTerms("airpods|airtag"))
    }

    /** A query with no pipe must behave exactly as a plain search always did. */
    @Test
    fun `a single term is unchanged`() {
        assertEquals(listOf("apple watch"), parseSearchTerms("apple watch"))
    }

    @Test
    fun `multi word terms survive splitting`() {
        assertEquals(
            listOf("apple watch", "galaxy buds", "nvme ssd"),
            parseSearchTerms("apple watch | galaxy buds | nvme ssd"),
        )
    }

    @Test
    fun `ignores empty segments from stray or trailing pipes`() {
        assertEquals(listOf("ssd"), parseSearchTerms("|ssd|"))
        assertEquals(listOf("ssd", "ram"), parseSearchTerms("ssd || ram"))
        assertEquals(listOf("ssd"), parseSearchTerms("  ssd  |   |  "))
    }

    @Test
    fun `blank and null searches produce no terms`() {
        assertEquals(emptyList(), parseSearchTerms(null))
        assertEquals(emptyList(), parseSearchTerms(""))
        assertEquals(emptyList(), parseSearchTerms("   "))
        assertEquals(emptyList(), parseSearchTerms("|||"))
    }

    @Test
    fun `deduplicates repeated terms`() {
        assertEquals(listOf("ssd", "ram"), parseSearchTerms("ssd | ram | ssd"))
    }

    @Test
    fun `caps the number of terms`() {
        val many = (1..50).joinToString("|") { "term$it" }
        assertEquals(20, parseSearchTerms(many).size)
    }

    /**
     * `%` and `_` are LIKE wildcards. Without escaping, searching "50%" would match far
     * more than intended, and "_" would match any single character.
     */
    @Test
    fun `escapes like wildcards so they match literally`() {
        assertEquals("50\\%", escapeLike("50%"))
        assertEquals("a\\_b", escapeLike("a_b"))
        // Backslash first, so an escape character in the term isn't doubled twice.
        assertEquals("a\\\\b", escapeLike("a\\b"))
        assertTrue(escapeLike("airpods") == "airpods", "ordinary text is untouched")
    }
}
