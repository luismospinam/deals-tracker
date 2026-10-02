package com.luis.dealstracker.web

import com.luis.dealstracker.i18n.tr
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.title
import kotlinx.html.input

/**
 * A GET search form for any screen whose rows are filtered by [com.luis.dealstracker.db.SearchQuery]:
 * `a | b` matches either, `!x` excludes. [carry] are the screen's other controls (view, aisle, store)
 * as hidden fields, so searching does not reset them. The input is named `q`.
 */
internal fun FlowContent.searchBox(
    action: String,
    value: String?,
    carry: Map<String, String>,
    placeholder: String = "leche | huevos | !aceite",
    formClass: String = "grocery-search",
) {
    form(classes = formClass) {
        attributes["method"] = "get"
        attributes["action"] = action
        carry.forEach { (name, v) -> input(type = InputType.hidden, name = name) { this.value = v } }
        input(type = InputType.search, name = "q") {
            this.value = value.orEmpty()
            this.placeholder = placeholder
            title = tr(
                "Title search, accent-insensitive. Separate terms with | to match any of them; " +
                    "start a term with ! to exclude it (!aceite = everything without aceite).",
                "Búsqueda por título, sin importar tildes. Separa términos con | para encontrar cualquiera; " +
                    "empieza un término con ! para excluirlo (!aceite = todo lo que no tenga aceite).",
            )
        }
        button { +tr("Search", "Buscar") }
    }
}
