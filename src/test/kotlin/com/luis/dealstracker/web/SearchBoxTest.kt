package com.luis.dealstracker.web

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchBoxTest {
    private fun html(value: String?, clearHref: String? = "/groceries?view=all") =
        createHTML(prettyPrint = false).div { searchBox("/groceries", value, mapOf("view" to "all"), clearHref = clearHref) }

    @Test
    fun `a clear link appears beside Search while a search is applied`() {
        val h = html("leche")
        assertTrue("""class="typetag clear"""" in h && """href="/groceries?view=all"""" in h, h)
    }

    @Test
    fun `no clear link when nothing is searched`() {
        assertFalse("typetag clear" in html(null))
        assertFalse("typetag clear" in html("   "))
    }

    @Test
    fun `no clear link on a screen that gives no target`() {
        assertFalse("typetag clear" in html("leche", clearHref = null))
    }

    @Test
    fun `groceries clear drops the search and keeps view, aisle and store`() {
        val c = GroceryControls(GroceryView.ALL, setOf("Dairy"), "leche", GrocerySort.NAME, chains = setOf("exito"))
        val url = c.url(search = null)
        assertFalse("q=" in url, url)
        assertTrue("view=all" in url && "store=exito" in url && "group=Dairy" in url, url)
    }
}
