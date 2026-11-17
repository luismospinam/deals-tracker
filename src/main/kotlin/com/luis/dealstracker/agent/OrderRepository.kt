package com.luis.dealstracker.agent

import com.luis.dealstracker.exito.CartStore
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

/** A cart the order agent started (V47). [cartId] is null until its first add went through. */
data class AgentOrder(val id: Long, val runId: Long?, val createdAt: Instant, val store: CartStore, val payWith: String, val cartId: String?)

data class AgentOrderItem(
    val dealId: Long?, val productId: String, val title: String,
    val price: BigDecimal?, val usual: BigDecimal?, val cardPrice: BigDecimal?, val reason: String,
    /** "agent", or "you" for a line the operator added with the 🛒 button ([OrderRepository.openForStore]). */
    val addedBy: String = AGENT_ADDED,
) {
    val byOperator: Boolean get() = addedBy == OPERATOR_ADDED
}

const val AGENT_ADDED = "agent"
const val OPERATOR_ADDED = "you"

/** One order-agent run as Pinned shows it: how it went, its reply, its cart if any, and the operator's note. */
data class OrderRun(
    val runId: Long,
    val startedAt: Instant,
    val status: String,
    val summary: String?,
    val error: String?,
    val feedback: String?,
    val order: AgentOrder?,
    val items: List<AgentOrderItem>,
    /** Whether an Exito order was placed from this cart after it was filled. */
    val ordered: Boolean,
    /** What that order actually held, as (product id, title): the operator's changes on Exito's site included. */
    val orderedLines: List<Pair<String, String>> = emptyList(),
)

/** One of the operator's notes on an order run: when, the cart's store if there was one, the text. */
data class OrderNote(val at: Instant, val store: CartStore?, val text: String)

/**
 * Every note as the agent reads it, oldest first so the newest has the last word. Cut from the oldest end past
 * [maxChars], saying so, so a long history never crowds out the rest of the context.
 */
internal fun notesText(notes: List<OrderNote>, zone: java.time.ZoneId, maxChars: Int = 20_000): String {
    if (notes.isEmpty()) return "(no notes yet)"
    val lines = notes.sortedBy { it.at }.map { n ->
        val day = n.at.atZone(zone).toLocalDate()
        "$day (${n.store?.let { "${it.key} cart" } ?: "no cart"}): ${n.text.replace(Regex("\\s+"), " ").trim()}"
    }
    val kept = ArrayDeque<String>()
    var size = 0
    for (l in lines.asReversed()) {
        if (size + l.length + 1 > maxChars && kept.isNotEmpty()) break
        kept.addFirst(l); size += l.length + 1
    }
    val dropped = lines.size - kept.size
    return (if (dropped > 0) "($dropped oldest notes left out for length)\n" else "") + kept.joinToString("\n")
}

/** Exito's own service lines ("Preparación Del Pedido", the order's preparation fee): never a product to buy. */
internal fun isServiceLine(productId: String, title: String): Boolean =
    productId == "492664" || title.trim().startsWith("Preparación Del Pedido", ignoreCase = true)

/**
 * How the order placed from an agent cart differs from what the cart held: products the operator took out before
 * paying, and products they put in that the cart did not have (service lines aside). By product id.
 */
internal fun orderChanges(items: List<AgentOrderItem>, ordered: List<Pair<String, String>>): Pair<List<String>, List<String>> {
    val boughtIds = ordered.map { it.first }.toSet()
    val cartIds = items.map { it.productId }.toSet()
    val removed = items.filter { it.productId !in boughtIds }.map { it.title }
    val added = ordered.filter { (pid, title) -> pid !in cartIds && !isServiceLine(pid, title) }.map { it.second }
    return removed to added
}

class OrderRepository(private val ds: DataSource) {

