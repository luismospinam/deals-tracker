package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the tracked size back out of a title.
 *
 * The size lives in the title because `deals` has no size column — `AmazonSource.titleWithSize`
 * appends `" — <size>"`. The tracked tab splits it back out so the size can be scanned down its
 * own column, and that split has to be conservative: a product whose own name contains an em
 * dash must not have half its name mistaken for a size.
 */
class TrackedViewsTest {

    /** Minimal Deal for the charge arithmetic; only the fields the column reads are meaningful. */
    private fun trackedDeal(
        price: String,
        shippingCost: String? = null,
        listPrice: String? = null,
        minEver: String? = null,
        maxEver: String? = null,
    ) = Deal(
        id = 1, source = "amazon", sourceDealId = "B0", title = "Item — M",
        url = "https://www.amazon.com/dp/B0", merchant = "amazon", asin = "B0",
        category = null, imageUrl = null, brand = "adidas", gtin = null, modelCode = null,
        matchKey = null, modelKey = null, currency = "USD",
        firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
        state = DealState.NEW, coShipping = CoShipping.YES, promoted = false, promotedAt = null,
        communityScore = null, postedAt = null, categoryGroup = "Fashion",
        coShippingSource = "AUTO", coShippingEvidence = null, coShippingImportFree = true,
        coShippingCost = shippingCost?.let(::BigDecimal),
        price = BigDecimal(price),
        listPrice = listPrice?.let(::BigDecimal),
        minPriceEver = minEver?.let(::BigDecimal),
        maxPriceEver = maxEver?.let(::BigDecimal),
        observationCount = if (minEver != null) 2 else 1,
    )

    @Test
    fun `splits the size the adapter appended`() {
        assertEquals("8", sizeOf("Nike Men's Revolution 8 Road Running Shoes — 8"))
        assertEquals("Nike Men's Revolution 8 Road Running Shoes", itemName("Nike Men's Revolution 8 Road Running Shoes — 8"))
    }

    @Test
    fun `handles spelled-out and compound size labels`() {
        assertEquals("Medium", sizeOf("Nike Club Fleece Hoodie — Medium"))
        assertEquals("XX-Large", sizeOf("adidas Team Issue Pullover — XX-Large"))
        assertEquals("8.5 Women/7.5 Men", sizeOf("adidas Samba — 8.5 Women/7.5 Men"))
    }

    /** An untracked row (or any other source's deal) has no suffix and must report none. */
    @Test
    fun `a title with no size suffix has no size`() {
        assertNull(sizeOf("Nevecon Side By Side ELECTROLUX 528 LITROS"))
        assertEquals("Nevecon Side By Side ELECTROLUX 528 LITROS", itemName("Nevecon Side By Side ELECTROLUX 528 LITROS"))
    }

    /**
     * The conservative guard. Real Amazon titles do contain em dashes — this one is from the
     * live feed — and treating the tail as a size would both invent a size and truncate the name.
     */
    @Test
    fun `a long tail after an em dash is prose, not a size`() {
        val prose = "Nike Sportswear Club — the classic fleece pullover, now in seasonal colours"
        assertNull(sizeOf(prose), "a 60-character tail is not a size label")
        assertEquals(prose, itemName(prose), "the name must survive intact")
    }

    /** Re-ingesting must not append the size twice, and reading back must still find one. */
    @Test
    fun `an already-suffixed title round-trips`() {
        val once = "Nike Club Jogger — Small"
        assertEquals("Small", sizeOf(once))
        assertEquals("Nike Club Jogger", itemName(once))
    }

    @Test
    fun `an em dash with an empty tail is not a size`() {
        assertNull(sizeOf("Nike Something — "))
    }

    // --- sorting, and the at-lowest badge ---

    /** Amazon's advertised discount, deepest first. */
    @Test
    fun `the off sort orders by discount, deepest first`() {
        val ordered = listOf(
            trackedDeal(price = "20.00", listPrice = "40.00"),   // 50%
            trackedDeal(price = "80.00", listPrice = "100.00"),  // 20%
            trackedDeal(price = "10.00", listPrice = "40.00"),   // 75%
        ).sortedWith(comparatorFor(TrackedSort.OFF))

        assertEquals(listOf("75.0", "50.0", "20.0"), ordered.map { it.discountPercent!!.toPlainString() })
    }

