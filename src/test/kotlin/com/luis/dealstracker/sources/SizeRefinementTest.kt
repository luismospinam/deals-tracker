package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.amazon.SizeRefinement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discovering Amazon's own size filter from a search page.
 *
 * The fixture is trimmed from three real unfiltered searches captured 2026-09-21 — `nike men
 * shoes`, `nike men hoodie`, `nike men pants` — keeping the refinement rail, which is all this
 * reads. It deliberately contains more than one size dimension, because that is the trap.
 */
class SizeRefinementTest {

    private val options by lazy {
        SizeRefinement.parse(
            requireNotNull(javaClass.getResourceAsStream("/fixtures/amazon/size-refinements.html")) {
                "missing fixture"
            }.bufferedReader().readText(),
        )
    }

    @Test
    fun `parses the real refinement rail`() {
        assertTrue(options.size >= 20, "expected several dimensions' worth of sizes, got ${options.size}")
    }

    /**
     * The headline case: the operator writes `8`, and this finds the opaque node id — the exact
     * value that returned 45 usable size-8 tiles in one request against the live site.
     */
    @Test
    fun `finds the shoe size the operator configured`() {
        val hit = assertNotNull(SizeRefinement.pick(options, "8"))
        assertEquals("p_n_pt_nav_size_men_shoe", hit.parameter)
        assertEquals("1285098011", hit.nodeId)
        assertEquals("p_n_pt_nav_size_men_shoe:1285098011", hit.rh)
    }

    /** Apparel uses a different parameter entirely — assuming the footwear one finds nothing. */
    @Test
    fun `finds an apparel size under its own parameter`() {
        val hit = assertNotNull(SizeRefinement.pick(options, "M"))
        assertEquals("p_n_pt_nav_size_men_international_size", hit.parameter)
        assertEquals("2475468011", hit.nodeId)

        val small = assertNotNull(SizeRefinement.pick(options, "S"))
        assertEquals("2475467011", small.nodeId)
    }

    /**
     * Exact matching, as everywhere else in this adapter. `8` must not select `8.5`, and `XL` must
     * not select `XLT` — a loose match returns a real page of the wrong size, which is worse than
     * returning nothing.
     */
    @Test
    fun `sizes are matched exactly, never by prefix`() {
        assertEquals("1285098011", SizeRefinement.pick(options, "8")?.nodeId)
        val half = SizeRefinement.pick(options, "8.5")
        if (half != null) {
            assertTrue(half.nodeId != "1285098011", "8.5 must not resolve to the size-8 node")
        }
        assertEquals("2475470011", SizeRefinement.pick(options, "XL")?.nodeId)
    }

    /**
     * Shoes offer size AND width; pants offer international size AND waist. Filtering by the wrong
     * dimension is a silent wrong answer, so unwanted dimensions are never selected.
     */
    @Test
    fun `never filters by a dimension that is not a garment size`() {
        // The fixture contains shoe_width options. A width must never be returned for a size.
        options.filter { it.parameter.contains("width") }.forEach { width ->
            val picked = SizeRefinement.pick(options, width.label)
            assertTrue(
                picked == null || !picked.parameter.contains("width"),
                "picked a width dimension for '${width.label}'",
            )
        }
    }

    /**
     * Waist is available but deliberately ranked below garment size, so a config saying `S`/`M`
     * cannot be answered with a waist measurement.
     */
    @Test
    fun `garment size is preferred over waist when both exist`() {
        // "32" exists only as a waist in the fixture, so it may resolve there -- that is fine.
        // What must not happen is a letter size resolving to waist.
        assertEquals(
            "p_n_pt_nav_size_men_international_size",
            SizeRefinement.pick(options, "M")?.parameter,
        )
    }

    /**
     * An unreachable size returns null AND reports what was on offer, so the operator can supply a
     * URL by hand instead of being told nothing. This is the case they asked to be told about.
     */
    @Test
    fun `an unavailable size is reported, not guessed`() {
        assertNull(SizeRefinement.pick(options, "99"))
        assertNull(SizeRefinement.pick(options, ""))

        val offered = SizeRefinement.offered(options)
        assertTrue(offered.contains("8"), "the diagnostic must list the sizes that WERE found")
        assertTrue(offered.contains("M"))
    }

    /** The spelled-out form belongs to product pages, not the rail — so it must not match here. */
    @Test
    fun `the rail uses short labels, and no aliasing is invented`() {
        assertNull(
            SizeRefinement.pick(options, "Medium"),
            "the rail says M; silently treating 'Medium' as M would hide a config mistake",
        )
    }

    @Test
    fun `a page with no refinement rail yields nothing rather than throwing`() {
        assertTrue(SizeRefinement.parse("<html><body><p>no filters here</p></body></html>").isEmpty())
    }
}