    /** The run's cart, starting it at [store] when it has none. A run has one cart; a second call returns the first. */
    fun begin(runId: Long, store: CartStore, payWith: String): AgentOrder = ds.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO agent_orders (run_id, store, pay_with) VALUES (?, ?, ?) ON CONFLICT (run_id) DO NOTHING",
        ).use { st ->
            st.setLong(1, runId); st.setString(2, store.key); st.setString(3, payWith)
            st.executeUpdate()
        }
        forRun(conn, runId)!!
    }

    fun forRun(runId: Long): AgentOrder? = ds.connection.use { forRun(it, runId) }

    private fun forRun(conn: Connection, runId: Long): AgentOrder? =
        conn.prepareStatement("SELECT id, run_id, created_at, store, pay_with, cart_id FROM agent_orders WHERE run_id = ?").use { st ->
            st.setLong(1, runId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else AgentOrder(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), CartStore.of(rs.getString(4)), rs.getString(5), rs.getString(6))
            }
        }

    fun byId(id: Long): AgentOrder? = ds.connection.use { conn ->
        conn.prepareStatement("SELECT id, run_id, created_at, store, pay_with, cart_id FROM agent_orders WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else AgentOrder(rs.getLong(1), rs.getLong(2).takeIf { !rs.wasNull() }, rs.getTimestamp(3).toInstant(), CartStore.of(rs.getString(4)), rs.getString(5), rs.getString(6))
            }
        }
    }

    fun setCart(orderId: Long, cartId: String) = ds.connection.use { conn ->
        conn.prepareStatement("UPDATE agent_orders SET cart_id = ? WHERE id = ?").use { st ->
            st.setString(1, cartId); st.setLong(2, orderId); st.executeUpdate()
        }
    }

    fun addItem(orderId: Long, item: AgentOrderItem) = ds.connection.use { conn ->
        conn.prepareStatement(
            "INSERT INTO agent_order_items (order_id, deal_id, product_id, title, price, usual, card_price, reason, added_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, orderId)
            if (item.dealId != null) st.setLong(2, item.dealId) else st.setNull(2, java.sql.Types.BIGINT)
            st.setString(3, item.productId); st.setString(4, item.title)
            st.setBigDecimal(5, item.price); st.setBigDecimal(6, item.usual); st.setBigDecimal(7, item.cardPrice)
            st.setString(8, item.reason.take(600))
            st.setString(9, item.addedBy)
            st.executeUpdate()
        }
    }

    fun items(orderId: Long): List<AgentOrderItem> = ds.connection.use { items(it, orderId) }

    private fun items(conn: Connection, orderId: Long): List<AgentOrderItem> =
        conn.prepareStatement(
            "SELECT deal_id, product_id, title, price, usual, card_price, reason, added_by FROM agent_order_items WHERE order_id = ? ORDER BY id",
        ).use { st ->
            st.setLong(1, orderId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AgentOrderItem(
                                rs.getLong(1).takeIf { !rs.wasNull() }, rs.getString(2), rs.getString(3),
                                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8),
                            ),
                        )
                    }
                }
            }
        }

    /** The order agent's last [limit] runs, newest first, each with its cart, items and note. */
    fun recentRuns(limit: Int = 6, days: Int = 60): List<OrderRun> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT r.id, r.started_at, r.status, r.summary, r.error, r.feedback,
                   o.id, o.created_at, o.store, o.pay_with, o.cart_id,
                   EXISTS (SELECT 1 FROM exito_orders e WHERE o.cart_id IS NOT NULL AND e.cart_id = o.cart_id
                                                         AND e.placed_at >= o.created_at AND e.status <> 'canceled') AS ordered
              FROM agent_runs r
              LEFT JOIN agent_orders o ON o.run_id = r.id
             WHERE r.agent = ? AND r.started_at >= now() - make_interval(days => ?)
             ORDER BY r.started_at DESC LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, ORDER_AGENT); st.setInt(2, days); st.setInt(3, limit)
            val rows = st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val orderId = rs.getLong(7).takeIf { !rs.wasNull() }
                        val order = orderId?.let {
                            AgentOrder(it, rs.getLong(1), rs.getTimestamp(8).toInstant(), CartStore.of(rs.getString(9)), rs.getString(10), rs.getString(11))
                        }
                        add(
                            OrderRun(
                                rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), order, emptyList(), rs.getBoolean(12),
                            ),
                        )
                    }
                }
            }
            rows.map { r ->
                val o = r.order ?: return@map r
                r.copy(items = items(conn, o.id), orderedLines = if (r.ordered && o.cartId != null) orderedLines(conn, o.cartId, o.createdAt) else emptyList())
            }
        }
    }

    /**
     * The agent's cart the 🛒 button adds to at [store]: the newest one filled at that store since the start of
     * today ([since]), that has a cart at Exito and has not been ordered. Null otherwise, and the 🛒 button uses the
     * operator's own cart as before.
     */
    fun openForStore(store: CartStore, since: Instant): AgentOrder? = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT o.id, o.run_id, o.created_at, o.store, o.pay_with, o.cart_id FROM agent_orders o
             WHERE o.store = ? AND o.created_at >= ? AND o.cart_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM exito_orders e WHERE e.cart_id = o.cart_id AND e.placed_at >= o.created_at AND e.status <> 'canceled')
             ORDER BY o.created_at DESC LIMIT 1
            """.trimIndent(),
        ).use { st ->
            st.setString(1, store.key); st.setTimestamp(2, java.sql.Timestamp.from(since))
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else AgentOrder(rs.getLong(1), rs.getLong(2).takeIf { !rs.wasNull() }, rs.getTimestamp(3).toInstant(), CartStore.of(rs.getString(4)), rs.getString(5), rs.getString(6))
            }
        }
    }

    /** The lines of the first order placed from [cartId] after [since], as (product id, title). */
    private fun orderedLines(conn: Connection, cartId: String, since: Instant): List<Pair<String, String>> =
        conn.prepareStatement(
            """
            SELECT i.product_id, i.title FROM exito_order_items i
             WHERE i.order_id = (SELECT e.order_id FROM exito_orders e WHERE e.cart_id = ? AND e.placed_at >= ? AND e.status <> 'canceled'
                                  ORDER BY e.placed_at LIMIT 1)
             ORDER BY i.line
            """.trimIndent(),
        ).use { st ->
            st.setString(1, cartId); st.setTimestamp(2, java.sql.Timestamp.from(since))
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }

    /** Every note the operator saved on an order run, whatever its age. */
    fun allNotes(): List<OrderNote> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT r.started_at, o.store, r.feedback FROM agent_runs r LEFT JOIN agent_orders o ON o.run_id = r.id
             WHERE r.agent = ? AND r.feedback IS NOT NULL AND r.feedback <> '' ORDER BY r.started_at
            """.trimIndent(),
        ).use { st ->
            st.setString(1, ORDER_AGENT)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(OrderNote(rs.getTimestamp(1).toInstant(), rs.getString(2)?.let { CartStore.of(it) }, rs.getString(3))) }
            }
        }
    }

    /** Saves the operator's note on [runId], blank clearing it. False when the run is not the order agent's. */
    fun saveFeedback(runId: Long, text: String?): Boolean = ds.connection.use { conn ->
        conn.prepareStatement(
            "UPDATE agent_runs SET feedback = ?, feedback_at = CASE WHEN ?::text IS NULL THEN NULL ELSE now() END WHERE id = ? AND agent = ?",
        ).use { st ->
            val note = text?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }
            st.setString(1, note); st.setString(2, note); st.setLong(3, runId); st.setString(4, ORDER_AGENT)
            st.executeUpdate() == 1
        }
    }

    // --- products across the two Exitos --------------------------------------------------------------

    /** The Exito product id behind each of [dealIds] (the same id at both stores). */
    fun productIds(dealIds: Collection<Long>): Map<Long, String> = if (dealIds.isEmpty()) emptyMap() else ds.connection.use { conn ->
        conn.prepareStatement("SELECT id, source_deal_id FROM deals WHERE id = ANY(?)").use { st ->
            st.setArray(1, conn.createArrayOf("bigint", dealIds.toTypedArray()))
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getString(2)) } }
        }
    }

    /** The active rows of [productIds] at [sources]: each store's copy. */
    fun copies(productIds: Collection<String>, sources: Collection<String>): List<Long> =
        if (productIds.isEmpty() || sources.isEmpty()) emptyList() else ds.connection.use { conn ->
            conn.prepareStatement("SELECT id FROM deals WHERE source = ANY(?) AND source_deal_id = ANY(?) AND is_active").use { st ->
                st.setArray(1, conn.createArrayOf("text", sources.toTypedArray()))
                st.setArray(2, conn.createArrayOf("text", productIds.toTypedArray()))
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    /**
     * The last day the shelf or card promotions on each row's latest reading run, from the store's own
     * promotion names (V18). Rows with no dated promotion are absent.
     */
    fun promoEnds(dealIds: Collection<Long>): Map<Long, LocalDate> = if (dealIds.isEmpty()) emptyMap() else ds.connection.use { conn ->
        conn.prepareStatement(
            """
            WITH latest AS (
                SELECT DISTINCT ON (deal_id) deal_id, id FROM price_observations
                 WHERE deal_id = ANY(?) AND observed_at > now() - interval '36 hours'
                 ORDER BY deal_id, observed_at DESC
            )
            SELECT l.deal_id, min(p.valid_to) FROM latest l
              JOIN observation_promotions op ON op.observation_id = l.id
              JOIN promotions p ON p.id = op.promotion_id
             WHERE p.kind IN ('SHELF', 'CARD') AND p.valid_to IS NOT NULL
             GROUP BY 1
            """.trimIndent(),
        ).use { st ->
            st.setArray(1, conn.createArrayOf("bigint", dealIds.toTypedArray()))
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getDate(2).toLocalDate()) } }
        }
    }
}
