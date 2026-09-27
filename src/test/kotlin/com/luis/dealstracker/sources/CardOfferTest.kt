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
        assertEquals(CardScope.OTHER, cardOfferOf(offer(cardTeaser("2", "10")), BigDecimal.ONE, BigDecimal("10000"))?.scope)
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
}
