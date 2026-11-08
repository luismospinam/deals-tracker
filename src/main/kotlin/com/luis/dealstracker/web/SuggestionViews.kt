package com.luis.dealstracker.web

import com.luis.dealstracker.agent.Suggestion
import com.luis.dealstracker.i18n.tr
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary

/** The kind as a short chip label. */
internal fun suggestionKindLabel(kind: String): String = when (kind) {
    "buy_now" -> tr("buy now", "comprar ya")
    "stock_up" -> tr("stock up", "abastecerse")
    // Said as what to do, not as the kind's id: "pin?" and "switch" did not explain themselves.
    "wait" -> tr("wait for a drop", "espera que baje")
    "switch" -> tr("cheaper option", "opción más barata")
    "pin" -> tr("add to pins?", "¿agregar a fijados?")
    else -> tr("idea", "idea")
}

/**
 * The chip's tone. Only "act now" kinds take the accent; the rest stay neutral, so the panel does not
 * outshout the price table under it. Green is not used: the stylesheet keeps it for verified outcomes.
 */
private fun kindTone(kind: String) = when (kind) {
    "buy_now", "stock_up" -> "chip-new"
    else -> ""
}

/** How many show before "Show more": enough for a normal morning, without pushing the table off screen. */
internal const val SUGGESTIONS_SHOWN = 4

/**
 * The agents' suggestions, above the grocery table, as a disclosure like the digest beside it: open,
 * but one click from out of the way. Each answers in place.
 */
internal fun FlowContent.suggestionsPanel(list: List<Suggestion>) {
    if (list.isEmpty()) return
    details("suggestions") {
        attributes["id"] = "suggestions"
        attributes["open"] = "open"
        summary {
            span("sugg-head") { +tr("Suggestions", "Sugerencias") }
            span("sugg-count") { +list.size.toString() }
            span("sugg-sub") { +tr("from the grocery agent", "del agente de mercado") }
        }
        div("sugg-list") {
            list.take(SUGGESTIONS_SHOWN).forEach { suggestionItem(it) }
            val rest = list.drop(SUGGESTIONS_SHOWN)
            if (rest.isNotEmpty()) {
                details("sugg-more") {
                    summary { +tr("Show ${rest.size} more", "Ver ${rest.size} más") }
                    rest.forEach { suggestionItem(it) }
                }
            }
        }
    }
}

internal fun FlowContent.suggestionItem(s: Suggestion) {
    div("suggestion") {
        attributes["id"] = "suggestion-${s.id}"
        span("chip sugg-kind ${kindTone(s.kind)}".trim()) { +suggestionKindLabel(s.kind) }
        div("sugg-body") {
            if (s.dealId != null) a(href = "/deal/${s.dealId}", classes = "sugg-title") { +s.title } else span("sugg-title") { +s.title }
            p("sugg-reason") { +s.reason }
        }
        div("sugg-actions") {
            fun post(action: String, block: kotlinx.html.BUTTON.() -> Unit) = button {
                type = ButtonType.button
                attributes["hx-post"] = "/suggestions/${s.id}/$action"
                attributes["hx-target"] = "#suggestion-${s.id}"
                attributes["hx-swap"] = "outerHTML"
                block()
            }
            // Pinning changes something, so it is the one accent button; "Got it" only records the answer.
            if (s.kind == "pin" && s.dealId != null) post("pin") { classes = setOf("primary", "sugg-btn"); +tr("Pin it", "Fijar") }
            else post("accept") { classes = setOf("sugg-btn"); +tr("Got it", "Entendido") }
            // The reason box only when asked for: it is optional, and eight empty boxes were noise.
            details("sugg-dismiss") {
                summary { +tr("Dismiss", "Descartar") }
                div("sugg-dismiss-form") {
                    input(InputType.text, name = "note") {
                        attributes["id"] = "sugg-note-${s.id}"
                        placeholder = tr("Why? Optional, helps the next run", "¿Por qué? Opcional, ayuda a la próxima corrida")
                        attributes["aria-label"] = tr("Reason for dismissing", "Motivo para descartar")
                    }
                    post("dismiss") {
                        classes = setOf("sugg-btn")
                        attributes["hx-include"] = "#sugg-note-${s.id}"
                        +tr("Dismiss", "Descartar")
                    }
                }
            }
        }
    }
}

/** What replaces an answered suggestion: one dim line, so the list does not jump. */
internal fun suggestionAnswered(answer: String): String = createHTML().div("suggestion answered") {
    +when (answer) {
        "pin" -> tr("Pinned. The next run will take it into account.", "Fijado. La próxima corrida lo tendrá en cuenta.")
        "dismiss" -> tr("Dismissed. The next run will take it into account.", "Descartado. La próxima corrida lo tendrá en cuenta.")
        else -> tr("Noted.", "Anotado.")
    }
}
