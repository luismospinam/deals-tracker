package com.luis.dealstracker.web

import com.luis.dealstracker.i18n.AppLanguage
import com.luis.dealstracker.i18n.I18n
import com.luis.dealstracker.i18n.Lang
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.withCharset
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.util.AttributeKey
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.createHTML

/** The cookie the masthead switch sets. A year: it is a preference, not a session. */
internal const val LANG_COOKIE = "lang"
private const val LANG_COOKIE_MAX_AGE = 365 * 24 * 3600

private val LangKey = AttributeKey<Lang>("lang")

/**
 * The language this request renders in: the switch's cookie if it was used, else the browser's
 * `Accept-Language`, else English.
 *
 * The cookie wins because it is an explicit choice, and "Spanish, even though my phone is in
 * English" is exactly what the switch exists for.
 */
internal fun resolveLang(cookie: String?, acceptLanguage: String?): Lang =
    Lang.fromCode(cookie) ?: Lang.fromAcceptLanguage(acceptLanguage) ?: Lang.EN

internal val ApplicationCall.lang: Lang
    get() = attributes.computeIfAbsent(LangKey) {
        resolveLang(request.cookies[LANG_COOKIE], request.headers[HttpHeaders.AcceptLanguage]).also { l ->
            // The notification language follows the browser until the switch is used. Cheap:
            // it only touches the database when the value actually changes.
            AppLanguage.remember(l, isChoice = request.cookies[LANG_COOKIE] != null)
        }
    }

/**
 * `respondHtml` in the request's language. Every page goes through this rather than
 * `respondHtml`: Ktor's version renders lazily while the response is written, possibly on another
 * thread, where the language set for this request would not be visible (see [I18n]). So the page
 * is built to a string here, synchronously, inside the language block.
 */
internal suspend fun ApplicationCall.respondPage(block: HTML.() -> Unit) {
    val here = request.local.uri
    val body = I18n.with(lang) {
        "<!DOCTYPE html>\n" + createHTML().html {
            attributes["lang"] = I18n.lang.code
            CurrentPath.with(here) { block() }
        }
    }
    respondText(body, ContentType.Text.Html.withCharset(Charsets.UTF_8))
}

/**
 * The path and query of the page being rendered, for the language switch's return link. Same
 * thread-local shape as [I18n], set by [respondPage] only.
 */
internal object CurrentPath {
    private val current = ThreadLocal<String>()
    val value: String get() = current.get() ?: "/"
    fun <T> with(path: String, block: () -> T): T {
        current.set(path)
        try { return block() } finally { current.remove() }
    }
}

/** An htmx fragment (a bare `<tr>`) in the request's language. */
internal suspend fun ApplicationCall.respondFragment(render: () -> String) {
    respondText(I18n.with(lang) { render() }, ContentType.Text.Html)
}

/**
 * `GET /lang/{code}?back=/groceries`: the masthead switch. Sets the cookie and returns to the page
 * it was pressed on, so switching language never loses your place.
 */
internal fun Route.languageRoute() {
    get("/lang/{code}") {
        val chosen = Lang.fromCode(call.parameters["code"])
            ?: return@get call.respondText("unknown language", status = HttpStatusCode.NotFound)
        call.response.cookies.append(
            Cookie(LANG_COOKIE, chosen.code, maxAge = LANG_COOKIE_MAX_AGE, path = "/", httpOnly = true),
        )
        AppLanguage.remember(chosen, isChoice = true)
        // Only a local path, so the switch cannot be used as an open redirect.
        val back = call.request.queryParameters["back"]?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
        call.respondRedirect(back)
    }
}
