package com.luis.dealstracker.model

/**
 * A screen size in a listing title, in whole inches: `55 Pulgadas`, `65"`, `55''`, `43”`, `55-Inch`,
 * `50 in`. Colombian stores and Amazon both put the size in the title, and nowhere else we read.
 *
 * A number followed by an inch unit counts, and failing that a number straight after the word TV
 * ("Televisor 32 Smart TV", "Tv 50 Samsung") or straight before the panel type ("Smart TV Original
 * 32 LED", "55 QLED"), which is how some stores write it. A bare `55`
 * elsewhere is not read as a size, nor are the centimetres some stores add ("177 cm"), nor model
 * codes like `UN55U8000`: each of those also turns up meaning something else, and a wrong size
 * would hide a TV that was wanted.
 */
private val WITH_UNIT = Regex(
    """(?<![\d.,])(\d{1,3})(?:[.,]\d+)?\s*(?:"|''|”|″|´´|’’|-?\s*inch(?:es)?\b|-?\s*in\b|pulgadas?\b|pulg\b|pul\b)""",
    RegexOption.IGNORE_CASE,
)
private val AFTER_TV = Regex("""\b(?:televisor|televisi[oó]n|tv)\s+(\d{2,3})\b""", RegexOption.IGNORE_CASE)
private val BEFORE_PANEL = Regex("""(?<![\d.,])(\d{2,3})\s+(?:led|qled|oled|qned|miniled|uhd|fhd|hd)\b""", RegexOption.IGNORE_CASE)

/**
 * Spaces that are not U+0020. Falabella writes "43\u00A0Pulgadas" with a non-breaking space, which
 * `\s` does not match, so that 43-inch set passed a 55-inch floor until this was folded first.
 */
private val ODD_SPACES = Regex("[\u00A0\u2007\u202F\u2009\u200A]")

/** Screens run from a 5" portable to a 150" projector screen; anything else is not a screen size. */
private val PLAUSIBLE = 5..150

/** The inch size [title] names, or null when it names none. */
fun screenInches(title: String): Int? {
    val t = title.replace(ODD_SPACES, " ")
    // In order of trust: an explicit unit, then the two unit-less spellings.
    return (WITH_UNIT.findAll(t) + AFTER_TV.findAll(t) + BEFORE_PANEL.findAll(t))
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .firstOrNull { it in PLAUSIBLE }
}

/**
 * Whether a listing clears a watch's smallest screen. A title with no size passes: an unknown size
 * is not a small one, and dropping it would hide exactly the listings whose titles are terse.
 */
fun fitsMinScreen(minInches: Int?, title: String): Boolean {
    if (minInches == null) return true
    val inches = screenInches(title) ?: return true
    return inches >= minInches
}
