package com.luis.dealstracker.verify

/**
 * Compares the size a tracked row claims with the size its own product page reports.
 *
 * The claim is the title suffix `AmazonSource.titleWithSize` appends (`… — 8`), which came from
 * Amazon's size FILTER. That filter returns a representative of the product family, so the claim
 * can be wrong; the page's own variation entry cannot be.
 *
 * ## Only reject what is certainly wrong
 *
 * A rejection removes the row for good, so anything short of a definite mismatch is [Unknown]:
 *
 *  - **Numeric sizes and plain letter sizes are judged.** Letter sizes go through a small, closed
 *    table (`M` = `Medium`, `XS` = `X-Small`); a page label outside it is left alone, not guessed at.
 *  - **A page with no readable size** is [Unknown], never a mismatch.
 *  - **Same number plus unrecognised text** (`8 US`) is [Unknown]. Only text that names a
 *    different shoe — a width or a women's/kids' scale — is a mismatch.
 */
internal object SizeCheck {

    sealed interface Verdict {
        data object Match : Verdict
        data class Mismatch(val pageSize: String) : Verdict
        data object Unknown : Verdict
    }

    private val NUMERIC = Regex("""\d+(?:\.\d+)?""")
    private val LEADING_NUMBER = Regex("""^(\d+(?:\.\d+)?)""")
    private val OTHER_SHOE = Regex("""wide|narrow|women|kid|little|big|youth|toddler""", RegexOption.IGNORE_CASE)

    private val WOMENS = Regex("""\b(?:wom[ae]n(?:'?s)?|wmns|ladies|girls?)\b""", RegexOption.IGNORE_CASE)
    private val MENS = Regex("""\b(?:men|mens|men's|unisex)\b""", RegexOption.IGNORE_CASE)

    /**
     * Why a row is for the wrong wearer, or null when it is not evidently so.
     *
     * Every tracked search names men's items, but Amazon's results mix in women's ones, and a women's
     * size 8 is a different shoe from a men's 8. Only a name that says women's (or girls', ladies',
     * `WMNS`) and never men's or unisex counts: "Hoodie for Men & Women" fits, so it stays. The word
     * boundaries matter -- `men's` also appears inside `Women's`, and must not make it look unisex.
     */
    fun wrongAudience(title: String): String? {
        val name = title.substringBeforeLast(" — ")
        if (!WOMENS.containsMatchIn(name) || MENS.containsMatchIn(name)) return null
        return "listing is for women (\"${WOMENS.find(name)!!.value}\" in the name), tracking men's"
    }

    /** The size a title claims: what follows the last ` — `, or null when it carries none. */
    fun claimedSize(title: String): String? =
        title.substringAfterLast(" — ", missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }

    /**
     * Garment letter sizes in the two spellings Amazon uses -- the filter rail says `S`, the product
     * page says `Small` or `X-Small` -- folded to one canonical form. Anything else (`Small Tall`,
     * `Medium/31" Inseam`, `Standard`, a waist number) is NOT in the table and stays unjudged: a
     * mismatch retires the row, so only a pair that is certainly two different sizes may do it.
     */
    private val LETTER_SIZES: Map<String, String> = mapOf(
        "xxs" to "xxs", "xx-small" to "xxs", "xx small" to "xxs", "2x-small" to "xxs",
        "xs" to "xs", "x-small" to "xs", "x small" to "xs", "extra small" to "xs",
        "s" to "s", "small" to "s",
        "m" to "m", "medium" to "m",
        "l" to "l", "large" to "l",
        "xl" to "xl", "x-large" to "xl", "x large" to "xl", "extra large" to "xl",
        "xxl" to "xxl", "2xl" to "xxl", "xx-large" to "xxl", "2x-large" to "xxl", "xx large" to "xxl",
        "xxxl" to "3xl", "3xl" to "3xl", "3x-large" to "3xl", "xxx-large" to "3xl",
    )

    private fun letterSize(label: String): String? = LETTER_SIZES[normalize(label)]

    fun judge(title: String, pageSize: String?): Verdict {
        val claimed = claimedSize(title)
        letterSize(claimed ?: "")?.let { wantedLetter ->
            val pageLetter = pageSize?.let(::letterSize) ?: return Verdict.Unknown
            return if (pageLetter == wantedLetter) Verdict.Match else Verdict.Mismatch(pageSize)
        }
        val wanted = claimed?.let(::normalize)?.takeIf { NUMERIC.matches(it) }
            ?: return Verdict.Unknown
        val page = pageSize?.let(::normalize)?.takeIf { it.isNotEmpty() }
            // Some listings carry no variation data at all and state the size in the name instead
            // ("… (UK 8.5 US 9.5 EU 43 …)"), so a page with no size dimension falls back to that.
            ?: return judgeByName(title, wanted)

        if (page == wanted) return Verdict.Match
        val number = LEADING_NUMBER.find(page)?.groupValues?.get(1) ?: return Verdict.Unknown
        return when {
            number != wanted -> Verdict.Mismatch(pageSize)
            OTHER_SHOE.containsMatchIn(page) -> Verdict.Mismatch(pageSize)
            else -> Verdict.Unknown
        }
    }

    /** `US 9.5`, as a standalone token. A `-`, `/` or `to` right after means a range, which is not one size. */
    private val US_SIZE = Regex("""\bUS\s*(\d+(?:\.\d+)?)(?!\s*(?:[-–/]|to\b|\d))""", RegexOption.IGNORE_CASE)

    /**
     * The size the listing's own NAME states, for a page with no size dimension.
     *
     * Deliberately narrower than the dimension comparison: only an explicit `US <n>` counts, and only
     * when the name states exactly one such size and is not a women's/kids' listing, where the same
     * label means a different shoe. Anything else is [Verdict.Unknown].
     */
    private fun judgeByName(title: String, wanted: String): Verdict {
        val name = title.substringBeforeLast(" — ")
        if (OTHER_SHOE.containsMatchIn(name)) return Verdict.Unknown
        val stated = US_SIZE.findAll(name).map { normalize(it.groupValues[1]) }.toSet()
        val only = stated.singleOrNull() ?: return Verdict.Unknown
        return if (only == wanted) Verdict.Match else Verdict.Mismatch("US $only (from the name)")
    }

    /** Case- and space-folded, a decimal comma read as a point (`8,5` is how es-CO prints 8.5). */
    private fun normalize(label: String): String =
        label.trim().lowercase()
            .replace(Regex("""(?<=\d),(?=\d)"""), ".")
            .replace(Regex("""\s+"""), " ")
}
