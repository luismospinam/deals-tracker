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

/** The name purchases made through the cart are logged under, whichever store they were added at. */
internal const val EXITO_CART_SOURCE = "exito-grocery"

/**
 * The Exito stores the cart button can buy at. One is chosen at a time ([MyCartStore]) with the switch
 * on the Pinned view, and it decides three things together: which store's copy of a row gets the button
 * ([sources]), which seller prices and stocks the add ([seller]), and which store the order is picked up
 * at -- Exito reads the pickup store from the digits of the seller sent with each add (`037`, `031`).
 *
 * Each store has a cart of its own ([cartIdKey]). An Exito cart has one pickup store, so a cart holding
 * Laureles items cannot simply take a Colombia one; with two carts, switching store never mixes them and
 * never loses what was already added for the other.
 */
enum class CartStore(
    val key: String,
    val label: String,
    val seller: String,
    val sources: Set<String>,
    /**
     * Where the store's pickup point is, as VTEX wants it: longitude then latitude, as Exito records it on an order
     * picked up there (`pickupStoreInfo.address`). Used to make Exito list the store's pickup option for a cart
     * (see [pickupSearchBody]); the option itself is picked by its id.
     */
    val geo: List<Double>,
    /** The street as Exito's store picker writes it on the pickup address it saves (see [pickupAddress]). */
    val pickupStreet: String,
) {
    LAURELES("laureles", "Exito Laureles", "exitocol037", setOf("exito-grocery", "exito-household"), listOf(-75.602029, 6.246248), "Carrera 81 # 37 - 100"),
    COLOMBIA("colombia", "Exito Colombia", "exitocol031", setOf("exito-colombia-grocery", "exito-colombia-household"), listOf(-75.58298, 6.255477), "Carrera 66 # 49 - 01");

    /** "Laureles", "Colombia": the store without the chain, for places that already say Exito. */
    val short: String get() = label.removePrefix("Exito ")

    /** Laureles keeps the key it had before there were two stores, so its open cart carries over. */
    val cartIdKey: String
        get() = if (this == LAURELES) com.luis.dealstracker.db.SettingsRepository.EXITO_CART_ID
        else com.luis.dealstracker.db.SettingsRepository.EXITO_CART_ID + "." + key

    /** The purchase log's last reading of this store's cart, kept apart for the same reason. */
    val snapshotKey: String
        get() = if (this == LAURELES) com.luis.dealstracker.db.SettingsRepository.EXITO_CART_SNAPSHOT
        else com.luis.dealstracker.db.SettingsRepository.EXITO_CART_SNAPSHOT + "." + key

    companion object {
        /** An unknown or missing key is Laureles, the store the tracker began with. */
        fun of(key: String?): CartStore = entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: LAURELES
    }
}

/**
 * The store in force. Read on every render and every add; written at boot from `app_settings` and by the
 * switch (`POST /exito-store`), which saves it too. In memory because a page draws hundreds of rows.
 */
object MyCartStore {
    @Volatile
    var current: CartStore = CartStore.LAURELES
}

/** Every provider a cart purchase's product may be a row of, at any store. */
internal val EXITO_CART_SOURCES: Set<String> = CartStore.entries.flatMap { it.sources }.toSet()

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

    /**
     * Exito's search for the store has no stock of it now: sold out since the last crawl saw it. The
     * product search runs with `hideUnavailableItems`, so "not found" there means none to sell.
     */
    data object SoldOut : CartResult

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

/**
 * Where to read the cart for an add. A store with no cart yet asks Exito for a new one (`forceNewCart`),
 * rather than whatever cart the login last had: that could be the other store's, and a Colombia item
 * added to the Laureles cart would be picked up at the wrong store.
 */
internal fun orderFormUrl(baseUrl: String, saved: String?): String =
    "$baseUrl/api/checkout/pub/orderForm" + if (saved == null) "?forceNewCart=true" else ""

/** True when Exito answered with a cart that belongs to another store: never adopted, never added to. */
internal fun isOtherStoresCart(answered: String, otherStoresCarts: Collection<String?>): Boolean =
    answered.isNotBlank() && answered in otherStoresCarts.filterNotNull()

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
    // Blank when Exito gave no reason about this item; the toast words that case in the reader's language.
    return CartResult.Refused(messageAbout(form, sku.name) ?: generalMessage(form) ?: "")
}

