package com.luis.dealstracker.web

import com.luis.dealstracker.db.ChainRival
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.db.ShelfPrice
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import java.math.BigDecimal
import java.time.Instant
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exito and Carulla share a barcode on ~4,400 products, and the grocery tab only ever compared a chain's
 * stores with each other: Kinder at Exito $9,600 said nothing about Carulla's $10,080.
 */
class GroceryRivalsTest {

    private val stores = linkedMapOf(
        "exito-grocery" to GroceryStore("Exito Laureles", "exito"),
        "exito-colombia-grocery" to GroceryStore("Exito Colombia", "exito"),
        "carulla-grocery" to GroceryStore("Carulla Laureles", "carulla"),
        "pricesmart-americas-grocery" to GroceryStore("PriceSmart Las Américas", "pricesmart"),
    )

    private fun row(
        id: Long, source: String, price: String, key: String? = "gtin:1", title: String = "Kinder",
        onShelf: Boolean = true, pinned: Boolean = false,
    ) = GroceryRow(
        deal = Deal(
            id = id, source = source, sourceDealId = "p$id", title = title,
            url = "https://example.com/$id", merchant = source.substringBefore('-') + ".com", asin = null,
            category = null, imageUrl = null, brand = null, gtin = null, modelCode = null,
            matchKey = key, modelKey = null, currency = "COP",
            firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
            state = DealState.NEW, coShipping = CoShipping.YES, promoted = false, promotedAt = null,
            communityScore = null, postedAt = null, categoryGroup = "Groceries",
            coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
            coShippingCost = null, price = BigDecimal(price), listPrice = null,
            minPriceEver = null, maxPriceEver = null, observationCount = 1,
        ),
        size = null, group = "Pantry", pinnedAt = if (pinned) Instant.EPOCH else null, usual = null, low = null, days = 0,
        onShelf = onShelf, chain = stores[source]?.chain ?: source,
    )

    private fun rival(chain: String, price: String, id: Long = 99) =
        ChainRival(stores.values.first { it.chain == chain }.label, chain, BigDecimal(price), "COP", id)

    private fun shelf(id: Long, source: String, price: String, key: String = "gtin:1", currency: String = "COP") =
        ShelfPrice(id, source, key, BigDecimal(price), currency)

    @Test
    fun `the rival is the cheapest copy at another chain, never the row's own chain`() {
        val exito = row(1, "exito-grocery", "9600")
        val prices = listOf(
            shelf(1, "exito-grocery", "9600"),
            shelf(2, "exito-colombia-grocery", "9000"), // cheaper, but the same chain: mergeStores' business
            shelf(3, "carulla-grocery", "10080"),
        )
        val rival = withRivals(listOf(exito), prices, stores).single().rival
        assertEquals(ChainRival("Carulla Laureles", "carulla", BigDecimal("10080"), "COP", 3), rival)
    }

    @Test
    fun `no barcode, another barcode, or another currency means no rival`() {
        val prices = listOf(shelf(3, "carulla-grocery", "10080"), shelf(4, "carulla-grocery", "1", key = "gtin:2"))
        assertNull(withRivals(listOf(row(1, "pricesmart-americas-grocery", "9600", key = null)), prices, stores).single().rival)
        assertNull(withRivals(listOf(row(1, "exito-grocery", "9600", key = "gtin:9")), prices, stores).single().rival)
        assertNull(
            withRivals(listOf(row(1, "exito-grocery", "9600")), listOf(shelf(3, "carulla-grocery", "3", currency = "USD")), stores)
                .single().rival,
        )
    }

    @Test
    fun `the gap is the rival's price over this one, negative when the rival is cheaper`() {
        val r = ChainRival("Carulla Laureles", "carulla", BigDecimal("10080"), "COP", 3)
        assertEquals(BigDecimal("5.0"), row(1, "exito-grocery", "9600").copy(rival = r).rivalGap)
        assertEquals(BigDecimal("-4.8"), row(1, "carulla-grocery", "10080").copy(rival = r.copy(price = BigDecimal("9600"))).rivalGap)
        assertNull(row(1, "exito-grocery", "9600").rivalGap)
    }

