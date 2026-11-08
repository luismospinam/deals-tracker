package com.luis.dealstracker.web

import com.luis.dealstracker.agent.Suggestion
import java.time.Instant
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestionViewsTest {
    private fun s(id: Long, kind: String, deal: Long? = 7) =
        Suggestion(id, "grocery", Instant.EPOCH, kind, deal, "Arroz Diana 5 kg", "At 21,900 vs a usual 25,000, and you buy it every 14 days.", "new")

    @Test
    fun `the inbox lists each suggestion with its reason, answered in place`() {
        val html = createHTML().div { suggestionsPanel(listOf(s(1, "buy_now"), s(2, "pin"))) }
        assertTrue(html.contains("Suggestions") && html.contains("<span class=\"sugg-count\">2</span>"))
        assertTrue(html.contains("buy now") && html.contains("usual 25,000"))
        assertTrue(html.contains("hx-post=\"/suggestions/1/accept\"") && html.contains("hx-post=\"/suggestions/1/dismiss\""))
        assertTrue(html.contains("href=\"/deal/7\""))
    }

    @Test
    fun `only dismissing sends the reason, and the reason box stays shut until asked for`() {
        val html = createHTML().div { suggestionItem(s(1, "buy_now")) }
        val dismiss = html.substringAfter("<details class=\"sugg-dismiss\">")
        assertTrue(dismiss.contains("hx-include=\"#sugg-note-1\""), "the dismiss reason goes with the answer")
        assertFalse(html.substringBefore("<details class=\"sugg-dismiss\">").contains("hx-include"), "Got it sends no reason")
        assertFalse(html.contains("<details class=\"sugg-dismiss\" open"))
    }

    @Test
    fun `only act-now kinds take the accent, and only Pin it is a filled button`() {
        assertTrue(createHTML().div { suggestionItem(s(1, "buy_now")) }.contains("chip sugg-kind chip-new"))
        assertTrue(createHTML().div { suggestionItem(s(1, "wait")) }.contains("class=\"chip sugg-kind\""))
        assertFalse(createHTML().div { suggestionItem(s(1, "buy_now")) }.contains("primary"))
    }

    @Test
    fun `four show, the rest fold under Show more`() {
        val html = createHTML().div { suggestionsPanel((1L..7L).map { s(it, "wait") }) }
        val (shown, folded) = html.split("<details class=\"sugg-more\">")
        assertEquals(SUGGESTIONS_SHOWN, Regex("class=\"suggestion\"").findAll(shown).count())
        assertTrue(folded.contains("Show 3 more") && Regex("class=\"suggestion\"").findAll(folded).count() == 3)
    }

    @Test
    fun `kinds are labelled as what to do`() {
        assertEquals("add to pins?", suggestionKindLabel("pin"))
        assertEquals("wait for a drop", suggestionKindLabel("wait"))
        assertEquals("cheaper option", suggestionKindLabel("switch"))
        assertEquals("¿agregar a fijados?", com.luis.dealstracker.i18n.I18n.with(com.luis.dealstracker.i18n.Lang.ES) { suggestionKindLabel("pin") })
    }

    @Test
    fun `a pin suggestion offers Pin it instead of Got it`() {
        val html = createHTML().div { suggestionItem(s(2, "pin")) }
        assertTrue(html.contains("/suggestions/2/pin") && html.contains("Pin it"))
        assertFalse(html.contains("/suggestions/2/accept"))
    }

    @Test
    fun `no suggestions, no panel`() {
        assertFalse(createHTML().div { suggestionsPanel(emptyList()) }.contains("suggestions"))
        assertEquals(true, suggestionAnswered("dismiss").contains("Dismissed"))
    }
}
