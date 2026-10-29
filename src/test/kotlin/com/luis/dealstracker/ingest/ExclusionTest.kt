package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Amazon ships internationally but not groceries, liquids or medicines, and bulky
 * low-value consumables are not worth shipping to Colombia even when permitted.
 * These titles are real examples pulled from the live Slickdeals feed.
 */
class ExclusionTest {

    private val service = IngestService(
        repo = throwingRepo(),
        registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
        app = fakeAppConfig(),
        http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
    )

    /** Mirrors the patterns in config/sources.yaml. */
    private val provider = ProviderConfig(
        id = "slickdeals",
        type = "rss-slickdeals",
        listOptions = mapOf(
            "excludeTitlePatterns" to listOf(
                "Amazon Grocery", "Amazon Fresh", "Amazon Saver", "Happy Belly", "Wickedly Prime",
                "\\bS&S\\b", "\\bSnS\\b",
                "\\b(juice|soda|coffee|tea|cereal|granola|oatmeal|cookies?|candy|chocolate)\\b",
                "\\b(snacks?|chips|cashews?|almonds|peanuts|pretzels|crackers)\\b",
                "\\b(sauce|ketchup|mustard|mayo|soup|pasta|noodles|seasoning|syrup)\\b",
                "\\b(gatorade|soylent|protein powder)\\b",
                "\\b(vitamins?|multivitamin|supplements?|antacid|antihistamine|ibuprofen)\\b",
            ),
        ),
    )

    private fun deal(title: String) = RawDeal(
        externalId = title.hashCode().toString(),
        title = title,
        url = "u",
        price = BigDecimal("9.99"),
        currency = "USD",
    )

    private fun kept(vararg titles: String) =
        service.applyExclusions(provider, titles.map { deal(it) }).map { it.title }

    @Test
    fun `excludes amazon grocery private labels`() {
        val titles = listOf(
            "Amazon Grocery, Fruit & Nut Chewy Granola Bars 4 boxes, 7.4 Oz, 6 Ct, \$6.33 S&S",
            "[SnS] \$0.47* | 8-oz Amazon Fresh No Salt Added Tomato Sauce at Amazon",
            "Amazon Saver, Crunchy Chocolate Chip Cookies, 12 Oz~\$1.65 @ Amazon",
            "15-Pack 1.5-Ounce Wickedly Prime Roasted Cashews Snack Pack \$9.50 w/ S&S",
        )
        assertTrue(kept(*titles.toTypedArray()).isEmpty(), "all grocery labels should be dropped")
    }

    @Test
    fun `excludes food and drink`() {
        assertTrue(
            kept(
                "6-Pk 20-Oz Gatorade Lower Sugar Sports Drink (Fruit Punch) \$3.80",
                "Back to Nature Fudge Mint Cookies, 5.5 oz Box, \$3.97 @ Amazon",
                "[SnS, AC] \$1.05* | 14-Ounce Heinz Yellow Mustard at Amazon",
                "20-Count 0.9-Oz Goldfish Cheddar Crackers Variety Pack",
            ).isEmpty(),
        )
    }

    @Test
    fun `excludes supplements and otc medicine`() {
        assertTrue(
            kept(
                "Optimum Nutrition Opti-Men Daily Multivitamin Supplement, 90 Count \$15.72",
                "[SnS, AC] \$3.29* | 160-Ct TUMS Ultra Strength Chewable Antacid Tablets at Amazon",
            ).isEmpty(),
        )
    }

    /**
     * The important half: real electronics must survive. An over-broad pattern that
     * silently eats these would defeat the whole point of the source.
     */
    @Test
    fun `keeps electronics and durable goods`() {
        val wanted = listOf(
            "\$189.99* | Apple AirPods Pro 3 Wireless Earbuds at Amazon",
            "4 Pack Apple AirTag (2nd Generation) \$85 + Free S&H at Amazon",
            "Apple Watch Band - Sport Loop - 46mm - Blue Mist \$39",
            "ASUS ROG Harpe Mini Core Wired Gaming Mouse (Black) \$29.99",
            "Open box Apple Watch Series 11 [GPS 42mm] Smartwatch \$282.70",
            "\$429 | Apple AirPods Max 2 Wireless Over-Ear Headphones at Amazon",
            "Samsung 990 PRO 2TB NVMe SSD \$139",
        )
        assertEquals(wanted, kept(*wanted.toTypedArray()), "no durable good should be excluded")
    }

    /** "S&H" (shipping/handling) must not be mistaken for "S&S" (Subscribe & Save). */
    @Test
    fun `does not confuse free S and H with subscribe and save`() {
        val title = "4 Pack Apple AirTag (2nd Generation) \$85 + Free S&H at Amazon"
        assertEquals(listOf(title), kept(title))
    }

