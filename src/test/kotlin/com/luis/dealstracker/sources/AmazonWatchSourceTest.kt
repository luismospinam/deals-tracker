package com.luis.dealstracker.sources

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.ingest.Categorizer
import com.luis.dealstracker.sources.amazon.AmazonParser
import com.luis.dealstracker.sources.amazon.AmazonSelectors
import com.luis.dealstracker.sources.amazon.AmazonWatchSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watches' Amazon adapter, off the same captured search page the tracker's tests use.
 *
 * `fetch()` is not called, as with every adapter here; what matters is the row it builds, and in
 * particular the two things that make it a watch's row rather than a tracked one.
 */
class AmazonWatchSourceTest {

    private val source = AmazonWatchSource { emptyList() }
    private val cfg = ProviderConfig(id = "amazon-watches", type = "amazon-watch", baseUrl = "https://www.amazon.com")
    private val base = "https://www.amazon.com"

    private fun hit() = AmazonParser.parseSearch(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/amazon/search-nike-shoes.html"))
            .bufferedReader().readText(),
        AmazonSelectors.from(cfg),
        base,
    ).first()

    @Test
    fun `a row remembers which watches asked for it`() {
        val raw = source.toRawDeal(hit(), watchIds = setOf(2L, 7L), category = "TV & Video")
        assertEquals(setOf(2L, 7L), raw.watchIds, "this is what makes an English title a candidate")
        assertEquals(hit().asin, raw.asin, "the ASIN is what lets the shipping verifier pick it up")
    }

    /** No size is appended: a TV is tracked whole, at the price Amazon shows for the tile. */
    @Test
    fun `the title is kept as Amazon published it`() {
        assertEquals(hit().title, source.toRawDeal(hit(), setOf(1L), null).title)
    }

    /**
     * The watch's category is handed over as the row's source category, and the categorizer takes
     * a bucket name as it stands -- so an English title that matches no rule still lands where the
     * watch is pinned, rather than in `Other` where that watch would never see it.
     */
    @Test
    fun `the watch's category is the row's category`() {
        val categorizer = Categorizer.load(File("."))
        val raw = source.toRawDeal(hit(), setOf(2L), category = "TV & Video")
        assertEquals("TV & Video", categorizer.categorize(raw.category, raw.title))
        assertTrue(raw.title.contains("Nike", ignoreCase = true), "a shoe title, so no rule would say TV")
    }
}
