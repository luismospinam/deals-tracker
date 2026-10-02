package com.luis.dealstracker.db

/**
 * A title search box value: `|`-separated terms, OR'd together, where a term starting with `!` is
 * an exclusion instead.
 *
 * `"leche | queso"` -> matches either. `"!aceite"` -> everything without aceite.
 * `"leche | !deslactosada"` -> leche, but not the deslactosada ones. Exclusions always win, however
 * they are ordered, and a query with only exclusions starts from every row.
 *
 * Reusable by any screen with a title column: [toSql] yields clauses to AND into its WHERE, and
 * `searchBox` in the web layer renders the input. Used by the feed and Groceries. Watch terms still use
 * `parseSearchTerms`, which has its own separate exclude field.
 */
data class SearchQuery(val include: List<String>, val exclude: List<String>) {
    val isEmpty get() = include.isEmpty() && exclude.isEmpty()

    /**
     * WHERE clauses (to be AND-ed) plus their string parameters, in placeholder order. Matching is
     * accent-insensitive (V9), so `cafe` finds `Café`. Inclusion also takes a trigram match, like
     * the feed; exclusion is substring-only, because a fuzzy exclusion would hide unrelated rows.
     */
    fun toSql(column: String = "d.title"): Pair<List<String>, List<String>> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()
        if (include.isNotEmpty()) {
            clauses += include.joinToString(" OR ", "(", ")") { "(unaccent($column) ILIKE unaccent(?) OR $column % ?)" }
            include.forEach { params += "%${escapeLike(it)}%"; params += it }
        }
        exclude.forEach {
            clauses += "unaccent($column) NOT ILIKE unaccent(?)"
            params += "%${escapeLike(it)}%"
        }
        return clauses to params
    }

    companion object {
        const val NEGATE = '!'
        private const val MAX_TERMS = 20

        fun parse(search: String?): SearchQuery {
            if (search.isNullOrBlank()) return SearchQuery(emptyList(), emptyList())
            val include = mutableListOf<String>()
            val exclude = mutableListOf<String>()
            search.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TERMS).forEach {
                if (it.startsWith(NEGATE)) it.drop(1).trim().takeIf { t -> t.isNotEmpty() }?.let(exclude::add)
                else include += it
            }
            return SearchQuery(include, exclude)
        }
    }
}
