package com.luis.dealstracker.exito

import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

/** The one provider that gets a cart button: Exito priced at Laureles. Colombia and the rest do not. */
internal const val EXITO_CART_SOURCE = "exito-grocery"

/** The cookie VTEX sets on login. It is the whole credential: the cart belongs to whoever presents it. */
internal const val EXITO_COOKIE_NAME = "VtexIdclientAutCookie_exitocol"

/** The name VTEX documents generically; accepted on paste so a header copied under it still works. */
private const val GENERIC_COOKIE_NAME = "VtexIdclientAutologin"

/** What happened when we tried to put an item in the cart; each case is a different toast. */
sealed interface CartResult {
    /** [quantity] is how many of the item the cart now holds, which exceeds 1 on a repeat click. */
    data class Added(val title: String?, val quantity: Int) : CartResult

    /** No cookie saved yet. */
    data object NotConnected : CartResult

    /** A cookie is saved but Exito no longer honours it (past its `exp`, or logged out elsewhere). */
    data object Expired : CartResult

    /** Exito answered, but the item did not end up in the cart: out of stock, or not sold at this store. */
    data class Refused(val reason: String) : CartResult

    /** We could not complete the exchange at all: network, or a response we could not read. */
    data class Failed(val reason: String) : CartResult
}

/** A product's cart identity: the SKU is not what we store (that is the product id). */
data class CartSku(val skuId: String, val seller: String, val name: String?)

internal val cartJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * What a pasted cookie value looks like once the paste's accidents are removed. People copy
 * `VtexIdclientAutCookie_exitocol=eyJ…;` from a header, or the bare value from DevTools with a trailing
 * newline; either should work.
 */
internal fun cleanCookie(raw: String): String =
    raw.trim()
        .removePrefix("$EXITO_COOKIE_NAME=")
        .removePrefix("$GENERIC_COOKIE_NAME=")
        .substringBefore(';')
        .trim('"', '\'', ' ', '\n', '\r', '\t')

/**
 * The `exp` of a VTEX auth token (a JWT), or null if [token] is not one. Only the payload is read;
 * the signature is Exito's to check, not ours.
 */
internal fun cookieExpiry(token: String): Instant? {
    val payload = token.split('.').getOrNull(1) ?: return null
    return try {
        val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        cartJson.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochSecond)
    } catch (_: Exception) {
        null
    }
}

/**
 * The link that opens cart [cartId] in a browser. Exito's checkout loads the cart a link names, so the
 * tracker's cart opens on a phone without the app (confirmed on the shopper's phone, 2026-10-14).
 */
internal fun cartLink(baseUrl: String, cartId: String): String = "$baseUrl/checkout/?orderFormId=$cartId#/cart"

/**
 * The cart id to save after Exito answered [answered] for a request that carried [saved], or null to keep
 * what is saved. Exito answers with the saved cart while it is open, and with a fresh one when none was sent
 * or an order closed it; the fresh one becomes the tracker's cart, and the old id is dropped with it.
 */
internal fun cartToAdopt(saved: String?, answered: String): String? = answered.takeIf { it.isNotBlank() && it != saved }

/** True when [token] declares an expiry that has passed. A token with no readable expiry is given the benefit. */
internal fun cookieExpired(token: String, now: Instant): Boolean =
    cookieExpiry(token)?.let { it <= now } ?: false

/**
 * The SKU to add for [productId], from an Intelligent Search answer asked at the Laureles region.
 *
 * The region is what makes this the Laureles item: with `hideUnavailableItems` the search only returns
 * what that store stocks, and the seller named is the one Exito's own site would add for it. That seller
 * is usually `1`, NOT the store's `exitocol037` -- that id only selects the region. (The older
 * `catalog_system` search ignores the region and answers with the national listing, so it cannot be used.)
 *
 * The search is free text, so other products come back with it; only an exact `productId` match counts.
 * Null when the product is absent -- not stocked at Laureles -- or none of its sellers has stock.
 */
internal fun skuFromSearch(body: String, productId: String): CartSku? {
    val products = try {
        cartJson.parseToJsonElement(body).jsonObject["products"] as? JsonArray
    } catch (_: Exception) {
        null
    } ?: return null
    val product = products.map { it.jsonObject }
        .firstOrNull { it["productId"]?.jsonPrimitive?.contentOrNull == productId } ?: return null
    val name = product["productName"]?.jsonPrimitive?.contentOrNull
    for (item in product["items"] as? JsonArray ?: return null) {
        val obj = item.jsonObject
        val id = obj["itemId"]?.jsonPrimitive?.contentOrNull ?: continue
        val sellers = (obj["sellers"] as? JsonArray)?.map { it.jsonObject } ?: continue
        val inStock = sellers.filter {
            (it["commertialOffer"]?.jsonObject?.get("AvailableQuantity")?.jsonPrimitive?.intOrNull ?: 0) > 0
        }
        // The default seller when it has stock, else the first that does.
        val chosen = inStock.firstOrNull { it["sellerDefault"]?.jsonPrimitive?.booleanOrNull == true } ?: inStock.firstOrNull()
        val sellerId = chosen?.get("sellerId")?.jsonPrimitive?.contentOrNull ?: continue
        return CartSku(id, sellerId, name)
    }
    return null
}

