package com.luis.dealstracker.sources

import com.luis.dealstracker.sources.slickdeals.SlickdealsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Tests run against a real Slickdeals frontpage feed captured 2026-08-23, so the
 * awkward cases are genuine rather than invented.
 */
class SlickdealsSourceTest {

    private val feed: String = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/slickdeals-frontpage.xml"),
    ) { "missing fixture" }.bufferedReader().readText()

    private val source = SlickdealsSource()

    /**
     * 23 of 24 items parse. The one skipped is a Chipotle buy-2-get-1 promo whose
     * body carries no price at all — with no price it can have no price history and
     * no discount, so there is nothing meaningful to track.
     */
    @Test
    fun `parses every item that has a price`() {
        assertEquals(24, source.countItems(feed), "fixture should still have 24 items")
        val parsed = source.parse(feed)
        assertEquals(23, parsed.size)
        assertTrue(
            parsed.none { it.deal.title.contains("Chipotle") },
            "the priceless BOGO promo should be the one skipped",
        )
    }

    @Test
    fun `resolves exactly the amazon deals`() {
        val amazon = source.parse(feed).filter { it.merchant == "amazon" }
        assertEquals(8, amazon.size, "8 of the 24 items are Amazon-primary")
    }

    /**
     * The case that drove the resolution order. This deal's body reads
     * "Amazon.com has … Target [target.com] also has …" but contains *only*
     * target.com links, so a link-first rule silently discards a real Amazon deal.
     */
    @Test
    fun `prefers body text over links when a secondary merchant is the only one linked`() {
        val lego = source.parse(feed).single { it.deal.title.contains("LEGO City Emergency") }
        assertEquals("amazon", lego.merchant, "primary merchant is named in prose, not linked")
        assertEquals("body", lego.resolvedVia)
        assertTrue(
            feed.contains("data-store-slug=\"target\""),
            "fixture must still contain the target links that make this case meaningful",
        )
    }

    /** AliExpress items are genuinely AliExpress deals, not sponsored contamination. */
    @Test
    fun `drops aliexpress deals`() {
        val parsed = source.parse(feed)
        val ali = parsed.filter { it.merchant == "aliexpress" }
        assertTrue(ali.size >= 8, "feed contains many genuine AliExpress deals; found ${ali.size}")
        assertTrue(ali.none { it.merchant == "amazon" })
    }

    @Test
    fun `drops other US retailers that will not ship to Colombia`() {
        val parsed = source.parse(feed)
        val merchants = parsed.mapNotNull { it.merchant }.toSet()
        // Note: "target" is absent on purpose. The fixture's only Target mention is
        // the LEGO deal, where Target is the secondary retailer and Amazon is
        // primary -- so no deal in this feed is Target-primary.
        listOf("walmart", "best-buy", "the-home-depot", "greentoe", "aliexpress").forEach {
            assertTrue(it in merchants, "fixture should contain a $it deal to prove it gets dropped")
        }

        val nonAmazon = parsed.filter { it.merchant != "amazon" }
        assertTrue(nonAmazon.isNotEmpty())
        assertTrue(
            nonAmazon.none { it.deal.asin != null },
            "no non-Amazon deal should have picked up an ASIN",
        )
    }

    @Test
    fun `extracts asin when present`() {
        val withAsin = source.parse(feed).filter { it.deal.asin != null }
        assertEquals(7, withAsin.size, "7 Amazon-primary items carry an ASIN")
        assertTrue(withAsin.all { it.deal.asin!!.matches(Regex("^[A-Z0-9]{10}$")) })
        val juice = source.parse(feed).single { it.deal.title.contains("Ocean Spray") }
        assertEquals("B000V9QHX4", juice.deal.asin)
    }

    /**
     * Title first: it states the price you actually pay.
     *
     * This assertion used to be the reverse, generalised from the Ocean Spray item where the
     * title says $3.65 and the body $3.67. Two cents is indistinguishable from noise, and the
     * rule drawn from it was wrong wherever a coupon, promo code or Subscribe & Save is
     * involved — there the body's "for $N" is the price *before* the discount, and the gap is
     * not cents but multiples. Across 47 live feed items carrying both, 28 disagreed: a $14
     * shaver was stored at $54.24, its struck-through list price.
     *
     * So the two-cent cases now resolve to the title's number. That is the accepted cost of
     * getting the coupon cases right, and it is the right trade for a tool that exists to
     * detect real price movement.
     */
    @Test
    fun `prefers title price over body price`() {
        val juice = source.parse(feed).single { it.deal.title.contains("Ocean Spray") }
        assertEquals("3.65", juice.deal.price.toPlainString())
    }

    /** Coupon and promo-code wording, taken verbatim from the live feed. */
    @Test
    fun `does not store the pre-discount price for coupon deals`() {
        listOf(
            Triple(
                "Greenworks 60V 4.0AH High Current (HC) Battery \$87.45 + Free S&H",
                "Amazon has Greenworks 60V 4.0AH High Current (HC) Battery for \$166.47 - \$79.03 " +
                    "when you 'clip' the coupon on the product page",
                "87.45",
            ),
            Triple(
                "Tablo 4th Gen 2-Turner 128GB Over the Air DVR w/ No Antenna \$59.95 + Free S&H",
                "Tablo TV has the Tablo TV 2-Tuner Over-The-Air DVR on sale for \$79.95 - \$20 " +
                    "with discount code ELLIS at checkout",
                "59.95",
            ),
            Triple(
                "Mbzoey Men's IPX7 Waterproof Electric Foil Shaver w/ Magnetic Cover \$14 + Free S&H",
                "Amazon has Mbzoey Men's IPX7 Waterproof Electric Foil Shaver on sale for \$54.24",
                "14.00",
            ),
        ).forEach { (title, body, expected) ->
            val fromTitle = source.extractPriceFromTitle(title)
            assertEquals(expected, fromTitle?.toPlainString(), "title price for: $title")
            // The body genuinely does contain a higher number; the point is that it loses.
            val fromBody = source.extractPrice(body)
            assertTrue(
                fromBody != null && fromBody > fromTitle,
                "this case only matters because the body price is higher: $title",
            )
        }
    }

    /** The body is still the fallback — plenty of titles carry no price at all. */
    @Test
    fun `falls back to the body when the title has no price`() {
        assertEquals(null, source.extractPriceFromTitle("Anker USB-C Charger, No Price Here"))
        assertEquals(
            "19.99",
            source.extractPrice("Amazon has the Anker USB-C Charger for \$19.99 shipped")?.toPlainString(),
        )
    }

    /** "Shipping is free ... on $35+ orders" must not be mistaken for the price. */
    @Test
    fun `does not read shipping thresholds as the price`() {
        assertEquals(
            "3.67",
            source.extractPrice("Amazon has Juice for \$3.67. Shipping is free with Prime or on \$35+ orders.")
                ?.toPlainString(),
        )
    }

    @Test
    fun `slickdeals publishes no list price so discount is unknown rather than zero`() {
        val parsed = source.parse(feed)
        assertTrue(parsed.all { it.deal.listPrice == null })
        // Null, not 0 -- otherwise the discount floor would treat missing data as
        // a real 0% discount and silently reject the entire source.
        assertTrue(parsed.all { it.deal.discountPercent == null })
    }

    @Test
    fun `uses the stable thread id and strips tracking params`() {
        val lego = source.parse(feed).single { it.deal.title.contains("LEGO City Emergency") }
        assertEquals("19913307", lego.deal.externalId)
        assertTrue(lego.deal.url.startsWith("https://slickdeals.net/f/19913307-"))
        assertTrue(!lego.deal.url.contains("utm_"), "tracking params should be stripped")
    }

    @Test
    fun `via marketplace resolves to the marketplace not the third party seller`() {
        // Feed contains "B King via Amazon has ..." and "KYY-USA via Amazon has ..."
        val parsed = source.parse(feed)
        val viaAmazon = parsed.filter { it.deal.title.contains("LISEN") || it.deal.title.contains("addlon") }
        assertTrue(viaAmazon.isNotEmpty(), "fixture should contain 'via Amazon' marketplace listings")
        assertTrue(viaAmazon.all { it.merchant == "amazon" })
    }

    @Test
    fun `items with no merchant signal at all resolve to null`() {
        // The Jackery and ECOFLOW items carry neither a "<Merchant> has" body nor any
        // store-slug link, so merchant is genuinely unknown and they must not be
        // guessed into the Amazon bucket.
        val unknown = source.parse(feed).filter { it.merchant == null }
        assertTrue(unknown.isNotEmpty(), "fixture contains items with no merchant signal")
        assertTrue(unknown.all { it.resolvedVia == "none" })
    }

    /**
     * Regression guard for the "<Seller> via [ marketplace.com ] has" form, where the
     * marketplace appears *only* inside the brackets. An earlier version stripped
     * brackets before matching and produced merchants like "bluetti-via".
     */
    @Test
    fun `resolves marketplace when it appears only inside brackets`() {
        val parsed = source.parse(feed)
        val bluetti = parsed.single { it.deal.title.startsWith("Bluetti AC70") }
        assertEquals("aliexpress", bluetti.merchant)
        assertEquals("body", bluetti.resolvedVia)

        val kingroon = parsed.single { it.deal.title.contains("KINGROON") }
        assertEquals("aliexpress", kingroon.merchant)

        assertTrue(
            parsed.none { it.merchant?.endsWith("-via") == true },
            "no merchant should be left as a dangling '<name>-via'",
        )
    }

    /**
     * Slickdeals RSS caps every response at 25 items. `page` is ignored on the
     * `mode=` feeds but works on `q=` searches, which is the only route to deals older
     * than the ~25 most recent. This checks the URL expansion that relies on that.
     */
    @Test
    fun `expands search terms into one paginated url per page`() {
        val cfg = com.luis.dealstracker.config.ProviderConfig(
            id = "slickdeals",
            type = "rss-slickdeals",
            options = mapOf("searchPages" to "3"),
            listOptions = mapOf(
                "urls" to listOf("https://slickdeals.net/newsearch.php?mode=frontpage&rss=1"),
                "searches" to listOf("apple", "airtag"),
            ),
        )
        val urls = source.buildFeedUrls(cfg)

        assertEquals(1 + 2 * 3, urls.size, "1 fixed feed + 2 terms x 3 pages")
        assertTrue(urls.any { it.contains("mode=frontpage") })
        (1..3).forEach { page ->
            assertTrue(urls.any { it.contains("q=apple") && it.endsWith("page=$page") }, "missing apple page $page")
        }
        assertTrue(urls.toSet().size == urls.size, "no duplicate URLs")
    }

    @Test
    fun `url encodes multi word search terms`() {
        val cfg = com.luis.dealstracker.config.ProviderConfig(
            id = "s", type = "rss-slickdeals",
            options = mapOf("searchPages" to "1"),
            listOptions = mapOf("searches" to listOf("apple watch")),
        )
        val url = source.buildFeedUrls(cfg).single()
        assertTrue(url.contains("q=apple+watch") || url.contains("q=apple%20watch"), "got $url")
        assertTrue(!url.contains("q=apple watch"), "raw space would break the request")
    }

    @Test
    fun `falls back to the single url when no searches are configured`() {
        val cfg = com.luis.dealstracker.config.ProviderConfig(
            id = "s", type = "rss-slickdeals",
            url = "https://feeds.feedburner.com/SlickdealsnetFP",
        )
        assertEquals(listOf("https://feeds.feedburner.com/SlickdealsnetFP"), source.buildFeedUrls(cfg))
    }

    /**
     * Not every deal uses the `"<Merchant> has <product> for $X"` template. Search
     * results also carry a bare form with no verb at all:
     *
     *   `Apple Watch Band - Sport Loop - 46mm - Blue Mist $39.00`  (real example)
     *
     * Here the body rule matches nothing, so merchant must fall through to the link
     * slug and the price must fall back to the title. Both fallbacks are load-bearing
     * for this template — a real Amazon deal is lost if either is removed.
     */
    @Test
    fun `handles the bare template with no has verb`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
            <channel><item>
              <title>Apple Watch Band - Sport Loop - 46mm - Blue Mist - One Size (Fits Most) ${'$'}39</title>
              <link>https://slickdeals.net/f/19800001-apple-watch-band-sport-loop</link>
              <content:encoded><![CDATA[<div>Thumb Score: +8</div><div>Apple Watch Band -
                Sport Loop - 46mm - Blue Mist - One Size (Fits Most) [<a
                data-store-slug="amazon" data-store-id="1">amazon.com</a>] ${'$'}39.00</div>]]></content:encoded>
            </item></channel></rss>
        """.trimIndent()

        val parsed = source.parse(xml).single()
        assertEquals("amazon", parsed.merchant, "should fall through to the link slug")
        assertEquals("firstlink", parsed.resolvedVia)
        assertEquals("39.00", parsed.deal.price.toPlainString(), "should fall back to the title price")
        assertEquals("19800001", parsed.deal.externalId)
    }

    /**
     * The community score is the only deal-quality signal for this source, since it
     * publishes no list price and so no discount can be computed. Present on every item.
     */
    @Test
    fun `extracts the community thumb score for every item`() {
        val parsed = source.parse(feed)
        assertTrue(
            parsed.all { it.deal.communityScore != null },
            "every Slickdeals item carries a Thumb Score",
        )
        val juice = parsed.single { it.deal.title.contains("Ocean Spray") }
        assertEquals(15, juice.deal.communityScore)
    }

    @Test
    fun `parses positive negative and zero scores`() {
        assertEquals(170, source.extractThumbScore("Thumb Score: +170 Amazon has ..."))
        assertEquals(-14, source.extractThumbScore("Thumb Score: -14 Amazon has ..."))
        assertEquals(0, source.extractThumbScore("Thumb Score: 0 Amazon has ..."))
        // Null, not 0: "no score published" and "community rated it 0" differ.
        assertNull(source.extractThumbScore("Amazon has a thing for \$5"))
    }

    /**
     * The score must be read before normalizeBody strips the prefix — otherwise it is
     * silently lost, and the prefix would also be mistaken for the merchant name.
     */
    @Test
    fun `score survives body normalisation stripping its prefix`() {
        val raw = "Thumb Score: +32 Amazon.com has LEGO City for \$34.79"
        assertEquals(32, source.extractThumbScore(raw))
        assertTrue(
            !source.normalizeBody(raw).contains("Thumb Score"),
            "normalizeBody must still strip the prefix so it isn't read as a merchant",
        )
    }

    /**
     * The feed sends RFC 822 with a TWO-digit year -- "Wed, 01 Oct 25 22:06:29 +0000" --
     * which DateTimeFormatter.RFC_1123_DATE_TIME rejects outright. Getting this wrong
     * silently nulls every date, which in turn disables the age filter entirely.
     */
    @Test
    fun `parses the two digit year pubDate format slickdeals actually sends`() {
        val parsed = source.parsePubDate("Wed, 01 Oct 25 22:06:29 +0000")
        assertNotNull(parsed, "two-digit-year RFC 822 must parse")
        assertEquals("2025-10-01T22:06:29Z", parsed.toString())
    }

    @Test
    fun `also accepts a four digit year`() {
        val parsed = source.parsePubDate("Wed, 01 Oct 2025 22:06:29 +0000")
        assertEquals("2025-10-01T22:06:29Z", assertNotNull(parsed).toString())
    }

    @Test
    fun `returns null for missing or unparseable dates rather than throwing`() {
        assertNull(source.parsePubDate(null))
        assertNull(source.parsePubDate(""))
        assertNull(source.parsePubDate("   "))
        assertNull(source.parsePubDate("last Tuesday"))
    }

    @Test
    fun `extracts a posted date for every item in the feed`() {
        val parsed = source.parse(feed)
        assertTrue(parsed.all { it.deal.postedAt != null }, "pubDate is present on every item")
    }

    @Test
    fun `every parsed deal has a usable price and id`() {
        source.parse(feed).forEach {
            assertTrue(it.deal.price.signum() > 0, "${it.deal.title} has non-positive price")
            assertTrue(it.deal.externalId.isNotBlank())
            assertEquals("USD", it.deal.currency)
        }
    }
}
