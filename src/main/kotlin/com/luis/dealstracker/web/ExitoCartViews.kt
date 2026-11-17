package com.luis.dealstracker.web

import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.exito.CartResult
import com.luis.dealstracker.exito.CartStore
import com.luis.dealstracker.exito.MyCartStore
import com.luis.dealstracker.exito.cookieExpiry
import com.luis.dealstracker.i18n.tr
import kotlinx.html.ButtonType
import kotlinx.html.a
import kotlinx.html.span
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.title
import java.time.Duration
import java.time.Instant

/**
 * The row itself when it is the Exito store being bought at ([MyCartStore], Laureles unless switched) and
 * on the shelf -- the only row the cart button sits on. Never another copy beneath it: a button on a row
 * that says "Exito Laureles" must add at Laureles. Pinned leads with that store's copy wherever it has one
 * ([preferredSources]), so the rows that can be bought there are the rows that say so.
 */
internal fun cartCopy(row: GroceryRow, store: CartStore = MyCartStore.current): GroceryRow? =
    row.takeIf { it.deal.source in store.sources && it.onShelf }

/**
 * The toast for one add-to-cart attempt, as a fragment for `#toast`. A success carries [cartLink] when the
 * tracker has a cart to open, so the shopper can go straight from "added" to paying, on a phone above all.
 */
internal fun cartToast(
    result: CartResult,
    title: String?,
    cartLink: String? = null,
    /** When the row was last seen in stock, for the sold-out message: the crawl it came from. */
    lastSeen: java.time.Instant? = null,
    store: CartStore = MyCartStore.current,
): String {
    val (kind, text) = when (result) {
        is CartResult.Added -> "ok" to buildString {
            append(tr("Added to your Exito ${store.short} cart", "Agregado a tu carrito de Exito ${store.short}"))
            (result.title ?: title)?.let { append(": ").append(it.take(60)) }
            if (result.quantity > 1) append(" ×${result.quantity}")
        }
        CartResult.NotConnected -> "warn" to tr(
            "Not connected to Exito yet -- paste your cookie under \"Exito cart\" at the bottom of this page.",
            "Aún no estás conectado a Exito -- pega tu cookie en \"Carrito Exito\" al final de esta página.",
        )
        CartResult.Expired -> "warn" to tr(
            "Your Exito login expired -- paste a fresh cookie under \"Exito cart\" at the bottom of this page.",
            "Tu sesión de Exito venció -- pega una cookie nueva en \"Carrito Exito\" al final de esta página.",
        )
        is CartResult.Refused -> "err" to if (result.reason.isBlank()) {
            tr("Exito did not add it (out of stock at this store?)", "Exito no lo agregó (¿agotado en esta tienda?)")
        } else {
            tr("Exito did not add it: ", "Exito no lo agregó: ") + result.reason
        }
        CartResult.SoldOut -> "warn" to buildString {
            append(tr("${store.short} is sold out of it right now", "${store.short} no tiene unidades en este momento"))
            lastSeen?.let {
                val at = it.atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { t -> "%02d:%02d".format(t.hour, t.minute) }
                append(tr(" (it had stock at the $at check)", " (tenía unidades en la revisión de las $at)"))
            }
            append(tr(". Marked unavailable there until the next check.", ". Queda como no disponible allí hasta la próxima revisión."))
        }
        is CartResult.Failed -> "err" to tr("Could not reach Exito: ", "No se pudo contactar a Exito: ") + result.reason
    }
    return createHTML(prettyPrint = false).div("toast toast-$kind") {
        attributes["role"] = "status"
        span("toast-text") { +text }
        if (result is CartResult.Added && cartLink != null) {
            a(href = cartLink, target = "_blank", classes = "toast-link") { +tr("Open cart", "Abrir carrito") }
        }
    }
}

/** The cart button on a grocery row, or nothing for stores that do not get one. */
internal fun kotlinx.html.FlowContent.cartButton(row: GroceryRow) {
    val copy = cartCopy(row) ?: return
    button(type = ButtonType.button, classes = "cart-btn") {
        attributes["hx-post"] = "/groceries/${copy.deal.id}/cart"
        attributes["hx-target"] = "#toast"
        attributes["hx-swap"] = "innerHTML"
        attributes["hx-disabled-elt"] = "this"
        // What the button says about itself afterwards; see CART_FEEDBACK_JS.
        attributes["data-done"] = tr("✓ Added", "✓ Agregado")
        attributes["data-fail"] = tr("! Check", "! Revisa")
        val at = MyCartStore.current.short
        title = tr("Add one to your Exito cart ($at)", "Agregar uno a tu carrito de Exito ($at)")
        +tr("🛒 cart", "🛒 carrito")
    }
}

/**
 * How the saved login reads in the panel, so "connected" is never claimed for a lapsed one. The cart, which
 * the tracker creates itself on the first add, is named by its last characters only.
 */
internal fun cookieStatus(token: String?, cartId: String?, now: Instant): String = when {
    token == null -> tr("Not connected.", "Sin conectar.")
    else -> {
        val exp = cookieExpiry(token)
        when {
            exp != null && exp <= now -> tr("Cookie expired -- paste a fresh one.", "Cookie vencida -- pega una nueva.")
            exp == null -> tr("Connected (cookie expiry unreadable)${cartNote(cartId)}.", "Conectado (vencimiento ilegible)${cartNote(cartId)}.")
            else -> {
                val left = Duration.between(now, exp)
                val h = left.toHours()
                val span = if (h >= 1) "${h}h ${left.toMinutesPart()}m" else "${left.toMinutes()}m"
                tr(
                    "Connected -- cookie valid for about $span${cartNote(cartId)}.",
                    "Conectado -- la cookie sirve unos $span${cartNote(cartId)}.",
                )
            }
        }
    }
}

