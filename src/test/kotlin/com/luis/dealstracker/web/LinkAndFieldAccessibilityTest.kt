package com.luis.dealstracker.web

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source scans guarding three accessibility rules, for the same reason as [TableAccessibilityTest]:
 * the thing to catch is a NEW link or field written without them.
 *
 *  - a thumbnail link that repeats the title link is hidden from assistive tech (no nameless links);
 *  - a `label` that does not wrap its field points at it with `for`;
 *  - every product thumbnail `img` is lazy-loaded.
 */
class LinkAndFieldAccessibilityTest {

    private val views = File("src/main/kotlin/com/luis/dealstracker/web").listFiles { f -> f.extension == "kt" }.orEmpty()

    @Test
    fun `thumbnail links are hidden from assistive tech`() {
        val bad = views.flatMap { f ->
            val lines = f.readLines()
            lines.withIndex()
                .filter { (_, l) -> Regex("""\ba\(href\b.*\{\s*$""").containsMatchIn(l) }
                .filter { (i, _) -> lines.drop(i + 1).take(6).any { "img(" in it && ("thumb" in it || "detail-img" in it) } }
                .filterNot { (i, _) -> lines.drop(i + 1).take(3).any { "thumbLinkHidden()" in it } }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue(bad.isEmpty(), "thumbnail link without thumbLinkHidden():\n" + bad.joinToString("\n"))
    }

    @Test
    fun `a label that does not wrap its field says which one it names`() {
        val bad = views.flatMap { f ->
            f.readLines().withIndex()
                // One-line `label { +tr(...) }` closes before the field, so it wraps nothing.
                .filter { (_, l) -> Regex("""\blabel\s*\{.*\}\s*$""").containsMatchIn(l) }
                // `label { +"" }` is the blank spacer above the Add button, not a caption.
                .filterNot { (_, l) -> "htmlFor" in l || l.contains("""label { +"" }""") }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue(bad.isEmpty(), "label without htmlFor:\n" + bad.joinToString("\n"))
    }

    @Test
    fun `product thumbnails load lazily`() {
        val bad = views.flatMap { f ->
            val lines = f.readLines()
            lines.withIndex()
                .filter { (_, l) -> "img(classes = \"thumb\"" in l }
                .filterNot { (i, _) -> lines.drop(i).take(2).any { "\"loading\"] = \"lazy\"" in it } }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue(bad.isEmpty(), "thumb img not lazy:\n" + bad.joinToString("\n"))
    }
}
