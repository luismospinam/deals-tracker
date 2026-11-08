package com.luis.dealstracker.agent

import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.exito.CartContents
import com.luis.dealstracker.exito.CartLine
import com.luis.dealstracker.exito.EXITO_CART_SOURCE
import com.luis.dealstracker.exito.ExitoCart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.sql.DataSource

/** What the cart held at the last read: its id and each product's quantity. */
@Serializable
internal data class CartSnapshot(val cart: String, val lines: Map<String, Int>)

/** A product's quantity moving between two reads: positive was added (bought), negative taken out. */
internal data class CartChange(val line: CartLine, val delta: Int)

/**
 * What changed between [before] and [now]. A different cart id means the old one was closed by an order
 * (or replaced), so the new cart starts from nothing: everything in it is new. A product gone from the
 * cart is a change of minus its old quantity.
 */
internal fun cartChanges(before: CartSnapshot?, now: CartContents): List<CartChange> {
    val prev = before?.takeIf { it.cart == now.cartId }?.lines.orEmpty()
    val present = now.lines.associateBy { it.productId }
    val changed = now.lines.mapNotNull { l -> (l.quantity - (prev[l.productId] ?: 0)).takeIf { it != 0 }?.let { CartChange(l, it) } }
    val removed = prev.filterKeys { it !in present }.map { (pid, q) -> CartChange(CartLine(pid, pid, 0, null), -q) }
    return changed + removed
}

/**
 * The purchase log's Exito side. Adding to the cart is when the operator buys, so a product that appears
 * or grows in the tracker's cart is logged as bought; one taken out again before the order is un-logged
 * (only purchases from the same, still open cart). Items added in Exito's phone app land in that app's own
 * cart, which cannot be read (see [ExitoCart.add]), so those are not seen.
 */
class PurchaseLog(
    private val ds: DataSource,
    private val cart: ExitoCart,
    private val settings: SettingsRepository,
) {
    /** Reads the cart and logs what changed since the last read. Returns a log line, or null for no change. */
    suspend fun syncExitoCart(): String? {
        val now = cart.contents() ?: return null
        val before = settings.get(SettingsRepository.EXITO_CART_SNAPSHOT)?.let { runCatching { json.decodeFromString<CartSnapshot>(it) }.getOrNull() }
        val changes = cartChanges(before, now)
        changes.forEach { c -> if (c.delta > 0) record(c.line, c.delta, now.cartId) else unrecord(c.line.productId, -c.delta, now.cartId) }
        settings.set(mapOf(SettingsRepository.EXITO_CART_SNAPSHOT to json.encodeToString(CartSnapshot.serializer(), CartSnapshot(now.cartId, now.lines.associate { it.productId to it.quantity }))))
        if (changes.isEmpty()) return null
        return "[purchases] exito cart: " + changes.joinToString { "${if (it.delta > 0) "+" else ""}${it.delta} ${it.line.name.take(40)}" }
    }

    private fun record(line: CartLine, quantity: Int, cartId: String) = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO purchases (deal_id, source, product_id, title, quantity, unit_price, currency, origin, cart_id)
            SELECT (SELECT id FROM deals WHERE source = ? AND source_deal_id = ? ORDER BY id LIMIT 1), ?, ?, ?, ?, ?, 'COP', 'exito_cart', ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, EXITO_CART_SOURCE); st.setString(2, line.productId)
            st.setString(3, EXITO_CART_SOURCE); st.setString(4, line.productId); st.setString(5, line.name)
            st.setInt(6, quantity); st.setBigDecimal(7, line.unitPrice); st.setString(8, cartId)
            st.executeUpdate()
        }
    }

    /** Takes [quantity] back off the newest purchases of [productId] from [cartId]. */
    private fun unrecord(productId: String, quantity: Int, cartId: String) = ds.connection.use { conn ->
        var left = quantity
        val rows = conn.prepareStatement(
            "SELECT id, quantity FROM purchases WHERE source = ? AND product_id = ? AND cart_id = ? ORDER BY bought_at DESC",
        ).use { st ->
            st.setString(1, EXITO_CART_SOURCE); st.setString(2, productId); st.setString(3, cartId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1) to rs.getInt(2)) } }
        }
        for ((id, q) in rows) {
            if (left <= 0) break
            if (q <= left) {
                conn.prepareStatement("DELETE FROM purchases WHERE id = ?").use { it.setLong(1, id); it.executeUpdate() }
            } else {
                conn.prepareStatement("UPDATE purchases SET quantity = ? WHERE id = ?").use { it.setInt(1, q - left); it.setLong(2, id); it.executeUpdate() }
            }
            left -= q
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
