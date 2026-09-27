package com.luis.dealstracker.ingest

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every title here is a real one from Exito or Carulla at Laureles, 2026-09-24. */
class UnitSizeTest {

    private fun size(title: String, unit: String? = "un", multiplier: Double? = 1.0) =
        UnitSize.of(title, unit, multiplier)?.let { it.measure to it.quantity.stripTrailingZeros().toPlainString() }

    @Test
    fun `reads the parenthesised pack size both stores end titles with`() {
        assertEquals("g" to "600", size("Café SELLO ROJO molido fuerte (600  gr)"))
        assertEquals("ml" to "500", size("Aceite de oliva CARBONELL MARCA EXCLUSIVA extra virgen (500  ml)"))
        assertEquals("un" to "12", size("Huevos AAA AVINAL rojo (12  und)"))
        assertEquals("un" to "6", size("Agua con gas BRISA saborizada surtida x6und (6  UNIDAD)"))
        assertEquals("g" to "148.8", size("Galletas TOSH tipo sándwich yogurt y fresas (148.8  gr)"))
    }

    @Test
    fun `reads produce sizes written without parentheses`() {
        assertEquals("g" to "150", size("Espinaca Baby  150  gr"))
        assertEquals("g" to "1000", size("Pera Nacional TAEQ 1000  gr"))
        assertEquals("un" to "1", size("Mandarina Onecco  1  und"))
        assertEquals("un" to "3", size("Aguacates x 3 unidades"))
    }

    /** A multipack names the part before the whole; the whole is what the price buys. */
    @Test
    fun `the last size in the title wins`() {
        assertEquals("ml" to "1200", size("Jugo HIT Surtido x 6und 200ml (1200  ml)"))
        assertEquals("ml" to "5000", size("Bebida DEL VALLE fresh frutas cítricas x2und 2.5l (5000  ml)"))
        assertEquals("g" to "676", size("Alimento lácteo BON YURT choco krispis x4 und 169 g (676  gr)"))
    }

    /** Weighed goods name no size in the title; the multiplier is the pack weight in kg. */
    @Test
    fun `a weighed item takes its size from the multiplier`() {
        assertEquals("g" to "1173", size("Zapayo", "kg", 1.173))
        assertEquals("g" to "500", size("Uva Verde Sin Semilla", "kg", 0.5))
        assertEquals("g" to "1000", size("Pimenton Colores", "kg", 1.0))
    }

    @Test
    fun `a dot followed by three digits is a thousands group`() {
        assertEquals("ml" to "1000", size("Agua CRISTAL (1.000 ml)"))
        assertEquals("ml" to "2500", size("Aceite (2,5 l)"))
    }

    /** Null, never a guess: a wrong pack size makes a wrong "cheaper per kilo". */
    @Test
    fun `no unit word means no size`() {
        assertNull(size("12 x Limón Tahití"))
        assertNull(size("2 x Piña"))
        assertNull(size("Sandia Gourmet"))
        // "8 grande" is not 8 grams: the unit must end at a word boundary.
        assertNull(size("Huevos 8 grande"))
    }

    @Test
    fun `unit price is per 100 g, per litre and per unit`() {
        assertEquals(BigDecimal("1000"), unitPrice(BigDecimal("6000"), UnitSize("g", BigDecimal(600))))
        assertEquals(BigDecimal("3000"), unitPrice(BigDecimal("4500"), UnitSize("ml", BigDecimal(1500))))
        assertEquals(BigDecimal("1000"), unitPrice(BigDecimal("12000"), UnitSize("un", BigDecimal(12))))
        assertNull(unitPrice(BigDecimal("12000"), null))
    }
}