private fun cartNote(cartId: String?): String =
    if (cartId == null) tr("; the cart is created on the first add", "; el carrito se crea con el primer producto")
    else ", cart …${cartId.takeLast(6)}"

/** The "Exito cart" panel at the foot of Groceries: status, one paste box for the login, and a way to forget it. */
internal fun exitoCartPanel(token: String?, cartId: String?, now: Instant = Instant.now()): String =
    createHTML(prettyPrint = false).div("exito-cart") {
        id = "exito-cart"
        details {
            val lapsed = token != null && cookieExpiry(token)?.let { it <= now } == true
            if (token == null || lapsed) attributes["open"] = ""
            summary { +tr("Exito cart", "Carrito Exito") }
            p("muted") { +cookieStatus(token, cartId, now) }
            p("muted") {
                +tr(
                    "On exito.com signed in: DevTools (⌘⌥I) → Application → Cookies → www.exito.com → copy the VALUE of VtexIdclientAutCookie_exitocol. It lasts about a day. The cart needs nothing from you: the tracker keeps its own and the Open cart link shows it.",
                    "En exito.com con tu sesión: DevTools (⌘⌥I) → Application → Cookies → www.exito.com → copia el VALOR de VtexIdclientAutCookie_exitocol. Dura cerca de un día. El carrito no necesita nada de ti: el tracker lleva el suyo y el enlace Abrir carrito lo muestra.",
                )
            }
            form {
                attributes["hx-post"] = "/groceries/exito-cart"
                attributes["hx-target"] = "#exito-cart"
                attributes["hx-swap"] = "outerHTML"
                // A password field so the cookie is not echoed on screen or kept in autofill history.
                input(type = InputType.password, name = "cookie") {
                    attributes["autocomplete"] = "off"
                    attributes["aria-label"] = tr("Exito login cookie", "Cookie de sesión de Éxito")
                    attributes["placeholder"] = tr("login cookie (eyJ…)", "cookie de sesión (eyJ…)")
                }
                button(type = ButtonType.submit) { +tr("Save", "Guardar") }
                if (token != null) {
                    button(type = ButtonType.submit, name = "clear") {
                        value = "1"
                        +tr("Forget", "Olvidar")
                    }
                }
            }
        }
    }

/** The Open cart link for the top of Groceries, or nothing until the tracker has a cart. */
internal fun openCartLink(link: String?, store: CartStore = MyCartStore.current): String =
    if (link == null) ""
    else createHTML(prettyPrint = false).a(href = link, target = "_blank", classes = "open-cart") {
        id = "open-cart"
        // Named: there is a cart per store, and this opens the one being bought at.
        +tr("🛒 Open Exito ${store.short} cart", "🛒 Abrir carrito Exito ${store.short}")
    }

/** The tracker's own door to the cart: it sets Compra y Recoge, then sends the browser on to Exito. */
internal const val OPEN_CART_PATH = "/groceries/exito-cart/open"

/** The href an Open cart link uses: the tracker's door when there is a cart, nothing when there is none. */
internal fun openCartVia(cartLink: String?): String? = cartLink?.let { OPEN_CART_PATH }

/** Where the store switch posts. */
internal const val CART_STORE_PATH = "/exito-store"

/**
 * "Buying at: Laureles | Colombia", above the cart link on Pinned. A plain form that posts and comes
 * back to [back]: the whole page has to be drawn again, since the cart buttons, the "can add" count and
 * the cart link all follow the store. The choice is said in words and `aria-pressed`, not colour alone.
 */
internal fun kotlinx.html.FlowContent.cartStoreSwitch(back: String, current: CartStore = MyCartStore.current) {
    form(action = CART_STORE_PATH, method = kotlinx.html.FormMethod.post, classes = "typetags storepick") {
        input(type = InputType.hidden, name = "back") { value = back }
        span("typetags-label") { +tr("Buying at", "Comprando en") }
        CartStore.entries.forEach { store ->
            val on = store == current
            button(type = ButtonType.submit, name = "store", classes = if (on) "typetag on" else "typetag") {
                value = store.key
                attributes["aria-pressed"] = on.toString()
                title = if (on) tr("The cart button adds from ${store.label}, for pickup there", "El botón de carrito agrega desde ${store.label}, para recoger allí")
                else tr("Switch the cart button to ${store.label}: its own cart, picked up there", "Cambiar el botón de carrito a ${store.label}: su propio carrito, para recoger allí")
                +"Exito ${store.short}"
            }
        }
    }
}

/** Where the Open cart link lands: loaded after the page, so the page query never reads the cart id. */
internal fun kotlinx.html.FlowContent.openCartSlot() {
    div {
        id = "open-cart"
        attributes["hx-get"] = "/groceries/exito-cart/link"
        attributes["hx-trigger"] = "load"
        attributes["hx-swap"] = "outerHTML"
    }
}

/** Where the panel lands on the page: it loads itself, so the page query never reads the cookie. */
internal fun kotlinx.html.FlowContent.exitoCartSlot() {
    div("exito-cart") {
        id = "exito-cart"
        attributes["hx-get"] = "/groceries/exito-cart"
        attributes["hx-trigger"] = "load"
        attributes["hx-swap"] = "outerHTML"
    }
}
