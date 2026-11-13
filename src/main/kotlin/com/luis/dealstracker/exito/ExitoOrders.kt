package com.luis.dealstracker.exito

import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.ingest.ProductIdentity
import com.luis.dealstracker.sources.PoliteHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * The account's order history lives on the checkout host. `www.exito.com` answers 404 for the same
 * path (measured 2026-11-11), and the login cookie the cart button holds is accepted here as it is.
 */
internal const val EXITO_ORDERS_URL = "https://tienda.exito.com/api/oms/user/orders"

/** One order as the history list gives it: enough to tell whether its detail must be read. */
data class OrderSummary(
    val orderId: String,
    val placedAt: Instant,
    val status: String,
    val statusDescription: String?,
    val lastChange: Instant?,
    val cartId: String?,
    val hostname: String?,
    val total: BigDecimal?,
    val paymentNames: String?,
    val currency: String,
)

/** A page of the history list, and how many pages there are. */
data class OrderPage(val orders: List<OrderSummary>, val pages: Int)

/** One line of an order. Prices are per unit, in pesos. */
data class OrderLine(
    val line: Int,
    val productId: String,
    val skuId: String?,
    val ean: String?,
    val title: String,
    val quantity: Int,
    /** What a unit cost after the order's discounts. */
    val unitPrice: BigDecimal?,
    /** The shelf price the order started from. */
    val storePrice: BigDecimal?,
    val listPrice: BigDecimal?,
    val seller: String?,
    val measurementUnit: String?,
    val unitMultiplier: BigDecimal?,
)

/** What an order's own page adds to its [OrderSummary]: the totals, who paid, and the lines. */
data class OrderDetail(
    val total: BigDecimal?,
    val itemsTotal: BigDecimal?,
    val discountsTotal: BigDecimal?,
    val shippingTotal: BigDecimal?,
    val paymentNames: String?,
    val lines: List<OrderLine>,
)

/** VTEX money is an integer number of cents. */
private fun JsonObject.cents(key: String): BigDecimal? = this[key]?.jsonPrimitive?.longOrNull?.let { BigDecimal.valueOf(it, 2) }

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** VTEX dates carry seven fractional digits and an offset: `2026-11-08T20:40:11.0000000+00:00`. */
private fun JsonObject.time(key: String): Instant? = text(key)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/** The history list, or null when the body is not one (a login page, an error). */
internal fun parseOrderList(body: String?): OrderPage? = try {
    val root = cartJson.parseToJsonElement(body ?: "").jsonObject
    val list = root["list"] as? JsonArray ?: throw IllegalArgumentException("no list")
    val orders = list.map { it.jsonObject }.mapNotNull { o ->
        OrderSummary(
            orderId = o.text("orderId") ?: return@mapNotNull null,
            placedAt = o.time("creationDate") ?: return@mapNotNull null,
            status = o.text("status") ?: "unknown",
            statusDescription = o.text("statusDescription"),
            lastChange = o.time("lastChange"),
            cartId = o.text("orderFormId"),
            hostname = o.text("hostname"),
            total = o.cents("totalValue"),
            paymentNames = o.text("paymentNames"),
            currency = o.text("currencyCode") ?: "COP",
        )
    }
    OrderPage(orders, (root["paging"] as? JsonObject)?.get("pages")?.jsonPrimitive?.intOrNull ?: 1)
} catch (_: Exception) {
    null
}

/**
 * One order's lines and totals, or null when the body is not an order. Reads only what is stored: the
 * same document carries the delivery address and the account holder's name and document, which are
 * never looked at.
 */
