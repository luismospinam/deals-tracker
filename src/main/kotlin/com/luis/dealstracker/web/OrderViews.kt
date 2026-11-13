package com.luis.dealstracker.web

import com.luis.dealstracker.exito.PlacedLine
import com.luis.dealstracker.exito.PlacedOrder
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import io.ktor.http.Parameters
import kotlinx.html.FlowContent
import kotlinx.html.MAIN
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Groceries > Orders: what was paid on each Exito order against what the tracker knew that day.
 *
 * The comparison is to the item's own usual price when the order was placed (the median of the 30
 * days before, stored with the line by `ExitoOrderSync`) and to the lowest price recorded up to
 * then, with or without a card. An order from before the tracker read that item has nothing to be compared with, and says so
 * rather than counting as a saving of zero.
 */
internal const val ORDERS_PATH = "/groceries/orders"

/** How the price paid stood against the item's history on the day. */
internal enum class PaidStanding { LOWEST, UNDER, SAME, OVER }

/** What the whole line cost. */
internal fun PlacedLine.cost(): BigDecimal? = unitPrice?.multiply(quantity.toBigDecimal())

/** The usual price of one unit as it was sold: per kilo prices scaled to the half kilo on the line. */
internal fun PlacedLine.usualEach(): BigDecimal? = usual?.multiply(multiplier)?.setScale(2, RoundingMode.HALF_UP)

internal fun PlacedLine.lowestEach(): BigDecimal? = lowest?.multiply(multiplier)?.setScale(2, RoundingMode.HALF_UP)

internal fun PlacedLine.shelfEach(): BigDecimal? = shelf?.multiply(multiplier)?.setScale(2, RoundingMode.HALF_UP)

/**
 * What the same quantity cost on the shelf that day, without a card. A line with no shelf reading is
 * taken at what was paid, so its whole saving counts as timing rather than as a card's doing.
 */
internal fun PlacedLine.shelfCost(): BigDecimal? = (shelfEach() ?: unitPrice)?.multiply(quantity.toBigDecimal())

/** What the same quantity would have cost at the usual price; null when there is nothing to compare. */
internal fun PlacedLine.usualCost(): BigDecimal? =
    if (unitPrice == null) null else usualEach()?.takeIf { it.signum() > 0 }?.multiply(quantity.toBigDecimal())

/** Whole percent under the usual price; negative when more was paid. */
internal fun PlacedLine.percentUnder(): Int? {
    val usual = usualEach()?.takeIf { it.signum() > 0 } ?: return null
    val paid = unitPrice ?: return null
    return usual.subtract(paid).multiply(BigDecimal(100)).divide(usual, 0, RoundingMode.HALF_UP).toInt()
}

/**
 * LOWEST when the price paid was at or under the lowest price recorded until then, card prices
 * included. Null without a usual price.
 */
internal fun PlacedLine.standing(): PaidStanding? {
    val pct = percentUnder() ?: return null
    val low = lowestEach()
    return when {
        pct < 0 -> PaidStanding.OVER
        low != null && unitPrice!! <= low -> PaidStanding.LOWEST
        pct > 0 -> PaidStanding.UNDER
        else -> PaidStanding.SAME
    }
}

/**
 * The lines that could be compared, added up. [paid] and [usual] cover those lines only, so [saved]
 * is never flattered by an item with no history.
 */
internal data class PaidStats(
    val lines: Int,
    val compared: Int,
    val paid: BigDecimal,
    val usual: BigDecimal,
    val atLowest: Int,
    val over: Int,
    val overCost: BigDecimal,
    /** What those lines cost on the shelf, without a card: between [usual] and [paid] when both savings are real. */
    val shelf: BigDecimal = paid,
) {
    /** Paid under the shelf price: the card (or a coupon at checkout). Negative if the shelf price rose before checkout. */
    val byCard: BigDecimal get() = shelf.subtract(paid)

    /** The shelf price was under usual that day: saved by when it was bought, whatever paid for it. */
    val byTiming: BigDecimal get() = usual.subtract(shelf)

    val saved: BigDecimal get() = usual.subtract(paid)
    val percent: Int get() = if (usual.signum() == 0) 0 else saved.multiply(BigDecimal(100)).divide(usual, 0, RoundingMode.HALF_UP).toInt()
}

internal fun paidStats(lines: List<PlacedLine>): PaidStats {
    val compared = lines.filter { it.usualCost() != null }
    val over = compared.filter { it.standing() == PaidStanding.OVER }
    return PaidStats(
        lines = lines.size,
        compared = compared.size,
        paid = compared.sumOf { it.cost()!! },
        usual = compared.sumOf { it.usualCost()!! },
        atLowest = compared.count { it.standing() == PaidStanding.LOWEST },
        over = over.size,
        overCost = over.sumOf { it.cost()!!.subtract(it.usualCost()!!) },
        shelf = compared.sumOf { it.shelfCost()!! },
    )
}

