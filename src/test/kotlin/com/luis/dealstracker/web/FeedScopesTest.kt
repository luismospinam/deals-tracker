package com.luis.dealstracker.web

import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cover for the scope row that replaced the top-level `favorites` tab.
 *
 * Favorites used to be a fourth masthead tab, beside `tracked` and `watches` — which implied it was
 * a fourth kind of thing. It is not: it is the feed with `state = FAVORITE` pinned, sharing the
 * query, the columns and the row renderer (see [FeedScreen]). Moving it into the feed as a scope
 * makes that relationship visible, and these tests pin the three things that can silently break in
 * the process: the filters surviving a scope switch, `state` NOT surviving it, and the badge.
 */
class FeedScopesTest {

    private fun render(q: Map<String, String>, screen: FeedScreen, count: Int = 0): String =
        createHTML().html { body { main { feedScopes(q, screen, count) } } }

    private fun empty() = FILTER_PARAMS.associateWith { "" }

    @Test
    fun `both scopes are always offered, with the current one marked`() {
        val feed = render(empty(), FeedScreen.FEED)
        assertTrue(feed.contains("All deals"), "the feed scope must be reachable")
        assertTrue(feed.contains("Favorites"), "the favorites scope must be reachable")
        // The class drives the visual state and aria-current the assistive one; a scope row that
        // highlights nothing leaves you unable to tell which list you are looking at.
        assertTrue(feed.contains("""class="scope on""""), "the active scope must be marked")
        assertTrue(feed.contains("""aria-current="page""""), "the active scope must say so to a screen reader")
    }

    @Test
    fun `the active scope is the one rendered, not always the feed`() {
        val favorites = render(empty(), FeedScreen.FAVORITES)
        // The marked anchor must be the favorites one. Checking the substring order is enough:
        // "All deals" comes first in the markup, so if it carried the `on` class the class would
        // appear before the label rather than after it.
        val onAt = favorites.indexOf("scope on")
        val allDealsAt = favorites.indexOf("All deals")
        assertTrue(onAt > allDealsAt, "on /favorites it is Favorites that must be marked, not All deals")
    }

    /**
     * The whole reason the links are built rather than hardcoded. Switching scope with a search
     * active and losing the search would make the control feel like it discarded your work.
     */
    @Test
    fun `the current filters ride along when switching scope`() {
        val html = render(empty() + mapOf("q" to "anker", "sort" to "price"), FeedScreen.FEED)
        assertTrue(html.contains("/favorites?"), "the favorites link must carry the query string")
        assertTrue(html.contains("q=anker"), "the search must survive a scope switch")
        assertTrue(html.contains("sort=price"), "the sort must survive a scope switch")
    }

    /**
     * `state` is pinned server-side by [favoritesFilter] precisely so a hand-edited URL cannot turn
     * the favorites scope into a general feed. Carrying it here would add a parameter that is then
     * silently ignored — the kind of thing that makes a filter look broken.
     */
    @Test
    fun `state is dropped rather than carried into favorites`() {
        val html = render(empty() + mapOf("state" to "DISMISSED"), FeedScreen.FEED)
        assertFalse(html.contains("state=DISMISSED"), "state must not be carried into the favorites scope")
    }

    @Test
    fun `blank filters produce clean links rather than empty parameters`() {
        val html = render(empty(), FeedScreen.FEED)
        assertFalse(html.contains("?"), "no filters set must yield a bare href, not a trailing '?'")
        assertTrue(html.contains("""href="/favorites""""), "the favorites link must still be present")
    }

    @Test
    fun `a multi-word search is encoded, not pasted raw into the href`() {
        val html = render(empty() + mapOf("q" to "usb c charger"), FeedScreen.FEED)
        // A raw space in an href is the bug this guards: it survives some browsers and breaks others.
        assertFalse(html.contains("q=usb c charger"), "the search term must be URL-encoded")
        assertTrue(
            html.contains("q=usb+c+charger") || html.contains("q=usb%20c%20charger"),
            "expected an encoded search term, got: $html",
        )
    }

    @Test
    fun `the count shows when there are favorites and stays hidden at zero`() {
        assertTrue(render(empty(), FeedScreen.FEED, count = 12).contains("12"), "a real count must show")
        // "Favorites 0" reads as a broken counter; a bare label reads as an invitation.
        assertFalse(
            render(empty(), FeedScreen.FEED, count = 0).contains("scope-count"),
            "zero favorites must render no badge at all",
        )
    }

    /** Two scopes, no more: the control is a switch, so a third anchor would make it a nav bar. */
    @Test
    fun `exactly two scopes are rendered`() {
        val html = render(empty(), FeedScreen.FEED)
        // Counts anchors, not the class attribute: the wrapper is `class="scopes"`, which any
        // prefix match on `class="scope` also hits.
        assertEquals(2, Regex("""<a\b[^>]*class="scope\b""").findAll(html).count(), "expected exactly two scope links")
    }
}

/** The feed leaves out the providers that fetch items whether or not they are on offer. */
class FeedHiddenSourcesTest {

    private fun app(vararg providers: com.luis.dealstracker.config.ProviderConfig) =
        com.luis.dealstracker.config.AppConfig(
            defaults = com.luis.dealstracker.config.Defaults(),
            providers = providers.toList(),
            db = com.luis.dealstracker.config.DbConfig("jdbc:none", "u", "p"),
            mlClientId = null,
            mlClientSecret = null,
        )

    private val app = app(
        com.luis.dealstracker.config.ProviderConfig(id = "exito", type = "vtex"),
        com.luis.dealstracker.config.ProviderConfig(id = "slickdeals", type = "rss-slickdeals"),
        com.luis.dealstracker.config.ProviderConfig(id = "amazon", type = "amazon"),
        com.luis.dealstracker.config.ProviderConfig(id = "amazon-watches", type = "amazon-watch", enabled = false),
    )

    @Test
    fun `both Amazon providers are kept off the feed, disabled or not`() {
        // Disabled still counts: turning a provider off leaves its stored rows behind.
        assertEquals(listOf("amazon", "amazon-watches"), feedHiddenSources(app))
        // Slickdeals' Amazon deals stay: those are community-posted offers, not a sweep.
        assertFalse("slickdeals" in feedHiddenSources(app))
    }

    @Test
    fun `the feed excludes them but favorites does not`() {
        assertEquals(listOf("amazon", "amazon-watches"), feedScreenFilter(io.ktor.http.Parameters.Empty, app).excludeSources)
        assertTrue(favoritesFilter(io.ktor.http.Parameters.Empty).excludeSources.isEmpty(), "a favorite stays yours")
    }
}