/** The body of VTEX's add-items call. */
internal fun addItemPayload(sku: CartSku, quantity: Int = 1): String =
    buildJsonObject {
        put(
            "orderItems",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", sku.skuId)
                        put("quantity", quantity)
                        put("seller", sku.seller)
                    },
                )
            },
        )
    }.toString()

/**
 * Reads the order form VTEX returns after an add. Success is judged by the cart actually holding the
 * SKU, not by the HTTP status: VTEX answers 200 with a `messages` entry when an item is out of stock.
 */
internal fun interpretAdd(status: Int, body: String?, sku: CartSku): CartResult {
    if (status == 401 || status == 403) return CartResult.Expired
    if (status !in 200..299 || body == null) return CartResult.Failed("Exito answered HTTP $status")
    val form = try { cartJson.parseToJsonElement(body).jsonObject } catch (_: Exception) {
        return CartResult.Failed("unreadable cart response")
    }
    val line = (form["items"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull {
        it["id"]?.jsonPrimitive?.contentOrNull == sku.skuId
    }
    if (line != null && line["availability"]?.jsonPrimitive?.contentOrNull.let { it != null && it != "available" }) {
        // Exito takes the line into the cart and flags it (seen 2026-10-15: HTTP 200, line present,
        // availability "cannotBeDelivered", plus an error message). The line existing is not an add.
        return CartResult.Refused(unavailableReason(form, sku))
    }
    if (line != null) {
        return CartResult.Added(
            title = line["name"]?.jsonPrimitive?.contentOrNull ?: sku.name,
            quantity = line["quantity"]?.jsonPrimitive?.intOrNull ?: 1,
        )
    }
    val message = (form["messages"] as? JsonArray)
        ?.firstNotNullOfOrNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
    return CartResult.Refused(message ?: "Exito did not add it (out of stock at this store?)")
}

/** Exito's own words for why a line cannot be bought, or a plain default. */
internal fun unavailableReason(form: JsonObject, sku: CartSku): String =
    (form["messages"] as? JsonArray)?.map { it.jsonObject }
        ?.firstOrNull { it["status"]?.jsonPrimitive?.contentOrNull == "error" }
        ?.get("text")?.jsonPrimitive?.contentOrNull
        ?: "${sku.name ?: "This product"} is not available for delivery at Laureles"

/** Body that sets the line at [index] to quantity 0, which removes it. */
internal fun removeLinePayload(index: Int): String = buildJsonObject {
    put("orderItems", buildJsonArray { add(buildJsonObject { put("index", index); put("quantity", 0) }) })
}.toString()

/** Position of [skuId] among the order form's lines, or null. */
internal fun lineIndex(body: String?, skuId: String): Int? = try {
    (cartJson.parseToJsonElement(body ?: "").jsonObject["items"] as? JsonArray)
        ?.indexOfFirst { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == skuId }?.takeIf { it >= 0 }
} catch (_: Exception) {
    null
}

/** Whether an order form is the signed-in customer's rather than an anonymous basket. */
internal fun isLoggedIn(orderForm: JsonElement): Boolean {
    val obj = orderForm as? JsonObject ?: return false
    obj["loggedIn"]?.jsonPrimitive?.booleanOrNull?.let { return it }
    // Older order forms omit `loggedIn`; a profile email is the same fact.
    return (obj["clientProfileData"] as? JsonObject)?.get("email")?.jsonPrimitive?.contentOrNull != null
}

/**
 * Adds items to the signed-in customer's Exito cart through VTEX's public checkout API.
 *
 * The cart lives on Exito's side and is tied to the account, so what is added here shows up in the
 * phone app too. The credential is the pasted [EXITO_COOKIE_NAME] cookie, kept in `app_settings`; it
 * lapses after about a day, which is reported as [CartResult.Expired] rather than a generic failure.
 */
class ExitoCart(
    private val http: PoliteHttp,
    private val settings: SettingsRepository,
    private val baseUrl: String,
    private val seller: String,
    private val now: () -> Instant = Instant::now,
) {
    private val skus = java.util.concurrent.ConcurrentHashMap<String, CartSku>()

    /** The saved cookie, if any. */
    fun cookie(): String? = settings.get(SettingsRepository.EXITO_COOKIE)?.takeIf { it.isNotBlank() }

    fun save(raw: String): String? {
        val token = cleanCookie(raw).takeIf { it.isNotEmpty() } ?: return null
        settings.set(mapOf(SettingsRepository.EXITO_COOKIE to token))
        return token
    }

    fun clear() = settings.set(mapOf(SettingsRepository.EXITO_COOKIE to "", SettingsRepository.EXITO_CART_ID to ""))

    /** The id of the tracker's own cart, once the first add has created it. See [add]. */
    fun cartId(): String? = settings.get(SettingsRepository.EXITO_CART_ID)?.takeIf { it.isNotBlank() }

    /** Where the shopper opens this cart on any device: Exito's checkout loads the cart a link names. */
    fun cartLink(): String? = cartId()?.let { cartLink(baseUrl, it) }

    /**
     * Adds to the tracker's OWN cart, and opens it for the shopper by [cartLink].
     *
     * A cart is found by its id, not by who is logged in, and carts belong to a browser or app rather than to
     * the account (measured 2026-10-14: the same account showed three different carts on desktop Chrome, the
     * phone app and a second browser). The phone app's id cannot be read, but a link can open any cart in a
     * browser (confirmed on the shopper's phone), so the tracker keeps a cart of its own.
     *
     * It uses the saved id while Exito still honours it. When Exito answers with a different cart -- none saved
     * yet, or the old one was closed by an order -- that answer is adopted and saved, and the add goes there.
     */
    suspend fun add(productId: String, quantity: Int = 1): CartResult {
        val token = cookie() ?: return CartResult.NotConnected
        if (cookieExpired(token, now())) return CartResult.Expired
        return try {
            val sku = skus[productId] ?: lookup(productId)?.also { skus[productId] = it }
                ?: return CartResult.Refused("Exito does not list this product at Laureles")
            val saved = cartId()
            val login = "$EXITO_COOKIE_NAME=$token"
            val formResult = http.get(
                "$baseUrl/api/checkout/pub/orderForm",
                mapOf("Cookie" to (saved?.let { "$login; checkout.vtex.com=__ofid=$it" } ?: login), "Accept" to "application/json"),
            )
            if (formResult.status == 401 || formResult.status == 403) return CartResult.Expired
            val form = formResult.body?.let { cartJson.parseToJsonElement(it) }
                ?: return CartResult.Failed("Exito answered HTTP ${formResult.status}")
            if (!isLoggedIn(form)) return CartResult.Expired
            val cartId = form.jsonObject["orderFormId"]?.jsonPrimitive?.contentOrNull
                ?: return CartResult.Failed("no cart id in Exito's answer")
            cartToAdopt(saved, cartId)?.let { settings.set(mapOf(SettingsRepository.EXITO_CART_ID to it)) }

            val added = http.postJson(
                "$baseUrl/api/checkout/pub/orderForm/$cartId/items",
                addItemPayload(sku, quantity),
                mapOf("Cookie" to "$login; checkout.vtex.com=__ofid=$cartId", "Accept" to "application/json"),
            )
            val result = interpretAdd(added.status, added.body, sku)
            if (result is CartResult.Refused) {
                // A refused line may still sit in the cart; take it out so the cart holds only what can be bought.
                lineIndex(added.body, sku.skuId)?.let { index ->
                    runCatching {
                        http.postJson(
                            "$baseUrl/api/checkout/pub/orderForm/$cartId/items/update",
                            removeLinePayload(index),
                            mapOf("Cookie" to "$login; checkout.vtex.com=__ofid=$cartId", "Accept" to "application/json"),
                        )
                    }
                }
            }
            result
        } catch (e: Exception) {
            CartResult.Failed(e.message?.take(120) ?: e::class.simpleName.orEmpty())
        }
    }

    private suspend fun lookup(productId: String): CartSku? {
        // No trailing slash after product_search: with one, Exito answers 301 and the redirect loses the query.
        val region = java.net.URLEncoder.encode(
            Base64.getEncoder().encodeToString("SW#$seller".toByteArray()),
            Charsets.UTF_8,
        )
        val result = http.get(
            "$baseUrl/api/io/_v/api/intelligent-search/product_search" +
                "?query=$productId&regionId=$region&hideUnavailableItems=true&count=10",
        )
        // Parsed whatever the status: Intelligent Search answers 406 with a perfectly good body (seen
        // 2026-10-14), so gating on 2xx threw away every result. A bad body just yields no SKU.
        return result.body?.let { skuFromSearch(it, productId) }
    }
}