    /**
     * A row with no list price is not a 0%-off row, so it sorts LAST rather than mixing in with
     * genuinely undiscounted ones. Same rule the column follows when it renders a blank.
     */
    @Test
    fun `rows with no list price sort last, not as zero`() {
        val ordered = listOf(
            trackedDeal(price = "50.00", listPrice = null),
            trackedDeal(price = "30.00", listPrice = "60.00"),
        ).sortedWith(comparatorFor(TrackedSort.OFF))

        assertNotNull(ordered.first().discountPercent, "a real discount must outrank an unknown one")
        assertNull(ordered.last().discountPercent)
    }

    /**
     * The badge you can only earn with evidence.
     *
     * `price <= minPriceEver` is trivially TRUE for any row whose price never moved, because the
     * minimum includes the current observation — that exact mistake once put "lowest yet" on every
     * row of the feed. So an observed decline is required too.
     */
    @Test
    fun `at-lowest requires an actual observed decline`() {
        // Seen at 40, now 25: a real fall, and currently the lowest.
        assertTrue(trackedDeal(price = "25.00", minEver = "25.00", maxEver = "40.00").isAtLowestEver)

        // Never moved. Lowest by arithmetic, but there is no decline to report.
        assertFalse(
            trackedDeal(price = "25.00", minEver = "25.00", maxEver = "25.00").isAtLowestEver,
            "a price that never moved must not claim to be at a low",
        )

        // Fell from 40 to 25, then back to 30: not at the low now.
        assertFalse(trackedDeal(price = "30.00", minEver = "25.00", maxEver = "40.00").isAtLowestEver)
    }

    /** At-lowest rows come first, then by how far they fell. */
    @Test
    fun `the lowest sort surfaces badged rows first`() {
        val notAtLow = trackedDeal(price = "30.00", minEver = "25.00", maxEver = "40.00")
        val smallFall = trackedDeal(price = "38.00", minEver = "38.00", maxEver = "40.00")
        val bigFall = trackedDeal(price = "25.00", minEver = "25.00", maxEver = "90.00")

        val ordered = listOf(notAtLow, smallFall, bigFall).sortedWith(comparatorFor(TrackedSort.LOWEST))

        assertTrue(ordered[0].isAtLowestEver && ordered[1].isAtLowestEver)
        assertEquals(bigFall.price, ordered[0].price, "the deepest fall leads")
        assertFalse(ordered[2].isAtLowestEver, "rows not at a low sort last")
    }

    /** An unknown sort key falls back to the default rather than erroring. */
    @Test
    fun `an unknown sort key falls back to the default`() {
        assertEquals(TrackedSort.PRICE, TrackedSort.of(null))
        assertEquals(TrackedSort.PRICE, TrackedSort.of("nonsense"))
        assertEquals(TrackedSort.OFF, TrackedSort.of("off"))
        assertEquals(TrackedSort.LOWEST, TrackedSort.of("LOWEST"))
    }

    /**
     * Boxers get their own tag rather than being folded into pants.
     *
     * They share the configured size (S) and Amazon's `international_size` rail, but "show me
     * boxers" and "show me trousers" are different questions. BOXERS is declared before PANTS
     * because the real title is "No Fly Trunks Boxer Brief Underwear" — `trunk` and `brief` must
     * not be read as trousers.
     */
    @Test
    fun `boxers classify as their own type, not as pants`() {
        assertEquals(
            ProductType.BOXERS,
            ProductType.of("New Balance Men's No Fly Trunks Boxer Brief Underwear — S"),
        )
        assertEquals(ProductType.BOXERS, ProductType.of("Men's Everyday Cotton Stretch Trunks — S"))
        // ...while real trousers are unaffected.
        assertEquals(ProductType.PANTS, ProductType.of("Men's Tiro 19 Track Pants — S"))
    }

    // --- origin: found by a search vs added by URL ---