/** Orders with the lines that are not groceries (a fridge bought on the same account) left out. */
internal fun groceryOrders(orders: List<PlacedOrder>): List<PlacedOrder> =
    orders.map { it.copy(lines = it.lines.filter(PlacedLine::grocery)) }.filter { it.lines.isNotEmpty() }

private fun dayOf(order: PlacedOrder, zone: ZoneId, pattern: String): String =
    order.placedAt.atZone(zone).format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(tr("en", "es"))))

internal fun MAIN.groceryOrdersPage(placed: List<PlacedOrder>, pinnedCount: Int, zone: ZoneId = ZoneId.systemDefault()) {
    groceryScopes(GroceryControls.from(Parameters.Empty), pinnedCount, ordersOn = true)

    val orders = groceryOrders(placed)
    if (orders.isEmpty()) {
        div("empty") {
            +tr(
                "No orders read yet. They come from your Exito account's order history once the Exito login is saved: ",
                "Aún no se han leído pedidos. Vienen del historial de pedidos de tu cuenta Exito cuando se guarda el inicio de sesión: ",
            )
            code { +"./dt run --args=\"--exito-session --renew --save\"" }
        }
        return
    }

    p("tracked-intro") {
        +tr(
            "Your Exito orders, each item against its own usual price on the day you bought it (the median of the 30 days before) and the lowest price recorded until then, card prices included. An item the tracker had not read yet has no comparison.",
            "Tus pedidos de Exito, cada producto frente a su propio precio habitual el día de la compra (la mediana de los 30 días anteriores) y el precio más bajo registrado hasta entonces, incluidos los precios con tarjeta. Un producto que aún no se había leído no tiene comparación.",
        )
    }

    val currency = orders.first().currency
    val comparable = orders.filter { paidStats(it.lines).compared > 0 }
    if (comparable.isNotEmpty()) {
        val all = paidStats(comparable.flatMap { it.lines })
        div("shop-plan order-sum") {
            span("shop-plan-h") { +tr("Since ${dayOf(comparable.last(), zone, "d MMM yyyy")}", "Desde el ${dayOf(comparable.last(), zone, "d MMM yyyy")}") }
            span { +tr("${comparable.size} order(s)", "${comparable.size} pedido(s)") }
            span {
                +tr("paid ", "pagaste ")
                span("plan-total") { +formatMoney(all.paid, currency) }
                +tr(" for items usually ", " por productos que suelen costar ")
                span("plan-total") { +formatMoney(all.usual, currency) }
            }
            when {
                all.saved.signum() > 0 -> span("plan-note") { +tr("saved ${formatMoney(all.saved, currency)} (${all.percent}%)", "ahorraste ${formatMoney(all.saved, currency)} (${all.percent}%)") }
                all.saved.signum() < 0 -> span("plan-note order-over") { +tr("${formatMoney(all.saved.negate(), currency)} over usual", "${formatMoney(all.saved.negate(), currency)} sobre lo habitual") }
            }
            if (all.saved.signum() > 0) {
                span("plan-note muted") {
                    title = tr(
                        "Timing: the shelf price was under usual that day. Card: you paid under the shelf price.",
                        "Momento: el precio en góndola estaba bajo lo habitual ese día. Tarjeta: pagaste menos que el precio en góndola.",
                    )
                    +tr(
                        "${formatMoney(all.byTiming, currency)} by timing, ${formatMoney(all.byCard, currency)} by card",
                        "${formatMoney(all.byTiming, currency)} por el momento, ${formatMoney(all.byCard, currency)} por la tarjeta",
                    )
                }
            }
            span("plan-note muted") { +tr("${all.atLowest} of ${all.compared} items at their lowest", "${all.atLowest} de ${all.compared} productos en su mínimo") }
            if (all.over > 0) {
                span("plan-note muted") {
                    +tr("${all.over} over usual, ${formatMoney(all.overCost, currency)} more", "${all.over} sobre lo habitual, ${formatMoney(all.overCost, currency)} de más")
                }
            }
            val earlier = orders.size - comparable.size
            if (earlier > 0) {
                span("plan-note muted") { +tr("$earlier earlier order(s) have no price history", "$earlier pedido(s) anteriores no tienen historial de precios") }
            }
        }
    }

    div("aisles") {
        orders.forEachIndexed { i, order ->
            val stats = paidStats(order.lines)
            details("aisle order") {
                // Fixed: these are not the Pinned aisles, so the script that remembers those leaves them be.
                attributes["data-fixed"] = "1"
                if (i == 0) attributes["open"] = "open"
                summary {
                    span("aisle-name") { +dayOf(order, zone, "EEE d MMM yyyy") }
                    span("aisle-n") { +tr("${order.lines.size} item(s)", "${order.lines.size} producto(s)") }
                    order.total?.let { span("order-total") { +formatMoney(it, order.currency) } }
                    order.payment?.let { span("order-pay") { +it } }
                    span("aisle-chips") {
                        when {
                            stats.compared == 0 -> span("order-none") { +tr("no price history", "sin historial de precios") }
                            stats.saved.signum() > 0 -> span("chip chip-drop") {
                                title = tr(
                                    "Against the usual price of the ${stats.compared} item(s) that had one: ${formatMoney(stats.byTiming, order.currency)} by timing, ${formatMoney(stats.byCard, order.currency)} by card",
                                    "Frente al precio habitual de los ${stats.compared} producto(s) que lo tenían: ${formatMoney(stats.byTiming, order.currency)} por el momento, ${formatMoney(stats.byCard, order.currency)} por la tarjeta",
                                )
                                +tr("saved ${formatMoney(stats.saved, order.currency)} · ${stats.percent}%", "ahorro ${formatMoney(stats.saved, order.currency)} · ${stats.percent}%")
                            }
                            stats.saved.signum() < 0 -> span("chip chip-over") {
                                +tr("${formatMoney(stats.saved.negate(), order.currency)} over usual", "${formatMoney(stats.saved.negate(), order.currency)} sobre lo habitual")
                            }
                            else -> span("order-none") { +tr("at the usual price", "al precio habitual") }
                        }
                        if (stats.atLowest > 0) {
                            span("chip stand-lowest-chip") {
                                title = tr("Bought at or under the lowest price recorded until then", "Comprados al precio más bajo registrado hasta entonces, o por debajo")
                                +tr("${stats.atLowest} at lowest", "${stats.atLowest} en mínimo")
                            }
                        }
                    }
                }
                orderTable(order)
            }
        }
    }
}

