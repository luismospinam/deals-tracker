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

    @Test
    fun `evidence is capped so a row cannot be bloated`() {
        val v = extractor.extract(PageSignals(availability = "cannot be shipped to your selected delivery location " + "x".repeat(2000)))
        assertTrue(v.evidence.length <= CoShippingExtractor.EVIDENCE_LIMIT)
    }
}
