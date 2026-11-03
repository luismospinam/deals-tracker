package com.luis.dealstracker.web

import com.luis.dealstracker.db.CardProgram
import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.db.cardChoices
import com.luis.dealstracker.db.heldCardsValue
import com.luis.dealstracker.model.HeldCards
import com.luis.dealstracker.model.usableOffer
import kotlin.test.AfterTest
import com.luis.dealstracker.model.CardOffer
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import kotlinx.html.div
import kotlinx.html.main
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Card-only prices outside the watch and grocery pages: the feed row, the deal page's header, and the
 * "Cards I hold" setting that turns on the card alerts. Asking what the fridge cost with CMR last month
 * used to take a database query.
 */
class CardPriceViewsTest {

    private val cmr = CardOffer(BigDecimal("2399900"), BigDecimal("2.04"), CardScope.CMR)

    private fun deal(card: CardOffer? = null, minCard: String? = null) = Deal(
        id = 107322, source = "falabella", sourceDealId = "141337704", title = "Nevecón Electrolux 528L",
        url = "https://www.falabella.com.co/x", merchant = null, asin = null, category = null, imageUrl = null,
        brand = "ELECTROLUX", gtin = null, modelCode = null, matchKey = null, modelKey = null, currency = "COP",
        firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH, isActive = true, state = DealState.SEEN,
        coShipping = CoShipping.YES, promoted = false, promotedAt = null, communityScore = null, postedAt = null,
        categoryGroup = null, coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null,
        coShippingCost = null, price = BigDecimal("2449900"), listPrice = BigDecimal("5859900"),
        minPriceEver = BigDecimal("2329900"), maxPriceEver = BigDecimal("2659900"), observationCount = 40,
        card = card, minCardPriceEver = minCard?.let(::BigDecimal),
    )

    @Test
    fun `the feed row shows today's card price under the cash price`() {
        val html = rowFragment(deal(card = cmr))
        assertTrue(html.contains("card-price store-only"), html)
        assertTrue(html.contains("CMR only −2.04% → \$2,399,900"), html)
        assertFalse(rowFragment(deal()).contains("card-price"), "no chip without a card price")
    }

    @Test
    fun `the deal page shows the card price and the lowest one, even when there is none today`() {
        val today = createHTML().main { dealDetail(deal(card = cmr, minCard = "2179900"), emptyList(), feedRow = false) }
        assertTrue(today.contains("detail-card"))
        assertTrue(today.contains("CMR only −2.04% → \$2,399,900"))
        assertTrue(today.contains("lowest with card \$2,179,900"))

        val none = createHTML().main { dealDetail(deal(minCard = "2179900"), emptyList(), feedRow = false) }
        assertTrue(none.contains("no card price today · lowest with card \$2,179,900"), none)

        assertFalse(createHTML().main { dealDetail(deal(), emptyList(), feedRow = false) }.contains("detail-card"))
    }

    @Test
    fun `the note says when today's card price is the lowest`() {
        assertEquals("lowest card price recorded", cardLowNote(deal(card = cmr, minCard = "2399900"), cmr))
        assertNull(cardLowNote(deal(card = cmr), cmr), "no history of card prices, nothing to compare")
    }

    @AfterTest
    fun noCards() { HeldCards.current = emptySet() }

    private val visa = CardOffer(BigDecimal("1949000"), BigDecimal("39.09"), CardScope.VISA)
    private val addi = CardOffer(BigDecimal("1999000"), BigDecimal("37.53"), CardScope.ADDI)

