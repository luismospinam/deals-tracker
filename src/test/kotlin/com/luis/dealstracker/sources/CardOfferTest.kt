package com.luis.dealstracker.sources

import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.sources.vtex.IsSearchResponse
import com.luis.dealstracker.sources.vtex.VtexGrocerySource
import com.luis.dealstracker.sources.vtex.VtexOffer
import com.luis.dealstracker.sources.vtex.VtexParam
import com.luis.dealstracker.sources.vtex.VtexTeaser
import com.luis.dealstracker.sources.vtex.VtexTeaserConditions
import com.luis.dealstracker.sources.vtex.VtexTeaserEffects
import com.luis.dealstracker.sources.vtex.cardOfferOf
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Card-only prices from VTEX teasers. The fixture holds real Intelligent Search offers captured
 * 2026-09-25 at Exito Laureles and Carulla Laureles. Every expected card price was read off the
 * store's own rendered product page the same day ("$3.280 Mastercard / $4.100 Otros").
 */
class CardOfferTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val source = VtexGrocerySource()
    private val products = json.decodeFromString<IsSearchResponse>(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/vtex-card-teasers.json")).bufferedReader().readText(),
    ).products.associateBy { it.productId }

    private fun deal(id: String) =
        source.toGroceryDeal(products.getValue(id), "https://www.exito.com", "COP", "Drinks")!!

    /** The pinned item that started this: shelf 4,100, page "$3.280 Mastercard". */
    @Test
    fun `gatorade blue ice at exito laureles`() {
        val d = deal("3173448")
        assertEquals(BigDecimal("4100.00"), d.price, "the shelf price stays the main price")
        assertEquals(BigDecimal("3280.00"), d.cardOffer?.price)
        assertEquals(BigDecimal("20.00"), d.cardOffer?.percent)
        assertEquals(CardScope.MASTERCARD, d.cardOffer?.scope)
    }

    /** Shelf 5,193 on a 6,110 item, 25% card: the page shows $4,583 -- off 6,110, not stacked. */
    @Test
    fun `the card discount does not stack on a shelf promotion`() {
        assertEquals(BigDecimal("4583.00"), deal("130605").cardOffer?.price)
    }

    /** ListPrice 4,220 but PriceWithoutDiscount 4,100; the page's $3,280 is 20% off the latter. */
    @Test
    fun `the percentage comes off the price before promotion, not the list price`() {
        assertEquals(BigDecimal("3280.00"), deal("3248411").cardOffer?.price)
    }

    /** Already 3,280 on the shelf; 20% off 4,100 is 3,280 too, and the page shows no card price. */
    @Test
    fun `a card price that does not beat the shelf is dropped`() {
        assertNull(deal("408762").cardOffer)
    }

    /** Carulla lists the ids the other way round -- "501,4" -- and it is the same Mastercard offer. */
    @Test
    fun `carulla's reversed id order is still mastercard`() {
        val d = deal("3163405")
        assertEquals(BigDecimal("19435.00"), d.cardOffer?.price)
        assertEquals(CardScope.MASTERCARD, d.cardOffer?.scope)
    }

    /** "Pague 2, lleve 3" is a multi-buy on a SKU list, not a card price. */
    @Test
    fun `a pay-x-take-y multibuy is not a card price`() {
        assertNull(deal("3088489").cardOffer)
    }

    private fun offer(vararg teasers: VtexTeaser, price: String = "10000") =
        VtexOffer(Price = BigDecimal(price), ListPrice = BigDecimal(price), PriceWithoutDiscount = BigDecimal(price), teasers = teasers.toList())

    private fun cardTeaser(ids: String, percent: String, minimum: Int = 0) = VtexTeaser(
        name = "mastercard_${percent}_x",
        conditions = VtexTeaserConditions(listOf(VtexParam("PaymentMethodId", ids)), minimum),
        effects = VtexTeaserEffects(listOf(VtexParam("PercentualDiscount", percent))),
    )

    /** Not yet seen live, but the store runs promotions on its own card alone. */
    @Test
    fun `store-card-only is its own scope`() {
        val card = cardOfferOf(offer(cardTeaser("501", "30")), BigDecimal.ONE, BigDecimal("10000"))
        assertEquals(CardScope.STORE_CARD, card?.scope)
        assertEquals(BigDecimal("7000.00"), card?.price)
    }

    @Test
    fun `the best of several card teasers wins, and an unknown card is kept as other`() {
        val best = cardOfferOf(
            offer(cardTeaser("4,501", "20"), cardTeaser("501", "35")),
            BigDecimal.ONE, BigDecimal("10000"),
        )
        assertEquals(CardScope.STORE_CARD, best?.scope)
        assertEquals(CardScope.OTHER, cardOfferOf(offer(cardTeaser("999", "10")), BigDecimal.ONE, BigDecimal("10000"))?.scope)
    }

    @Test
    fun `a card teaser needing a minimum quantity is a multibuy, not a card price`() {
        assertNull(cardOfferOf(offer(cardTeaser("4,501", "20", minimum = 2)), BigDecimal.ONE, BigDecimal("10000")))
    }

    /** Weighed goods are quoted per kg; the card price scales with the pack exactly as the shelf does. */
    @Test
    fun `weighed goods scale the card price by the pack`() {
        val card = cardOfferOf(offer(cardTeaser("4,501", "20"), price = "20000"), BigDecimal("0.5"), BigDecimal("10000.00"))
        assertEquals(BigDecimal("8000.00"), card?.price)
    }

    private fun tableTeaser(condition: VtexParam, discount: String) = VtexTeaser(
        name = "x_aliado_tabladeprecios_electrodigital",
        conditions = VtexTeaserConditions(listOf(condition), 0),
        effects = VtexTeaserEffects(listOf(VtexParam("PromotionalPriceTableItemsIds", "1"), VtexParam("PromotionalPriceTableItemsDiscount", discount))),
    )

    /** Nevecon Electrolux 423 L, exito.com 2026-11-01: list 3,199,900, shelf 2,399,925. */
    @Test
    fun `a price-table discount comes off the list price and the best one wins`() {
        val o = VtexOffer(
            Price = BigDecimal("2399925"), ListPrice = BigDecimal("3199900"), PriceWithoutDiscount = BigDecimal("3199900"),
            teasers = listOf(
                tableTeaser(VtexParam("RestrictionsBins", "555845,570423"), "1300900.00"),
                tableTeaser(VtexParam("PaymentMethodId", "102"), "1200900.00"),
            ),
        )
        val card = cardOfferOf(o, BigDecimal.ONE, BigDecimal("2399925.00"))
        assertEquals(BigDecimal("1899000.00"), card?.price)
        assertEquals(BigDecimal("40.65"), card?.percent)
        assertEquals(CardScope.OTHER, card?.scope)
    }

    @Test
    fun `the addi price-table offer alone is kept`() {
        val o = VtexOffer(
            Price = BigDecimal("2399925"), ListPrice = BigDecimal("3199900"), PriceWithoutDiscount = BigDecimal("3199900"),
            teasers = listOf(tableTeaser(VtexParam("PaymentMethodId", "102"), "1200900.00")),
        )
        assertEquals(BigDecimal("1999000.00"), cardOfferOf(o, BigDecimal.ONE, BigDecimal("2399925.00"))?.price)
    }

    @Test
    fun `a price-table discount that does not beat the shelf is dropped`() {
        val o = VtexOffer(
            Price = BigDecimal("2399925"), ListPrice = BigDecimal("3199900"), PriceWithoutDiscount = BigDecimal("3199900"),
            teasers = listOf(tableTeaser(VtexParam("RestrictionsBins", "555845"), "500000.00")),
        )
        assertNull(cardOfferOf(o, BigDecimal.ONE, BigDecimal("2399925.00")))
    }

    /** Televisor CAIXUN 55", exito.com 2026-11-05: the page shows 1,279,903 beside the Tarjeta Éxito logo. */
    @Test
    fun `exito's own BIN price table is the store card`() {
        val o = VtexOffer(
            Price = BigDecimal("1399900"), ListPrice = BigDecimal("3999900"), PriceWithoutDiscount = BigDecimal("3999900"),
            teasers = listOf(
                tableTeaser(VtexParam("RestrictionsBins", "555845,570423,590309"), "2719997.00")
                    .copy(name = "exito1_aliado_tabladeprecios_electrodigital"),
            ),
        )
        val card = cardOfferOf(o, BigDecimal.ONE, BigDecimal("1399900.00"))
        assertEquals(BigDecimal("1279903.00"), card?.price)
        assertEquals(CardScope.STORE_CARD, card?.scope)
        assertEquals(CardScope.STORE_CARD, com.luis.dealstracker.sources.vtex.scopeOfBinTable("EXITO_aliado_tabladeprecios_jul11"))
        // Another bank's BIN list stays unnamed rather than guessed at.
        assertEquals(CardScope.OTHER, com.luis.dealstracker.sources.vtex.scopeOfBinTable("avvillas_20_prov_mercado"))
        assertEquals(CardScope.OTHER, com.luis.dealstracker.sources.vtex.scopeOfBinTable(null))
    }

    private fun binPercentTeaser(name: String, percent: String) = VtexTeaser(
        name = name,
        conditions = VtexTeaserConditions(listOf(VtexParam("RestrictionsBins", "231030,266000,510120,515067")), 0),
        effects = VtexTeaserEffects(listOf(VtexParam("PercentualDiscount", percent))),
    )

    /**
     * Limón Amarillo Importado at Exito Laureles, 2026-11-10: shelf 3,487/kg, before promotion 4,980, and
     * `exito1_35_aliado_mercado_fruver_...` -- 35% with Tarjeta Éxito, off the 4,980 and not stacked.
     */
    @Test
    fun `exito's own-card percentage on the grocery aisles is a store-card price`() {
        val o = VtexOffer(
            Price = BigDecimal("3487"), ListPrice = BigDecimal("8780"), PriceWithoutDiscount = BigDecimal("4980"),
            teasers = listOf(binPercentTeaser("exito1_35_aliado_mercado_fruver_id40794_06oct_29dic", "35")),
        )
        val card = cardOfferOf(o, BigDecimal.ONE, BigDecimal("3487.00"))
        assertEquals(BigDecimal("3237.00"), card?.price)
        assertEquals(BigDecimal("35.00"), card?.percent)
        assertEquals(CardScope.STORE_CARD, card?.scope)
        // Sold by weight: the 150 g lemon scales with the pack, as its shelf price does.
        assertEquals(BigDecimal("486.00"), cardOfferOf(o, BigDecimal("0.15"), BigDecimal("523.05"))?.price)
    }

    /** A percentage already in the shelf price reproduces it exactly, and saves nothing. */
    @Test
    fun `an own-card percentage already in the shelf price is dropped`() {
        val o = VtexOffer(
            Price = BigDecimal("3237"), ListPrice = BigDecimal("4980"), PriceWithoutDiscount = BigDecimal("4980"),
            teasers = listOf(binPercentTeaser("exito1_35_aliado_mercado_fruver_x", "35")),
        )
        assertNull(cardOfferOf(o, BigDecimal.ONE, BigDecimal("3237.00")))
    }

    /** Easy's BIN percentages are still ignored: only Exito's own card is read this way. */
    @Test
    fun `another bank's BIN percentage is still not read`() {
        val o = VtexOffer(
            Price = BigDecimal("411920"), ListPrice = BigDecimal("514900"), PriceWithoutDiscount = BigDecimal("514900"),
            teasers = listOf(binPercentTeaser("SW T. INT COD Y TMC 20", "35"), binPercentTeaser("avvillas_20_prov_mercado", "35")),
        )
        assertNull(cardOfferOf(o, BigDecimal.ONE, BigDecimal("411920.00")))
        assertEquals(CardScope.STORE_CARD, com.luis.dealstracker.sources.vtex.scopeOfBinTable("exito1_35_aliado_mercado_fruver_id40794"))
        assertEquals(CardScope.STORE_CARD, com.luis.dealstracker.sources.vtex.scopeOfBinTable("exito_20_aliado_x"))
        // Carulla's copy of the same promotion: same id, same BINs.
        assertEquals(CardScope.STORE_CARD, com.luis.dealstracker.sources.vtex.scopeOfBinTable("carulla1_35_aliado_mercado_fruver_id40794_06oct_29dic"))
    }

    /** V35: every offer survives, best per card, so a card that is not the cheapest still has its price. */
    @Test
    fun `every card's offer is kept, cheapest first, and the ids name the card`() {
        val o = VtexOffer(
            Price = BigDecimal("2399925"), ListPrice = BigDecimal("3199900"), PriceWithoutDiscount = BigDecimal("3199900"),
            teasers = listOf(
                tableTeaser(VtexParam("RestrictionsBins", "555845,570423"), "1300900.00"),
                tableTeaser(VtexParam("PaymentMethodId", "102"), "1200900.00"),
                tableTeaser(VtexParam("PaymentMethodId", "2,209"), "1250900.00"),
                tableTeaser(VtexParam("PaymentMethodId", "4,210"), "1100900.00"),
                // A second Addi table, worse: only the best per card is kept.
                tableTeaser(VtexParam("PaymentMethodId", "102"), "1000900.00"),
            ),
        )
        val offers = com.luis.dealstracker.sources.vtex.cardOffersOf(o, BigDecimal.ONE, BigDecimal("2399925.00"))
        assertEquals(
            listOf(CardScope.OTHER to "1899000.00", CardScope.VISA to "1949000.00", CardScope.ADDI to "1999000.00", CardScope.MASTERCARD to "2099000.00"),
            offers.map { it.scope to it.price.toPlainString() },
        )
        assertEquals(offers.first(), cardOfferOf(o, BigDecimal.ONE, BigDecimal("2399925.00")), "the history's single card price is still the cheapest")
    }

    @Test
    fun `payment ids name the card`() {
        val scope: (Set<String>) -> CardScope = { com.luis.dealstracker.sources.vtex.scopeOfPaymentIds(it) }
        assertEquals(CardScope.MASTERCARD, scope(setOf("4", "501")), "any Mastercard, the store card included")
        assertEquals(CardScope.MASTERCARD, scope(setOf("4", "210")))
        assertEquals(CardScope.STORE_CARD, scope(setOf("501")))
        assertEquals(CardScope.VISA, scope(setOf("2", "209")))
        assertEquals(CardScope.VISA, scope(setOf("2")))
        assertEquals(CardScope.ADDI, scope(setOf("102")))
        assertEquals(CardScope.OTHER, scope(setOf("2", "102")), "two programmes in one teaser is not one card")
        assertEquals(CardScope.OTHER, scope(setOf("999")))
    }
}