    /**
     * The two origins are not equally trustworthy, which is the reason to surface the distinction.
     *
     * Amazon's size FILTER returns a representative of the product family rather than strictly the
     * matching child, so a searched row's size is Amazon's claim. A pasted URL names an exact ASIN,
     * so its price and size are the item's own.
     */
    @Test
    fun `origin parses from its label and ignores anything else`() {
        assertEquals(TrackedOrigin.MANUAL, TrackedOrigin.of("added"))
        assertEquals(TrackedOrigin.SEARCH, TrackedOrigin.of("found"))
        assertEquals(TrackedOrigin.MANUAL, TrackedOrigin.of("ADDED"))
        // Unknown and absent both mean "no filter" rather than an error.
        assertNull(TrackedOrigin.of("manual"))
        assertNull(TrackedOrigin.of(null))
        assertNull(TrackedOrigin.of(""))
    }

    // --- extra charges (shipping / import) ---

    /**
     * The landed cost is what a tracker is actually asked for, and freight changes the answer.
     *
     * On the verified rows Amazon quotes ~$6.99 against items of $20-45, which turns a 51%-off tee
     * into roughly 33% off. `Deal` already computes the ratio, so this pins the arithmetic the
     * column relies on rather than the markup.
     */
    @Test
    fun `shipping cost is expressed as a share of the item price`() {
        val deal = trackedDeal(price = "20.32", shippingCost = "6.99")
        assertEquals(BigDecimal("34"), deal.shippingCostPercent)
        assertFalse(deal.shippingCostDominates, "34% is material but does not dominate")
        assertEquals(BigDecimal("27.31"), deal.price.add(deal.coShippingCost!!))
    }

    /**
     * The extreme this project already measured: of 27 verified deals carrying a fee, 23 were above
     * half the item's price and 10 above the whole price — $248.68 to ship a $267.99 television.
     * Those must read as a reason not to buy, not a footnote.
     */
    @Test
    fun `a fee worth more than the item dominates`() {
        val deal = trackedDeal(price = "267.99", shippingCost = "248.68")
        assertTrue(deal.shippingCostDominates)
        assertEquals(BigDecimal("93"), deal.shippingCostPercent)
    }

    /**
     * Unchecked and free-of-charge are different facts and must not render the same.
     *
     * `co_shipping_cost` is null when the Colombia check has not run, and ZERO when Amazon was asked
     * and quoted nothing — which is why the column shows a blank for the first and "free ship" for
     * the second rather than `$0` for both.
     */
    @Test
    fun `an unquoted fee is null, never zero`() {
        assertNull(trackedDeal(price = "8.37", shippingCost = null).shippingCostPercent)
        assertEquals(
            BigDecimal.ZERO,
            trackedDeal(price = "8.37", shippingCost = "0.00").shippingCostPercent,
            "a quoted zero is a real answer and stays distinguishable from silence",
        )
    }

    // --- product-type tags ---

    /** Real stored titles, so the classifier is pinned against what the adapter actually writes. */
    @Test
    fun `classifies real tracked titles by product type`() {
        assertEquals(ProductType.SHOES, ProductType.of("Nike Men's Revolution 8 Road Running Shoes — 8"))
        assertEquals(ProductType.SHOES, ProductType.of("adidas Men's Daily 4.0 Sneakers — 8"))
        assertEquals(ProductType.HOODIES, ProductType.of("Nike Men's Fleece Pullover Hoodie Sweatshirt — Medium"))
        assertEquals(ProductType.PANTS, ProductType.of("Nike Men's Sportswear Club Jogger Sweatpant — Small"))
        assertEquals(ProductType.PANTS, ProductType.of("adidas Men's Tiro 19 Track Pants — Small"))
    }

    /**
     * The four misclassifications a live run produced, every title exact.
     *
     * Each was reported from the UI: the jersey appeared under a `shoes` filter, and the other three
     * sat in `other` with no bucket at all.
     */
    @Test
    fun `real misclassified titles land in the right bucket`() {
        // Names BOTH a top and footwear. The size is M, so it is the top -- and that is Amazon's
        // own guarantee, since its size filter is why the row exists at all.
        assertEquals(
            ProductType.SHIRTS,
            ProductType.of("Mens Squadra 25 Jersey Soccer Cleats - Orange — M"),
            "a size-M item is not footwear, whatever its title says",
        )

        assertEquals(
            ProductType.SHIRTS,
            ProductType.of("Men's Short-Sleeve Hydroguard — M"),
            "a sleeve length is only ever said of a top",
        )
        assertEquals(
            ProductType.HOODIES,
            ProductType.of("Mens Essentials Warm-Up 3-Stripes Athletic Outerwear Casual - Blue — M"),
            "outerwear is an upper-body layer",
        )
        assertEquals(
            ProductType.PANTS,
            ProductType.of("Men's Essentials Warm-up Open Hem 3-stripes Tracksuit Bottoms — S"),
            "tracksuit bottoms are bottoms",
        )
        assertEquals(
            ProductType.PANTS,
            ProductType.of("Mens Woven Cargo Casual - Green — S"),
            "cargo is a trouser cut",
        )
    }

