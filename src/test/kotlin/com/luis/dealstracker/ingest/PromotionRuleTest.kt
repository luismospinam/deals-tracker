package com.luis.dealstracker.ingest

import com.luis.dealstracker.model.RawDeal
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The promotion rule decides what Luis actually sees, so its edge cases are pinned
 * down here. Exercised directly to keep it independent of the DB.
 */
class PromotionRuleTest {

    private val service = IngestService(
        repo = throwingRepo(),
        registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
        app = fakeAppConfig(),
        http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
    )

    private val floor = BigDecimal(25)

    private fun deal(price: String, listPrice: String? = null) = RawDeal(
        externalId = "x",
        title = "t",
        url = "u",
        price = BigDecimal(price),
        currency = "COP",
        listPrice = listPrice?.let { BigDecimal(it) },
    )

    private fun reason(
        price: String,
        listPrice: String? = null,
        previousMin: String? = null,
        curated: Boolean = false,
        isNew: Boolean = true,
    ) = service.promotionReason(
        deal(price, listPrice),
        previousMin?.let { BigDecimal(it) },
        floor,
        curated,
        isNew,
    )

    @Test
    fun `advertised discount at or above the floor promotes`() {
        assertEquals(PromotionReason.DISCOUNT_FLOOR, reason("75", "100"))
        assertEquals(PromotionReason.DISCOUNT_FLOOR, reason("74", "100"))
    }

    /** 24% must not pass a 25% floor -- the boundary is where an off-by-one would hide. */
    @Test
    fun `advertised discount below the floor does not promote`() {
        assertEquals(PromotionReason.NONE, reason("76", "100"))
        assertEquals(PromotionReason.NONE, reason("99", "100"))
    }

    /**
     * The reason the floor alone is insufficient: Exito marks ~94% of its catalog as
     * discounted, so a modest advertised discount is the default state, not a signal.
     */
    @Test
    fun `missing list price is not treated as a zero percent discount`() {
        assertEquals(PromotionReason.NONE, reason("100", listPrice = null))
    }

    @Test
    fun `list price at or below sale price does not promote`() {
        assertEquals(PromotionReason.NONE, reason("100", "100"))
        assertEquals(PromotionReason.NONE, reason("100", "90"))
    }

    /** Beats an inflated list price: independent of what the retailer claims. */
    @Test
    fun `price below the recorded minimum promotes even with no discount claim`() {
        assertEquals(PromotionReason.PRICE_DROP, reason("90", listPrice = null, previousMin = "100"))
    }

    @Test
    fun `price equal to the recorded minimum is not a drop`() {
        assertEquals(PromotionReason.NONE, reason("100", previousMin = "100"))
    }

    @Test
    fun `price above the recorded minimum is not a drop`() {
        assertEquals(PromotionReason.NONE, reason("110", previousMin = "100"))
    }

    @Test
    fun `a real drop takes precedence over the discount floor`() {
        // Both rules fire; PRICE_DROP is the stronger claim and should be reported.
        assertEquals(PromotionReason.PRICE_DROP, reason("50", "100", previousMin = "60"))
    }

    /**
     * On the very first sighting there is no history, so the drop rule cannot fire.
     * Documented explicitly because it means day one is threshold-driven only.
     */
    @Test
    fun `first sighting cannot trigger a price drop`() {
        assertEquals(PromotionReason.NONE, reason("50", previousMin = null))
    }

    @Test
    fun `curated source promotes newly seen items with no price data`() {
        assertEquals(PromotionReason.CURATED_SOURCE, reason("50", curated = true, isNew = true))
    }

    /** Otherwise every poll would re-promote every unchanged Slickdeals item. */
    @Test
    fun `curated source does not re-promote items already seen`() {
        assertEquals(PromotionReason.NONE, reason("50", curated = true, isNew = false))
    }

    @Test
    fun `curated source still reports a genuine drop rather than just being new`() {
        assertEquals(
            PromotionReason.PRICE_DROP,
            reason("40", previousMin = "50", curated = true, isNew = false),
        )
    }
}
