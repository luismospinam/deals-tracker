package com.luis.dealstracker.ingest

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer

/** One category and the patterns that assign a deal to it. Order in the file is priority. */
@Serializable
data class CategoryRule(
    val name: String,
    /** Matched against the source's own category path — preferred, being the retailer's own taxonomy. */
    val sourcePatterns: List<String> = emptyList(),
    /** Matched against the product title — the only option for Slickdeals. */
    val titlePatterns: List<String> = emptyList(),
)

@Serializable
data class CategoriesFile(val categories: List<CategoryRule> = emptyList())

/**
 * Assigns each deal one of a small set of broad categories, so a single UI filter works
 * across sources that categorise incompatibly.
 *
 * Why this is needed at all:
 *  - Exito and Carulla publish real 3-level taxonomies, but *different* ones, in Spanish,
 *    and disagree on spelling ("Tecnología" vs "Tecnologia").
 *  - Slickdeals publishes only its forum name — "Hot Deals" for 212 of 220 deals — which
 *    says nothing about the product.
 *
 * Matching is case- and accent-insensitive, which is what makes the two VTEX spellings
 * resolve to the same bucket.
 */
class Categorizer(rules: List<CategoryRule>) {

    /** Patterns are pre-compiled against the normalized form, so nothing is recompiled per deal. */
    private data class Compiled(val name: String, val source: List<Regex>, val title: List<Regex>)

    private val compiled: List<Compiled> = rules.map { rule ->
        Compiled(
            name = rule.name,
            source = rule.sourcePatterns.mapNotNull { compile(rule.name, it) },
            title = rule.titlePatterns.mapNotNull { compile(rule.name, it) },
        )
    }

    val categoryNames: List<String> = rules.map { it.name } + OTHER

    /**
     * Resolves a deal's category.
     *
     * The source's own path is tried first for every rule before falling back to titles,
     * rather than checking both per rule — a retailer's taxonomy is far more reliable than
     * keyword-matching a marketing title, so it should win outright.
     */
    fun categorize(sourceCategory: String?, title: String): String {
        val normalizedCategory = sourceCategory?.let { normalize(it) }
        if (normalizedCategory != null) {
            // A source category that already IS one of our bucket names is taken as it stands.
            // Only `amazon-watch` publishes one: it tags each row with the category of the watch
            // that asked for it, because an English Amazon title is the weakest evidence there is
            // and the watch already said what the thing is. No retailer taxonomy uses these
            // English names, so nothing else takes this path.
            compiled.firstOrNull { normalize(it.name) == normalizedCategory }?.let { return it.name }

            compiled.firstOrNull { rule -> rule.source.any { it.containsMatchIn(normalizedCategory) } }
                ?.let { return it.name }
        }

        val normalizedTitle = normalize(title)
        compiled.firstOrNull { rule -> rule.title.any { it.containsMatchIn(normalizedTitle) } }
            ?.let { return it.name }

        return OTHER
    }

    private fun compile(category: String, pattern: String): Regex? =
        runCatching { Regex(normalize(pattern), RegexOption.IGNORE_CASE) }
            .onFailure { println("[categories] bad regex in '$category': '$pattern' — ${it.message}") }
            .getOrNull()

    companion object {
        const val OTHER = "Other"

        private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")

        /**
         * Lowercases and strips diacritics, so "Tecnología" and "Tecnologia" — the same
         * category spelled differently by Exito and Carulla — both match one pattern.
         *
         * Applied to the patterns too, so a pattern may be written with or without accents.
         */
        internal fun normalize(text: String): String =
            COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        fun load(projectDir: File = File(".")): Categorizer {
            val file = File(projectDir, "config/categories.yaml")
            if (!file.exists()) {
                println("[categories] ${file.path} not found; every deal will be '$OTHER'")
                return Categorizer(emptyList())
            }
            val parsed = yaml.decodeFromString(CategoriesFile.serializer(), file.readText())
            require(parsed.categories.isNotEmpty()) { "config/categories.yaml defines no categories" }

            val duplicates = parsed.categories.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate category names in categories.yaml: $duplicates" }
            require(OTHER !in parsed.categories.map { it.name }) {
                "'$OTHER' is the implicit fallback and must not be defined explicitly"
            }
            return Categorizer(parsed.categories)
        }
    }
}
