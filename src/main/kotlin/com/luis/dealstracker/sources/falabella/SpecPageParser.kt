package com.luis.dealstracker.sources.falabella

import com.luis.dealstracker.db.DealSpecs
import com.luis.dealstracker.sources.SpecText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads a product's specification table and description off its own page.
 *
 * Falabella and Homecenter run the same Next.js build, but their search results and their product
 * pages carry different data. The search results the adapter polls hold prices and titles; the table
 * of what the product *is* sits only on the page, in `__NEXT_DATA__`:
 *
 *  - **Falabella:** `props.pageProps.productData.attributes.specifications`, a list of `{name, value}`.
 *  - **Homecenter:** `props.pageProps.productProps.result.attributes`, a list of `{name, values, group}`
 *    where the customer-facing rows are those in group `AttributesSortedMap`; the rest are internal
 *    flags (`is_marketplace_product`, `provider_id`, ...) that would only cost tokens.
 *
 * Null means the page has neither shape -- the site changed, or this is not a product page -- which the
 * caller must NOT record as "no specs", or one redesign would mark every product as checked.
 */
object SpecPageParser {
    fun parse(html: String): DealSpecs? {
        val island = FalabellaNextSource().extractNextData(html) ?: return null
        val pageProps = island["props"]?.jsonObject?.get("pageProps") as? JsonObject ?: return null
        return falabella(pageProps) ?: homecenter(pageProps)
    }

    private fun falabella(pageProps: JsonObject): DealSpecs? {
        val product = pageProps["productData"] as? JsonObject ?: return null
        val specs = ((product["attributes"] as? JsonObject)?.get("specifications") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { SpecText.pair(it.text("name"), it.text("value")) }
            .let(::merge)
        // The long text is the clean copy; the short one is the seller's own note, often HTML-escaped,
        // and sometimes the only place the layout ("esquinero en L con baúl") is stated.
        val description = listOfNotNull(product.text("longDescription"), product.text("description"))
            .joinToString(" ") { unescape(it) }
        return DealSpecs(specs, SpecText.description(description))
    }

    private fun homecenter(pageProps: JsonObject): DealSpecs? {
        val result = (pageProps["productProps"] as? JsonObject)?.get("result") as? JsonObject ?: return null
        val specs = (result["attributes"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.text("group") == CUSTOMER_FACING_GROUP }
            .mapNotNull { a ->
                val values = (a["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                SpecText.pair(a.text("name"), values.filter { it.isNotBlank() }.joinToString("; "))
            }
            .let(::merge)
        val description = listOfNotNull(result.text("longDescription"), result.text("description"))
            .joinToString(" ") { unescape(it) }
        return DealSpecs(specs, SpecText.description(description))
    }

    /**
     * Pairs to a map, joining the values of a repeated label. Homecenter lists `Tipo` twice (the
     * product class and the fitting type), and `toMap` would silently keep only the last.
     */
    private fun merge(pairs: List<Pair<String, String>>): Map<String, String> =
        pairs.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.distinct().joinToString("; ") }

    /** Falabella's short description arrives entity-escaped (`&lt;div&gt;`), so decode before stripping tags. */
    private fun unescape(text: String): String = if (text.contains("&lt;")) SpecText.decodeEntities(text) else text

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val CUSTOMER_FACING_GROUP = "AttributesSortedMap"
}
