package com.luis.dealstracker.verify

import com.luis.dealstracker.model.CoShipping
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture holds the real page text captured from four Amazon product pages, with a
 * Medellín delivery address set. **Each verdict in it was confirmed by a human opening the
 * same page in their own browser**, which is what makes this ground truth rather than a
 * snapshot of whatever the code happened to produce.
 */
@Serializable
private data class SignalFixture(
    val asin: String,
    val description: String,
    val humanVerifiedVerdict: String,
    val availability: String,
    val deliveryBlock: String,
    val amazonGlobal: String,
)

class CoShippingExtractorTest {

    private val extractor = CoShippingExtractor()
    private val json = Json { ignoreUnknownKeys = true }

    private val fixtures: List<SignalFixture> = json.decodeFromString(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/amazon/co-shipping-signals.json")) {
            "missing amazon signal fixture"
        }.bufferedReader().readText(),
    )

    private fun SignalFixture.signals() = PageSignals(availability, deliveryBlock, amazonGlobal)

    @Test
    fun `fixture covers both outcomes`() {
        assertEquals(4, fixtures.size)
        assertEquals(2, fixtures.count { it.humanVerifiedVerdict == "YES" })
        assertEquals(2, fixtures.count { it.humanVerifiedVerdict == "NO" })
    }

    /** The whole point: agree with what the human saw, on every case. */
    @Test
    fun `matches every human-verified verdict`() {
        fixtures.forEach { f ->
            val v = extractor.extract(f.signals())
            assertEquals(
                CoShipping.valueOf(f.humanVerifiedVerdict), v.value,
                "${f.asin} (${f.description}) — evidence was: ${v.evidence.take(120)}",
            )
            assertFalse(v.unclear, "${f.asin} should be a definite verdict")
            assertTrue(v.evidence.isNotBlank(), "${f.asin} must carry its evidence")
        }
    }

    /**
     * A refused item leaves `#deliveryBlockMessage` EMPTY and puts the reason in
     * `#availability`. Reading only the delivery block — the obvious first guess — would
     * therefore return "unclear" for every non-shippable item rather than NO.
     */
    @Test
    fun `refusal is read from availability, not the empty delivery block`() {
        val refused = fixtures.filter { it.humanVerifiedVerdict == "NO" }
        assertTrue(refused.all { it.deliveryBlock.isEmpty() }, "refused items have no delivery block")
        refused.forEach {
            assertTrue(
                it.availability.contains("cannot be shipped", ignoreCase = true),
                "${it.asin} refusal should live in #availability",
            )
            assertEquals(CoShipping.NO, extractor.extract(it.signals()).value)
        }
    }

    @Test
    fun `import charges are reported when Amazon states them`() {
        val shippable = fixtures.filter { it.humanVerifiedVerdict == "YES" }
        shippable.forEach {
            assertEquals(
                true, extractor.extract(it.signals()).importChargesIncluded,
                "${it.asin} says 'No Import Charges'",
            )
        }
        // Nothing to report when Amazon is silent, which must not read as "charges apply".
        assertNull(extractor.extract(PageSignals(deliveryBlock = "FREE delivery to Colombia")).importChargesIncluded)
    }

    /**
     * Conservative by design. An absent signal is far more likely to mean the page had not
     * finished loading than that the item ships, and a wrong YES sends someone to buy
     * something that cannot arrive.
     */
    @Test
    fun `no signal at all is unclear, never a yes`() {
        val v = extractor.extract(PageSignals())
        assertEquals(CoShipping.UNKNOWN, v.value)
        assertTrue(v.unclear)
        assertEquals("no eligibility signal on page", v.evidence)
    }

    @Test
    fun `in stock alone is not enough for a yes`() {
        // "In Stock" says nothing about the destination; the country must be named.
        val v = extractor.extract(PageSignals(availability = "In Stock"))
        assertEquals(CoShipping.UNKNOWN, v.value)
        assertTrue(v.unclear)
    }

    @Test
    fun `delivery to a different country is not a yes for Colombia`() {
        val v = extractor.extract(
            PageSignals(deliveryBlock = "FREE delivery Tuesday, September 1 to Mexico."),
        )
        assertEquals(CoShipping.UNKNOWN, v.value, "naming another country must not count")
    }

    @Test
    fun `refusal wins even if a delivery estimate is also present`() {
        // Trust the explicit refusal over an estimate that may be left over from a previous
        // address; a false YES is the more costly error.
        val v = extractor.extract(
            PageSignals(
                availability = "This item cannot be shipped to your selected delivery location.",
                deliveryBlock = "FREE delivery Tuesday to Colombia",
            ),
        )
        assertEquals(CoShipping.NO, v.value)
    }

    @Test
    fun `global shipping widget alone can establish a yes`() {
        val v = extractor.extract(
            PageSignals(amazonGlobal = "No Import Charges & FREE Shipping to Colombia Details"),
        )
        assertEquals(CoShipping.YES, v.value)
        assertEquals(true, v.importChargesIncluded)
    }

    @Test
    fun `country is configurable`() {
        val mx = CoShippingExtractor(country = "Mexico")
        assertEquals(
            CoShipping.YES,
            mx.extract(PageSignals(deliveryBlock = "FREE delivery to Mexico")).value,
        )
        assertEquals(
            CoShipping.UNKNOWN,
            mx.extract(PageSignals(deliveryBlock = "FREE delivery to Colombia")).value,
        )
    }

    /**
     * The finding that prompted this: "No Import Charges & FREE Shipping to Colombia" and
     * "No Import Charges & $60.66 Shipping to Colombia" are the SAME verdict with the same
     * import-charge status, but very different deals. Storing only the verdict hid a fee
     * large enough to erase any discount.
     */
    @Test
    fun `captures the international shipping fee`() {
        val paid = extractor.extract(
            PageSignals(amazonGlobal = "No Import Charges & \$60.66 Shipping to Colombia Details"),
        )
        assertEquals(CoShipping.YES, paid.value)
        assertEquals("60.66", paid.shippingCost?.toPlainString())
        assertEquals(true, paid.importChargesIncluded, "import charges genuinely are zero here")

        val free = extractor.extract(
            PageSignals(amazonGlobal = "No Import Charges & FREE Shipping to Colombia Details"),
        )
        assertEquals("0.00", free.shippingCost?.toPlainString(), "FREE is recorded as 0, not null")
    }

    @Test
    fun `free and null shipping cost mean different things`() {
        // null must keep meaning "Amazon quoted nothing" -- collapsing it into 0 would
        // silently claim free shipping we never saw stated.
        assertNull(extractor.extract(PageSignals(deliveryBlock = "FREE delivery to Colombia")).shippingCost)
    }

    @Test
    fun `parses a thousands-separated fee`() {
        assertEquals(
            "1234.50",
            extractor.shippingCost("No Import Charges & \$1,234.50 Shipping to Colombia")?.toPlainString(),
        )
    }

    @Test
    fun `import fees are reported when they do apply`() {
        val v = extractor.extract(
            PageSignals(amazonGlobal = "Import Fees Deposit \$12.30 & FREE Shipping to Colombia"),
        )
        assertEquals(false, v.importChargesIncluded, "charges apply, so this must not read as covered")
        assertEquals("0.00", v.shippingCost?.toPlainString())
    }

    @Test
    fun `all four fixtures still agree after adding cost extraction`() {
        fixtures.forEach { f ->
            assertEquals(CoShipping.valueOf(f.humanVerifiedVerdict), extractor.extract(f.signals()).value, f.asin)
        }
    }

    /**
     * Digital items render no shipping widget at all, so left alone they stay UNCLEAR and
     * get re-checked forever against a question that has no answer. Real examples from the
     * feed: $1.99 Kindle books and a Nintendo eShop gift card.
     */
    @Test
    fun `digital items resolve without a shipping widget`() {
        val ebook = extractor.extract(
            PageSignals(availability = "Available instantly", title = "\$1.99 | Solito: A Memoir (eBook) by Javier Zamora"),
        )
        assertEquals(CoShipping.YES, ebook.value)
        assertFalse(ebook.unclear, "must not stay unclear and be retried forever")
        assertTrue(ebook.evidence.contains("digital item"), "evidence should say why")
        assertTrue(
            ebook.evidence.contains("did not confirm"),
            "the inference must be stated, not presented as Amazon's confirmation",
        )

        val giftCard = extractor.extract(
            PageSignals(title = "Prime Members: \$10 Nintendo eShop Gift Card (Digital Code)"),
        )
        assertEquals(CoShipping.YES, giftCard.value)
    }

    /** Plural trap: `\be-?book\b` cannot match "eBooks" — no boundary between k and s. */
    @Test
    fun `detects plural digital markers`() {
        listOf(
            "Gordon Ramsay Cookbooks [eBooks] \$1.99-\$2.99",
            "3 Audiobooks for the price of one",
            "\$50 Amazon Gift Cards bundle",
        ).forEach { t ->
            assertEquals(
                CoShipping.YES, extractor.extract(PageSignals(title = t)).value,
                "should detect digital in: $t",
            )
        }
    }

    /**
     * Regression: a Prime Video film reached the browser and burned ~2 minutes to end up
     * UNCLEAR, because the digital rule only knew about "digital code" and "digital
     * download". Streaming video needs no shipping either, and detecting it by title costs
     * zero Amazon requests.
     */
    @Test
    fun `detects digital video and game wording`() {
        listOf(
            "Sonic The Hedgehog 3 (2024) (4K UHD Digital Film) \$4.99",
            "The Batman (Digital HD) \$4.99",
            "Dune Part Two (Digital Movie) \$7.99",
            "Assorted Digital Movies \$4.99 each",
            "Hogwarts Legacy (PC Digital Game) \$19.99",
            "Various Digital Rentals \$0.99",
            "Cyberpunk 2077 (Digital) \$24.99",
        ).forEach { t ->
            assertEquals(
                CoShipping.YES, extractor.extract(PageSignals(title = t)).value,
                "should detect digital in: $t",
            )
        }
    }

    /**
     * The counterweight to the rule above. "Digital" is in the name of a great many
     * physical products, and a 4K disc is a parcel however much its title resembles a
     * streaming listing. Treating any of these as digital would claim they need no
     * shipping — the exact error the video rule risks if written loosely.
     */
    @Test
    fun `physical products named digital are not treated as digital`() {
        listOf(
            "Canon EOS R100 Digital Camera \$399",
            "ThermoPro Digital Meat Thermometer \$12.99",
            "Nixplay 10\" Digital Photo Frame \$89",
            "Oppenheimer (4K UHD Blu-ray) \$14.99",
            "Fire TV Stick 4K with Prime Video \$24.99",
            "Casio Men's Digital Watch \$18.99",
        ).forEach { t ->
            val v = extractor.extract(PageSignals(availability = "In Stock", title = t))
            assertEquals(
                CoShipping.UNKNOWN, v.value,
                "must not shortcut a physical item as digital: $t",
            )
            assertTrue(v.unclear, "should stay unresolved rather than claim a verdict: $t")
        }
    }

    @Test
    fun `a physical book is not treated as digital`() {
        // "Books & Media" contains hardcovers too; only digital markers should trigger this.
        val hardcover = extractor.extract(
            PageSignals(
                availability = "In Stock",
                title = "Marvel Studios Character Encyclopedia (Hardcover) \$11.20",
            ),
        )
        assertEquals(CoShipping.UNKNOWN, hardcover.value, "no shipping signal yet, so unclear")
        assertTrue(hardcover.unclear)
    }

    /** An explicit refusal from Amazon outranks our digital inference. */
    @Test
    fun `explicit refusal still wins over the digital shortcut`() {
        val v = extractor.extract(
            PageSignals(
                availability = "This item cannot be shipped to your selected delivery location.",
                title = "Some Kindle thing (eBook)",
            ),
        )
        assertEquals(CoShipping.NO, v.value)
    }

    /**
     * Amazon sometimes offers no buy box at all for the destination: no delivery estimate,
     * nothing in availability, and "See Similar Items" where Add-to-Cart would be. It never
     * says the item cannot ship — it just withdraws the option.
     *
     * All 7 deals showing this pattern were confirmed unshippable in a real browser, which
     * is what justifies calling it NO. The evidence marks it as inferred, because unlike the
     * explicit-refusal case it rests on an absence rather than Amazon's own words.
     */
    @Test
    fun `no buy box for the destination is a no`() {
        val v = extractor.extract(
            PageSignals(
                availability = "",
                deliveryBlock = "",
                buybox = "Deliver to Luis - Medellín 050034 / See Similar Items / " +
                    "See All Buying Options / Add to List",
                title = "Lodge Pre-Seasoned Cast Iron Burger Press, 6.25 Inches",
            ),
        )
        assertEquals(CoShipping.NO, v.value)
        assertFalse(v.unclear)
        assertTrue(v.evidence.contains("inferred"), "must not present an inference as Amazon's statement")
    }

    /**
     * All three conditions are required, so a page that simply had not finished loading is
     * not mistaken for an unavailable one — "See All Buying Options" also appears as a
     * secondary link on pages that DO have a buy box.
     */
    @Test
    fun `a shippable item with a buying-options link is still a yes`() {
        val v = extractor.extract(
            PageSignals(
                availability = "In Stock",
                deliveryBlock = "FREE delivery Monday, September 7 to Colombia",
                buybox = "In Stock / See All Buying Options / Add to Cart",
            ),
        )
        assertEquals(CoShipping.YES, v.value, "a real delivery estimate outranks the link")
    }

    @Test
    fun `an empty page is still unclear, not a no`() {
        // No buybox text either -- nothing to go on, so it must not become a NO.
        assertEquals(CoShipping.UNKNOWN, extractor.extract(PageSignals()).value)
    }

    @Test
    fun `evidence is capped so a row cannot be bloated`() {
        val v = extractor.extract(PageSignals(availability = "cannot be shipped to your selected delivery location " + "x".repeat(2000)))
        assertTrue(v.evidence.length <= CoShippingExtractor.EVIDENCE_LIMIT)
    }
}
