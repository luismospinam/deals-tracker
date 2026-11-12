package com.luis.dealstracker.web

import com.luis.dealstracker.i18n.tr
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.title
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The sign-in in front of the app for anyone who is not sitting at this Mac (2026-11-12).
 *
 * The web UI is bound to 127.0.0.1, but the Cloudflare quick tunnel publishes it at an address anyone
 * can open, and the UI can change config, put items in the Exito cart and spend the Claude plan. So a
 * request that did not come from this machine itself must carry a session, which a passcode buys.
 *
 * A request counts as local only when its socket is loopback AND it carries no Cloudflare header:
 * cloudflared runs on this Mac, so tunnelled traffic also arrives from 127.0.0.1, and its `Cf-*`
 * headers are what tell the two apart (the same test `agent.mcpRefusal` makes). A visitor cannot
 * remove them; Cloudflare adds them after the visitor's request is out of their hands.
 *
 * With no passcode set, a remote request is refused outright rather than let in.
 */
internal const val SESSION_COOKIE = "dt_session"
internal const val PASSCODE_KEY = "DEALS_PASSCODE"
internal val SESSION_LENGTH: Duration = Duration.ofDays(30)

private val LOOPBACK = setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost")

/** Whether a request came from this Mac itself, not through the tunnel or the network. */
internal fun isLocalRequest(remoteHost: String, header: (String) -> String?): Boolean =
    remoteHost in LOOPBACK && header("Cf-Connecting-Ip") == null && header("Cf-Ray") == null

/** What a remote request without a session may still reach: the sign-in itself and the stylesheet it uses. */
internal fun isOpenPath(path: String): Boolean =
    path == "/login" || path.startsWith("/static/")

private fun hmac(key: ByteArray, message: String): String =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        .doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * A session: when it ends, signed. The passcode is part of what is signed, so changing the passcode
 * ends every session; the [key] is this install's own secret, so a session cannot be made elsewhere.
 */
internal fun sessionToken(expires: Instant, key: ByteArray, passcode: String): String =
    "${expires.epochSecond}.${hmac(key, "${expires.epochSecond}|$passcode")}"

internal fun validSession(token: String?, now: Instant, key: ByteArray, passcode: String): Boolean {
    val (expiry, signature) = token?.split('.', limit = 2)?.takeIf { it.size == 2 } ?: return false
    val expires = expiry.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return false
    if (!expires.isAfter(now)) return false
    return MessageDigest.isEqual(signature.toByteArray(), hmac(key, "$expiry|$passcode").toByteArray())
}

internal fun passcodeMatches(typed: String, passcode: String): Boolean =
    MessageDigest.isEqual(typed.trim().toByteArray(), passcode.toByteArray())

/**
 * Wrong passcodes, counted: [perVisitor] from one address or [overall] from all of them within
 * [window] locks the sign-in until the oldest of them ages out. The overall cap is what stops a guesser
 * who changes address each time; it is generous enough that one person's typos never reach it.
 */
class SignInAttempts(
    private val perVisitor: Int = 5,
    private val overall: Int = 50,
    private val window: Duration = Duration.ofMinutes(15),
) {
    private val failures = ArrayDeque<Pair<Instant, String>>()

    @Synchronized
    fun locked(visitor: String, now: Instant): Boolean {
        while (failures.isNotEmpty() && !failures.first().first.plus(window).isAfter(now)) failures.removeFirst()
        return failures.size >= overall || failures.count { it.second == visitor } >= perVisitor
    }

    @Synchronized
    fun failed(visitor: String, now: Instant) {
        failures.addLast(now to visitor)
    }

    @Synchronized
    fun succeeded(visitor: String) {
        failures.removeAll { it.second == visitor }
    }
}

/** A local page path to return to after signing in; anything else (another site, a scheme) becomes the feed. */
internal fun safeBack(back: String?): String =
    back?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/login") && '\\' !in it } ?: "/"

/**
 * The passcode from `.env` (or the environment), read again whenever the file changes, so setting or
 * changing it needs no restart. Blank counts as not set.
 */
class PasscodeSource(private val envFile: File, private val env: (String) -> String? = System::getenv) {
    private var stamp = -1L
    private var cached: String? = null

    @Synchronized
    fun current(): String? {
        env(PASSCODE_KEY)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val modified = if (envFile.exists()) envFile.lastModified() else 0L
        if (modified != stamp) {
            stamp = modified
            cached = if (modified == 0L) null else envFile.readLines().map { it.trim() }
                .firstOrNull { it.startsWith("$PASSCODE_KEY=") }
                ?.substringAfter("=")?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotEmpty() }
        }
        return cached
    }
}

/** This install's signing secret, created on first use in [file] (owner-readable only, gitignored). */
fun sessionSecret(file: File): ByteArray {
    file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 32 }?.let { return it.toByteArray() }
    val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    file.writeText(secret + "\n")
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    return secret.toByteArray()
}

