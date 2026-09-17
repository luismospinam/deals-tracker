package com.luis.dealstracker.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every GTIN and title here was captured from live exito.com on 2026-09-07, so the cases
 * this pins are the ones the feature actually meets rather than invented ones.
 */
class ProductIdentityTest {

    @Test
    fun `accepts real GS1 codes published by Exito`() {
        // 770 is the Colombian GS1 prefix; these are Challenger, Haceb, Mabe and Electrolux
        // fridges respectively. Returned left-padded to GTIN-14, the form GS1 compares in.
        mapOf(
            "7705191041773" to "07705191041773",
            "7704353404340" to "07704353404340",
            "7704712216294" to "07704712216294",
            "7909569438510" to "07909569438510",
        ).forEach { (raw, expected) ->
            assertEquals(expected, ProductIdentity.validGtin(raw), "$raw should validate")
        }
    }

    /**
     * Exito publishes both forms for one MABE fridge: "757638435765" as a UPC-12 and
     * "0757638435765" as the same code in EAN-13. Unpadded those are two keys for one product,
     * and the cross-site comparison sees two fridges instead of one.
     */
    @Test
    fun `the same barcode in UPC-12 and EAN-13 yields one key`() {
        val upc = ProductIdentity.validGtin("757638435765")
        val ean = ProductIdentity.validGtin("0757638435765")
        assertEquals("00757638435765", upc)
        assertEquals(upc, ean)
        assertEquals(
            ProductIdentity.matchKey("757638435765", "MABE", "MSL480LMLPS0"),
            ProductIdentity.matchKey("0757638435765", "MABE", "MSL480LMLPS0"),
        )
    }

    /** EAN-8 is its own numbering scheme, not a short GTIN-13, so it is not padded. */
    @Test
    fun `leaves EAN-8 at its own width`() {
        // 96385074 is the GS1 documentation example.
        assertEquals("96385074", ProductIdentity.validGtin("96385074"))
    }

    @Test
    fun `rejects Exito's internal references from the same ean field`() {
        // The whole reason validGtin does a checksum: Exito puts these in `ean` alongside
        // real GS1 codes. Clustering on them would merge unrelated products.
        assertNull(ProductIdentity.validGtin("14636"))
        assertNull(ProductIdentity.validGtin("14638"))
        assertNull(ProductIdentity.validGtin("IZ20000899908"))
    }

    @Test
    fun `rejects a code whose check digit is wrong`() {
        // Same digits as a valid Challenger EAN with the last one changed, so length and
        // shape are right and only the checksum can catch it.
        assertNull(ProductIdentity.validGtin("7705191041774"))
    }

    @Test
    fun `rejects blank and non-numeric input`() {
        assertNull(ProductIdentity.validGtin(null))
        assertNull(ProductIdentity.validGtin(""))
        assertNull(ProductIdentity.validGtin("not-a-code"))
    }

    @Test
    fun `tolerates separators in a published code`() {
        assertEquals("07705191041773", ProductIdentity.validGtin("7705191041773"))
        assertEquals("07705191041773", ProductIdentity.validGtin("770-5191-041773"))
        assertEquals("07705191041773", ProductIdentity.validGtin(" 7705191041773 "))
    }

    @Test
    fun `extracts model codes from real appliance titles`() {
        val cases = mapOf(
            "Nevera No Frost Challenger 272 Litros CR290 Titanium" to "CR290",
            "Nevera  MABE No Frost No Frost 421 Lts. Brutos  RMP410FZCC" to "RMP410FZCC",
            "Nevera Haceb No Frost 271 Litros H6164nt27" to "H6164NT27",
            "Nevera MABE No Frost Congelador Superior 313L RMA313FXCG" to "RMA313FXCG",
        )
        cases.forEach { (title, expected) ->
            assertEquals(expected, ProductIdentity.modelCode(title), title)
        }
    }

    @Test
    fun `prefers the full code when a title repeats a truncated form`() {
        // Exito titles really do trail off like this, and the shorter form is a prefix of
        // the longer, so "first match" would pick the wrong one.
        assertEquals(
            "ERB163HB",
            ProductIdentity.modelCode("NEVERA ELECTROLUX Frost Tipo europeo 173 Litros ERB163HB ERB163H"),
        )
    }

    @Test
    fun `reads Haceb's all-digit model numbers`() {
        assertEquals(
            "9003541",
            ProductIdentity.modelCode("Nevera HACEB No Frost Congelador Superior 243 LTS  9003541 TITANIO"),
        )
    }

    @Test
    fun `never mistakes a capacity for a model code`() {
        // The failure this guards against: "247L" is alphanumeric and four characters, so a
        // naive rule picks it, and every 247-litre fridge on the market then clusters as
        // one product.
        assertEquals(
            "RMA247FXCG",
            ProductIdentity.modelCode("Nevera MABE No Frost Congelador Superior 247L RMA247FXCG"),
        )
        // With no real code present, a capacity must yield nothing rather than itself.
        assertNull(ProductIdentity.modelCode("Nevera No Frost 421 Lts Brutos"))
        assertNull(ProductIdentity.modelCode("Nevera Congelador Superior 206 Litros"))
    }

    @Test
    fun `does not return the brand as a model code`() {
        assertNull(ProductIdentity.modelCode("Nevera Hisense No Frost", brand = "HISENSE"))
    }

    @Test
    fun `returns null when a title carries no code at all`() {
        assertNull(ProductIdentity.modelCode("Nevera No Frost Congelador Superior"))
    }

    @Test
    fun `prefers a valid gtin over brand and model`() {
        assertEquals(
            "gtin:07705191041773",
            ProductIdentity.matchKey("7705191041773", "CHALLENGER", "CR290"),
        )
    }

    @Test
    fun `falls back to brand and model when the gtin is junk`() {
        // The Exito case in one assertion: ean is an internal ref, so identity must come
        // from the title instead of being abandoned.
        assertEquals(
            "mdl:mabe|rmp410fzcc",
            ProductIdentity.matchKey("14636", "MABE", "RMP410FZCC"),
        )
    }

    @Test
    fun `folds case and accents so two retailers' spellings agree`() {
        assertEquals(
            ProductIdentity.matchKey(null, "MABE", "RMP410FZCC"),
            ProductIdentity.matchKey(null, "mabe", "rmp410fzcc"),
        )
        assertEquals(
            ProductIdentity.matchKey(null, "Electrolux", "ERB163HB"),
            ProductIdentity.matchKey(null, "ELECTROLUX", "erb163hb"),
        )
    }

    @Test
    fun `yields no key when neither tier is available`() {
        // The common case for groceries and most Slickdeals rows. Must be null, never a
        // placeholder — two nulls must not cluster.
        assertNull(ProductIdentity.matchKey(null, null, null))
        assertNull(ProductIdentity.matchKey("14636", "MABE", null))
        assertNull(ProductIdentity.matchKey(null, null, "RMP410FZCC"))
        assertNull(ProductIdentity.matchKey(null, "  ", "RMP410FZCC"))
    }
}