/** One line of a cart as the purchase log reads it. [unitPrice] is in pesos (VTEX sends cents). */
data class CartLine(val productId: String, val name: String, val quantity: Int, val unitPrice: java.math.BigDecimal?)

/** A cart's id and lines, from an order form. Null when the body is not one. */
data class CartContents(val cartId: String, val lines: List<CartLine>)

internal fun cartContents(body: String?): CartContents? = try {
    val form = cartJson.parseToJsonElement(body ?: "").jsonObject
    val id = form["orderFormId"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("no id")
    val lines = (form["items"] as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { line ->
        val pid = line["productId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val cents = (line["sellingPrice"] ?: line["price"])?.jsonPrimitive?.longOrNull
        CartLine(
            pid, line["name"]?.jsonPrimitive?.contentOrNull ?: pid, line["quantity"]?.jsonPrimitive?.intOrNull ?: 1,
            cents?.let { java.math.BigDecimal.valueOf(it, 2) },
        )
    }
    // The same product can sit on two lines (two sellers); the log counts the product.
    CartContents(id, lines.groupBy { it.productId }.map { (_, ls) -> ls.first().copy(quantity = ls.sumOf { it.quantity }) })
} catch (_: Exception) {
    null
}

/**
 * For the log, why Exito turned an add down: its error code and message, and the line's availability. Never
 * the whole body, which holds the shopper's profile and address. Blank when the body says nothing of use.
 */
internal fun refusalNote(body: String?): String {
    val form = try { cartJson.parseToJsonElement(body ?: return "").jsonObject } catch (_: Exception) {
        return if (body.isNullOrBlank()) "" else " (not JSON: ${body.take(80).replace('\n', ' ')})"
    }
    val parts = buildList {
        (form["error"] as? JsonObject)?.let { e ->
            add("error ${e["code"]?.jsonPrimitive?.contentOrNull}: ${e["message"]?.jsonPrimitive?.contentOrNull}")
        }
        form["message"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let { add("message: $it") }
        (form["messages"] as? JsonArray)?.mapNotNull { m ->
            (m as? JsonObject)?.let { "${it["code"]?.jsonPrimitive?.contentOrNull}: ${it["text"]?.jsonPrimitive?.contentOrNull}" }
        }?.forEach { add(it) }
        (form["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("availability")?.jsonPrimitive?.contentOrNull }
            ?.distinct()?.takeIf { it.isNotEmpty() }?.let { add("availability $it") }
    }
    return if (parts.isEmpty()) "" else " (" + parts.joinToString("; ").take(300) + ")"
}

/**
 * Exito's message about the item named [name], if any. A cart keeps every message since it was made, so the
 * first one is usually about some earlier item: on 2026-11-17 every refusal after the first read "PINA PORCIONADA
 * ... no está disponible", whatever was being added. Messages name the item ("El ítem <name> no ..."), so the one
 * that names this item is the one that is about it.
 */
internal fun messageAbout(form: JsonObject, name: String?): String? {
    val key = name?.replace(Regex("\\s+"), " ")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return (form["messages"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        ?.lastOrNull { it.replace(Regex("\\s+"), " ").lowercase().contains(key) }
}

/** The latest message that is about no item in particular ("Sin stock"), never one naming another item. */
internal fun generalMessage(form: JsonObject): String? =
    (form["messages"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        ?.lastOrNull { !Regex("(^|\\s)[ií]tem\\s", RegexOption.IGNORE_CASE).containsMatchIn(it) }

/** Exito's own words for why a line cannot be bought, or a plain default. */
internal fun unavailableReason(form: JsonObject, sku: CartSku): String {
    val lineName = (form["items"] as? JsonArray)?.map { it.jsonObject }
        ?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == sku.skuId }?.get("name")?.jsonPrimitive?.contentOrNull
    return messageAbout(form, lineName) ?: messageAbout(form, sku.name)
        ?: "${lineName ?: sku.name ?: "This product"} is not available at this store"
}

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

/**
 * The body of Exito's own add-to-cart call. `location` is what Exito's site sends once Compra y Recoge is
 * chosen in the header (`shippingType` PE, the store's dependency code, the city's postal code); the dependency
 * code is the digits of the store seller id (`exitocol037` -> `037`).
 */
internal fun siteAddPayload(sku: CartSku, quantity: Int, seller: String, postalCode: String = "05001", orderFormId: String? = null): String =
    buildJsonObject {
        // Required since 2026-11-17: without it Exito answers 400 "orderFormId is required".
        orderFormId?.let { put("orderFormId", it) }
        put("orderItems", buildJsonArray {
            add(buildJsonObject { put("quantity", quantity); put("seller", sku.seller); put("id", sku.skuId) })
        })
        put("location", buildJsonObject {
            put("shippingType", "PE")
            put("postalCode", postalCode)
            put("dependencyCode", seller.filter { it.isDigit() })
        })
    }.toString()

/**
 * The pickup option Exito lists for [seller]'s store in an order form, as `(sla id, pickup address)`: the SLA
 * whose pickup point is `<seller>_ptorecogida_<digits>` (`exitocol037_ptorecogida_037`). Null when the form
 * lists none, which is the case until the cart has an address near the store.
 */
internal fun pickupSlaFor(body: String?, seller: String): Pair<String, JsonObject>? = try {
    val point = "${seller}_ptorecogida_${seller.filter { it.isDigit() }}"
    ((cartJson.parseToJsonElement(body ?: "").jsonObject["shippingData"] as? JsonObject)?.get("logisticsInfo") as? JsonArray)
        ?.flatMap { (it.jsonObject["slas"] as? JsonArray).orEmpty() }
        ?.map { it.jsonObject }
        ?.firstOrNull { it["pickupPointId"]?.jsonPrimitive?.contentOrNull == point }
        ?.let { sla ->
            val id = sla["id"]?.jsonPrimitive?.contentOrNull ?: return@let null
            val address = (sla["pickupStoreInfo"] as? JsonObject)?.get("address") as? JsonObject ?: return@let null
            id to address
        }
} catch (_: Exception) {
    null
}

/**
 * Whether every line of an order form is set to Compra y Recoge at [seller]'s store. False for a cart with no
 * lines: there is nothing to set yet. A cart can lose this (seen 2026-11-17, after a fresh sign-in: no address,
 * no channel), and then Exito prices nothing new -- every add came back "no está disponible".
 */
internal fun isPickupAt(body: String?, seller: String): Boolean = try {
    val lines = ((cartJson.parseToJsonElement(body ?: "").jsonObject["shippingData"] as? JsonObject)?.get("logisticsInfo") as? JsonArray)
        ?.map { it.jsonObject }.orEmpty()
    val tail = "(ptorecogida_${seller.filter { it.isDigit() }})"
    lines.isNotEmpty() && lines.all {
        it["selectedDeliveryChannel"]?.jsonPrimitive?.contentOrNull == "pickup-in-point" &&
            it["selectedSla"]?.jsonPrimitive?.contentOrNull?.endsWith(tail) == true
    }
} catch (_: Exception) {
    false
}

/**
 * Pickup at [store] as Exito's checkout will read it: every line on the store's pickup option ([isPickupAt]) and
 * the cart's address the store's pickup address ([pickupAddress]: complement = the store's name). A cart on
 * pickup with any other address opened in the checkout as home delivery (2026-11-17).
 */
internal fun pickupReady(body: String?, store: CartStore): Boolean = isPickupAt(body, store.seller) && try {
    ((cartJson.parseToJsonElement(body ?: "").jsonObject["shippingData"] as? JsonObject)?.get("address") as? JsonObject)
        ?.get("complement")?.jsonPrimitive?.contentOrNull.equals(store.label, ignoreCase = true)
} catch (_: Exception) {
    false
}

/** The number of lines in an order form's shipping data, which the pickup selection must name one by one. */
internal fun logisticsCount(body: String?): Int = try {
    ((cartJson.parseToJsonElement(body ?: "").jsonObject["shippingData"] as? JsonObject)?.get("logisticsInfo") as? JsonArray)?.size ?: 0
} catch (_: Exception) {
    0
}

/** First step of setting pickup: an address near [store], so Exito lists that store's pickup option. */
internal fun pickupSearchBody(store: CartStore, formBody: String? = null): String = buildJsonObject {
    put("selectedAddresses", buildJsonArray { add(pickupAddress(store, formBody)) })
    put("clearAddressIfPostalCodeNotFound", false)
}.toString()

/**
 * The address a Compra y Recoge cart must carry for Exito's checkout to treat it as a pickup: the one Exito's
 * own store picker saves on the account, a "search" address at the store with the street written Exito's way,
 * number "0" and the store's name as complement. That is how the checkout tells a store pickup from a home:
 * given a bare address (no street) or the store's own street address, it opened the cart as home delivery and
 * asked for an address; given this one, it showed "Compra y Recoge, Almacén: Exito Colombia" (2026-11-17).
 *
 * The account's saved copy is used when the order form lists one ([formBody]'s `availableAddresses`), so the
 * checkout sees the same address its picker made; otherwise one of the same shape is built.
 */
internal fun pickupAddress(store: CartStore, formBody: String? = null): JsonObject {
    val saved = try {
        ((cartJson.parseToJsonElement(formBody ?: "").jsonObject["shippingData"] as? JsonObject)?.get("availableAddresses") as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull {
                it["addressType"]?.jsonPrimitive?.contentOrNull == "search" &&
                    it["complement"]?.jsonPrimitive?.contentOrNull.equals(store.label, ignoreCase = true) &&
                    it["street"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
            }
    } catch (_: Exception) {
        null
    }
    return saved ?: buildJsonObject {
        put("addressType", "search")
        put("country", "COL")
        put("state", "Antioquia")
        put("city", "Medellín")
        put("postalCode", "05001")
        put("street", store.pickupStreet)
        put("number", "0")
        put("complement", store.label)
        put("geoCoordinates", buildJsonArray { store.geo.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }
}

/** Second step: every one of [lines] lines set to the pickup [sla], at [store]'s [pickupAddress]. */
internal fun pickupSelectBody(sla: String, store: CartStore, lines: Int, formBody: String? = null): String = buildJsonObject {
    put("selectedAddresses", buildJsonArray { add(pickupAddress(store, formBody)) })
    put("clearAddressIfPostalCodeNotFound", false)
    put("logisticsInfo", buildJsonArray {
        repeat(lines) { i ->
            add(buildJsonObject { put("itemIndex", i); put("selectedDeliveryChannel", "pickup-in-point"); put("selectedSla", sla) })
        }
    })
}.toString()

/**
 * The checkout data Exito's own site writes on a cart (custom app `order`): no bag, sales channel 1, channel 15.
 * Exito's checkout reads it: a pickup cart without it opened as home delivery (2026-11-17). The site also writes
 * a reserved pickup slot (`idSlotInstaleap`); a cart without one still opened as pickup and offered slots.
 */
internal val CHECKOUT_FIELDS: Map<String, String> = linkedMapOf(
    "bagSkuId" to "0", "bagQuantitySupport" to "100000", "bagName" to "Sin bolsa", "bagSkuTaxId" to "0",
    "checkBag" to "true", "channelId" to "15", "salesChannel" to "1",
)

internal fun checkoutFieldsBody(): String = buildJsonObject { CHECKOUT_FIELDS.forEach { (k, v) -> put(k, v) } }.toString()

/** Whether an order form already carries [CHECKOUT_FIELDS]' channel, so it need not be written again. */
internal fun hasCheckoutFields(body: String?): Boolean = try {
    ((cartJson.parseToJsonElement(body ?: "").jsonObject["customData"] as? JsonObject)?.get("customApps") as? JsonArray)
        ?.map { it.jsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == "order" }
        ?.let { (it["fields"] as? JsonObject)?.get("channelId")?.jsonPrimitive?.contentOrNull } == CHECKOUT_FIELDS["channelId"]
} catch (_: Exception) {
    false
}

/** The delivery channels the lines of an order form have selected, for the log: never the address. */
internal fun deliveryChannels(body: String?): List<String> = try {
    ((cartJson.parseToJsonElement(body ?: "").jsonObject["shippingData"] as? JsonObject)?.get("logisticsInfo") as? JsonArray)
        ?.mapNotNull { it.jsonObject["selectedDeliveryChannel"]?.jsonPrimitive?.contentOrNull }?.distinct().orEmpty()
} catch (_: Exception) {
    emptyList()
}

/**
 * The names of the pickup points the lines of an order form have selected ("Éxito Colombia"), for the
 * log: it is how a switch of store is seen to have reached Exito. The name only, never the address.
 */
internal fun pickupPoints(body: String?): List<String> = try {
    ((cartJson.parseToJsonElement(body ?: "").jsonObject["shippingData"] as? JsonObject)?.get("logisticsInfo") as? JsonArray)
        ?.mapNotNull { line ->
            val info = line.jsonObject
            val selected = info["selectedSla"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            (info["slas"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == selected }
                ?.let { sla -> (sla["pickupStoreInfo"] as? JsonObject)?.get("friendlyName")?.jsonPrimitive?.contentOrNull }
                ?.takeIf { it.isNotBlank() }
        }?.distinct().orEmpty()
} catch (_: Exception) {
    emptyList()
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
    /** Laureles' seller, as config has it. */
    private val seller: String,
    private val now: () -> Instant = Instant::now,
    /** The store being bought at, read on every call: the switch changes it while the app runs. */
    private val store: () -> CartStore = { CartStore.LAURELES },
    /** Each store's seller. Config's value for the stores it names, the enum's own otherwise. */
    private val sellerOf: (CartStore) -> String = { if (it == CartStore.LAURELES) seller else it.seller },
) {
    /** Keyed by seller as well as product: the same product is a different stock check at each store. */
    private val skus = java.util.concurrent.ConcurrentHashMap<String, CartSku>()

    /** The saved cookie, if any. */
    fun cookie(): String? = settings.get(SettingsRepository.EXITO_COOKIE)?.takeIf { it.isNotBlank() }

    fun save(raw: String): String? {
        val token = cleanCookie(raw).takeIf { it.isNotEmpty() } ?: return null
        settings.set(mapOf(SettingsRepository.EXITO_COOKIE to token))
        return token
    }

    fun clear() = settings.set(mapOf(SettingsRepository.EXITO_COOKIE to "") + CartStore.entries.associate { it.cartIdKey to "" })

    /** The id of the tracker's own cart, once the first add has created it. See [add]. */
    fun cartId(): String? = settings.get(store().cartIdKey)?.takeIf { it.isNotBlank() }

    /** The setting the purchase log keeps its last reading of the current store's cart in. */
    fun snapshotKey(): String = store().snapshotKey

    /** The providers of the store being bought at: the rows a line of its cart is most likely one of. */
    fun storeSources(): Set<String> = store().sources

    /** Where the shopper opens this cart on any device: Exito's checkout loads the cart a link names. */
    fun cartLink(): String? = cartId()?.let { cartLink(baseUrl, it) }

    /** The link that opens any cart of the tracker's, such as one the order agent filled. */
    fun cartLinkFor(cartId: String): String = cartLink(baseUrl, cartId)

    /**
     * Sets the tracker's cart to Compra y Recoge at the store being bought at, if it is not already, so the
     * cart opens ready to pay. The shopper cannot do it on the site: choosing Compra y Recoge in Exito's
     * header starts a new, empty cart in that browser (seen 2026-11-17) instead of changing this one.
     * Best effort: a failure leaves the cart as it was and the link still opens it.
     */
    suspend fun ensurePickup(): Boolean = ensurePickup(store(), cartId())

    /** [ensurePickup] for any cart of the tracker's, such as the order agent's, at the store it was filled for. */
    suspend fun ensurePickup(at: CartStore, cartId: String?): Boolean {
        val token = cookie() ?: return false
        if (cookieExpired(token, now())) return false
        val saved = cartId ?: return false
        val headers = mapOf("Cookie" to "$EXITO_COOKIE_NAME=$token; checkout.vtex.com=__ofid=$saved", "Accept" to "application/json")
        return try {
            val form = http.get("$baseUrl/api/checkout/pub/orderForm/$saved", headers)
            if (form.status !in 200..299) return false
            if (!hasCheckoutFields(form.body)) writeCheckoutFields(at, saved, headers)
            if (pickupReady(form.body, at)) return true
            val set = setPickup(at, saved, headers) != null
            if (set) println("[exito-cart] ${at.label}: cart set to pickup before opening it")
            set
        } catch (e: Exception) {
            println("[exito-cart] ${at.label}: could not check pickup -- ${e.message?.take(120)}")
            false
        }
    }

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
        // Read once: a switch made while this add is in flight must not split it between two stores.
        val at = store()
        return addTo(
            at,
            saved = settings.get(at.cartIdKey)?.takeIf { it.isNotBlank() },
            others = CartStore.entries.filter { it != at }.map { settings.get(it.cartIdKey)?.takeIf { id -> id.isNotBlank() } },
            adopt = { settings.set(mapOf(at.cartIdKey to it)) },
            productId = productId,
            quantity = quantity,
        )
    }

    /** The manual carts of every store (the 🛒 button's), which no other cart of the tracker's may ever become. */
    fun manualCartIds(): List<String> = CartStore.entries.mapNotNull { settings.get(it.cartIdKey)?.takeIf { id -> id.isNotBlank() } }

    /**
     * Adds to a cart of the tracker's at [at]: [saved] when there is one, else a new cart, which is handed
     * to [adopt] (and so is any replacement Exito answers with). A cart in [others] is never adopted or
     * added to -- for the 🛒 button that is the other store's cart; for the order agent, both manual carts.
     */
    suspend fun addTo(
        at: CartStore,
        saved: String?,
        others: Collection<String?>,
        adopt: (String) -> Unit,
        productId: String,
        quantity: Int = 1,
    ): CartResult {
        val token = cookie() ?: return CartResult.NotConnected
        if (cookieExpired(token, now())) return CartResult.Expired
        return try {
            val seller = sellerOf(at)
            val cacheKey = "$seller:$productId"
            // The line keeps the seller the search names (usually `1`). Never the store's own (`exitocol031`): a line
            // sold by it gets no promotion or card price and reads "Vendido por: Exitocol031" (2026-11-17). Orders
            // picked up at a store show its seller only because Exito swaps it in when the order is placed.
            val sku = skus[cacheKey] ?: lookup(productId, seller)?.also { skus[cacheKey] = it }
                ?: return CartResult.SoldOut
            val login = "$EXITO_COOKIE_NAME=$token"
            var cartId = ""
            // Twice at most. The second time is only for an answer that is the other store's cart -- which a
            // saved cart closed by an order might get -- and asks outright for a new one. Without it every
            // later add would send the same closed id, get the same answer and be refused for ever.
            for (ask in listOf(saved, null).distinct()) {
                val formResult = http.get(
                    orderFormUrl(baseUrl, ask),
                    mapOf("Cookie" to (ask?.let { "$login; checkout.vtex.com=__ofid=$it" } ?: login), "Accept" to "application/json"),
                )
                if (formResult.status == 401 || formResult.status == 403) return CartResult.Expired
                val form = formResult.body?.let { cartJson.parseToJsonElement(it) }
                    ?: return CartResult.Failed("Exito answered HTTP ${formResult.status}")
                if (!isLoggedIn(form)) return CartResult.Expired
                cartId = form.jsonObject["orderFormId"]?.jsonPrimitive?.contentOrNull
                    ?: return CartResult.Failed("no cart id in Exito's answer")
                if (!isOtherStoresCart(cartId, others)) break
                println("[exito-cart] ${at.label}: Exito answered with another store's cart" + if (ask != null) "; asking for a new one" else "")
            }
            if (isOtherStoresCart(cartId, others)) return CartResult.Failed("Exito returned the other store's cart; try again")
            cartToAdopt(saved, cartId)?.let(adopt)

            val headers = mapOf("Cookie" to "$login; checkout.vtex.com=__ofid=$cartId", "Accept" to "application/json")
            if (cartId != saved) {
                // A new cart is placed at the store before its first add, as the store picker does, so the first item
                // is priced and stocked there. Added first into an empty cart, a piña Exito Colombia stocked came back
                // "no está disponible" (2026-11-17).
                runCatching { http.postJson("$baseUrl/api/checkout/pub/orderForm/$cartId/attachments/shippingData", pickupSearchBody(at), headers) }
            }
            // Exito's own site adds through /api/orderForm/items and names the pickup store in `location`; that is
            // how a cart comes to be Compra y Recoge. The plain VTEX add (below) leaves it on home delivery, and
            // the call that would switch it afterwards is refused (HTTP 403, CHK003, seen 2026-10-20).
            val viaSite = runCatching { http.postJson("$baseUrl/api/orderForm/items?orderFormId=$cartId", siteAddPayload(sku, quantity, seller, orderFormId = cartId), headers) }.getOrNull()
            val added = if (viaSite != null && viaSite.status in 200..299 && viaSite.body != null) viaSite else {
                println("[exito-cart] site add answered ${viaSite?.status}${refusalNote(viaSite?.body)}; adding through the VTEX API instead (cart stays on home delivery)")
                http.postJson("$baseUrl/api/checkout/pub/orderForm/$cartId/items", addItemPayload(sku, quantity), headers)
            }
            println("[exito-cart] ${at.label}: add HTTP ${added.status}, delivery channels now ${deliveryChannels(added.body)}, pickup at ${pickupPoints(added.body)}")
            var result = interpretAdd(added.status, added.body, sku)
            var latest = added.body
            if (!pickupReady(latest, at)) {
                val fixed = setPickup(at, cartId, headers)
                if (fixed != null) {
                    latest = fixed
                    println("[exito-cart] ${at.label}: the cart had lost its pickup store; set it back to ${pickupPoints(fixed)}")
                    if (result is CartResult.Refused) {
                        val again = http.postJson("$baseUrl/api/orderForm/items?orderFormId=$cartId", siteAddPayload(sku, quantity, seller, orderFormId = cartId), headers)
                        result = interpretAdd(again.status, again.body, sku)
                        latest = again.body
                        println("[exito-cart] ${at.label}: added again after setting pickup -- ${if (result is CartResult.Added) "added" else result.toString()}")
                    }
                }
            }
            if (result is CartResult.Added && !hasCheckoutFields(latest)) writeCheckoutFields(at, cartId, headers)
            if (result !is CartResult.Added) println("[exito-cart] ${at.label}: not added -- $result${refusalNote(added.body)}; sku ${sku.skuId} from seller ${sku.seller}, cart …${cartId.takeLast(6)}${if (cartId != saved) " (new)" else ""}")
            if (result is CartResult.Refused) {
                // A refused line may still sit in the cart; take it out so the cart holds only what can be bought.
                lineIndex(latest, sku.skuId)?.let { index ->
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

    /** Writes [CHECKOUT_FIELDS] on [cartId]. Best effort: without them the checkout opens the cart as delivery. */
    private suspend fun writeCheckoutFields(at: CartStore, cartId: String, headers: Map<String, String>) {
        val r = runCatching { http.putJson("$baseUrl/api/checkout/pub/orderForm/$cartId/customData/order", checkoutFieldsBody(), headers) }.getOrNull()
        if (r == null || r.status !in 200..299) println("[exito-cart] ${at.label}: checkout data answered HTTP ${r?.status}${refusalNote(r?.body)}")
    }

    /** The store's own seller id (`exitocol037`), which names its pickup point; not the SKU's seller. */
    private fun sellerOfStore(at: CartStore): String = at.seller

    /**
     * Sets every line of [cartId] to Compra y Recoge at [at], as Exito's own store picker would: first an
     * address near the store, so the store's pickup option is listed, then that option for every line. Returns
     * the order form after, or null when Exito listed no such option or refused either step.
     */
    private suspend fun setPickup(at: CartStore, cartId: String, headers: Map<String, String>): String? = try {
        setPickupOrThrow(at, cartId, headers)
    } catch (e: Exception) {
        println("[exito-cart] ${at.label}: could not set pickup -- ${e.message?.take(120)}")
        null
    }

    private suspend fun setPickupOrThrow(at: CartStore, cartId: String, headers: Map<String, String>): String? {
        val url = "$baseUrl/api/checkout/pub/orderForm/$cartId/attachments/shippingData"
        val listed = http.postJson(url, pickupSearchBody(at, http.get("$baseUrl/api/checkout/pub/orderForm/$cartId", headers).body), headers)
        val option = pickupSlaFor(listed.body, at.seller)
        return if (listed.status !in 200..299 || option == null) {
            println("[exito-cart] ${at.label}: could not set pickup -- HTTP ${listed.status}, store's pickup option ${if (option == null) "not listed" else "listed"}${refusalNote(listed.body)}")
            null
        } else {
            val chosen = http.postJson(url, pickupSelectBody(option.first, at, logisticsCount(listed.body), listed.body), headers)
            if (chosen.status in 200..299 && isPickupAt(chosen.body, at.seller)) chosen.body
            else { println("[exito-cart] ${at.label}: pickup selection answered HTTP ${chosen.status}${refusalNote(chosen.body)}"); null }
        }
    }

    /**
     * What the tracker's cart holds now, for the purchase log. Null with no cart yet, no login, an
     * expired one, or an answer that is not a cart. Read only: unlike [add] it never adopts a new cart.
     */
    suspend fun contents(): CartContents? {
        val token = cookie() ?: return null
        if (cookieExpired(token, now())) return null
        val saved = cartId() ?: return null
        return try {
            val r = http.get(
                "$baseUrl/api/checkout/pub/orderForm",
                mapOf("Cookie" to "$EXITO_COOKIE_NAME=$token; checkout.vtex.com=__ofid=$saved", "Accept" to "application/json"),
            )
            if (r.status !in 200..299) null else cartContents(r.body)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun lookup(productId: String, seller: String): CartSku? {
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
