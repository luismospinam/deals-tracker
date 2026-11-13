package com.luis.dealstracker.ask

/**
 * Files pinned groceries under the right aisle by asking Claude, instead of trusting the store's own
 * category (Exito keeps biscuits under "Despensa", so they landed in Pantry rather than Snacks).
 *
 * Only pinned rows are ever asked about: the shopping list is a couple of hundred products, the shelf
 * is ~27,000, and the Pinned view is the one grouped by aisle. A row is asked about once -- the answer
 * is kept in `deals.aisle_ai` (V45) and a null there is what "not asked yet" means, so a newly pinned
 * item is picked up by the next [AisleClassifier.run] and nothing else is.
 */

/** The aisles, by the names the rest of the app stores and translates (`i18n.aisleLabel`). */
val GROCERY_AISLES = listOf(
    "Pantry", "Snacks & sweets", "Dairy & eggs", "Drinks", "Bakery", "Meat & fish",
    "Fruit & veg", "Deli", "Frozen", "Prepared", "Baby food",
)

/** One pinned row waiting for an aisle. [aisle] is where its store's category put it. */
data class AisleItem(val id: Long, val title: String, val brand: String?, val storeCategory: String?, val aisle: String?)

/** One line of the question: a product, and every row (store copy, pack size sold twice) it answers for. */
data class AisleQuestion(val no: Int, val title: String, val brand: String?, val storeCategory: String?, val ids: List<Long>)

val AISLE_SYSTEM_PROMPT = """
You file supermarket products from Colombian stores (titles are in Spanish) under one aisle each.

The aisles, exactly as they must be written:
${GROCERY_AISLES.joinToString("\n") { "- $it" }}

What goes where:
- Pantry: rice, pasta, grains, oil, canned food, sauces, spices, sugar, flour, coffee and tea to brew, breakfast cereal, spreads.
- Snacks & sweets: biscuits and cookies, crisps, nuts as a snack, chocolate, candy, cereal bars.
- Dairy & eggs: milk, yogurt, cheese, butter, cream, eggs, plant-based milks.
- Drinks: water, soft drinks, juice, ready-to-drink tea and coffee, energy drinks, beer, wine, spirits.
- Bakery: bread, arepas, tortillas, cakes, pastries.
- Meat & fish: raw or marinated chicken, beef, pork, fish, seafood -- chilled or frozen.
- Fruit & veg: fresh fruit, vegetables, herbs.
- Deli: ham, sausages, salami, bacon and other cold cuts.
- Frozen: ice cream, frozen vegetables, frozen potatoes, frozen ready meals and breaded products.
- Prepared: fresh ready-to-eat meals.
- Baby food: formula and food made for babies.

File a product by what it is, not by the store category beside it, which is a hint and is sometimes wrong.

You get one product per line: number, title, brand, store category, separated by tabs.
Answer with one line per product and nothing else: the number, a tab, the aisle. No heading, no notes.
""".trimIndent()

private fun clean(text: String?): String = text.orEmpty().replace(Regex("\\s+"), " ").trim()

/**
 * The lines to ask, one per distinct title: Exito and Carulla list the same product under the same
 * name, and asking once for both keeps them in one aisle as well as halving the question.
 */
fun aisleQuestions(items: List<AisleItem>): List<AisleQuestion> =
    items.filter { clean(it.title).isNotEmpty() }
        .groupBy { clean(it.title).lowercase() }
        .values
        .mapIndexed { i, rows ->
            val first = rows.first()
            AisleQuestion(i + 1, clean(first.title), clean(first.brand).ifEmpty { null }, clean(first.storeCategory).ifEmpty { null }, rows.map { it.id })
        }

fun aislePrompt(questions: List<AisleQuestion>): String =
    questions.joinToString("\n") { "${it.no}\t${it.title}\t${it.brand ?: "-"}\t${it.storeCategory ?: "-"}" }

private val ANSWER_LINE = Regex("""^\s*(\d+)\s*[\t|:.)-]\s*(.+?)\s*$""")

/**
 * Reads `number<tab>aisle` lines. A line that is not one, a number that was not asked, or an aisle
 * that is not on the list is dropped rather than guessed at: that product stays unfiled and is asked
 * about again on the next run.
 */
fun parseAisleAnswer(answer: String, aisles: List<String> = GROCERY_AISLES): Map<Int, String> {
    val known = aisles.associateBy { it.lowercase() }
    return answer.lines().mapNotNull { line ->
        val m = ANSWER_LINE.matchEntire(line) ?: return@mapNotNull null
        val aisle = known[m.groupValues[2].trim().trim('*', '`', '"').lowercase()] ?: return@mapNotNull null
        m.groupValues[1].toInt() to aisle
    }.toMap()
}

/** The aisle for every row of every answered question. */
fun aislesByRow(questions: List<AisleQuestion>, answers: Map<Int, String>): Map<Long, String> =
    questions.flatMap { q -> answers[q.no]?.let { aisle -> q.ids.map { it to aisle } }.orEmpty() }.toMap()

/**
 * [pending] reads the unfiled pinned rows, [ask] sends one question and returns the answer text (null
 * when the CLI failed), [save] writes the aisles. All three are passed in so the rule is testable
 * without a database or a `claude` binary.
 */
class AisleClassifier(
    private val pending: () -> List<AisleItem>,
    private val save: (Map<Long, String>) -> Int,
    private val ask: (String) -> String?,
    private val batchSize: Int = 60,
) {
    data class Result(val asked: Int, val filed: Int, val moved: Int, val failedBatches: Int)

    /**
     * One pass over what is unfiled now. Synchronized: a pin made while a pass is running waits for it
     * and then finds its own row still unfiled, rather than two passes paying for the same products.
     */
    @Synchronized
    fun run(): Result {
        val items = pending()
        if (items.isEmpty()) return Result(0, 0, 0, 0)
        val before = items.associate { it.id to it.aisle }
        var filed = 0
        var moved = 0
        var failed = 0
        val questions = aisleQuestions(items)
        questions.chunked(batchSize).forEach { batch ->
            val answer = ask(aislePrompt(batch))
            if (answer == null) {
                failed++
                return@forEach
            }
            val rows = aislesByRow(batch, parseAisleAnswer(answer))
            filed += save(rows)
            moved += rows.count { (id, aisle) -> before[id] != aisle }
        }
        return Result(questions.size, filed, moved, failed)
    }
}
