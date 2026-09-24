package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.FxRate
import com.luis.dealstracker.model.MatchReason
import com.luis.dealstracker.model.Watch
import com.luis.dealstracker.model.WatchCandidate
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchEvaluatorTest {

    private fun watch(spread: String = "15") = Watch(
        id = 1,
        name = "Nevera",
        terms = "nevera | refrigerador",
        excludeTerms = "organizador",
        categoryGroup = "Appliances",
        sources = emptyList(),
        minPrice = BigDecimal("800000"),
        maxPrice = BigDecimal("3500000"),
        currency = "COP",
        lookbackDays = 30,
        crossSiteMinSpread = BigDecimal(spread),
        enabled = true,
        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
    )

    private fun candidate(
        id: Long,
        source: String,
        price: String,
        priorFloor: String? = null,
        matchKey: String? = null,
        keys: List<String>? = null,
        currency: String = "COP",
    ) = WatchCandidate(
        dealId = id,
        source = source,
        title = "Nevera $id",
        url = "https://$source.example/deal/$id",
        price = BigDecimal(price),
        currency = currency,
        priorFloor = priorFloor?.let(::BigDecimal),
        keys = keys ?: listOfNotNull(matchKey),
    )

    // --- LOWEST_IN_WINDOW ---

    /**
     * The trap this rule is shaped around. A floor that includes the price being judged is
     * one that price can never beat, so the rule becomes either dead or trivial — which is
     * exactly how the feed's "lowest yet" badge came to appear on every single row
     * (`price <= minPriceEver` is always true when the minimum contains the current price).
     */
    @Test
    fun `a watch's first run is silent`() {
        // Forty fridges, one observation each, no history: the cheapest of them is just the
        // minimum of a list, not a deal. A banner here would be noise on day one.
        val candidates = listOf(
            candidate(1, "exito", "2000000"),
            candidate(2, "carulla", "2500000"),
            candidate(3, "easy", "3000000"),
        )
        val alerts = WatchEvaluator.lowestInWindow(watch(), candidates)
        assertTrue(alerts.isEmpty(), "expected silence, got ${alerts.map { it.evidence }}")
    }

    @Test
    fun `a second poll at unchanged prices is silent`() {
        val candidates = listOf(
            candidate(1, "exito", "2000000", priorFloor = "2000000"),
            candidate(2, "carulla", "2500000", priorFloor = "2500000"),
        )
        assertTrue(WatchEvaluator.lowestInWindow(watch(), candidates).isEmpty(), "nothing moved")
    }

    @Test
    fun `fires when a price beats the floor the watch had on record`() {
        val candidates = listOf(
            candidate(1, "exito", "1900000", priorFloor = "2400000"),
            candidate(2, "carulla", "2500000", priorFloor = "2500000"),
            candidate(3, "easy", "3000000", priorFloor = "3000000"),
        )
        val alerts = WatchEvaluator.lowestInWindow(watch(), candidates)
        assertEquals(1, alerts.size)
        assertEquals(1L, alerts[0].dealId)
        assertEquals(MatchReason.LOWEST_IN_WINDOW, alerts[0].reason)
        // The floor was 2.4M, held by exito's own earlier observation.
        assertTrue(alerts[0].evidence.contains("2,400,000"), alerts[0].evidence)
    }

    /**
     * The floor is the cheapest thing ever *recorded* in the window, not the cheapest thing
     * on sale right now. A rival that was briefly cheaper last week still sets the bar, or
     * "cheapest in 30 days" would be a lie.
     */
    @Test
    fun `a rival's past price still sets the bar`() {
        val candidates = listOf(
            candidate(1, "exito", "2200000", priorFloor = "2300000"),
            // Currently dearer, but it was down at 2.1M inside the window.
            candidate(2, "carulla", "2600000", priorFloor = "2100000"),
        )
        assertTrue(
            WatchEvaluator.lowestInWindow(watch(), candidates).isEmpty(),
            "2.2M has not beaten carulla's earlier 2.1M",
        )
    }

    /** The news we most want: something cheap appears that we have never seen before. */
    @Test
    fun `a brand-new listing with no history of its own can fire`() {
        val candidates = listOf(
            candidate(1, "exito", "2000000", priorFloor = "2000000"),
            candidate(2, "homecenter", "1600000", priorFloor = null),
        )
        val alerts = WatchEvaluator.lowestInWindow(watch(), candidates)
        assertEquals(listOf(2L), alerts.map { it.dealId })
        assertTrue(alerts[0].evidence.contains("exito"), alerts[0].evidence)
    }

    @Test
    fun `a drop that is not market-leading stays quiet`() {
        val candidates = listOf(
            candidate(1, "exito", "2000000", priorFloor = "2000000"),
            // A real 100k drop, but still far off the 2.0M floor.
            candidate(2, "carulla", "2900000", priorFloor = "3000000"),
        )
        assertTrue(WatchEvaluator.lowestInWindow(watch(), candidates).isEmpty(), "not the cheapest seen")
    }

    @Test
    fun `matching the floor exactly is not beating it`() {
        val candidates = listOf(
            candidate(1, "exito", "2000000", priorFloor = "2000000"),
            candidate(2, "carulla", "2000000", priorFloor = "2400000"),
        )
        assertTrue(WatchEvaluator.lowestInWindow(watch(), candidates).isEmpty())
    }

    // --- CROSS_SITE_SPREAD ---

    @Test
    fun `fires when the same product is materially cheaper at one retailer`() {
        val key = "gtin:7705191041773"
        val candidates = listOf(
            candidate(1, "exito", "1739900", matchKey = key),
            candidate(2, "easy", "2360900", matchKey = key),
        )
        val alerts = WatchEvaluator.crossSiteSpread(watch(), candidates)
        assertEquals(1, alerts.size)
        assertEquals(1L, alerts[0].dealId)
        assertEquals(MatchReason.CROSS_SITE_SPREAD, alerts[0].reason)
        // (2360900 - 1739900) / 2360900 = 26.3%
        assertTrue(alerts[0].evidence.startsWith("26.3%"), alerts[0].evidence)
    }

    @Test
    fun `stays quiet when the gap is under the watch's threshold`() {
        val key = "gtin:7705191041773"
        val candidates = listOf(
            candidate(1, "exito", "2300000", matchKey = key),
            candidate(2, "easy", "2360900", matchKey = key),
        )
        // 2.6% -- real, but not worth a banner.
        assertTrue(WatchEvaluator.crossSiteSpread(watch(spread = "15"), candidates).isEmpty())
        // Same data fires for someone who asked for a 1% threshold.
        assertEquals(1, WatchEvaluator.crossSiteSpread(watch(spread = "1"), candidates).size)
    }

    @Test
    fun `never compares two listings from the same retailer`() {
        val key = "gtin:7705191041773"
        val candidates = listOf(
            candidate(1, "exito", "1500000", matchKey = key),
            candidate(2, "exito", "2400000", matchKey = key),
        )
        // A duplicate listing at one retailer is not a price comparison.
        assertTrue(WatchEvaluator.crossSiteSpread(watch(), candidates).isEmpty())
    }

    /**
     * The case the second key exists for. Exito, Carulla and Easy publish GS1 barcodes;
     * Falabella and Homecenter publish none and are identified by brand plus model. The same
     * MABE side-by-side is listed at all of them, so with one key per listing it carried
     * `gtin:…` at one retailer and `mdl:…` at another and never matched itself — and the
     * comparison that mattered most, across two different corporate groups, was the one that
     * could never happen.
     */
    @Test
    fun `a barcode-keyed listing matches a model-keyed one at another retailer`() {
        val candidates = listOf(
            // Exito publishes a barcode, and a model code is derivable from its title too.
            candidate(1, "exito", "2553682", keys = listOf("gtin:0757638435765", "mdl:mabe|msl480lmlps0")),
            // Falabella publishes no barcode at all.
            candidate(2, "falabella", "2419900", keys = listOf("mdl:mabe|msl480lmlps0")),
        )
        val alerts = WatchEvaluator.crossSiteSpread(watch(spread = "1"), candidates)
        assertEquals(listOf(2L), alerts.map { it.dealId }, "falabella is the cheaper of the two")
        assertTrue(alerts[0].evidence.contains("mdl:mabe|msl480lmlps0"), alerts[0].evidence)
        assertTrue(alerts[0].evidence.contains("exito"), alerts[0].evidence)
    }

    /**
     * Called directly, the rule still refuses to compare two currencies: `evaluate` converts before
     * it runs, so anything still in a different currency here is one the rate could not cover.
     *
     * Latent until the `amazon` adapter arrived: every source but Slickdeals was COP, and
     * Slickdeals publishes neither a brand nor a barcode so it rarely shares an identity key
     * with a Colombian retailer. Amazon rows carry an ASIN-bearing listing that can share a
     * model key with one, so an unguarded comparison would announce a US$189.99 item as ~100%
     * cheaper than the COP 700,000 equivalent.
     */
    @Test
    fun `prices in different currencies are never compared`() {
        val candidates = listOf(
            candidate(1, "exito", "700000", keys = listOf("mdl:nike|dv0788"), currency = "COP"),
            candidate(2, "amazon", "189", keys = listOf("mdl:nike|dv0788"), currency = "USD"),
        )
        assertTrue(
            WatchEvaluator.crossSiteSpread(watch(spread = "1"), candidates).isEmpty(),
            "a USD price must not be read as undercutting a COP one",
        )
    }

    @Test
    fun `same-currency rivals at different retailers still compare`() {
        // The guard must not disable the rule for the ordinary case it exists to serve.
        val candidates = listOf(
            candidate(1, "exito", "700000", keys = listOf("mdl:nike|dv0788"), currency = "COP"),
            candidate(2, "amazon", "500000", keys = listOf("mdl:nike|dv0788"), currency = "COP"),
        )
        val alerts = WatchEvaluator.crossSiteSpread(watch(spread = "1"), candidates)
        assertEquals(listOf(2L), alerts.map { it.dealId })
    }

    @Test
    fun `a shared key is required, not merely one key in common with a third party`() {
        // A and B share a model key. C shares nothing with A. C must not be dragged into A's
        // comparison just because it sits in the same candidate list.
        val candidates = listOf(
            candidate(1, "exito", "3000000", keys = listOf("mdl:mabe|aaa111")),
            candidate(2, "falabella", "2000000", keys = listOf("mdl:mabe|aaa111")),
            candidate(3, "easy", "500000", keys = listOf("mdl:other|zzz999")),
        )
        val alerts = WatchEvaluator.crossSiteSpread(watch(spread = "1"), candidates)
        assertEquals(listOf(2L), alerts.map { it.dealId })
        // Measured against exito's 3.0M, not against easy's unrelated 500k.
        assertTrue(alerts[0].evidence.contains("exito"), alerts[0].evidence)
    }

    @Test
    fun `products with no identity key never cluster`() {
        // Two unrelated fridges with no GTIN and no model code. Treating null as a shared
        // key would claim they are the same product and invent a bogus 36% spread.
        val candidates = listOf(
            candidate(1, "exito", "1500000", matchKey = null),
            candidate(2, "easy", "2360900", matchKey = null),
        )
        assertTrue(WatchEvaluator.crossSiteSpread(watch(), candidates).isEmpty())
    }

    @Test
    fun `different products at different retailers do not cluster`() {
        val candidates = listOf(
            candidate(1, "exito", "1500000", matchKey = "gtin:7705191041773"),
            candidate(2, "easy", "2360900", matchKey = "mdl:mabe|rmp410fzcc"),
        )
        assertTrue(WatchEvaluator.crossSiteSpread(watch(), candidates).isEmpty())
    }

    @Test
    fun `picks the cheapest rival when a product is sold at three retailers`() {
        val key = "mdl:mabe|rmp410fzcc"
        val candidates = listOf(
            candidate(1, "exito", "2000000", matchKey = key),
            candidate(2, "easy", "2600000", matchKey = key),
            candidate(3, "carulla", "3200000", matchKey = key),
        )
        val alerts = WatchEvaluator.crossSiteSpread(watch(), candidates)
        // Only Exito is cheap enough to fire, and it is measured against easy's 2.6M --
        // the cheapest alternative, not the most flattering one.
        assertEquals(listOf(1L), alerts.map { it.dealId })
        assertTrue(alerts[0].evidence.contains("easy"), alerts[0].evidence)
        assertTrue(alerts[0].evidence.contains("23.1%"), alerts[0].evidence)
    }

    // --- notification text ---

    /**
     * Real titles from the live Exito feed. A banner has roughly two lines, and the repeat
     * was costing one of them.
     */
    @Test
    fun `collapses a model code that Exito repeats in its own title`() {
        assertEquals(
            "Nevecón MABE Side By Side 480L MSL480LMLPS0",
            WatchService.squashRepeats("Nevecón MABE Side By Side 480L MSL480LMLPS0 MSL480LMLPS0"),
        )
    }

    @Test
    fun `only collapses adjacent repeats, and ignores case`() {
        assertEquals("Nevera HACEB No Frost", WatchService.squashRepeats("Nevera HACEB No no Frost"))
        // "Side By Side ... Side By Side" is a repeated phrase, not an adjacent word, and is
        // left alone -- guessing at phrases would start mangling legitimate titles.
        assertEquals(
            "Nevecon Side By Side ELECTROLUX Side By Side 528",
            WatchService.squashRepeats("Nevecon Side By Side ELECTROLUX Side By Side 528"),
        )
    }

    @Test
    fun `leaves an ordinary title untouched and survives odd spacing`() {
        assertEquals("Nevera No Frost 272 Litros", WatchService.squashRepeats("Nevera No Frost 272 Litros"))
        assertEquals("Nevera MABE 421 Lts", WatchService.squashRepeats("Nevera  MABE  421 Lts"))
        assertEquals("", WatchService.squashRepeats(""))
    }

    @Test
    fun `both rules can fire for one deal and are reported separately`() {
        val key = "gtin:7705191041773"
        val candidates = listOf(
            candidate(1, "exito", "1739900", priorFloor = "2400000", matchKey = key),
            candidate(2, "easy", "2360900", priorFloor = "2360900", matchKey = key),
        )
        val alerts = WatchEvaluator.evaluate(watch(), candidates, fx)
        assertEquals(
            setOf(MatchReason.LOWEST_IN_WINDOW, MatchReason.CROSS_SITE_SPREAD),
            alerts.map { it.reason }.toSet(),
        )
        assertTrue(alerts.all { it.dealId == 1L })
    }

    // --- currencies ---

    private val fx = FxRate(BigDecimal("3200"), "config")

    /**
     * The failure this once guarded against is silence: a watch whose terms span two languages
     * matches COP listings and USD ones, and a single minimum across raw numbers lands on the USD
     * figure -- $22.99 -- which no COP listing can be below, so the watch never fires again.
     *
     * With a rate the two are compared as what they cost. The USD mount is worth 73,568 pesos, well
     * above the COP floor, so it neither fires nor silences the COP mount that does beat it.
     */
    @Test
    fun `a cheap-looking USD listing is judged at its converted price`() {
        val alerts = WatchEvaluator.evaluate(
            watch(),
            listOf(
                candidate(1, "slickdeals", "22.99", priorFloor = "39.99", currency = "USD"),
                candidate(2, "exito", "19061", priorFloor = "20697", currency = "COP"),
            ),
            fx,
        )

        assertEquals(
            setOf(2L),
            alerts.filter { it.reason == MatchReason.LOWEST_IN_WINDOW }.map { it.dealId }.toSet(),
            "22.99 USD is 73,568 COP, which beats nothing; the COP mount must still fire",
        )
    }

    /** Evidence is the audit trail, so it names the quoted price and the rate it was converted at. */
    @Test
    fun `evidence reports the quoted price and the rate`() {
        val alerts = WatchEvaluator.evaluate(
            watch(),
            listOf(candidate(1, "slickdeals", "22.99", priorFloor = "39.99", currency = "USD")),
            fx,
        )

        val evidence = alerts.single().evidence
        assertTrue(evidence.contains("US$22.99"), "the price as Amazon quoted it, got: $evidence")
        assertTrue(evidence.contains("3,200.00 COP/USD (config)"), "the rate and its source, got: $evidence")
    }

    /**
     * The whole point of fetching Amazon for a watch: the same TV, cheaper there than at a
     * Colombian retailer once converted. Identity keys are shared, so this is the real rule.
     */
    @Test
    fun `an Amazon listing in USD can undercut a Colombian retailer`() {
        val alerts = WatchEvaluator.evaluate(
            watch(spread = "10"),
            listOf(
                candidate(1, "amazon-watches", "399.99", keys = listOf("mdl:samsung|un55du7000"), currency = "USD"),
                candidate(2, "exito", "1799900", keys = listOf("mdl:samsung|un55du7000"), currency = "COP"),
            ),
            fx,
        )

        val alert = alerts.single { it.reason == MatchReason.CROSS_SITE_SPREAD }
        assertEquals(1L, alert.dealId)
        assertTrue(alert.evidence.contains("cheaper than exito"), alert.evidence)
    }

    /**
     * The ledger re-announces only on a price lower than the one it last announced. If it stored
     * the converted figure, an unchanged dollar price would read as a new low the first day the
     * peso strengthened -- so the alert keeps the quoted price and carries the converted one aside.
     */
    @Test
    fun `an alert on a converted listing keeps its own currency's price`() {
        val alert = WatchEvaluator.evaluate(
            watch(),
            listOf(candidate(1, "amazon-watches", "22.99", priorFloor = "39.99", currency = "USD")),
            fx,
        ).single()

        assertEquals(BigDecimal("22.99"), alert.price)
        assertEquals(BigDecimal("73568"), alert.comparablePrice)
    }

    /** A currency the rate does not cover is never compared, as before any rate existed. */
    @Test
    fun `a currency the rate does not cover is judged only against itself`() {
        val alerts = WatchEvaluator.evaluate(
            watch(spread = "1"),
            listOf(
                candidate(1, "somewhere", "10", keys = listOf("k"), currency = "EUR"),
                candidate(2, "exito", "1000000", keys = listOf("k"), currency = "COP"),
            ),
            fx,
        )
        assertTrue(alerts.isEmpty(), "10 EUR must not read as undercutting 1,000,000 COP: $alerts")
    }

    /**
     * Cross-site comparison across currencies happens only at the converted price. Unconverted, a
     * 15,000 COP bracket read as "99.8% cheaper" than a $22.99 one; at 3,200 the rival costs 73,568
     * pesos, and the honest figure is 79.6% -- with the conversion named in the evidence.
     */
    @Test
    fun `cross-site spread across currencies uses the converted price`() {
        val alerts = WatchEvaluator.evaluate(
            watch(),
            listOf(
                candidate(1, "exito", "15000", matchKey = "mount-x", currency = "COP"),
                candidate(2, "slickdeals", "22.99", matchKey = "mount-x", currency = "USD"),
            ),
            fx,
        )

        val evidence = alerts.single { it.reason == MatchReason.CROSS_SITE_SPREAD }.evidence
        assertTrue(evidence.startsWith("79.6% cheaper than slickdeals at $73,568"), evidence)
        assertTrue(evidence.contains("converted: slickdeals US$22.99"), evidence)
    }
}