internal fun parseOrderDetail(body: String?): OrderDetail? = try {
    val root = cartJson.parseToJsonElement(body ?: "").jsonObject
    val items = root["items"] as? JsonArray ?: throw IllegalArgumentException("no items")
    val totals = (root["totals"] as? JsonArray).orEmpty().map { it.jsonObject }.associate { (it.text("id") ?: "") to it.cents("value") }
    val payments = ((root["paymentData"] as? JsonObject)?.get("transactions") as? JsonArray).orEmpty()
        .flatMap { (it.jsonObject["payments"] as? JsonArray).orEmpty() }
        .mapNotNull { it.jsonObject.text("paymentSystemName") }
        .distinct()
    OrderDetail(
        total = root.cents("value"),
        itemsTotal = totals["Items"],
        discountsTotal = totals["Discounts"],
        shippingTotal = totals["Shipping"],
        paymentNames = payments.joinToString(", ").takeIf { it.isNotEmpty() },
        lines = items.map { it.jsonObject }.mapIndexedNotNull { i, item ->
            val pid = item.text("productId") ?: return@mapIndexedNotNull null
            OrderLine(
                line = i,
                productId = pid,
                skuId = item.text("id"),
                ean = item.text("ean"),
                title = item.text("name") ?: pid,
                quantity = (item["quantity"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1),
                unitPrice = item.cents("sellingPrice") ?: item.cents("price"),
                storePrice = item.cents("price"),
                listPrice = item.cents("listPrice"),
                seller = item.text("seller"),
                measurementUnit = item.text("measurementUnit"),
                unitMultiplier = item["unitMultiplier"]?.jsonPrimitive?.doubleOrNull?.let { BigDecimal.valueOf(it) },
            )
        },
    )
} catch (_: Exception) {
    null
}

/**
 * Whether an order's detail has to be read: it is not stored yet, or the list says it changed since.
 * [stored] is the `last_change` kept from the previous read; [known] is whether the order is stored at
 * all (an order can be stored with no last change).
 */
internal fun needsDetail(order: OrderSummary, known: Boolean, stored: Instant?): Boolean = !known || order.lastChange != stored

/** A tracked row an order line could be: one of the Exito providers' rows with its product id or barcode. */
data class DealCandidate(val id: Long, val source: String, val sourceDealId: String, val matchKey: String?)

/** An Exito provider and the VTEX seller it is priced at (a store), if it is store-scoped. */
data class ExitoStore(val source: String, val seller: String?)

/**
 * The providers that sell from exito.com, from (id, baseUrl, regionSeller) of every configured one,
 * grocery stores first: an order is nearly always groceries, and the same product id exists in both.
 */
internal fun exitoStores(providers: List<Triple<String, String?, String?>>): List<ExitoStore> =
    providers.filter { (_, url, _) -> url != null && Regex("""^https?://(www\.)?exito\.com(/|$)""").containsMatchIn(url) }
        .map { (id, _, seller) -> ExitoStore(id, seller) }
        .sortedBy { if (it.seller != null) 0 else 1 }

/**
 * The tracked row an order line is, or null when the product is not tracked.
 *
 * The same product is a different row per store (Laureles and Colombia keep their own prices), so the
 * row of the store that sold the line wins. Next any Exito row with the product's own id, in the order
 * the providers are configured (Laureles first). Last a row sharing the barcode: Exito's non-grocery
 * search can list one product under another id (the 423 L Electrolux was ordered as 3231539 and is
 * tracked as 124173981). Never a row from another retailer -- its price is not what was on offer.
 */
internal fun pickDeal(line: OrderLine, candidates: List<DealCandidate>, stores: List<ExitoStore>): Long? {
    val order = stores.map { it.source }
    val sellerSource = line.seller?.let { s -> stores.firstOrNull { it.seller == s }?.source }
    val gtinKey = ProductIdentity.validGtin(line.ean)?.let { "gtin:$it" }
    return candidates
        .filter { it.source in order }
        .mapNotNull { c ->
            val rank = when {
                c.sourceDealId == line.productId && c.source == sellerSource -> 0
                c.sourceDealId == line.productId -> 1
                gtinKey != null && c.matchKey == gtinKey -> 2
                else -> return@mapNotNull null
            }
            Triple(rank, order.indexOf(c.source), c)
        }
        .minWithOrNull(compareBy({ it.first }, { it.second }, { it.third.id }))
        ?.third?.id
}

/** What the tracker had recorded for a row when an order was placed. */
data class TrackedAt(val price: BigDecimal?, val listPrice: BigDecimal?, val cardPrice: BigDecimal?, val usual: BigDecimal?)

/**
 * One line of a stored order, with what the tracker knew when it was placed: the item's usual price
 * (the median of the 30 days before) and the lowest price recorded up to that moment, card prices
 * included -- most of these orders were paid with the store's card, so the shelf price alone would
 * call nearly every line a record.
 *
 * [multiplier] is VTEX's `unitMultiplier`: 1 for a pack, 0.5 for fruit sold by the half kilo, where
 * [unitPrice] is for that half kilo and the tracked prices are per kilo.
 * [grocery] is false for a line the tracker follows outside the grocery aisles (a fridge, a TV).
 */
data class PlacedLine(
    val title: String,
    val quantity: Int,
    val unitPrice: BigDecimal?,
    val multiplier: BigDecimal = BigDecimal.ONE,
    val dealId: Long? = null,
    val usual: BigDecimal? = null,
    val lowest: BigDecimal? = null,
    val grocery: Boolean = true,
    /** The shelf price, what anyone pays without a card, as last read before the order. */
    val shelf: BigDecimal? = null,
)

data class PlacedOrder(
    val orderId: String,
    val placedAt: Instant,
    val total: BigDecimal?,
    val currency: String,
    val payment: String?,
    val lines: List<PlacedLine>,
)

/** The SQL around [ExitoOrderSync]; the rules above stay out of it. */
class ExitoOrderRepository(private val ds: DataSource) {

    /** Every stored order that was not cancelled, newest first, for the Groceries tab's Orders view. */
    fun placed(): List<PlacedOrder> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT o.order_id, o.placed_at, o.total, o.currency, o.payment_names,
                   i.title, i.quantity, i.unit_price, COALESCE(i.unit_multiplier, 1) AS multiplier,
                   i.deal_id, i.tracked_usual, i.tracked_price,
                   (d.id IS NULL OR d.grocery_group IS NOT NULL) AS grocery,
                   low.price AS lowest
            FROM exito_orders o
            JOIN exito_order_items i USING (order_id)
            LEFT JOIN deals d ON d.id = i.deal_id
            LEFT JOIN LATERAL (
                SELECT min(LEAST(po.price, COALESCE(po.card_price, po.price))) AS price FROM price_observations po
                WHERE po.deal_id = i.deal_id AND po.observed_at <= o.placed_at
            ) low ON TRUE
            WHERE o.status NOT ILIKE '%cancel%'
            ORDER BY o.placed_at DESC, o.order_id, i.line
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                val orders = LinkedHashMap<String, PlacedOrder>()
                while (rs.next()) {
                    val id = rs.getString("order_id")
                    val line = PlacedLine(
                        title = rs.getString("title"),
                        quantity = rs.getInt("quantity"),
                        unitPrice = rs.getBigDecimal("unit_price"),
                        multiplier = rs.getBigDecimal("multiplier"),
                        dealId = rs.getLong("deal_id").takeIf { !rs.wasNull() },
                        usual = rs.getBigDecimal("tracked_usual"),
                        lowest = rs.getBigDecimal("lowest"),
                        grocery = rs.getBoolean("grocery"),
                        shelf = rs.getBigDecimal("tracked_price"),
                    )
                    val order = orders[id] ?: PlacedOrder(
                        id, rs.getTimestamp("placed_at").toInstant(), rs.getBigDecimal("total"),
                        rs.getString("currency"), rs.getString("payment_names"), emptyList(),
                    )
                    orders[id] = order.copy(lines = order.lines + line)
                }
                orders.values.toList()
            }
        }
    }

    /** The stored `last_change` of each of [orderIds] that is stored at all. */
    fun lastChanges(orderIds: List<String>): Map<String, Instant?> {
        if (orderIds.isEmpty()) return emptyMap()
        return ds.connection.use { conn ->
            conn.prepareStatement("SELECT order_id, last_change FROM exito_orders WHERE order_id = ANY(?)").use { st ->
                st.setArray(1, conn.createArrayOf("text", orderIds.toTypedArray()))
                st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getTimestamp(2)?.toInstant()) } }
            }
        }
    }

    /** Rows of [sources] carrying [productId] or [gtinKey]. */
    fun candidates(sources: List<String>, productId: String, gtinKey: String?): List<DealCandidate> = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT id, source, source_deal_id, match_key FROM deals WHERE source = ANY(?) AND (source_deal_id = ? OR match_key = ?)",
        ).use { st ->
            st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
            st.setString(2, productId)
            st.setString(3, gtinKey)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(DealCandidate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))) }
            }
        }
    }

    /**
     * [dealId]'s latest reading at [at], and its usual price then: the median of its daily prices over the
     * 30 days before that day, the Groceries tab's definition, null with fewer than 3 days.
     */
    fun trackedAt(dealId: Long, at: Instant): TrackedAt = ds.connection.use { conn ->
        val ts = Timestamp.from(at)
        val reading = conn.prepareStatement(
            "SELECT price, list_price, card_price FROM price_observations WHERE deal_id = ? AND observed_at <= ? ORDER BY observed_at DESC LIMIT 1",
        ).use { st ->
            st.setLong(1, dealId); st.setTimestamp(2, ts)
            st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)) else null }
        }
        val usual = conn.prepareStatement(
            """
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY price), COUNT(*)
              FROM (SELECT observed_at::date, MIN(price) AS price
                      FROM price_observations
                     WHERE deal_id = ? AND observed_at >= ?::date - 30 AND observed_at < ?::date
                     GROUP BY 1) daily
            """.trimIndent(),
        ).use { st ->
            st.setLong(1, dealId); st.setTimestamp(2, ts); st.setTimestamp(3, ts)
            st.executeQuery().use { rs -> if (rs.next() && rs.getInt(2) >= 3) rs.getBigDecimal(1) else null }
        }
        TrackedAt(reading?.first, reading?.second, reading?.third, usual)
    }

    /** Stores an order and replaces its lines, in one transaction. */
    fun save(order: OrderSummary, detail: OrderDetail, lines: List<Triple<OrderLine, Long?, TrackedAt?>>) = ds.connection.use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """
                INSERT INTO exito_orders (order_id, placed_at, status, status_description, last_change, cart_id, hostname,
                                          currency, total, items_total, discounts_total, shipping_total, payment_names, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (order_id) DO UPDATE SET
                    status = EXCLUDED.status, status_description = EXCLUDED.status_description,
                    last_change = EXCLUDED.last_change, total = EXCLUDED.total, items_total = EXCLUDED.items_total,
                    discounts_total = EXCLUDED.discounts_total, shipping_total = EXCLUDED.shipping_total,
                    payment_names = EXCLUDED.payment_names, fetched_at = now()
                """.trimIndent(),
            ).use { st ->
                st.setString(1, order.orderId); st.setTimestamp(2, Timestamp.from(order.placedAt))
                st.setString(3, order.status); st.setString(4, order.statusDescription)
                st.setTimestamp(5, order.lastChange?.let(Timestamp::from))
                st.setString(6, order.cartId); st.setString(7, order.hostname); st.setString(8, order.currency)
                st.setBigDecimal(9, detail.total ?: order.total); st.setBigDecimal(10, detail.itemsTotal)
                st.setBigDecimal(11, detail.discountsTotal); st.setBigDecimal(12, detail.shippingTotal)
                st.setString(13, detail.paymentNames ?: order.paymentNames)
                st.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM exito_order_items WHERE order_id = ?").use { it.setString(1, order.orderId); it.executeUpdate() }
            conn.prepareStatement(
                """
                INSERT INTO exito_order_items (order_id, line, product_id, sku_id, ean, title, quantity, unit_price, store_price,
                                               list_price, seller, measurement_unit, unit_multiplier, deal_id,
                                               tracked_price, tracked_list_price, tracked_card_price, tracked_usual)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                for ((l, dealId, t) in lines) {
                    st.setString(1, order.orderId); st.setInt(2, l.line); st.setString(3, l.productId); st.setString(4, l.skuId)
                    st.setString(5, l.ean); st.setString(6, l.title); st.setInt(7, l.quantity); st.setBigDecimal(8, l.unitPrice)
                    st.setBigDecimal(9, l.storePrice); st.setBigDecimal(10, l.listPrice); st.setString(11, l.seller)
                    st.setString(12, l.measurementUnit); st.setBigDecimal(13, l.unitMultiplier)
                    if (dealId != null) st.setLong(14, dealId) else st.setNull(14, java.sql.Types.BIGINT)
                    st.setBigDecimal(15, t?.price); st.setBigDecimal(16, t?.listPrice)
                    st.setBigDecimal(17, t?.cardPrice); st.setBigDecimal(18, t?.usual)
                    st.addBatch()
                }
                st.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}

/**
 * Reads the account's Exito orders and stores the new and changed ones with their lines (V43).
 *
 * One request for the newest page of the history, then one per order that is new or whose `lastChange`
 * moved. A page with nothing to read ends the run, so a normal run is a single request; the first one
 * walks the whole history. An order whose detail cannot be read is left out and tried again next run.
 */
class ExitoOrderSync(
    private val http: PoliteHttp,
    private val settings: SettingsRepository,
    private val repo: ExitoOrderRepository,
    private val stores: List<ExitoStore>,
    private val ordersUrl: String = EXITO_ORDERS_URL,
    private val now: () -> Instant = Instant::now,
) {
    /** Runs once and returns a line for the log. */
    suspend fun sync(): String {
        val token = settings.get(SettingsRepository.EXITO_COOKIE)?.takeIf { it.isNotBlank() }
            ?: return "[exito-orders] skipped: no Exito login is saved"
        if (cookieExpired(token, now())) return "[exito-orders] skipped: the Exito login has expired (it is renewed daily)"
        val headers = mapOf("Cookie" to "$EXITO_COOKIE_NAME=$token", "Accept" to "application/json")
        var saved = 0
        var failed = 0
        var page = 1
        while (page <= MAX_PAGES) {
            val r = http.get("$ordersUrl?page=$page&per_page=$PER_PAGE", headers)
            val list = parseOrderList(r.body).takeIf { r.ok }
                ?: return "[exito-orders] the order history answered HTTP ${r.status}; $saved order(s) stored before that"
            val known = repo.lastChanges(list.orders.map { it.orderId })
            val toRead = list.orders.filter { needsDetail(it, it.orderId in known, known[it.orderId]) }
            for (order in toRead) {
                val detail = runCatching { http.get("$ordersUrl/${order.orderId}", headers) }.getOrNull()
                    ?.takeIf { it.ok }?.let { parseOrderDetail(it.body) }
                if (detail == null) { failed++; continue }
                runCatching { repo.save(order, detail, detail.lines.map(matched(order.placedAt))) }
                    .onSuccess { saved++ }.onFailure { failed++ }
            }
            if (toRead.isEmpty() || page >= list.pages) break
            page++
        }
        return "[exito-orders] $saved order(s) stored" + if (failed > 0) ", $failed could not be read and will be retried" else ""
    }

    private fun matched(placedAt: Instant): (OrderLine) -> Triple<OrderLine, Long?, TrackedAt?> = { line ->
        val gtinKey = ProductIdentity.validGtin(line.ean)?.let { "gtin:$it" }
        val dealId = pickDeal(line, repo.candidates(stores.map { it.source }, line.productId, gtinKey), stores)
        Triple(line, dealId, dealId?.let { repo.trackedAt(it, placedAt) })
    }

    private companion object {
        const val PER_PAGE = 15
        /** A stop for a list that never ends, not a limit on history: 40 pages is 600 orders. */
        const val MAX_PAGES = 40
    }
}
