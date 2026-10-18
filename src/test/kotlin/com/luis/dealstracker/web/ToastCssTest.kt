package com.luis.dealstracker.web

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cart toast must outlast the reduced-motion rule that shrinks every animation to 0.01ms: the toast's
 * fade ends hidden and is held by `forwards`, so without its own override it vanishes at once for anyone
 * with animation effects off (seen on Windows).
 */
class ToastCssTest {
    private val css = checkNotNull(javaClass.getResource("/static/app.css")) { "app.css missing" }.readText()

    private fun reducedMotionBlocks(): List<String> =
        Regex("""@media \(prefers-reduced-motion: reduce\) \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(css).map { it.groupValues[1] }.toList()

    @Test
    fun `reduced motion still shows the toast for its full time`() {
        val block = reducedMotionBlocks().single { "animation-duration: 0.01ms" in it }
        val rule = Regex("""#toast \.toast \{([^}]*)\}""").find(block)?.groupValues?.get(1)
        assertTrue(rule != null, "the reduced-motion block needs its own #toast .toast rule")
        assertTrue("8s" in rule && "!important" in rule && "steps(1" in rule, rule)
    }

    @Test
    fun `the hold keyframes end hidden`() {
        val frames = Regex("""@keyframes toast-hold \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(css)?.groupValues?.get(1)
        assertTrue(frames != null && "visibility: hidden" in frames, "toast-hold must end hidden")
    }
}
