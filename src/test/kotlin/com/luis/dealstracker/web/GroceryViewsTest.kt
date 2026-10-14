package com.luis.dealstracker.web

import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.ingest.UnitSize
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import io.ktor.http.parametersOf
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroceryViewsTest {

    private fun row(
        id: Long = 1,
        title: String = "Café SELLO ROJO (600  gr)",
        price: String = "18000",
        listPrice: String? = null,
        usual: String? = "20000",
        days: Int = 5,
        onShelf: Boolean = true,
        size: UnitSize? = UnitSize("g", BigDecimal(600)),
        pinned: Boolean = false,
        source: String = "carulla-grocery",
        product: String = "$id",
        card: com.luis.dealstracker.model.CardOffer? = null,
    ) = GroceryRow(
        deal = Deal(
            id = id, source = source, sourceDealId = product, title = title,
            url = "https://www.carulla.com/x/p", merchant = source.substringBefore('-') + ".com", asin = null,
            category = null, imageUrl = null, brand = null, gtin = null, modelCode = null,
            matchKey = null, modelKey = null, currency = "COP",
            firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true,
            state = DealState.NEW, coShipping = CoShipping.YES, promoted = false, promotedAt = null,
            communityScore = null, postedAt = null, categoryGroup = "Groceries",
            coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
            coShippingCost = null, price = BigDecimal(price), listPrice = listPrice?.let(::BigDecimal),
            minPriceEver = null, maxPriceEver = null, observationCount = days + 1,
        ),
        size = size, group = "Pantry", pinnedAt = if (pinned) Instant.EPOCH else null,
        usual = usual?.let(::BigDecimal), low = usual?.let(::BigDecimal), days = days, onShelf = onShelf,
        card = card,
    )

    @Test
    fun `a deal is ten percent under the usual price, with enough history`() {
        assertEquals(BigDecimal("10.0"), row(price = "18000").belowUsual)
        assertTrue(row(price = "18000").isDeal)
        assertFalse(row(price = "18100").isDeal)
        // Two days is not a usual price yet: blank, not a percentage.
        assertNull(row(price = "10000", days = 2).belowUsual)
        assertFalse(row(price = "10000", days = 2).isDeal)
    }

    @Test
    fun `deals view shows only on-shelf deals, deepest first`() {
        val rows = listOf(
            row(id = 1, price = "18000"),                 // 10%
            row(id = 2, price = "14000"),                 // 30%
            row(id = 3, price = "19900"),                 // not a deal
            row(id = 4, price = "10000", onShelf = false), // off the shelf
        )
        val shown = groceryShown(rows, GroceryControls.from(parametersOf()))
        assertEquals(listOf(2L, 1L), shown.map { it.deal.id })
    }

    @Test
    fun `unit price sort puts unknown sizes last`() {
        val rows = listOf(
            row(id = 1, price = "12000", size = null),
            row(id = 2, price = "12000", size = UnitSize("g", BigDecimal(1000))), // 1,200/100g
            row(id = 3, price = "9000", size = UnitSize("g", BigDecimal(500))),   // 1,800/100g
        )
        val shown = groceryShown(rows, GroceryControls.from(parametersOf("view" to listOf("all"), "sort" to listOf("unit"))))
        assertEquals(listOf(2L, 3L, 1L), shown.map { it.deal.id })
    }

    @Test
    fun `search is carried on deals and all items but not pinned`() {
        val all = GroceryControls.from(parametersOf("view" to listOf("all"), "q" to listOf("café")))
        assertEquals("café", all.search)
        assertEquals("café", GroceryControls.from(parametersOf("q" to listOf("café"))).search)
        assertNull(GroceryControls.from(parametersOf("view" to listOf("pinned"), "q" to listOf("café"))).search)
        // url() itself keeps the search (sort/aisle links need it); the section tabs pass search = null.
        assertEquals("/groceries?q=caf%C3%A9", all.url(view = GroceryView.DEALS, sort = null))
        assertEquals("/groceries", all.url(view = GroceryView.DEALS, sort = null, search = null))
        val html = createHTML().main { groceryPage(listOf(row()), listOf("Pantry" to 1), all, listOf("carulla-grocery")) }
        assertTrue(html.contains("href=\"/groceries\""), "the Deals tab link must not carry the search")
        assertEquals("/groceries?view=pinned", all.url(view = GroceryView.PINNED, sort = null))
        assertEquals("!aceite", GroceryControls.from(parametersOf("q" to listOf("!aceite"))).search)
    }

    @Test
    fun `row cells line up with their headers`() {
        val html = createHTML().main {
            groceryPage(listOf(row()), listOf("Pantry" to 1), GroceryControls.from(parametersOf()), listOf("carulla-grocery"))
        }
        // The last table is the main one; the first is Pinned (empty here, so absent).
        val table = html.substringAfterLast("<table")
        val thead = table.substringAfter("<thead>").substringBefore("</thead>")
        val tr = table.substringAfter("<tbody>").substringBefore("</tr>")
        val th = Regex("<th[ >]").findAll(thead).count()
        val td = Regex("<td[ >]").findAll(tr).count()
        assertTrue(th > 5)
        assertEquals(th, td)
        assertTrue("−10.0%" in tr, tr)
        assertTrue("/100 g" in tr, tr)
    }

    /**
     * The store's badge truncates. Both pairs are real, read off the rendered pages on 2026-09-24:
     * Exito's 12 x Cebolla Blanca reads -20% at 20.8%, Carulla's penne reads -35% at 35.9%.
     */
    @Test
    fun `store discount is rounded down, as the store's own page shows it`() {
        assertEquals(20, row(price = "11215", listPrice = "14160").deal.storeDiscountPercent)
        assertEquals(35, row(price = "5990", listPrice = "9350").deal.storeDiscountPercent)
        assertEquals(32, row(price = "20440", listPrice = "30250").deal.storeDiscountPercent)
        assertNull(row(price = "5990", listPrice = null).deal.storeDiscountPercent)
    }

    /** Pinned keeps a staple Laureles stopped stocking; the other two views do not. */
    @Test
    fun `pinned view lists pins, including ones off the shelf`() {
        val rows = listOf(
            row(id = 1, pinned = true, price = "19900"),
            row(id = 2, pinned = true, onShelf = false),
            row(id = 3, price = "10000"),
        )
        val pinned = GroceryControls.from(parametersOf("view" to listOf("pinned")))
        assertEquals(listOf(1L, 2L), groceryShown(rows, pinned).map { it.deal.id }.sorted())
        assertEquals(listOf(3L), groceryShown(rows, GroceryControls.from(parametersOf())).map { it.deal.id })
    }

    @Test
    fun `pinned opens on the deepest discount versus usual, unknown last`() {
        val rows = listOf(
            row(id = 1, title = "Arroz", pinned = true, price = "19000"),                  // 5% under
            row(id = 2, title = "Leche", pinned = true, price = "14000"),                  // 30% under
            row(id = 3, title = "Aceite", pinned = true, price = "10000", days = 2),       // no usual yet
            row(id = 4, title = "Cafe", pinned = true, price = "21000"),                   // 5% ABOVE usual
            row(id = 5, title = "Sal", pinned = true, onShelf = false, price = "16000"),   // 20% under, off the shelf
        )
        val pinned = GroceryControls.from(parametersOf("view" to listOf("pinned")))
        assertEquals(GrocerySort.BELOW, pinned.sort)
        assertEquals(listOf(2L, 5L, 1L, 4L, 3L), groceryShown(rows, pinned).map { it.deal.id })
    }

    @Test
    fun `an explicit sort still wins on pinned, and all items still opens by name`() {
        val byName = GroceryControls.from(parametersOf("view" to listOf("pinned"), "sort" to listOf("name")))
        assertEquals(GrocerySort.NAME, byName.sort)
        assertEquals(GrocerySort.NAME, GroceryControls.from(parametersOf("view" to listOf("all"))).sort)
    }

    @Test
    fun `pinned is a tab beside deals and all items, with a count`() {
        val html = createHTML().main {
            groceryPage(
                listOf(row(pinned = true)), listOf("Pantry" to 1),
                GroceryControls.from(parametersOf("view" to listOf("pinned"))), listOf("carulla-grocery"),
                pinnedCount = 1,
            )
        }
        val scopes = html.substringAfter("<div class=\"scopes\">").substringBefore("</div>")
        assertEquals(listOf("Deals", "All items", "Pinned"), Regex(">([A-Za-z ]+)<").findAll(scopes).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList())
        assertTrue("scope-count\">1<" in scopes, scopes)
        // No aisle tags on the shopping list, and the unpin button tells the server which view it is on.
        // The label, not the `typetags` class, which the phone Sort row shares.
        assertFalse(">Aisle<" in html)
        assertTrue("unpin?view=pinned" in html, html)
    }

    /** No pins yet is a normal state: the tabs stay, and the page says how to pin. */
    @Test
    fun `an empty pinned view keeps the tabs`() {
        val html = createHTML().main {
            groceryPage(emptyList(), listOf("Pantry" to 1), GroceryControls.from(parametersOf("view" to listOf("pinned"))), listOf("carulla-grocery"))
        }
        assertTrue("class=\"scopes\"" in html)
        assertTrue("Nothing pinned yet" in html)
        assertFalse("Nothing read yet" in html)
    }

    private val stores = mapOf(
        "exito-grocery" to GroceryStore("Exito Laureles", "exito"),
        "exito-colombia-grocery" to GroceryStore("Exito Colombia", "exito"),
        "carulla-grocery" to GroceryStore("Carulla Laureles", "carulla"),
    )

    /** Real pair from 2026-09-24: Lechuga verde crespa PET 130 g, 6,780 at Laureles, 4,120 at Colombia. */
    @Test
    fun `one row per product per chain, at the cheaper store`() {
        val merged = mergeStores(
            listOf(
                row(id = 1, source = "exito-grocery", product = "lechuga", price = "6780"),
                row(id = 2, source = "exito-colombia-grocery", product = "lechuga", price = "4120"),
                // Same product id at Carulla is a different chain, so it stays its own row.
                row(id = 3, source = "carulla-grocery", product = "lechuga", price = "5000"),
            ),
            stores,
        )
        assertEquals(2, merged.size)
        val exito = merged.single { it.deal.source.startsWith("exito") }
        assertEquals("Exito Colombia", exito.store)
        assertEquals(listOf("Exito Laureles"), exito.others.map { it.store })
        assertEquals("Carulla Laureles", merged.single { it.deal.source == "carulla-grocery" }.store)
    }

    /** A price you cannot pay at that store today is not the cheaper option. */
    @Test
    fun `an off-shelf copy loses whatever its price`() {
        val merged = mergeStores(
            listOf(
                row(id = 1, source = "exito-grocery", product = "p", price = "9000"),
                row(id = 2, source = "exito-colombia-grocery", product = "p", price = "5000", onShelf = false),
            ),
            stores,
        )
        assertEquals("Exito Laureles", merged.single().store)
    }

    @Test
    fun `a pin at either store pins the product`() {
        val merged = mergeStores(
            listOf(
                row(id = 1, source = "exito-grocery", product = "p", price = "5000"),
                row(id = 2, source = "exito-colombia-grocery", product = "p", price = "6000", pinned = true),
            ),
            stores,
        )
        assertTrue(merged.single().pinnedAt != null)
    }

    /**
     * Each store keeps its own history, so the cheaper store is judged against its OWN usual price.
     * Colombia at 4,120 against its usual 4,200 is 1.9% down -- not 39% off the Laureles price.
     */
    @Test
    fun `vs usual compares a store with itself`() {
        val merged = mergeStores(
            listOf(
                row(id = 1, source = "exito-grocery", product = "p", price = "6780", usual = "6780"),
                row(id = 2, source = "exito-colombia-grocery", product = "p", price = "4120", usual = "4200"),
            ),
            stores,
        ).single()
        assertEquals(BigDecimal("1.9"), merged.belowUsual)
        assertFalse(merged.isDeal)
    }

    @Test
    fun `the store cell shows the other store's price`() {
        val merged = mergeStores(
            listOf(
                row(id = 1, source = "exito-grocery", product = "p", price = "6780"),
                row(id = 2, source = "exito-colombia-grocery", product = "p", price = "4120"),
            ),
            stores,
        )
        val html = createHTML().main {
            groceryPage(merged, listOf("Pantry" to 1), GroceryControls.from(parametersOf("view" to listOf("all"))), stores.keys.toList())
        }
        val cell = html.substringAfter("<td class=\"col-size\"").substringBefore("</td>")
        assertTrue("Exito Colombia" in cell, cell)
        assertTrue("Laureles $6,780" in cell, cell)
    }

    private val bogota = java.time.ZoneId.of("America/Bogota")
    private val nowAt = Instant.parse("2026-09-25T13:00:00Z") // 08:00 in Medellín

    private fun status(
        lastStarted: String?, lastError: String? = null, lastItems: Int? = 7187,
        finished: Boolean = true, success: String? = lastStarted,
    ) = com.luis.dealstracker.db.SyncStatus(
        source = "exito-grocery",
        lastStarted = lastStarted?.let(Instant::parse),
        lastFinished = if (finished) lastStarted?.let(Instant::parse) else null,
        lastItems = lastItems, lastError = lastError,
        lastSuccess = success?.let(Instant::parse), lastSuccessItems = success?.let { 7187 },
        checkedAt = nowAt,
    )

    @Test
    fun `clock times read as local, relative to today`() {
        assertEquals("today 07:02", clockTime(Instant.parse("2026-09-25T12:02:00Z"), nowAt, bogota))
        assertEquals("yesterday 15:01", clockTime(Instant.parse("2026-09-24T20:01:00Z"), nowAt, bogota))
        assertEquals("Sep 23 07:00", clockTime(Instant.parse("2026-09-23T12:00:00Z"), nowAt, bogota))
    }

    /** The first Carulla crawl finished "cleanly" with 0 items; to a reader of prices that failed. */
    @Test
    fun `a zero-item run is a failure, a running one is not`() {
        assertTrue(status("2026-09-25T12:00:00Z", lastItems = 0).failed)
        assertTrue(status("2026-09-25T12:00:00Z", lastError = "timeout").failed)
        assertFalse(status("2026-09-25T12:00:00Z").failed)
        assertTrue(status("2026-09-25T12:50:00Z", finished = false).running)
        assertFalse(status("2026-09-25T12:50:00Z", finished = false).failed)
    }

    /** After a failed run the page still shows the older prices, and must say which ones. */
    @Test
    fun `footer names the last good read beside a failure, and the next run`() {
        val line = SyncLine(
            "Exito Laureles",
            status("2026-09-25T12:00:00Z", lastError = "HTTP 503", success = "2026-09-24T20:01:00Z"),
            next = Instant.parse("2026-09-25T20:00:00Z"), hours = listOf(7, 15),
        )
        val html = createHTML().main {
            groceryPage(emptyList(), emptyList(), GroceryControls.from(parametersOf()), listOf("exito-grocery"), sync = listOf(line))
        }
        val footer = html.substringAfter("Last sync")
        assertTrue("Exito Laureles" in footer, footer)
        assertTrue("failed" in footer && "HTTP 503" in footer, footer)
        assertTrue("daily at 07:00, 15:00" in footer, footer)
        // Rendered even when the body returned early ("Nothing read yet").
        assertTrue("Nothing read yet" in html)
    }

    /** A restart mid-crawl leaves no finish time; that must not read "running" forever. */
    @Test
    fun `an unfinished run older than an hour is interrupted, not running`() {
        val stale = status("2026-09-25T11:30:00Z", finished = false) // 90 min before nowAt
        assertFalse(stale.running)
        assertTrue(stale.interrupted)
        assertFalse(stale.failed)
        val fresh = status("2026-09-25T12:50:00Z", finished = false)
        assertTrue(fresh.running)
        assertFalse(fresh.interrupted)
    }

    private fun card(price: String, percent: String, scope: com.luis.dealstracker.model.CardScope = com.luis.dealstracker.model.CardScope.MASTERCARD) =
        com.luis.dealstracker.model.CardOffer(BigDecimal(price), BigDecimal(percent), scope)

    /** The pinned Gatorade: shelf 4,100, "$3.280 Mastercard" on exito.com. */
    @Test
    fun `the card price is a chip under the shelf price`() {
        val html = createHTML().main {
            groceryPage(
                listOf(row(price = "4100", usual = "4100", card = card("3280", "20.00"))),
                listOf("Drinks" to 1), GroceryControls.from(parametersOf("view" to listOf("all"))), listOf("exito-grocery"),
            )
        }
        val tr = html.substringAfterLast("<tbody>").substringBefore("</tr>")
        val price = tr.substringAfter("class=\"price\">").substringBefore("</td>")
        assertTrue(price.startsWith("$4,100"), price)
        assertTrue("Mastercard −20% → $3,280" in price, price)
    }

    @Test
    fun `a store-card-only offer says so`() {
        val html = createHTML().main {
            groceryPage(
                listOf(row(price = "10000", usual = "10000", card = card("7000", "30.00", com.luis.dealstracker.model.CardScope.STORE_CARD))),
                listOf("Drinks" to 1), GroceryControls.from(parametersOf("view" to listOf("all"))), listOf("exito-grocery"),
            )
        }
        assertTrue("card-price store-only" in html)
        assertTrue("Store card only −30% → $7,000" in html)
    }

    /** A one-day card promotion is not the item getting cheaper: nobody without that card can pay it. */
    @Test
    fun `vs usual and the deal rule ignore the card price`() {
        val r = row(price = "20000", usual = "20000", card = card("12000", "40.00"))
        assertEquals(BigDecimal("0.0"), r.belowUsual)
        assertFalse(r.isDeal)
        assertEquals(emptyList(), groceryShown(listOf(r), GroceryControls.from(parametersOf())))
    }

    @Test
    fun `card sort puts the biggest saving over the shelf first`() {
        val rows = listOf(
            row(id = 1, price = "10000", card = card("9000", "10.00")),  // saves 10%
            row(id = 2, price = "10000"),                                 // no card price
            row(id = 3, price = "4100", card = card("3280", "20.00")),   // saves 20%
        )
        val shown = groceryShown(rows, GroceryControls.from(parametersOf("view" to listOf("all"), "sort" to listOf("card"))))
        assertEquals(listOf(3L, 1L, 2L), shown.map { it.deal.id })
    }

    private val allStores = mapOf(
        "exito-grocery" to GroceryStore("Exito Laureles", "exito"),
        "exito-colombia-grocery" to GroceryStore("Exito Colombia", "exito"),
        "carulla-grocery" to GroceryStore("Carulla Laureles", "carulla"),
        "pricesmart-americas-grocery" to GroceryStore("PriceSmart Las Américas", "pricesmart"),
        "pricesmart-poblado-grocery" to GroceryStore("PriceSmart El Poblado", "pricesmart"),
    )

    @Test
    fun `chain names come from the stores' labels`() {
        assertEquals(mapOf("exito" to "Exito", "carulla" to "Carulla", "pricesmart" to "PriceSmart"), chainNames(allStores))
    }

    /** "Exito" means both Exitos; no chain means every store; an unknown chain means none. */
    @Test
    fun `a chain selects all of its stores`() {
        assertEquals(listOf("exito-colombia-grocery", "exito-grocery"), sourcesForChains(allStores, setOf("exito")))
        assertEquals(5, sourcesForChains(allStores, emptySet()).size)
        assertEquals(emptyList(), sourcesForChains(allStores, setOf("olimpica")))
        assertEquals(
            listOf("carulla-grocery", "pricesmart-americas-grocery", "pricesmart-poblado-grocery"),
            sourcesForChains(allStores, setOf("carulla", "pricesmart")),
        )
    }

    @Test
    fun `the store filter round-trips through the url, alongside aisle and view`() {
        val c = GroceryControls.from(parametersOf("view" to listOf("all"), "store" to listOf("pricesmart,Exito"), "group" to listOf("Pantry")))
        assertEquals(setOf("pricesmart", "exito"), c.chains)
        assertEquals("/groceries?view=all&store=exito%2Cpricesmart&group=Pantry", c.url())
        // Kept on Pinned, where the aisle is dropped.
        assertEquals("/groceries?view=pinned&store=exito%2Cpricesmart", c.url(view = GroceryView.PINNED, sort = null))
    }

    @Test
    fun `store tags sit above the aisle tags, and toggle`() {
        val html = createHTML().main {
            groceryPage(
                listOf(row()), listOf("Pantry" to 1), GroceryControls.from(parametersOf("store" to listOf("pricesmart"))),
                listOf("carulla-grocery"),
                chainCounts = listOf(Triple("exito", "Exito", 7300), Triple("carulla", "Carulla", 4800), Triple("pricesmart", "PriceSmart", 870)),
            )
        }
        val store = html.indexOf("typetags-label\">Store<"); val aisle = html.indexOf("typetags-label\">Aisle<")
        assertTrue(store in 0 until aisle, "store $store, aisle $aisle")
        val tags = html.substring(store, aisle)
        assertTrue("href=\"/groceries\" class=\"typetag on\">PriceSmart" in tags, tags)
        assertTrue("href=\"/groceries?store=exito%2Cpricesmart\" class=\"typetag\">Exito" in tags, tags)
        assertTrue("7,300" in tags)
    }

    @Test
    fun `no store row with a single chain`() {
        val html = createHTML().main {
            groceryPage(listOf(row()), listOf("Pantry" to 1), GroceryControls.from(parametersOf()), listOf("carulla-grocery"),
                chainCounts = listOf(Triple("carulla", "Carulla", 4800)))
        }
        assertFalse("typetags-label\">Store<" in html)
    }
}