    @Test
    fun `a cheaper rival is highlighted with its sign, a dearer one stays quiet`() {
        val cheaper = groceryRowFragment(
            row(1, "carulla-grocery", "10080").copy(rival = ChainRival("Exito Laureles", "exito", BigDecimal("9600"), "COP", 9)),
            GroceryView.DEALS,
        )
        assertTrue("store-rival cheaper" in cheaper, cheaper)
        assertTrue("href=\"/deal/9\"" in cheaper)
        assertTrue("Exito \$9,600" in cheaper, cheaper)
        assertTrue("−4.8%" in cheaper)

        val dearer = groceryRowFragment(
            row(1, "exito-grocery", "9600").copy(rival = ChainRival("Carulla Laureles", "carulla", BigDecimal("10080"), "COP", 3)),
            GroceryView.DEALS,
        )
        assertTrue("class=\"store-rival\"" in dearer, dearer)
        assertTrue("Carulla \$10,080" in dearer && "+5.0%" in dearer, dearer)
        assertFalse("store-rival" in groceryRowFragment(row(1, "exito-grocery", "9600"), GroceryView.DEALS))
    }

    /** Only the page on screen is looked up, so All items does not ask about 15,000 barcodes per load. */
    @Test
    fun `only the shown page's barcodes are looked up`() {
        val rows = (1L..(GROCERY_PAGE_SIZE + 5)).map { row(it, "exito-grocery", "1000", key = "gtin:$it", title = "Item %05d".format(it)) }
        val controls = GroceryControls(view = GroceryView.ALL, groups = emptySet(), search = null, sort = GrocerySort.NAME, page = 2)
        var asked: Collection<String> = emptyList()
        val out = withRivalsFor(rows, controls, stores) { keys ->
            asked = keys
            keys.map { shelf(10_000 + it.removePrefix("gtin:").toLong(), "carulla-grocery", "900", key = it) }
        }
        assertEquals(5, asked.size)
        assertEquals(5, out.count { it.rival != null })
        assertEquals(rows.size, out.size)
    }

    @Test
    fun `sorting by the other chain puts the biggest saving elsewhere first, no rival last`() {
        val rows = listOf(
            row(1, "exito-grocery", "10000", title = "A").copy(rival = rival("carulla", "9800")),  // -2%
            row(2, "exito-grocery", "10000", title = "B"),
            row(3, "exito-grocery", "10000", title = "C").copy(rival = rival("carulla", "8000")),  // -20%
            row(4, "exito-grocery", "10000", title = "D").copy(rival = rival("carulla", "12000")), // +20%
        )
        val controls = GroceryControls(view = GroceryView.ALL, groups = emptySet(), search = null, sort = GrocerySort.RIVAL)
        assertEquals(listOf(3L, 1L, 4L, 2L), groceryShown(rows, controls).map { it.deal.id })
        val cheaper = groceryShown(rows, controls.copy(cheaperElsewhere = true))
        assertEquals(listOf(3L, 1L), cheaper.map { it.deal.id }, "only the rows another chain sells for less")
    }

    @Test
    fun `the filter, the sort and Pinned look up every row in view, otherwise only the page`() {
        val base = GroceryControls(view = GroceryView.ALL, groups = emptySet(), search = null, sort = GrocerySort.NAME)
        assertFalse(needsAllRivals(base))
        assertTrue(needsAllRivals(base.copy(sort = GrocerySort.RIVAL)))
        assertTrue(needsAllRivals(base.copy(cheaperElsewhere = true)))
        assertTrue(needsAllRivals(base.copy(view = GroceryView.PINNED)))

        val rows = (1L..(GROCERY_PAGE_SIZE + 5)).map { row(it, "exito-grocery", "1000", key = "gtin:$it", title = "Item %05d".format(it)) }
        var asked = 0
        withRivalsFor(rows, base.copy(cheaperElsewhere = true), stores) { keys -> asked = keys.size; emptyList() }
        assertEquals(rows.size, asked)
    }

