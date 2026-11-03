package com.luis.dealstracker.web

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.fakeAppConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The feed's Source dropdown listed 24 sources on 2026-11-02, most of which the feed never shows:
 * grocery stores, both Amazon providers, disabled providers and a feed last fetched in August.
 */
class FeedSourceOptionsTest {

    private val app = run {
        val base = fakeAppConfig()
        fun p(id: String, type: String, enabled: Boolean = true) = ProviderConfig(id = id, type = type, enabled = enabled)
        com.luis.dealstracker.config.AppConfig(
            defaults = base.defaults,
            providers = listOf(
                p("slickdeals", "rss-slickdeals"),
                p("exito", "vtex"),
                p("amazon", "amazon"),
                p("amazon-watches", "amazon-watch"),
                p("exito-grocery", "vtex-grocery"),
                p("pricesmart-americas-grocery", "pricesmart"),
                p("mercadolibre-co", "mercadolibre", enabled = false),
                p("slickdeals-frontpage", "rss-slickdeals", enabled = false),
            ),
            db = base.db, mlClientId = null, mlClientSecret = null,
        )
    }

    @Test
    fun `the feed offers only enabled sources it can show`() {
        assertEquals(listOf("exito", "slickdeals"), feedSourceOptions(app, FeedScreen.FEED, current = null))
    }

    /** Tracked and grocery pins are listed on their own tabs, so Pinned offers what the feed offers. */
    @Test
    fun `Pinned offers the feed's sources`() {
        assertEquals(listOf("exito", "slickdeals"), feedSourceOptions(app, FeedScreen.PINNED, current = ""))
    }

    /** An old link to a since-disabled source still shows what it selects, rather than silently "all". */
    @Test
    fun `the source in the URL is always kept`() {
        assertEquals(listOf("exito", "mercadolibre-co", "slickdeals"), feedSourceOptions(app, FeedScreen.FEED, "mercadolibre-co"))
    }
}