private fun FlowContent.orderTable(order: PlacedOrder) {
    fun money(v: BigDecimal?) = v?.let { formatMoney(it, order.currency) } ?: "—"
    div("order-scroll") {
        table("order-table") {
            caption("sr-only") { +tr("Items in this order", "Productos de este pedido") }
            thead {
                tr {
                    th(scope = ThScope.col) { +tr("Item", "Producto") }
                    th(scope = ThScope.col, classes = "num") { +tr("Paid", "Pagaste") }
                    th(scope = ThScope.col, classes = "num col-shelf") {
                        title = tr("The price without a card, as last read before the order", "El precio sin tarjeta, según la última lectura antes del pedido")
                        +tr("Shelf then", "Góndola")
                    }
                    th(scope = ThScope.col, classes = "num") {
                        title = tr("The median of its daily prices over the 30 days before the order", "La mediana de sus precios diarios en los 30 días anteriores al pedido")
                        +tr("Usual then", "Habitual")
                    }
                    th(scope = ThScope.col, classes = "num col-lowest") {
                        title = tr("The lowest price recorded up to the order, with or without a card", "El precio más bajo registrado hasta el pedido, con o sin tarjeta")
                        +tr("Lowest then", "Mínimo")
                    }
                    th(scope = ThScope.col, classes = "num") { +tr("vs usual", "vs habitual") }
                }
            }
            tbody {
                order.lines.forEach { line ->
                    val standing = line.standing()
                    tr(if (standing == null) "dim" else null) {
                        td("order-item") {
                            if (line.quantity > 1) span("order-qty") { +"${line.quantity}× " }
                            if (line.dealId != null) a(href = "/deal/${line.dealId}") { +line.title } else +line.title
                            if (standing == PaidStanding.LOWEST) {
                                +" "
                                // The word is for wide screens; on a phone the arrow alone keeps the row to its title's height.
                                span("lowbadge lowbadge-near order-low") {
                                    title = tr("Bought at or under the lowest price recorded until then", "Comprado al precio más bajo registrado hasta entonces, o por debajo")
                                    +"↓"
                                    span("order-low-word") { +tr(" lowest", " mínimo") }
                                }
                            }
                        }
                        td("num") {
                            +money(line.unitPrice)
                            // The phone has no Shelf column, so the shelf price sits here when it differs.
                            val shelf = line.shelfEach()
                            if (shelf != null && line.unitPrice != null && shelf.compareTo(line.unitPrice) != 0) {
                                span("order-diff order-shelf-sub") { +tr("shelf ${money(shelf)}", "góndola ${money(shelf)}") }
                            }
                        }
                        td("num col-shelf") { +money(line.shelfEach()) }
                        td("num") { +money(line.usualEach()) }
                        td("num col-lowest") { +money(line.lowestEach()) }
                        td("num") {
                            val pct = line.percentUnder()
                            val diff = line.usualCost()?.subtract(line.cost())
                            when {
                                pct == null || diff == null -> +"—"
                                pct > 0 -> {
                                    span("chg chg-down") { +"−$pct%" }
                                    span("order-diff") { +tr("saved ${money(diff)}", "ahorro ${money(diff)}") }
                                }
                                pct < 0 -> {
                                    span("chg chg-up") { +"+${-pct}%" }
                                    span("order-diff") { +tr("${money(diff.negate())} more", "${money(diff.negate())} de más") }
                                }
                                else -> span("order-diff") { +tr("usual price", "precio habitual") }
                            }
                        }
                    }
                }
            }
        }
    }
}