    @Test
    fun `the controls carry the filter everywhere and the stop only on Pinned`() {
        val c = GroceryControls.from(io.ktor.http.parametersOf("view" to listOf("pinned"), "elsewhere" to listOf("1"), "buy" to listOf("Carulla")))
        assertTrue(c.cheaperElsewhere)
        assertEquals("carulla", c.buyAt)
        assertEquals("/groceries?view=pinned&elsewhere=1&buy=carulla", c.url())
        assertEquals("/groceries?view=all&elsewhere=1", c.url(view = GroceryView.ALL, sort = null))
        assertNull(GroceryControls.from(io.ktor.http.parametersOf("buy" to listOf("exito"))).buyAt, "Deals has no plan")
    }

    @Test
    fun `an item is bought where it is cheapest, ties and no rival at its own store`() {
        assertEquals("carulla" to BigDecimal("9000"), row(1, "exito-grocery", "10000").copy(rival = rival("carulla", "9000")).bestBuy())
        assertEquals("exito" to BigDecimal("10000"), row(1, "exito-grocery", "10000").copy(rival = rival("carulla", "10000")).bestBuy())
        assertEquals("exito" to BigDecimal("10000"), row(1, "exito-grocery", "10000").bestBuy())
        assertEquals(
            "carulla" to BigDecimal("12000"),
            row(1, "exito-grocery", "10000", onShelf = false).copy(rival = rival("carulla", "12000")).bestBuy(),
            "off its own shelf, the other chain is the only place to buy it",
        )
        assertNull(row(1, "exito-grocery", "10000", onShelf = false).bestBuy())
    }

    @Test
    fun `the plan splits Pinned by cheapest chain, totals each trip and counts the saving`() {
        val pinned = listOf(
            row(1, "exito-grocery", "10000", pinned = true).copy(rival = rival("carulla", "9000")),        // Carulla, saves 1,000
            row(2, "exito-grocery", "5000", pinned = true).copy(rival = rival("carulla", "6000")),         // Exito
            row(3, "carulla-grocery", "3000", pinned = true),                                               // Carulla
            row(4, "exito-grocery", "7000", pinned = true, onShelf = false).copy(rival = rival("carulla", "8000")), // Carulla, no saving
            row(5, "pricesmart-americas-grocery", "20000", pinned = true, key = null),                    // PriceSmart
            row(6, "exito-grocery", "4000", pinned = true, onShelf = false),                               // nowhere
        )
        val plan = shoppingPlan(pinned)!!
        assertEquals(
            listOf(
                PlanStop("carulla", 3, BigDecimal("20000")),
                PlanStop("exito", 1, BigDecimal("5000")),
                PlanStop("pricesmart", 1, BigDecimal("20000")),
            ),
            plan.stops,
        )
        assertEquals(BigDecimal("1000"), plan.saving)
        assertEquals(1, plan.unavailable)

        val controls = GroceryControls(view = GroceryView.PINNED, groups = emptySet(), search = null, sort = GrocerySort.BELOW, buyAt = "carulla")
        assertEquals(setOf(1L, 3L, 4L), groceryShown(pinned, controls).map { it.deal.id }.toSet(), "the Carulla trip, wherever the row is from")
    }

    @Test
    fun `Pinned shows the plan with a link per trip and the saving`() {
        val pinned = listOf(
            row(1, "exito-grocery", "10000", pinned = true).copy(rival = rival("carulla", "9000")),
            row(2, "exito-grocery", "5000", pinned = true),
        )
        val controls = GroceryControls(view = GroceryView.PINNED, groups = emptySet(), search = null, sort = GrocerySort.BELOW)
        val html = createHTML().main {
            groceryPage(
                pinned, listOf("Pantry" to 2), controls, stores.keys.toList(), pinnedCount = 2,
                chainCounts = listOf(Triple("exito", "Exito", 1), Triple("carulla", "Carulla", 1)),
            )
        }
        val plan = html.substringAfter("shop-plan\"").substringBefore("</div>")
        assertTrue("href=\"/groceries?view=pinned&amp;buy=carulla\"" in plan, plan)
        assertTrue("Carulla" in plan && "\$9,000" in plan, plan)
        assertTrue("Exito" in plan && "\$5,000" in plan, plan)
        assertTrue("saves \$1,000" in plan, plan)
        assertFalse("shop-plan" in createHTML().main {
            groceryPage(pinned, listOf("Pantry" to 2), controls.copy(view = GroceryView.ALL), stores.keys.toList())
        }, "only on Pinned")
    }
}
