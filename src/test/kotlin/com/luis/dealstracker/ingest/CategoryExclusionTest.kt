package com.luis.dealstracker.ingest

import com.luis.dealstracker.config.ProviderConfig
import com.luis.dealstracker.model.RawDeal
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Groceries stay off the feed even when a store-wide watch search finds them. The titles and category
 * paths are real feed rows from 2026-09-25, found for the `sanitario` watch term.
 */
class CategoryExclusionTest {

    private val service = IngestService(
        repo = throwingRepo(),
        registry = com.luis.dealstracker.sources.SourceRegistry(emptyList()),
        app = fakeAppConfig(),
        http = com.luis.dealstracker.sources.PoliteHttp("test", 0),
    )

    private fun deal(title: String, category: String?) =
        RawDeal(externalId = title, title = title, url = "u", price = BigDecimal.ONE, currency = "COP", category = category)

    private fun kept(excluded: List<String>, vararg deals: RawDeal) =
        service.applyCategoryExclusions(
            ProviderConfig(id = "exito", type = "vtex", listOptions = mapOf("excludeCategories" to excluded)),
            deals.toList(),
        ).map { it.title }

    @Test
    fun `drops everything under an excluded top level`() {
        assertEquals(
            listOf("Combo sanitario CORONA"),
            kept(
                listOf("Mercado"),
                deal("Cepillo sanitario TASK con base protectora", "Mercado > Aseo del hogar > Implementos para la limpieza"),
                deal("Tapete Sanitario Para Perros", "Mercado > Mascotas > Perros > Productos de aseo para perros"),
                deal("Combo sanitario CORONA", "Hogar y decoración > Baño"),
            ),
        )
    }

    /** Carulla spells its aisles with accents the config does not; both must still match. */
    @Test
    fun `matches regardless of accents and case`() {
        assertEquals(
            emptyList(),
            kept(listOf("Lacteos, huevos y refrigerados"), deal("Leche", "Lácteos, huevos y refrigerados > Leche")),
        )
    }

    /** Top level only: a deeper segment sharing the name is somebody else's aisle. */
    @Test
    fun `a deeper segment with the same name is kept`() {
        assertEquals(
            listOf("Horno"),
            kept(listOf("Despensa"), deal("Horno", "Electrodomésticos > Despensa")),
        )
    }

    @Test
    fun `no list, or no category on the row, keeps everything`() {
        assertEquals(listOf("A"), kept(emptyList(), deal("A", "Mercado > Despensa")))
        assertEquals(listOf("B"), kept(listOf("Mercado"), deal("B", null)))
    }
}
