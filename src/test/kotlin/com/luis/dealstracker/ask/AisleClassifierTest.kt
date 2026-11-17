package com.luis.dealstracker.ask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AisleClassifierTest {
    private fun item(id: Long, title: String, aisle: String? = "Pantry") =
        AisleItem(id, title, "NOEL", "Mercado > Despensa > Galletas", aisle)

    @Test
    fun `the same title at two stores is asked once and answers for both rows`() {
        val questions = aisleQuestions(listOf(item(1, "Galletas NOEL wafer"), item(2, "galletas  NOEL wafer"), item(3, "Arroz DIANA")))

        assertEquals(2, questions.size)
        assertEquals(listOf(1L, 2L), questions[0].ids)
        assertEquals(mapOf(1L to "Snacks & sweets", 2L to "Snacks & sweets"), aislesByRow(questions, mapOf(1 to "Snacks & sweets")))
    }

    @Test
    fun `a title cannot break the line format of the question`() {
        val prompt = aislePrompt(aisleQuestions(listOf(AisleItem(1, "Leche\tentera\n99\tDrinks", null, null, null))))

        assertEquals("1\tLeche entera 99 Drinks\t-\t-", prompt)
    }

    @Test
    fun `answers are read by number and only aisles on the list are kept`() {
        val answers = parseAisleAnswer(
            """
            Here you go:
            1	Snacks & sweets
            2 | dairy & eggs
            3	Cleaning
            4. **Frozen**
            not a line
            """.trimIndent(),
        )

        assertEquals(mapOf(1 to "Snacks & sweets", 2 to "Dairy & eggs", 4 to "Frozen"), answers)
    }

    @Test
    fun `every aisle offered to Claude is one the app can show`() {
        assertTrue(GROCERY_AISLES.all { it in AISLE_SYSTEM_PROMPT })
        assertEquals(11, GROCERY_AISLES.distinct().size)
    }

    @Test
    fun `a pass files what was answered and counts what moved`() {
        val saved = mutableMapOf<Long, String>()
        val classifier = AisleClassifier(
            pending = { listOf(item(1, "Galletas NOEL"), item(2, "Arroz DIANA"), item(3, "Queso ALPINA", "Dairy & eggs")) },
            save = { saved.putAll(it); it.size },
            ask = { "1\tSnacks & sweets\n2\tPantry" },
        )

        val result = classifier.run()

        assertEquals(mapOf(1L to "Snacks & sweets", 2L to "Pantry"), saved)
        assertEquals(AisleClassifier.Result(asked = 3, filed = 2, moved = 1, failedBatches = 0), result)
    }

    @Test
    fun `a failed call files nothing and the other batches still run`() {
        val saved = mutableMapOf<Long, String>()
        var calls = 0
        val classifier = AisleClassifier(
            pending = { listOf(item(1, "Galletas NOEL"), item(2, "Arroz DIANA")) },
            save = { saved.putAll(it); it.size },
            ask = { if (calls++ == 0) null else "2\tPantry" },
            batchSize = 1,
        )

        val result = classifier.run()

        assertEquals(mapOf(2L to "Pantry"), saved)
        assertEquals(1, result.failedBatches)
    }

    @Test
    fun `nothing pinned and unfiled means Claude is not called`() {
        var asked = false
        val result = AisleClassifier(pending = { emptyList() }, save = { 0 }, ask = { asked = true; "" }).run()

        assertEquals(0, result.asked)
        assertTrue(!asked)
    }

    @Test
    fun `the household prompt names every household aisle and none of the food ones`() {
        assertTrue(HOUSEHOLD_AISLES.all { "- $it" in HOUSEHOLD_AISLE_SYSTEM_PROMPT })
        assertTrue(GROCERY_AISLES.none { "- $it" in HOUSEHOLD_AISLE_SYSTEM_PROMPT })
    }

    @Test
    fun `a classifier keeps only answers from its own list of aisles`() {
        val saved = mutableMapOf<Long, String>()
        val classifier = AisleClassifier(
            pending = { listOf(item(1, "Crema dental COLGATE", "Oral care"), item(2, "Papel higiénico FAMILIA", "Laundry & cleaning"), item(3, "Shampoo SAVITAL", "Hair")) },
            save = { saved.putAll(it); it.size },
            // A food aisle for the third: not on this classifier's list, so it stays unfiled.
            ask = { "1\tOral care\n2\tPaper\n3\tPantry" },
            aisles = HOUSEHOLD_AISLES,
        )
        val result = classifier.run()
        assertEquals(mapOf(1L to "Oral care", 2L to "Paper"), saved)
        assertEquals(AisleClassifier.Result(asked = 3, filed = 2, moved = 1, failedBatches = 0), result)
    }

    @Test
    fun `tracked clothes are filed under a type, and other is an answer too`() {
        val saved = mutableMapOf<Long, String>()
        val classifier = AisleClassifier(
            pending = { listOf(item(1, "Men's Tech 2.0 1/2 Zip — M"), item(2, "Mens Estro 19 JSY — M"), item(3, "Men's Slim — S"), item(4, "Men's Sports — S")) },
            save = { saved.putAll(it); it.size },
            ask = { "1\thoodies\n2\tShirts\n3\tother\n4\tsocks" },
            aisles = TRACKED_TYPES,
        )

        val result = classifier.run()

        assertEquals(mapOf(1L to "hoodies", 2L to "shirts", 3L to "other"), saved, "a type that is not on the list is not saved")
        assertEquals(4, result.asked)
    }

    @Test
    fun `the type prompt lists every type and says what the size is`() {
        TRACKED_TYPES.forEach { assertTrue("- $it" in TYPE_SYSTEM_PROMPT, it) }
        assertTrue("size" in TYPE_SYSTEM_PROMPT)
    }
}