class TunnelGate(
    private val passcode: PasscodeSource,
    private val key: ByteArray,
    private val attempts: SignInAttempts = SignInAttempts(),
    private val now: () -> Instant = Instant::now,
) {
    /** Turns away every remote request that has no session, before any route sees it. */
    fun install(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val request = call.request
            if (isLocalRequest(request.origin.remoteAddress, request::header) || isOpenPath(request.path())) return@intercept
            val code = passcode.current()
            if (code != null && validSession(request.cookies[SESSION_COOKIE], now(), key, code)) return@intercept
            when {
                code == null -> call.respondText(
                    "Remote access is off: no passcode is set. Add $PASSCODE_KEY=<your passcode> to .env on the Mac.",
                    status = HttpStatusCode.Forbidden,
                )
                // An HTMX request swaps whatever comes back into the page, so it is sent to the sign-in instead.
                request.header("HX-Request") != null -> {
                    call.response.header("HX-Redirect", "/login")
                    call.respondText("", status = HttpStatusCode.Unauthorized)
                }
                request.httpMethod.value == "GET" -> call.respondRedirect("/login?back=" + java.net.URLEncoder.encode(request.uri, "UTF-8"))
                else -> call.respondText("Sign in first.", status = HttpStatusCode.Unauthorized)
            }
            finish()
        }
    }

    fun routes(route: Route) {
        route.get("/login") {
            val back = safeBack(call.request.queryParameters["back"])
            if (passcode.current() == null) call.respondSignIn(HttpStatusCode.Forbidden, back, SignInProblem.NO_PASSCODE)
            else call.respondSignIn(HttpStatusCode.OK, back, null)
        }
        route.post("/login") {
            val params = call.receiveParameters()
            val back = safeBack(params["back"])
            val visitor = call.request.header("Cf-Connecting-Ip") ?: call.request.origin.remoteAddress
            val code = passcode.current()
            val at = now()
            when {
                code == null -> call.respondSignIn(HttpStatusCode.Forbidden, back, SignInProblem.NO_PASSCODE)
                attempts.locked(visitor, at) -> call.respondSignIn(HttpStatusCode.TooManyRequests, back, SignInProblem.LOCKED)
                !passcodeMatches(params["passcode"].orEmpty(), code) -> {
                    attempts.failed(visitor, at)
                    println("[gate] wrong passcode from $visitor")
                    call.respondSignIn(HttpStatusCode.Unauthorized, back, SignInProblem.WRONG)
                }
                else -> {
                    attempts.succeeded(visitor)
                    call.response.cookies.append(
                        Cookie(
                            SESSION_COOKIE, sessionToken(at.plus(SESSION_LENGTH), key, code),
                            maxAge = SESSION_LENGTH.seconds.toInt(), path = "/", httpOnly = true,
                            // The tunnel is always https; a plain-http visit (the Wi-Fi, were it opened) still gets a usable cookie.
                            secure = call.request.header("Cf-Visitor")?.contains("https") == true,
                            extensions = mapOf("SameSite" to "Lax"),
                        ),
                    )
                    call.respondRedirect(back)
                }
            }
        }
    }
}

internal enum class SignInProblem { NO_PASSCODE, LOCKED, WRONG }

/** The sign-in page in the request's language; built to a string for the reason [respondPage] gives. */
private suspend fun io.ktor.server.application.ApplicationCall.respondSignIn(status: HttpStatusCode, back: String, problem: SignInProblem?) {
    val body = com.luis.dealstracker.i18n.I18n.with(lang) { signInHtml(back, problem) }
    respondText(body, io.ktor.http.ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

/** The sign-in page. With [SignInProblem.NO_PASSCODE] there is nothing to type, so no form. */
internal fun signInHtml(back: String, problem: SignInProblem?): String =
    "<!DOCTYPE html>\n" + kotlinx.html.stream.createHTML().html {
        attributes["lang"] = com.luis.dealstracker.i18n.I18n.lang.code
        signInPage(
            back,
            when (problem) {
                SignInProblem.NO_PASSCODE -> tr("Remote access is off: no passcode is set on the Mac.", "El acceso remoto está apagado: no hay clave configurada en el Mac.")
                SignInProblem.LOCKED -> tr("Too many wrong passcodes. Try again in 15 minutes.", "Demasiadas claves incorrectas. Intenta de nuevo en 15 minutos.")
                SignInProblem.WRONG -> tr("That passcode is not right.", "Esa clave no es correcta.")
                null -> null
            },
            form = problem != SignInProblem.NO_PASSCODE,
        )
    }

private fun kotlinx.html.HTML.signInPage(back: String, problem: String?, form: Boolean = true) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(tr("Sign in · Deals Tracker", "Entrar · Deals Tracker"))
        link(rel = "stylesheet", href = FONT_HREF)
        link(rel = "stylesheet", href = "/static/app.css?v=$CSS_VERSION")
    }
    body {
        header { h1 { +"Deals Tracker" } }
        main {
            div("signin") {
                p("signin-lead") { +tr("Enter the passcode to open the tracker from this device.", "Escribe la clave para abrir el tracker desde este dispositivo.") }
                if (problem != null) p("signin-problem") { attributes["role"] = "alert"; +problem }
                if (form) form(action = "/login", method = FormMethod.post) {
                    hiddenInput(name = "back") { value = back }
                    label { htmlFor = "passcode"; +tr("Passcode", "Clave") }
                    input(type = InputType.password, name = "passcode") {
                        id = "passcode"
                        required = true
                        attributes["autocomplete"] = "current-password"
                        attributes["autofocus"] = "autofocus"
                    }
                    button(type = ButtonType.submit, classes = "primary") { +tr("Sign in", "Entrar") }
                }
            }
        }
    }
}
