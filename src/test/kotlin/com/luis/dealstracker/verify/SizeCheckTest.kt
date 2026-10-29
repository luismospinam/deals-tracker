package com.luis.dealstracker.verify

import com.luis.dealstracker.verify.SizeCheck.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SizeCheckTest {

    private val shoe = "Supernova Rise 2 Running Shoes — 8"

    @Test fun `the same size matches`() = assertEquals(Verdict.Match, SizeCheck.judge(shoe, "8"))

    @Test fun `a half size is a different shoe, not a prefix match`() =
        assertEquals(Verdict.Mismatch("8.5"), SizeCheck.judge(shoe, "8.5"))

    @Test fun `a decimal comma is read as a point`() =
        assertEquals(Verdict.Mismatch("8,5"), SizeCheck.judge(shoe, "8,5"))

    @Test fun `a different number is a mismatch`() =
        assertEquals(Verdict.Mismatch("10"), SizeCheck.judge(shoe, "10"))

    @Test fun `the same number on a wide or women's scale is a mismatch`() {
        assertEquals(Verdict.Mismatch("8 Wide"), SizeCheck.judge(shoe, "8 Wide"))
        assertEquals(Verdict.Mismatch("8 Women/7 Men"), SizeCheck.judge(shoe, "8 Women/7 Men"))
    }

    @Test fun `same number with unrecognised text is not rejected`() =
        assertEquals(Verdict.Unknown, SizeCheck.judge(shoe, "8 US"))

    @Test fun `a page with no readable size is never a mismatch`() {
        assertEquals(Verdict.Unknown, SizeCheck.judge(shoe, null))
        assertEquals(Verdict.Unknown, SizeCheck.judge(shoe, "  "))
    }

    @Test fun `a letter size matches the page's spelled-out name for it`() {
        assertEquals(Verdict.Match, SizeCheck.judge("Hoodie — M", "Medium"))
        assertEquals(Verdict.Match, SizeCheck.judge("Pants — S", "Small"))
        assertEquals(Verdict.Match, SizeCheck.judge("Pants — XS", "X-Small"))
        assertEquals(Verdict.Match, SizeCheck.judge("Pants — XL", "X-Large"))
        assertEquals(Verdict.Match, SizeCheck.judge("Pants — XXL", "XX-Large"))
        assertEquals(Verdict.Match, SizeCheck.judge("Pants — S", "S"))
    }

    @Test fun `a different letter size is a mismatch -- the 658242 case, tracking S but the page is X-Small`() {
        assertEquals(Verdict.Mismatch("X-Small"), SizeCheck.judge("Mens Tigres Uanl DNA Pants — S", "X-Small"))
        assertEquals(Verdict.Mismatch("Small"), SizeCheck.judge("Hoodie — M", "Small"))
        assertEquals(Verdict.Mismatch("X-Large"), SizeCheck.judge("Hoodie — M", "X-Large"))
        assertEquals(Verdict.Mismatch("Large"), SizeCheck.judge("Hoodie — XL", "Large"))
    }

    @Test fun `a letter size against a label outside the table is not judged`() {
        assertEquals(Verdict.Unknown, SizeCheck.judge("Pants — S", "Small Tall"))
        assertEquals(Verdict.Unknown, SizeCheck.judge("Pants — M", "Medium/31\" Inseam"))
        assertEquals(Verdict.Unknown, SizeCheck.judge("Pants — S", "Standard"))
        assertEquals(Verdict.Unknown, SizeCheck.judge("Pants — S", "29"))
        assertEquals(Verdict.Unknown, SizeCheck.judge("Pants — S", null))
    }

    @Test fun `a title with no size suffix is not judged`() =
        assertEquals(Verdict.Unknown, SizeCheck.judge("Some Shoe", "9"))

    @Test fun `the claimed size is what follows the last separator`() {
        assertEquals("8", SizeCheck.claimedSize("Nike — Air Max — 8"))
        assertEquals(null, SizeCheck.claimedSize("Nike Air Max"))
    }

    private fun named(name: String) = "$name — 8"

    @Test fun `a page with no size dimension falls back to the US size in the name`() {
        assertEquals(
            Verdict.Mismatch("US 9.5 (from the name)"),
            SizeCheck.judge(named("Free Metcon 5 Mens Trainers DV3949 (UK 8.5 US 9.5 EU 43, Light Iron ore Green)"), null),
        )
        assertEquals(Verdict.Match, SizeCheck.judge(named("Mens Trainers (UK 7 US 8 EU 41)"), null))
    }

    @Test fun `the name fallback stays quiet unless the name states one clear US size`() {
        assertEquals(Verdict.Unknown, SizeCheck.judge(named("Mens Trainers (UK 8.5 EU 43)"), null))
        assertEquals(Verdict.Unknown, SizeCheck.judge(named("Mens Trainers US 8-10"), null))
        assertEquals(Verdict.Unknown, SizeCheck.judge(named("Mens Trainers US 8 and US 9"), null))
        assertEquals(Verdict.Unknown, SizeCheck.judge(named("Womens Trainers US 9.5"), null))
        assertEquals(Verdict.Unknown, SizeCheck.judge(named("Mens Trainers US 10 12"), null))
    }

    @Test fun `a size dimension on the page wins over the name`() =
        assertEquals(Verdict.Match, SizeCheck.judge(named("Mens Trainers (UK 8.5 US 9.5 EU 43)"), "8"))

    @Test fun `a women's listing is the wrong wearer`() {
        for (t in listOf("Womens Duramo Sl 2.0 — 8", "Women's Pegasus 41 Running Shoes — 8", "Women's WMNS Air Max Dawn — 8", "Ladies Sneaker — 8", "Girls Runner — 8")) {
            assertNotNull(SizeCheck.wrongAudience(t), t)
        }
    }

    @Test fun `men's, unisex and both-sexes listings are kept`() {
        for (t in listOf("Men's Hoodie for Men & Women — M", "Mens Pegasus — 8", "Unisex Runner Women Men — 8", "Pegasus 41 — 8", "Mens Shoes — 8")) {
            assertNull(SizeCheck.wrongAudience(t), t)
        }
    }
}
