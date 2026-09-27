package com.luis.dealstracker.sources

import com.luis.dealstracker.model.PromotionKind
import com.luis.dealstracker.sources.vtex.IsSearchResponse
import com.luis.dealstracker.sources.vtex.PromotionNames
import com.luis.dealstracker.sources.vtex.SiteBanners
import com.luis.dealstracker.sources.vtex.VtexGrocerySource
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every name here is a real one from Exito or Carulla on 2026-09-25. */
class PromotionNamesTest {

    private val today = LocalDate.of(2026, 9, 25)
    private fun dates(name: String) = PromotionNames.dates(name, today)

    @Test
    fun `dates in both orders, English and Spanish months, and sept`() {
        assertEquals(LocalDate.of(2026, 9, 25) to LocalDate.of(2026, 9, 25),
            dates("mastercard_20_aliado_prov_mercado_categorias_id40239_sep25_sep25"))
        assertEquals(LocalDate.of(2026, 9, 25) to LocalDate.of(2026, 9, 27),
            dates("cmp_15_fisi_1773367_mdo_plus_ID40220_25sep_27sep"))
        assertEquals(LocalDate.of(2026, 9, 17) to LocalDate.of(2026, 10, 18),
            dates("cmp_20_fisi_1747554_id39117_plus_17sept_18oct"))
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31),
            dates("cmp_20_prov_mcdo_fruver_franja_ID19665_1ene_31dic"))
    }

    /** `10jul_01ene` ends before it starts, so it runs over New Year. */
    @Test
    fun `a range ending before it starts rolls into next year`() {
        assertEquals(LocalDate.of(2026, 7, 10) to LocalDate.of(2027, 1, 1),
            dates("cmp_20_prov_mercado_cat2_vie_id33945_10jul_01ene"))
    }

    @Test
    fun `a name without a trailing range has no dates`() {
        assertEquals(null to null, dates("cmp_20_prov_mercado"))
        // A month-like token inside the name is not a range unless it ends the name.
        assertEquals(null to null, dates("cmp_20_sep25_mercado"))
    }

    @Test
    fun `shelf promotions read their percentage, and a fixed price is not one`() {
        assertEquals(BigDecimal("20"), PromotionNames.shelf("cmp_20_prov_mcdo_fruver_franja_ID19665_1ene_31dic").percent)
        // cmp_7650_... is a fixed price of 7,650, not 7650%.
        assertNull(PromotionNames.shelf("cmp_7650_fisi_1768612_mercado_plus_ID39869_19sep_30nov").percent)
        assertEquals(PromotionKind.SHELF, PromotionNames.shelf("cmp_25_exclu_mercado_plus_ID_22478_feb13_dic25").kind)
    }

    private val products = Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<IsSearchResponse>(
        requireNotNull(javaClass.getResourceAsStream("/fixtures/vtex-card-teasers.json")).bufferedReader().readText(),
    ).products.associateBy { it.productId }

    private fun promos(id: String) =
        VtexGrocerySource().toGroceryDeal(products.getValue(id), "https://www.exito.com", "COP", "x")!!.promotions

    @Test
    fun `a card teaser is recorded with its percent and card ids`() {
        val card = promos("3173448").single { it.kind == PromotionKind.CARD }
        assertEquals("mastercard_20_aliado_prov_mercado_categorias_id40239_sep25_sep25", card.name)
        assertEquals(BigDecimal("20"), card.percent)
        assertEquals("4,501", card.cardIds)
    }

    /** "Pague 2, lleve 3": the 100% is the free unit, so it is not recorded as a percentage. */
    @Test
    fun `a multibuy records pay and take, not a percentage`() {
        val m = promos("3088489").single { it.kind == PromotionKind.MULTIBUY }
        assertEquals(2, m.payQty)
        assertEquals(3, m.takeQty)
        assertNull(m.percent)
    }

    @Test
    fun `stock is kept from the chosen offer`() {
        assertEquals(10000, VtexGrocerySource().toGroceryDeal(products.getValue("3173448"), "https://www.exito.com", "COP", "x")!!.stockQty)
    }

    /** Two real SuperBar configs from exito.com's homepage, one of them marked hidden. */
    @Test
    fun `banner configs are read from the homepage HTML`() {
        val html = """
            <script>{"id":"1","name":"SuperBar","data":{"showComponent":true,"position":"top","textDesktop":"🥑30% con Mastercard en frutas y verduras 6:00 am a 11:59 am🍓","textMobile":"🥑30% con Mastercard en fruver","buttonLink":"/mercado/frutas-y-verduras","initialDate":"Sep 19, 2025 6:0 AM","endDate":"Sep 19, 2025 12:0 PM"}},
            {"id":"2","name":"SuperBar","data":{"showComponent":false,"textDesktop":"🥃Hasta 25% en whiskys","initialDate":"Sep 19, 2025 12:0 PM","endDate":"Sep 20, 2025 6:0 AM"}},
            {"id":"3","name":"Other","data":{"textDesktop":"not a banner"}}</script>
        """.trimIndent()
        val banners = SiteBanners.parse(html)
        assertEquals(2, banners.size)
        val fruver = banners.first()
        assertTrue(fruver.text.contains("6:00 am a 11:59 am"))
        assertEquals(true, fruver.shown)
        assertEquals(LocalDateTime.of(2025, 9, 19, 6, 0), fruver.statedFrom)
        assertEquals(LocalDateTime.of(2025, 9, 19, 12, 0), fruver.statedTo)
        assertEquals("/mercado/frutas-y-verduras", fruver.link)
        assertEquals(false, banners[1].shown)
    }
}
