package com.luis.dealstracker.sources.amazon

import org.jsoup.Jsoup

/**
 * Amazon's own size filter, discovered from a search page rather than resolved per item.
 *
 * ## Why this replaces resolving sizes item by item
 *
 * The original approach fetched each product's page, read its variation payload, and picked the
 * child ASIN matching the wanted size. It worked, but cost one or two `/dp/` requests **per item**
 * against a host that refuses freely — and that was the measured bottleneck: in one run 14 of 15
 * correctly-resolved size-8 shoes were lost at the price step because the requests were refused.
 *
 * Amazon already offers the same thing server-side. A search URL carrying
 * `rh=p_n_pt_nav_size_men_shoe:1285098011` returns only shoes available in size 8, each tile
 * already priced. Measured: **one request, 48 tiles, 45 usable, all USD**. The per-item fetches
 * disappear, and the size guarantee comes from Amazon instead of from our own matching.
 *
 * ## Why the ids are discovered rather than configured
 *
 * `1285098011` is an opaque browse-node id. Hardcoding a table of them would be a table of magic
 * numbers that rots silently when Amazon renumbers. But the mapping is published on every
 * unfiltered search page, in the left-hand refinement rail, labelled in plain English:
 *
 * ```
 * <a aria-label="Apply 8 filter to narrow results"
 *    href="/s?k=...&rh=...p_n_pt_nav_size_men_shoe%3A1285098011">8</a>
 * ```
 *
 * So the size the operator writes in config stays human (`8`, `M`, `S`) and the id is looked up.
 *
 * ## Three things that make this harder than it looks
 *
 * All three were observed on real pages, and each is pinned by a fixture case:
 *
 *  - **The parameter differs by category.** Footwear uses `p_n_pt_nav_size_men_shoe`; apparel uses
 *    `p_n_pt_nav_size_men_international_size`. Code that assumes one name finds nothing on the
 *    other category.
 *  - **A page offers several size dimensions at once.** Shoes carry size *and* width; pants carry
 *    international size *and* waist *and* big-and-tall. Filtering by width when you meant size is
 *    a silent wrong answer, so the dimension is chosen deliberately rather than taken first.
 *  - **The rail's labels are Amazon's SHORT forms.** The rail says `M` and `2XL` where the product
 *    pages say `Medium` and `XX-Large`. Both are Amazon's own wording for one size, in different
 *    places — so this path wants `M`, and equivalences are handled explicitly rather than by
 *    inventing an alias table.
 */
internal object SizeRefinement {

    /** One size the rail offers, and the `rh` term that selects it. */
    internal data class Option(val parameter: String, val label: String, val nodeId: String) {
        /** The `rh` value, e.g. `p_n_pt_nav_size_men_shoe:1285098011`. */
        val rh: String get() = "$parameter:$nodeId"
    }

    /**
     * Size parameters worth filtering on, most preferred first.
     *
     * Ordered, not a set, because a page offers several and the choice matters. Garment size before
     * waist: the config speaks in `S`/`M`, and a waist measurement is a different question. Width
     * and big-and-tall are deliberately **absent** — a `shoe_width` of `8` would be nonsense, and
     * matching it would filter by the wrong dimension while looking like it worked.
     */
    private val PREFERRED_PARAMETERS = listOf(
        "p_n_pt_nav_size_men_shoe",
        "p_n_pt_nav_size_men_international_size",
        "p_n_pt_nav_size_men_waist",
    )

    /** `aria-label="Apply 8 filter to narrow results"` -> `8`. Also matches the Remove form. */
    private val LABEL_RE = Regex("""(?:Apply|Remove)\s+(.{1,24}?)\s+filter""")

    /** Pulls a size parameter and its node id out of an `rh` value, however many times encoded. */
    private val RH_TERM_RE = Regex("""(p_n_[a-z_]*size[a-z_]*?)(?:%3A|%253A|:)(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Every size option the page offers, across all dimensions.
     *
     * Reads the refinement links' own `aria-label` for the size, because the link text is often an
     * image or a count rather than the size itself. Deliberately returns *all* dimensions and lets
     * [pick] choose, so a diagnostic can show what was actually on offer when a size is unreachable.
     */
    fun parse(html: String): List<Option> {
        val seen = LinkedHashSet<Option>()

        Jsoup.parse(html).select("a[aria-label]").forEach { link ->
            val label = LABEL_RE.find(link.attr("aria-label"))?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@forEach

            // The href is the link's own state, so the LAST matching term is the one it sets --
            // earlier ones are refinements already applied and carried along.
            val href = link.attr("href")
            RH_TERM_RE.findAll(href).lastOrNull()?.let { m ->
                seen += Option(m.groupValues[1].lowercase(), label, m.groupValues[2])
            }
        }
        return seen.toList()
    }

    /**
     * The `rh` term for [wantedSize], or null when the page does not offer it.
     *
     * Null is a reportable outcome, not a failure to paper over: it means either that Amazon does
     * not offer that size for this search, or that the size is written in a form the rail does not
     * use. Either way the caller says so with the sizes it *did* find, so the operator can supply a
     * URL by hand rather than being told nothing.
     *
     * Matching is exact after normalisation, for the reason `SizeMatcher` documents at length: on
     * the rail `XL` is a prefix of `XLT`, and `8` a prefix of `8.5`, so a loose match picks the
     * wrong size at a real price — the worst kind of wrong.
     */
    fun pick(options: List<Option>, wantedSize: String): Option? {
        val wanted = normalize(wantedSize)
        if (wanted.isEmpty()) return null

        // Dimension preference first, then an exact label match within it. Trying parameters in
        // order means a `waist` of 32 cannot shadow an international size of 32.
        PREFERRED_PARAMETERS.forEach { parameter ->
            options.firstOrNull { it.parameter == parameter && normalize(it.label) == wanted }
                ?.let { return it }
        }
        return null
    }

    /** The size labels a page offered on filterable dimensions, for a diagnostic when [pick] fails. */
    fun offered(options: List<Option>): List<String> =
        options.filter { it.parameter in PREFERRED_PARAMETERS }.map { it.label }.distinct()

    /**
     * Case- and space-folded, with a decimal comma read as a decimal point.
     *
     * The comma rule is the same one `SizeMatcher` needs: Amazon served this machine `8,5` in a
     * Spanish locale where the English page says `8.5`. Still no aliasing — `M` is not turned into
     * `Medium`, because guessing another vocabulary is how a wrong size gets tracked confidently.
     */
    private fun normalize(label: String): String =
        label.trim().lowercase()
            .replace(DECIMAL_COMMA, ".")
            .replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("""\s+""")
    private val DECIMAL_COMMA = Regex("""(?<=\d),(?=\d)""")
}
