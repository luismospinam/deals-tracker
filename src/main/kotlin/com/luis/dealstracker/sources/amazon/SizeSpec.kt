package com.luis.dealstracker.sources.amazon

/**
 * Which garment a search term is looking for, and therefore which configured size applies.
 *
 * Sizes group by garment type rather than by product — one shoe size, one top size, one
 * bottom size — so the config carries three values instead of one per item.
 */
internal enum class Garment(val configKey: String, val category: String?) {
    // The category is the one Amazon's own search HTML does not give us: a result tile carries
    // no department. Supplying it means `Categorizer` matches on a source-provided path, which
    // it prefers over guessing from a title -- so a Nike title that happens to match no keyword
    // still lands in Fashion rather than Other, where it would also be invisible to a watch
    // pinned to a category. `FalabellaNextSource` attaches a category for the same reason.
    //
    // Spanish, because these strings are matched against `Fashion`'s `sourcePatterns`
    // ("calzado", "ropa", "moda") in config/categories.yaml.
    SHOES("sizeShoes", "calzado"),
    TOPS("sizeTops", "ropa"),
    BOTTOMS("sizeBottoms", "ropa"),

    /**
     * No size dimension worth resolving (a cap, a bag). Tracked whole.
     *
     * **Category deliberately null.** This used to attach a clothing category unconditionally,
     * which is how a live run stored 33 televisions and toilets as `Fashion`. An unsized item
     * is one this adapter cannot classify from the search term, so it publishes nothing and
     * lets `Categorizer` work from the title -- the honest answer, including when that answer
     * turns out to be `Other`.
     */
    UNSIZED("", null);

    companion object {
        /**
         * Infers the garment from a search term, so adding a search does not also mean
         * declaring its type.
         *
         * Order matters: `sweatpants` and `sweatshirt` share a prefix, and `pants` must not
         * claim a `sweatshirt`. Longest-specific first, and BOTTOMS is tested before TOPS
         * because "track pants" and "sweatpants" both contain neither's ambiguity but
         * "shorts" would otherwise fall through.
         */
        fun of(term: String): Garment {
            val t = term.lowercase()
            return when {
                SHOE_WORDS.any { t.contains(it) } -> SHOES
                BOTTOM_WORDS.any { t.contains(it) } -> BOTTOMS
                TOP_WORDS.any { t.contains(it) } -> TOPS
                else -> UNSIZED
            }
        }

        private val SHOE_WORDS = listOf(
            "shoe", "sneaker", "trainer", "cleat", "boot", "sandal", "slide", "runner",
        )

        // Before TOPS: "sweatpants" contains no top word, but "track pants" and "jogger"
        // must not be reached by a broader top rule later.
        private val BOTTOM_WORDS = listOf(
            "pant", "short", "jogger", "sweatpant", "trouser", "tight", "legging",
            // Underwear takes the same `international_size` rail as shirts and pants -- verified
            // on a live boxer search, where S is the same node id (2475467011) as for pants. So it
            // needs no new size dimension, only a noun so the term is not read as UNSIZED.
            "boxer", "brief", "trunk", "underwear",
        )

        private val TOP_WORDS = listOf(
            "hoodie", "shirt", "tee", "t-shirt", "sweatshirt", "crewneck", "pullover",
            "jacket", "windbreaker", "top", "jersey", "polo", "vest",
        )
    }
}