    /** Word boundaries must stop "tea" matching "steam" or "rice" matching "price". */
    @Test
    fun `word boundaries prevent substring false positives`() {
        val wanted = listOf(
            "Steam Deck OLED 512GB Handheld Console \$479",
            "Price Drop: Anker 737 Power Bank 24000mAh \$89",
            "Teac Turntable TN-180BT \$199",
        )
        assertEquals(wanted, kept(*wanted.toTypedArray()))
    }

    @Test
    fun `no patterns configured means nothing is excluded`() {
        val bare = ProviderConfig(id = "x", type = "rss-slickdeals")
        val deals = listOf(deal("Amazon Grocery Cookies"), deal("SSD"))
        assertEquals(2, service.applyExclusions(bare, deals).size)
    }

    /** A malformed regex must be reported and skipped, not crash the whole poll. */
    @Test
    fun `invalid regex is skipped rather than fatal`() {
        val broken = ProviderConfig(
            id = "x", type = "rss-slickdeals",
            listOptions = mapOf("excludeTitlePatterns" to listOf("[unclosed", "Amazon Grocery")),
        )
        assertEquals(1, service.excludePatterns(broken).size, "only the valid pattern compiles")
        assertEquals(
            listOf("SSD"),
            service.applyExclusions(broken, listOf(deal("Amazon Grocery Cookies"), deal("SSD")))
                .map { it.title },
        )
    }
}

/**
 * Age filtering. Slickdeals' paginated search reaches back over 400 days and is NOT
 * ordered strictly by date, so a published-date cutoff is the only real guard.
 */
class AgeLimitTest {

    private val service = IngestService(
        repo = throwingRepo(),
        registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
        app = fakeAppConfig(),
        http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
    )

    private val provider = ProviderConfig(
        id = "slickdeals",
        type = "rss-slickdeals",
        options = mapOf("maxAgeDays" to "14"),
    )

    private fun deal(title: String, ageDays: Long?) = RawDeal(
        externalId = title,
        title = title,
        url = "u",
        price = BigDecimal("9.99"),
        currency = "USD",
        postedAt = ageDays?.let {
            java.time.Instant.now().minus(it, java.time.temporal.ChronoUnit.DAYS)
        },
    )

    @Test
    fun `keeps deals inside the window and drops older ones`() {
        val kept = service.applyAgeLimit(
            provider,
            listOf(
                deal("today", 0),
                deal("thirteen days", 13),
                deal("thirty days", 30),
                deal("last year", 437),
            ),
        ).map { it.title }
        assertEquals(listOf("today", "thirteen days"), kept)
    }

    /**
     * VTEX and MercadoLibre publish no posting date. Dropping undated deals would empty
     * the Colombian sources entirely, so they must pass through.
     */
    @Test
    fun `keeps deals with no posted date`() {
        val kept = service.applyAgeLimit(provider, listOf(deal("undated", null))).map { it.title }
        assertEquals(listOf("undated"), kept)
    }

    @Test
    fun `no maxAgeDays configured means no age filtering`() {
        val bare = ProviderConfig(id = "exito", type = "vtex")
        val deals = listOf(deal("ancient", 900), deal("fresh", 1))
        assertEquals(2, service.applyAgeLimit(bare, deals).size)
    }

    @Test
    fun `boundary is inclusive of the cutoff day`() {
        // 14d exactly should survive a 14-day window; 15d should not.
        assertEquals(1, service.applyAgeLimit(provider, listOf(deal("fourteen", 14))).size)
        assertEquals(0, service.applyAgeLimit(provider, listOf(deal("fifteen", 15))).size)
    }

    /** Mirrors the amazon provider's pattern in config/sources.yaml. */
    private val amazon = ProviderConfig(
        id = "amazon",
        type = "amazon",
        listOptions = mapOf("excludeTitlePatterns" to listOf("\\bkid(s|'s)?\\b")),
    )

    @Test
    fun `amazon drops kids items but keeps adult ones`() {
        val titles = listOf(
            "Kids' Cloud Foam - Pure Running Shoes — 8",
            "adidas Kids Cloud Foam Pure Running Shoes",
            "Kid's Fleece Hoodie — M",
            "Kids Fresh Foam Arishi V4 Running Shoe (Little Kid) — 8",
            "Men's Vomero 18 Road Running Shoe — 8",
            "Men's Skidmore Hoodie — M",
        )
        val kept = service.applyExclusions(amazon, titles.map { deal(it, null) }).map { it.title }
        assertEquals(listOf("Men's Vomero 18 Road Running Shoe — 8", "Men's Skidmore Hoodie — M"), kept)
    }
}