    /**
     * The same ambiguous title, sized as footwear, must classify as footwear.
     *
     * This is the other half of the size rule — it has to cut both ways, or it is just a thumb on
     * the scale for garments.
     */
    @Test
    fun `an ambiguous title sized as footwear is footwear`() {
        assertEquals(
            ProductType.SHOES,
            ProductType.of("Mens Squadra 25 Jersey Soccer Cleats - Orange — 8"),
        )
    }

    /**
     * Amazon truncates search-tile titles hard; "Men's Slim" and "Men's Modern" are real stored
     * ones. A letter size says only "a garment", which is not a bucket, so those stay OTHER rather
     * than being forced somewhere.
     */
    @Test
    fun `a title with no garment noun falls back to the size shape`() {
        assertEquals(ProductType.SHOES, ProductType.of("Men's Slim — 8"))
        assertEquals(ProductType.OTHER, ProductType.of("Men's Slim — S"))
        assertEquals(ProductType.OTHER, ProductType.of("Men's Modern — M"))
    }

    /** `shorts` stays plural, so "Short-Sleeve" cannot be read as a trouser. */
    @Test
    fun `short sleeve is not shorts`() {
        assertEquals(ProductType.SHIRTS, ProductType.of("Men's Park VIII Jersey Short Sleeve T-Shirt — M"))
        assertEquals(ProductType.PANTS, ProductType.of("Men's Club Fleece Shorts — S"))
    }

    /**
     * Two real misclassifications from live runs, pulling in opposite directions.
     *
     * `jersey` is a fabric as often as a garment, so "Single Jersey Tapered Open Hem Pants" was
     * filed under shirts. And `short` matches "Short Sleeve", so "Jersey Short Sleeve T-Shirt" was
     * filed under pants. Both titles are real adidas and Nike products from the tracked set.
     */
    @Test
    fun `jersey and short-sleeve titles land in the right bucket`() {
        assertEquals(
            ProductType.PANTS,
            ProductType.of("Men's Essentials Single Jersey Tapered Open Hem Pants — S"),
            "jersey is a fabric here, not a garment",
        )
        assertEquals(
            ProductType.SHIRTS,
            ProductType.of("Men's Park VIII Jersey Short Sleeve T-Shirt — M"),
            "'Short Sleeve' must not read as shorts",
        )
        // And a garment that really is shorts still classifies as bottoms — it is always plural.
        assertEquals(ProductType.PANTS, ProductType.of("Men's Club Fleece Shorts — S"))
    }

    @Test
    fun `a sweatshirt is a hoodie, not a shirt`() {
        assertEquals(ProductType.HOODIES, ProductType.of("adidas Essentials Sweatshirt — Medium"))
        assertEquals(ProductType.SHIRTS, ProductType.of("Nike Sportswear Tee — Medium"))
    }

    /**
     * A title whose nouns say nothing still classifies from its size shape — a numeric size is
     * footwear. "Nike Mens Football — 8" was a real stored row with no useful noun.
     */
    @Test
    fun `a numeric size implies footwear when the title is unhelpful`() {
        assertEquals(ProductType.SHOES, ProductType.of("Nike Mens Football — 8"))
    }

    /** No size and no recognisable noun is genuinely unknown, and says so rather than guessing. */
    @Test
    fun `an unclassifiable title falls to other`() {
        assertEquals(ProductType.OTHER, ProductType.of("Nike Backpack"))
        assertEquals(ProductType.OTHER, ProductType.of("adidas Water Bottle"))
    }
}