    /** With cards picked, the chip is the price YOUR card pays, even when another card's is lower. */
    @Test
    fun `the chip shows the held card's price, ticked, and fades one you do not hold`() {
        val mine = createHTML().div { cardChips(listOf(visa, addi), "COP", setOf(CardScope.ADDI)) }
        assertTrue(mine.contains("card-price store-only held"), mine)
        assertTrue(mine.contains("✓ Addi only −37.53% → \$1,999,000"), mine)
        assertFalse(mine.contains("1,949,000"), "Visa's lower price is not the operator's")

        val notMine = createHTML().div { cardChips(listOf(visa), "COP", setOf(CardScope.CMR)) }
        assertTrue(notMine.contains("card-price not-held"), notMine)
        assertTrue(notMine.contains("don't hold this card"))

        val none = createHTML().div { cardChips(listOf(visa, addi), "COP", emptySet()) }
        assertTrue(none.contains("Visa −39.09% → \$1,949,000") && !none.contains("held"), "no cards picked: the best offer, unmarked")
    }

    @Test
    fun `the feed row follows the global cards`() {
        HeldCards.current = setOf(CardScope.ADDI)
        val html = rowFragment(deal(card = visa).copy(cardOffers = listOf(visa, addi)))
        assertTrue(html.contains("✓ Addi only"), html)
    }

    @Test
    fun `the lowest-card note counts only your cards when you picked some`() {
        val d = deal(card = cmr, minCard = "2099000").copy(cardLows = mapOf(CardScope.CMR to BigDecimal("2179900"), CardScope.OTHER to BigDecimal("2099000")))
        assertEquals("lowest with your cards \$2,179,900", cardLowNote(d, cmr, setOf(CardScope.CMR)))
        assertEquals("no price with your cards today · lowest with your cards \$2,179,900", cardLowNote(d, null, setOf(CardScope.CMR)))
        assertNull(cardLowNote(d, null, setOf(CardScope.VISA)), "never seen with a Visa price")
        assertEquals("lowest with card \$2,099,000", cardLowNote(d, cmr), "no cards picked: every card's low")
    }

    @Test
    fun `card offers survive the JSON round trip, cheapest first, and unknown cards are dropped from lows`() {
        val json = com.luis.dealstracker.db.cardOffersJson(listOf(addi, visa))
        assertEquals(listOf(visa, addi), com.luis.dealstracker.db.parseCardOffers(json))
        assertEquals(emptyList(), com.luis.dealstracker.db.parseCardOffers("not json"))
        assertEquals(
            mapOf(CardScope.CMR to BigDecimal("2179900")),
            com.luis.dealstracker.db.parseCardLows("""{"CMR": 2179900, "NEWCARD": 5}"""),
        )
    }

    @Test
    fun `usable offer is the best held one, or the best of all with none picked`() {
        assertEquals(addi, usableOffer(listOf(visa, addi), setOf(CardScope.ADDI)))
        assertNull(usableOffer(listOf(visa, addi), setOf(CardScope.CMR)))
        assertEquals(visa, usableOffer(listOf(addi, visa), emptySet()))
    }

    @Test
    fun `the setting keeps known cards only and the store card also pays Mastercard offers`() {
        assertEquals("STORE_CARD,CMR", heldCardsValue(listOf("STORE_CARD", "CMR", "BOGUS", "OTHER")))
        assertEquals(setOf(CardScope.CMR), SettingsRepository.heldCards("CMR, NEWCARD"))
        assertEquals(setOf(CardScope.STORE_CARD, CardScope.MASTERCARD), SettingsRepository.heldCards("STORE_CARD"))
        assertEquals(setOf(CardScope.STORE_CARD), SettingsRepository.pickedCards("STORE_CARD"), "the form shows what was ticked")
        assertTrue(SettingsRepository.heldCards(null).isEmpty())
        assertTrue(SettingsRepository.heldCards("").isEmpty())
    }

    /** The picker offers what the crawls found, not the enum: a card no store runs is not listed. */
    @Test
    fun `card choices come from the crawls, grouped per card, without the unnamed one`() {
        val t = Instant.parse("2026-11-03T10:00:00Z")
        val programs = listOf(
            CardProgram(CardScope.CMR, "falabella", t, t, 80_000),
            CardProgram(CardScope.CMR, "homecenter", t, t.minusSeconds(60), 4_000),
            CardProgram(CardScope.ADDI, "easy", t, t, 4_615),
            CardProgram(CardScope.OTHER, "exito", t, t, 6_000),
            CardProgram(null, "exito", t, t, 3),
        )
        val choices = cardChoices(programs, held = setOf(CardScope.CODENSA))
        assertEquals(listOf(CardScope.CMR, CardScope.ADDI, CardScope.CODENSA), choices.map { it.scope })
        assertEquals(listOf("falabella", "homecenter"), choices.first().sources)
        assertEquals(84_000, choices.first().readings)
        assertEquals(emptyList(), choices.last().sources, "a held card no store showed stays, so it can be unticked")
    }

