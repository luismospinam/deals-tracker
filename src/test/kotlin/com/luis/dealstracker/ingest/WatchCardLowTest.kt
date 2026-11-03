package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.CardOffer
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.FxRate
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchCandidate
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The opt-in CARD_LOW rule. Falabella's 528 L Electrolux sat at 2,179,900 with CMR for three days in
 * October while every cash rule saw 2,329,900; with the card held, that is the price that was news.
 */
class WatchCardLowTest {

    private val cmr = setOf(CardScope.CMR)
    private val fx = FxRate(BigDecimal("4000"), "test")

    private fun watch() = Watch(
        id = 1, name = "Nevecón", terms = "nevecon", excludeTerms = null, categoryGroup = null,
        sources = emptyList(), minPrice = null, maxPrice = null, currency = "COP", lookbackDays = 30,
        crossSiteMinSpread = BigDecimal("15"), enabled = true, createdAt = Instant.EPOCH,
    )

    private fun candidate(
        id: Long,
        price: String,
        priorFloor: String? = null,
        card: String? = null,
        scope: CardScope = CardScope.CMR,
        heldCardFloor: String? = null,
        source: String = "falabella",
        currency: String = "COP",
    ) = WatchCandidate(
        dealId = id, source = source, title = "Nevecón $id", url = "https://x/$id",
        price = BigDecimal(price), currency = currency, priorFloor = priorFloor?.let(::BigDecimal),
        cardOffer = card?.let { CardOffer(BigDecimal(it), BigDecimal("5"), scope) },
        heldCardFloor = heldCardFloor?.let(::BigDecimal),
    )

    @Test
    fun `with no card held the rule never fires`() {
        val c = listOf(candidate(1, "2449900", priorFloor = "2329900", card = "2179900"))
        assertTrue(WatchEvaluator.cardLow(watch(), c, emptySet()).isEmpty())
        assertTrue(WatchEvaluator.evaluate(watch(), c, fx).none { it.reason == MatchReason.CARD_LOW })
    }

    @Test
    fun `a held card's price under every earlier price fires, at the card price, naming the card`() {
        val c = listOf(candidate(1, "2449900", priorFloor = "2329900", card = "2179900"))
        val alert = WatchEvaluator.cardLow(watch(), c, cmr).single()
        assertEquals(MatchReason.CARD_LOW, alert.reason)
        assertEquals(BigDecimal("2179900"), alert.price)
        assertEquals(CardScope.CMR, alert.card)
        assertTrue(alert.evidence.startsWith("CMR \$2,179,900 beat the \$2,329,900 30-day floor (cash, falabella)"), alert.evidence)
        assertTrue(alert.evidence.endsWith("cash \$2,449,900"), alert.evidence)
        assertEquals(BigDecimal("6.4"), alert.gapPercent)
    }

    @Test
    fun `a card the operator does not hold is ignored`() {
        val c = listOf(candidate(1, "2449900", priorFloor = "2329900", card = "2179900", scope = CardScope.MASTERCARD))
        assertTrue(WatchEvaluator.cardLow(watch(), c, cmr).isEmpty())
    }

    /** The floor counts what the held card paid before, so a card price that only repeats it is not news. */
    @Test
    fun `an earlier card low is part of the floor`() {
        val c = listOf(candidate(1, "2449900", priorFloor = "2329900", card = "2399900", heldCardFloor = "2179900"))
        assertTrue(WatchEvaluator.cardLow(watch(), c, cmr).isEmpty())
        val lower = listOf(candidate(1, "2449900", priorFloor = "2329900", card = "2099900", heldCardFloor = "2179900"))
        assertTrue(WatchEvaluator.cardLow(watch(), lower, cmr).single().evidence.contains("(with card, falabella)"))
    }

    /** Another listing's cash floor counts too: a card price above it is not a low for this watch. */
    @Test
    fun `a cheaper listing elsewhere keeps a card price from being a low`() {
        val c = listOf(
            candidate(1, "2449900", priorFloor = "2449900", card = "2199900"),
            candidate(2, "1899000", priorFloor = "1899000", source = "exito"),
        )
        assertTrue(WatchEvaluator.cardLow(watch(), c, cmr).isEmpty())
    }

    @Test
    fun `when the cash price is itself a new low only the cash alert fires`() {
        val c = listOf(candidate(1, "2299900", priorFloor = "2329900", card = "2179900"))
        val alerts = WatchEvaluator.evaluate(watch(), c, fx, cmr)
        assertEquals(listOf(MatchReason.LOWEST_IN_WINDOW), alerts.map { it.reason })
    }

    @Test
    fun `nothing fires without history`() {
        val c = listOf(candidate(1, "2449900", card = "2179900"))
        assertTrue(WatchEvaluator.cardLow(watch(), c, cmr).isEmpty())
    }

    @Test
    fun `evaluate passes the held cards through`() {
        val c = listOf(candidate(1, "2449900", priorFloor = "2329900", card = "2179900"))
        assertEquals(listOf(MatchReason.CARD_LOW), WatchEvaluator.evaluate(watch(), c, fx, cmr).map { it.reason })
    }

    /** A converted listing would put a peso card price in the ledger of a dollar row; no source quotes one anyway. */
    @Test
    fun `a listing in another currency is left out`() {
        val c = listOf(candidate(1, "650", priorFloor = "640", card = "500", currency = "USD", source = "amazon"))
        assertTrue(WatchEvaluator.evaluate(watch(), c, fx, cmr).none { it.reason == MatchReason.CARD_LOW })
    }

    /** V35: the held card need not be the cheapest offer on the listing to be the one that counts. */
    @Test
    fun `the held card's own offer is judged, not the cheapest card's`() {
        val c = candidate(1, "2399925", priorFloor = "2399925", card = "1899000", scope = CardScope.OTHER).copy(
            cardOffers = listOf(
                CardOffer(BigDecimal("1899000"), BigDecimal("40"), CardScope.OTHER),
                CardOffer(BigDecimal("1999000"), BigDecimal("37"), CardScope.ADDI),
            ),
        )
        val alert = WatchEvaluator.cardLow(watch(), listOf(c), setOf(CardScope.ADDI)).single()
        assertEquals(BigDecimal("1999000"), alert.price)
        assertEquals(CardScope.ADDI, alert.card)
    }
}
