package com.luis.dealstracker.web

import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import kotlinx.html.main
import kotlinx.html.stream.createHTML
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
        pinned: Boolean = false,
        asin: String = "B0",
    ) = Deal(
        id = 1, source = "amazon", sourceDealId = "B0", title = "Item — M",
        url = "https://www.amazon.com/dp/B0", merchant = "amazon", asin = asin,
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
        pinnedAt = if (pinned) Instant.EPOCH else null,
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

    // --- pinned ---

    @Test
    fun `pinned origin parses from its label`() {
        assertEquals(TrackedOrigin.PINNED, TrackedOrigin.of("pinned"))
    }

    @Test
    fun `the pinned list is the pinned rows plus the added ones, and nothing else`() {
        val pinned = trackedDeal(price = "10", pinned = true, asin = "P1")
        val added = trackedDeal(price = "20", asin = "A1")
        val found = trackedDeal(price = "30", asin = "F1")
        val shown = trackedShown(
            listOf(pinned, added, found), emptySet(), null, TrackedOrigin.PINNED, setOf("A1"), TrackedSort.PRICE,
        )
        assertEquals(listOf("P1", "A1"), shown.map { it.asin })
    }

    @Test
    fun `an added item that is also pinned is listed once`() {
        val both = trackedDeal(price = "10", pinned = true, asin = "A1")
        val shown = trackedShown(listOf(both), emptySet(), null, TrackedOrigin.PINNED, setOf("A1"), TrackedSort.PRICE)
        assertEquals(1, shown.size)
    }

    @Test
    fun `the pin button posts the opposite action and carries the folded variants`() {
        val off = pinButtonFragment(7, pinned = false, variants = listOf(8, 9), onPinnedView = false, removesRow = false)
        assertTrue("/tracked/7/pin?also=8,9" in off, off)
        assertTrue("☆ pin" in off, off)
        assertTrue("hx-target=\"this\"" in off, off)
        val on = pinButtonFragment(7, pinned = true, variants = emptyList(), onPinnedView = false, removesRow = false)
        assertTrue("/tracked/7/unpin\"" in on, on)
        assertTrue("★ pinned" in on, on)
    }

    @Test
    fun `unpinning on the pinned list targets the row and asks first`() {
        val html = pinButtonFragment(7, pinned = true, variants = emptyList(), onPinnedView = true, removesRow = true)
        assertTrue("hx-target=\"#tracked-7\"" in html, html)
        assertTrue("view=pinned" in html, html)
        assertTrue("hx-confirm" in html, html)
    }

    private fun detailHtml(deal: Deal, pinnable: Boolean) =
        kotlinx.html.stream.createHTML(prettyPrint = false).main { dealDetail(deal, emptyList(), pinnable) }

    @Test
    fun `the detail page shows the pin button for a tracked item, in its current state`() {
        val html = detailHtml(trackedDeal(price = "10", pinned = true), pinnable = true)
        assertTrue("/tracked/1/unpin" in html, html)
        assertTrue("★ pinned" in html, html)
    }

    @Test
    fun `the detail page has no pin button for a feed deal`() {
        assertFalse("fav-btn" in detailHtml(trackedDeal(price = "10"), pinnable = false))
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

    /**
     * "Crewneck" is a neckline, not a garment. These three sat under a `hoodies` filter, every
     * title exact, because HOODIES listed `crewneck` and is declared before SHIRTS.
     */
    @Test
    fun `a crewneck tee is a shirt, not a hoodie`() {
        listOf(
            "Men's Small Logo Short Sleeve Crewneck T-Shirt, Standard Fit, Breathable Lightweight Stretch Knit Fabric — M",
            "Men's Strike Short Sleeve Crewneck T-Shirt, Standard Fit, Lightweight Stretch Poly Jersey Fabric — M",
            "Men's All Day Cotton Stretch Short Sleeve Crewneck Undershirts (2-Pack) — M",
        ).forEach { assertEquals(ProductType.SHIRTS, ProductType.of(it), it) }
        // A crewneck sweatshirt still says so, and stays a hoodie.
        assertEquals(ProductType.HOODIES, ProductType.of("Crew Sweatshirt by Nike — M"))
    }

    /**
     * Every row cell must sit under its own header. The Extra cell once rendered after Change while
     * its header sat after Off, so Lowest, Highest, Low and Change each showed one column left.
     */
    @Test
    fun `row cells line up with their headers`() {
        val html = createHTML().main {
            trackedPage(listOf(trackedDeal("20.00", shippingCost = "6.99", minEver = "19.00", maxEver = "25.00")), listOf("amazon"))
        }
        val thead = html.substringAfter("<thead>").substringBefore("</thead>")
        val row = html.substringAfter("<tbody>").substringBefore("</tr>")
        fun classes(tag: String, of: String) =
            Regex("<$tag(?: class=\"([^\"]*)\")?[ >]").findAll(of).map { it.groupValues[1] }.toList()

        val headers = classes("th", thead)
        assertTrue(headers.size > 5, "expected the tracked header row, got: $headers")
        assertEquals(headers, classes("td", row))
    }

    /**
     * An unchecked row must say so. It rendered as a bare dash, and a T-shirt Amazon will not send
     * to Colombia sat on the tab looking like a clean price.
     */
    @Test
    fun `unchecked shipping is labelled, not a dash`() {
        val unchecked = trackedDeal("17.41").copy(coShipping = CoShipping.UNKNOWN, coShippingImportFree = null)
        val refused = trackedDeal("17.41").copy(coShipping = CoShipping.NO)
        val free = trackedDeal("17.41", shippingCost = "0.00")

        assertTrue(unchecked.shippingUnchecked)
        assertFalse(refused.shippingUnchecked, "a NO is an answer, not a gap")
        assertFalse(free.shippingUnchecked)

        // Shown, since a refused row is hidden by default -- see the non-shippable test below.
        val html = createHTML().main {
            trackedPage(listOf(unchecked, refused.copy(id = 2), free.copy(id = 3)), listOf("amazon"), showNoShip = true)
        }
        assertTrue(html.contains("unchecked-prov"), "the unchecked row carries its chip")
        assertTrue(html.contains("no ship to CO"), "a refusal is stated, not rendered as n/a")
        assertTrue(html.contains("1 not yet checked for shipping to Colombia"), "the count matches the one unchecked row")
    }

    /**
     * A verified "does not ship" is hidden by default and comes back with the toggle. Unchecked
     * rows are NOT hidden: they may ship, and hiding them would empty most of the tab while the
     * check catches up.
     */
    @Test
    fun `non-shippable rows are hidden unless asked for`() {
        val ships = trackedDeal("10.00", shippingCost = "0.00")
        val refused = trackedDeal("11.00").copy(id = 2, coShipping = CoShipping.NO)
        val unchecked = trackedDeal("12.00").copy(id = 3, coShipping = CoShipping.UNKNOWN, coShippingImportFree = null)
        val all = listOf(ships, refused, unchecked)

        fun shown(show: Boolean) = trackedShown(all, emptySet(), null, null, emptySet(), TrackedSort.PRICE, show).map { it.id }
        assertEquals(listOf(1L, 3L), shown(false))
        assertEquals(listOf(1L, 2L, 3L), shown(true))

        val hidden = createHTML().main { trackedPage(all, listOf("amazon")) }
        assertFalse(hidden.contains("tracked-2"), "the refused row is not rendered by default")
        assertTrue(hidden.contains("noship=show"), "the toggle offers the way back")
        assertFalse(hidden.contains("""checked="checked""""), "unticked by default")

        val visible = createHTML().main { trackedPage(all, listOf("amazon"), showNoShip = true) }
        assertTrue(visible.contains("tracked-2"))
        assertTrue(visible.contains("""checked="checked""""), "ticked when shown")
    }

    /** Every other control keeps the toggle's state, so filtering does not silently re-hide rows. */
    @Test
    fun `other controls carry the toggle along`() {
        val all = listOf(
            trackedDeal("10.00", shippingCost = "0.00"),
            trackedDeal("11.00").copy(id = 2, coShipping = CoShipping.NO),
        )
        val html = createHTML().main { trackedPage(all, listOf("amazon"), showNoShip = true) }
        val sortLinks = Regex("""href="(/tracked[^"]*sort=[^"]*)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(sortLinks.isNotEmpty())
        sortLinks.forEach { assertTrue(it.contains("noship=show"), "sort link drops the toggle: $it") }
    }

    /** The 608 V5 case: three ASINs of one shoe, all titled and pictured alike. */
    private fun variant(id: Long, asin: String, seen: Long, image: String? = "https://img/608.jpg") =
        trackedDeal("59.99").copy(
            id = id, asin = asin, sourceDealId = asin, title = "Men's 608 V5 — 8",
            imageUrl = image, lastSeenAt = Instant.ofEpochSecond(seen),
        )

    @Test
    fun `variants of one product collapse to a single row`() {
        val other = trackedDeal("20.00").copy(id = 9, title = "Something else — M", imageUrl = "https://img/x.jpg")
        val a = variant(1, "B07YZRSRW9", seen = 100)
        val b = variant(2, "B07YZR8WYF", seen = 300)
        val c = variant(3, "B07YZR5QS3", seen = 200)

        val (rows, others) = collapseVariants(listOf(other, a, b, c), emptySet())

        // The most recently checked variant stands in, and the input order is kept.
        assertEquals(listOf(9L, 2L), rows.map { it.id })
        assertEquals(listOf(3L, 1L), others.getValue(2).map { it.id })
        assertNull(others[9])
    }

    @Test
    fun `a row added by URL is the one kept`() {
        val searched = variant(1, "B07YZR8WYF", seen = 300)
        val added = variant(2, "B07YZRKYTC", seen = 100)
        assertEquals(listOf(2L), collapseVariants(listOf(searched, added), setOf("B07YZRKYTC")).rows.map { it.id })
    }

    @Test
    fun `rows without an image never group`() {
        val a = variant(1, "B1", seen = 1, image = null)
        val b = variant(2, "B2", seen = 2, image = null)
        assertEquals(2, collapseVariants(listOf(a, b), emptySet()).rows.size)
    }

    @Test
    fun `the collapsed row says how many it stands for and hides them all`() {
        val html = createHTML().main {
            trackedPage(listOf(variant(1, "B1", 1), variant(2, "B2", 2), variant(3, "B3", 3)), listOf("amazon"))
        }
        assertEquals(1, Regex("""id="tracked-\d+"""").findAll(html).count())
        assertTrue(html.contains("+2 variants"), html)
        assertTrue(html.contains("/tracked/3/hide?also=2,1"), html)
    }
}
