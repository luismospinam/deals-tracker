package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.amazon.AmazonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding the child ASIN of a family that comes in the size wanted.
 *
 * The page shape is the one a real Nike Pegasus 41 page carries (2026-10-05): the shown child was a
 * 7.5, and `dimensionValuesDisplayData` listed every child as `[size, colour]`.
 */
class SiblingSizeTest {

    private fun page(vararg children: Triple<String, String, String>, current: String = "B0CURRENT0") = """
        <script>
        var dataToReturn = {
            "currentAsin" : "$current",
            "dimensions" : ["size_name","color_name"],
            "dimensionValuesDisplayData" : {${children.joinToString(",") { (a, s, c) -> "\"$a\":[\"$s\",\"$c\"]" }}}
        };
        </script>
    """.trimIndent()

    private val cur = Triple("B0CURRENT0", "7.5", "Black")

    @Test fun `prefers the same colour in the wanted size`() {
        val html = page(cur, Triple("B0OTHERCLR", "8", "White"), Triple("B0SAMECLR1", "8", "Black"))
        val found = AmazonParser.siblingForSize(html, "8")
        assertEquals("B0SAMECLR1", found?.asin)
        assertTrue(found!!.sameColor)
    }

    @Test fun `falls back to another colour when the shown one has no such size`() {
        val found = AmazonParser.siblingForSize(page(cur, Triple("B0OTHERCLR", "8", "White")), "8")
        assertEquals("B0OTHERCLR", found?.asin)
        assertFalse(found!!.sameColor)
        assertEquals("White", found.color)
    }

    @Test fun `8 never matches 8 point 5`() =
        assertNull(AmazonParser.siblingForSize(page(cur, Triple("B0HALFSIZE", "8.5", "Black")), "8"))

    @Test fun `a decimal comma is read as a point`() =
        assertEquals("B0HALFSIZE", AmazonParser.siblingForSize(page(cur, Triple("B0HALFSIZE", "8,5", "Black")), "8.5")?.asin)

    @Test fun `the shown child is never offered as its own replacement`() =
        assertNull(AmazonParser.siblingForSize(page(cur), "7.5"))

    @Test fun `a page with no variation table has no sibling`() =
        assertNull(AmazonParser.siblingForSize("<html><span id=\"productTitle\">x</span></html>", "8"))
}
