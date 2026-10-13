package com.luis.dealstracker.sources

/**
 * Cleaning for the specification text adapters hand over, so every source stores it the same way.
 *
 * The data goes to a language model, where each character costs tokens on every question: markup,
 * embedded video players and legal boilerplate are all dead weight, and a description is capped so one
 * wordy retailer cannot make its rows dominate a prompt.
 */
object SpecText {
    /** A value longer than this is prose, not a specification; it belongs in the description. */
    const val MAX_VALUE = 300
    const val MAX_DESCRIPTION = 2500

    /** Labels that say nothing about the product: legal text, support contacts, embedded media. */
    private val NOISE = Regex(
        "garant[ií]a|servicio t[eé]cnico|v[ií]deo|video|consejos? de cuidado|vida [uú]til|" +
            "origen|pa[ií]s de origen|condici[oó]n del producto",
        RegexOption.IGNORE_CASE,
    )

    /** One cleaned label/value pair, or null when the value is empty, markup or noise. */
    fun pair(name: String?, value: String?): Pair<String, String>? {
        val label = name?.let(::plain)?.takeIf { it.isNotBlank() } ?: return null
        val text = value?.let(::plain)?.takeIf { it.isNotBlank() } ?: return null
        if (NOISE.containsMatchIn(label)) return null
        if (value.contains("<iframe", ignoreCase = true)) return null
        return label to text.take(MAX_VALUE)
    }

    /** A description as plain text: tags gone, entities decoded, whitespace collapsed, capped. */
    fun description(html: String?): String? =
        html?.let(::plain)?.takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION)

    fun plain(html: String): String =
        html.replace(Regex("<br\\s*/?>|</p>|</li>|</div>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .let(::decodeEntities)
            .replace(Regex("\\s+"), " ")
            .trim()

    /** The handful of entities WooCommerce and VTEX actually emit; numeric ones are decoded in general. */
    fun decodeEntities(text: String): String =
        text.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&nbsp;", " ")
}
