package com.luis.dealstracker.web

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every column header in the web views declares `scope="col"`, and every table has a caption.
 *
 * Checked over the source rather than over rendered pages: the tables on the grocery and watch pages
 * need a database-backed model to render, and the thing to guard is a NEW table or header written
 * without the attribute, which a source scan catches wherever it is.
 */
class TableAccessibilityTest {

    private val views = File("src/main/kotlin/com/luis/dealstracker/web").listFiles { f -> f.extension == "kt" }.orEmpty()

    @Test
    fun `every th call in the views names its scope`() {
        assertTrue(views.isNotEmpty(), "found the web views")
        val bare = views.flatMap { f ->
            f.readLines().withIndex()
                // `th(classes = ...)` and `th {` are the DSL's own forms; the local `th(label, key, ...)` helper
                // in WatchViews wraps a call that is checked here.
                .filter { (_, line) -> Regex("""\bth(\s*\{|\(classes\s*=)""").containsMatchIn(line) }
                .filterNot { (_, line) -> "ThScope.col" in line }
                .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue(bare.isEmpty(), "th without scope:\n" + bare.joinToString("\n"))
    }

    @Test
    fun `every table in the views has a caption`() {
        val uncaptioned = views.flatMap { f ->
            val lines = f.readLines()
            lines.withIndex()
                .filter { (_, line) -> Regex("""^\s*table(\(|\s*\{)""").containsMatchIn(line) }
                .filterNot { (i, _) -> lines.drop(i + 1).take(3).any { "caption(" in it } }
                .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue(uncaptioned.isEmpty(), "table without a caption:\n" + uncaptioned.joinToString("\n"))
    }
}
