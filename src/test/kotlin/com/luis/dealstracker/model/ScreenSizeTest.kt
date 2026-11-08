package com.luis.dealstracker.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading a TV's size out of its title, for a watch's smallest screen. Titles are real ones. */
class ScreenSizeTest {

    @Test
    fun `reads each way the stores write inches`() {
        assertEquals(55, screenInches("Televisor HISENSE 55 pulgadas QLED Uhd4K Smart TV 55Q4SV"))
        assertEquals(65, screenInches("Televisor SmartTv TCL 65\" 4k LED Google Tv 65V6C"))
        assertEquals(55, screenInches("Televisor Samsung 55'' LED UHD 4K Smart TV UN55U8000FKXZL"))
        assertEquals(43, screenInches("Televisor LG 43” LED Full HD"))
        assertEquals(65, screenInches("SAMSUNG 65-Inch Class Crystal UHD 4K DU7200 Smart TV"))
        assertEquals(50, screenInches("TCL 50 Inch Class S5 4K LED Smart TV"))
        assertEquals(70, screenInches("TV KALLEY 70\" Pulgadas 177 cm 70GQ405 4K-UHD QLED Smart TV Google"))
        assertEquals(55, screenInches("Televisor I 55 Pulgadas I 4K I Uhd I Un55U8000Hkxzl"))
    }

    @Test
    fun `reads a bare number straight after the word TV`() {
        assertEquals(32, screenInches("Televisor 32 Smart TV HD Google TV- MOT32NHLE05"))
        assertEquals(50, screenInches("Tv 50 Samsung 4K Uhd Smart Tv 50Ru7100"))
    }

    @Test
    fun `a unit beats a bare number`() {
        // "U65" is a model; the 65 in front of "Smart TV" is not followed by a unit either, so the
        // explicit size anywhere in the title wins when there is one.
        assertEquals(65, screenInches("Televisor iFFALCON U65 65 Smart TV 4K UHD Google TV 65 Pulgadas"))
    }

    @Test
    fun `model codes, centimetres and resolutions are not sizes`() {
        assertNull(screenInches("Televisor Samsung Uhd 4K Un55mu6500"))
        assertNull(screenInches("Televisor IFFALCON S45AR"))
        assertNull(screenInches("Televisor Lg 4K 1080p 2025"))
        assertNull(screenInches("Smart Tv Box 4K Android 6.0"))
    }

    @Test
    fun `a tiny portable screen still counts as small`() {
        assertEquals(7, screenInches("Televisor Lcd Panorámico Portátil De 7 Pulgadas"))
        assertFalse(fitsMinScreen(55, "Televisor Lcd Panorámico Portátil De 7 Pulgadas"))
    }

    @Test
    fun `the floor drops smaller sets and keeps the rest`() {
        assertFalse(fitsMinScreen(55, "Televisor HISENSE 43 pulgadas QLED Fhd Smart TV 43Q4SV"))
        assertFalse(fitsMinScreen(55, "Televisor Samsung 50\" Crystal UHD"))
        assertTrue(fitsMinScreen(55, "Televisor TCL 55 pulgadas LED Ultra HD Smart 4K 55V6C"))
        assertTrue(fitsMinScreen(55, "Televisor Samsung 85\" Crystal UHD 4K Smart UN85U8500HKXZL"))
    }

    @Test
    fun `a title with no size passes, and no floor passes everything`() {
        assertTrue(fitsMinScreen(55, "Televisor Samsung Uhd 4K Un55mu6500"))
        assertTrue(fitsMinScreen(null, "Televisor Hisense HD 32\" Smart Negro 32A4NV"))
    }

    @Test
    fun `a non-breaking space between number and unit still reads`() {
        // Falabella's own bytes: 43, U+00A0, Pulgadas. It slipped past a 55-inch floor on 2026-11-05.
        assertEquals(43, screenInches("Televisor I 43\u00A0Pulgadas I inch GTV I QLED I Multi V1 Series"))
    }

    @Test
    fun `reads a bare number right before the panel type`() {
        assertEquals(32, screenInches("Smart TV Original 32 LED Resistente a Golpes con Doble Vidrio Pantalla Indestructible"))
        assertEquals(65, screenInches("Samsung 65 QLED Q7F 4K"))
        // A resolution or a model is still not a size.
        assertNull(screenInches("Televisor Samsung 4K UHD Un55mu6500"))
    }
}