    @Test
    fun `the config page ticks your cards and shows overrides over the file`() {
        val file = com.luis.dealstracker.config.AppConfig(
            defaults = com.luis.dealstracker.config.Defaults(watchDigestTimes = listOf("19:00")),
            providers = listOf(
                com.luis.dealstracker.config.ProviderConfig("exito-grocery", "vtex-grocery", listOptions = mapOf("runWindows" to listOf("07:00-07:40"))),
                com.luis.dealstracker.config.ProviderConfig("falabella", "falabella-next"),
                com.luis.dealstracker.config.ProviderConfig("mercadolibre", "mercadolibre", enabled = false),
            ),
            db = com.luis.dealstracker.config.DbConfig("jdbc:x", "u", "p"), mlClientId = null, mlClientSecret = null,
        )
        val overrides = mapOf("pollIntervalMinutes" to "30", "enabled.falabella" to "false")
        val t = Instant.parse("2026-11-03T10:00:00Z")
        val model = ConfigModel(
            file = file, live = com.luis.dealstracker.config.ConfigOverrides.apply(file, overrides), overrides = overrides,
            cards = cardChoices(listOf(CardProgram(CardScope.CMR, "falabella", t, t, 10), CardProgram(CardScope.VISA, "exito", t, t, 5)), setOf(CardScope.CMR)),
            picked = setOf(CardScope.CMR), unnamedCardReadings = 42, lastRuns = mapOf("falabella" to t), prunedAt = null,
            now = t, zone = java.time.ZoneOffset.UTC,
        )
        val html = createHTML().main { configPage(model) }
        assertTrue(Regex("""value="CMR"[^>]*checked""").containsMatchIn(html), "CMR is held")
        assertFalse(Regex("""value="VISA"[^>]*checked""").containsMatchIn(html))
        assertTrue(html.contains("42 readings carry a card offer this app cannot name"))
        assertTrue(Regex("""name="pollIntervalMinutes"[^>]*value="30"[^>]*placeholder="60"""").containsMatchIn(html) ||
            Regex("""value="30"[^>]*name="pollIntervalMinutes"""").containsMatchIn(html), html)
        assertTrue(html.contains("placeholder=\"07:00-07:40\""), "the file's windows as the placeholder")
        assertTrue(html.contains("next today 19:00"), "the digest's next run")
        assertFalse(Regex("""value="falabella"[^>]*checked""").containsMatchIn(html), "paused")
        assertTrue(Regex("""value="mercadolibre"[^>]*disabled""").containsMatchIn(html), "off in the file cannot be switched on here")
        assertTrue(html.contains("off in sources.yaml"))
        assertTrue(html.contains("DISCORD_WATCHES_WEBHOOK_URL") && html.contains("not set"))
        assertFalse(html.contains("jdbc:x"), "no connection details on the page")
    }

    @Test
    fun `next time of day rolls over to tomorrow`() {
        val zone = java.time.ZoneOffset.UTC
        val now = Instant.parse("2026-11-03T20:00:00Z")
        assertEquals(Instant.parse("2026-11-04T08:00:00Z"), nextTimeOfDay(listOf("08:00", "19:00"), now, zone))
        assertEquals(Instant.parse("2026-11-03T19:00:00Z"), nextTimeOfDay(listOf("19:00"), Instant.parse("2026-11-03T18:59:00Z"), zone))
        assertNull(nextTimeOfDay(emptyList(), now, zone))
    }
}
