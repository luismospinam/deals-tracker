package com.luis.dealstracker.ask

/**
 * Files tracked clothes under a type when the title names none. The Tracked tab reads the type from
 * the title's nouns (`web.ProductType`), and Amazon's search tiles truncate and abbreviate: "Men's
 * Tech 2.0 1/2 Zip", "Fleece Hoody" and "Mens Estro 19 JSY" all landed under `other`.
 *
 * Only rows the nouns leave under `other` are asked about, once each: the answer is kept in
 * `deals.type_ai` (V46), and `other` is itself an answer, so a row nothing fits is not asked again.
 * The asking and parsing are [AisleClassifier]'s, given this prompt and this list of names.
 */

/** The names an answer may use: `web.ProductType`'s labels, in its order. A test holds the two together. */
val TRACKED_TYPES = listOf("shoes", "hoodies", "boxers", "pants", "shirts", "other")

val TYPE_SYSTEM_PROMPT = """
You file men's sportswear listings from Amazon under one type each. Titles are search-tile titles:
often truncated, abbreviated, or only a model name. The text after the last " — " is the size.

The types, exactly as they must be written:
${TRACKED_TYPES.joinToString("\n") { "- $it" }}

What goes where:
- shoes: any footwear. A numeric size such as 8 or 8.5 means footwear.
- hoodies: upper-body layers worn over a top: hoodies ("hoody"), sweatshirts, sweaters, cardigans, jackets, half-zip and quarter-zip tops, crew-neck fleece, track tops, vests.
- boxers: underwear.
- pants: anything for the legs: trousers, jeans, joggers, shorts ("short"), tights, a full tracksuit.
- shirts: T-shirts, jerseys ("JSY"), polos, tank and sleeveless tops, long-sleeve and compression tops, base layers.
- other: not clothing (socks, hats, bags and accessories included), or the title does not say enough to tell.

Use what you know of the brand's product lines: a "Tiro 23 Short" is shorts, a "Park 18" is a jersey.
Answer other rather than guess when a title could be two types.

You get one listing per line: number, title, brand, store category, separated by tabs.
Answer with one line per listing and nothing else: the number, a tab, the type. No heading, no notes.
""".trimIndent()
