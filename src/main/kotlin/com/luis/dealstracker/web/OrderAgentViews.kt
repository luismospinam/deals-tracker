package com.luis.dealstracker.web

import com.luis.dealstracker.agent.AgentOrderItem
import com.luis.dealstracker.agent.OrderRun
import com.luis.dealstracker.agent.cartTotal
import com.luis.dealstracker.agent.payLabel
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.formatMoney
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.unsafe
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.textArea
import kotlinx.html.ul
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where Run now posts. */
internal const val ORDER_RUN_PATH = "/order-agent/run"

/** The tracker's door to an agent cart: pickup is checked first, then the browser goes on to Exito. */
internal fun orderOpenPath(orderId: Long) = "/order-agent/orders/$orderId/open"

internal fun orderFeedbackPath(runId: Long) = "/order-agent/runs/$runId/feedback"

/** What the panel needs: the agent's recent runs (newest first), whether one is running, when the next is due. */
internal data class OrderPanelModel(
    val runs: List<OrderRun>,
    val running: Boolean,
    val nextRun: Instant?,
    val now: Instant = Instant.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

private fun cop(v: BigDecimal) = formatMoney(v, "COP")

/** "Tue 17 Nov, 08:47" in the reader's language; just the time when it is today. */
internal fun runWhen(at: Instant, now: Instant, zone: ZoneId): String {
    val locale = if (com.luis.dealstracker.i18n.I18n.lang == Lang.ES) Locale.forLanguageTag("es") else Locale.ENGLISH
    val local = at.atZone(zone)
    val time = "%02d:%02d".format(local.hour, local.minute)
    return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) tr("today, $time", "hoy, $time")
    else local.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) + ", " + time
}

/** The saving against usual prices of [items], each at what it costs paid as the cart is. Never negative. */
internal fun cartSaving(items: List<AgentOrderItem>): BigDecimal =
    items.fold(BigDecimal.ZERO) { s, i -> s + ((i.usual ?: i.price ?: BigDecimal.ZERO) - (i.cardPrice ?: i.price ?: BigDecimal.ZERO)) }
        .max(BigDecimal.ZERO)

/**
 * The agent's reply as the panel shows it: plain text, one line per line. Its bold and heading marks are dropped
 * (`**Fruit:**` read as literal asterisks, 2026-11-17), and list lines start with a bullet.
 */
internal fun plainSummary(text: String): String =
    text.replace("**", "").replace("__", "")
        .lines().map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim() }
        .map { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

/** What paying with the cart's card takes off, against the shelf prices of the same items. Zero for cash. */
internal fun cardSaving(items: List<AgentOrderItem>): BigDecimal =
    items.fold(BigDecimal.ZERO) { s, i -> s + ((i.price ?: BigDecimal.ZERO) - (i.cardPrice ?: i.price ?: BigDecimal.ZERO)) }
        .max(BigDecimal.ZERO)

/**
 * The payment note at the end of the explanation: the card the cart was built for and what it saves here, said
 * where it cannot be missed. Card prices only apply when the card pays, so this is the one instruction that
 * changes what the cart costs.
 */
private fun FlowContent.payNote(payWith: String, items: List<AgentOrderItem>) {
    val saving = cardSaving(items)
    div("oa-pay-note") {
        attributes["role"] = "note"
        span("oa-pay-icon") { attributes["aria-hidden"] = "true"; +"💳" }
        if (payWith == "cash" || saving.signum() == 0) {
            span { +tr("Pay any way you like: no card has a better price on these items.", "Paga como prefieras: ninguna tarjeta tiene mejor precio en estos productos.") }
        } else {
            span {
                +tr("Pay with ", "Paga con ")
                span("oa-pay-card") { +payLabel(payWith) }
                +tr(": it takes ${cop(saving)} off this cart. Paid another way, the card prices do not apply.",
                    ": le quita ${cop(saving)} a este carrito. Con otro medio de pago, los precios con tarjeta no aplican.")
            }
        }
    }
}

/** One run in a line, for the earlier-runs list. */
internal fun runOutcome(r: OrderRun): String = when {
    r.status == "running" -> tr("running", "corriendo")
    r.status != "ok" && r.items.isEmpty() -> tr("failed", "falló") + (r.error?.let { ": ${it.take(100)}" }.orEmpty())
    r.order != null && r.items.isNotEmpty() ->
        tr("${r.items.size} items at ${r.order.store.label}, ${cop(cartTotal(r.items))}", "${r.items.size} productos en ${r.order.store.label}, ${cop(cartTotal(r.items))}") +
            if (r.ordered) tr(" · ordered", " · pedido") else tr(" · not ordered", " · sin pedir")
    else -> tr("no cart", "sin carrito")
}

/**
 * The order agent on Pinned: the latest run's cart with a reason per item and the link to open it, or why
 * there is none; the box for telling it what it got right or wrong; and the runs before.
 */
internal fun FlowContent.orderAgentPanel(m: OrderPanelModel) {
    val latest = m.runs.firstOrNull()
    val today = latest != null && latest.startedAt.atZone(m.zone).toLocalDate() == m.now.atZone(m.zone).toLocalDate()
    // A disclosure, open by default; closing it is remembered for this run only (ORDER_PANEL_JS), so a new cart opens it again.
    details("order-agent") {
        id = "order-agent"
        attributes["open"] = ""
        latest?.let { attributes["data-run"] = it.runId.toString() }
        summary("oa-head") {
            h2("oa-title") {
                id = "order-agent-h"
                +if (today) tr("Today's cart", "Carrito de hoy") else tr("Order agent", "Agente de pedidos")
            }
            span("oa-sub") {
                +when {
                    m.running -> tr("filling a cart now; reload in a minute", "llenando un carrito ahora; recarga en un minuto")
                    latest != null -> tr("by the order agent, ", "del agente de pedidos, ") + runWhen(latest.startedAt, m.now, m.zone)
                    else -> tr("it fills a cart here each morning from your orders and the day's promotions", "llena aquí un carrito cada mañana con tus pedidos y las promociones del día")
                }
            }
            // What a closed panel still says: where the cart is and what it costs.
            latest?.order?.takeIf { latest.items.isNotEmpty() }?.let { o ->
                span("oa-glance") { +"${o.store.label} · ${cop(cartTotal(latest.items))}" }
            }
        }
        div("oa-body") {
            form(action = ORDER_RUN_PATH, method = FormMethod.post, classes = "oa-run") {
                button(type = ButtonType.submit, classes = "sugg-btn") {
                    if (m.running) attributes["disabled"] = "disabled"
                    +if (m.running) tr("Running…", "Corriendo…") else tr("Run now", "Correr ahora")
                }
            }
            if (latest == null) {
                p("oa-summary") {
                    +(m.nextRun?.let { tr("First run ", "Primera corrida ") + runWhen(it, m.now, m.zone) + "." }
                        ?: tr("Its schedule is off (Config → Schedules).", "Su horario está apagado (Configuración → Horarios)."))
                }
                return@div
            }
            latestRun(latest, m)
            feedbackForm(latest.runId, latest.feedback, saved = false)
            val earlier = m.runs.drop(1)
            if (earlier.isNotEmpty()) {
                details("oa-earlier") {
                    summary { +tr("Earlier runs (${earlier.size})", "Corridas anteriores (${earlier.size})") }
                    ul("oa-earlier-list") {
                        earlier.forEach { r ->
                            li {
                                span("oa-when") { +runWhen(r.startedAt, m.now, m.zone) }
                                span("oa-outcome") { +runOutcome(r) }
                                r.feedback?.let { p("oa-note") { +"“${it.take(200)}”" } }
                            }
                        }
                    }
                }
            }
        }
    }
    script { unsafe { +ORDER_PANEL_JS } }
}

/**
 * Keeps the panel closed across reloads once closed, for that run only: the run id is what is remembered, so the
 * next cart opens it again. Browser storage may be unavailable; then the panel simply opens each time.
 */
internal const val ORDER_PANEL_JS = """
(function () {
  var d = document.getElementById('order-agent');
  if (!d) return;
  var run = d.getAttribute('data-run'), key = 'orderPanelClosedRun';
  try { if (run && localStorage.getItem(key) === run) d.removeAttribute('open'); } catch (e) {}
  d.addEventListener('toggle', function () {
    try { if (d.open) localStorage.removeItem(key); else if (run) localStorage.setItem(key, run); } catch (e) {}
  });
})();
"""

private fun FlowContent.latestRun(r: OrderRun, m: OrderPanelModel) {
    val order = r.order
    if (order == null || r.items.isEmpty()) {
        if (r.status == "running") return
        if (r.status != "ok") p("oa-error") { +(tr("The run failed: ", "La corrida falló: ") + (r.error?.take(240) ?: "?")) }
        else {
            p("oa-none") { +tr("No cart today: nothing was worth buying.", "Hoy no hay carrito: nada valía la pena.") }
            r.summary?.let { p("oa-summary") { +plainSummary(it).take(1500) } }
        }
        return
    }
    div("oa-meta") {
        span("chip chip-new") { +order.store.label }
        span("oa-fact") { +(tr("Pay with ", "Paga con ") + payLabel(order.payWith)) }
        span("oa-fact num") { +cop(cartTotal(r.items)) }
        cartSaving(r.items).takeIf { it.signum() > 0 }?.let { span("oa-fact oa-save") { +tr("${cop(it)} under usual", "${cop(it)} bajo lo habitual") } }
        if (r.ordered) span("co-yes") { +tr("✓ ordered", "✓ pedido") }
    }
    if (r.status != "ok") p("oa-error") { +(tr("The run stopped early (", "La corrida se detuvo antes (") + (r.error?.take(160) ?: "?") + tr("); the cart has what it added.", "); el carrito tiene lo que alcanzó a agregar.")) }
    r.summary?.let { p("oa-summary") { +plainSummary(it).take(1500) } }
    payNote(order.payWith, r.items)
    if (order.cartId != null && !r.ordered) {
        a(href = orderOpenPath(order.id), target = "_blank", classes = "open-cart oa-open") {
            +tr("🛒 Open this cart at Exito ${order.store.short}", "🛒 Abrir este carrito en Exito ${order.store.short}")
        }
    }
    ol("oa-items") {
        r.items.forEach { i -> orderItem(i) }
    }
}

private fun kotlinx.html.OL.orderItem(i: AgentOrderItem) {
    li("oa-item") {
        div("oa-item-top") {
            if (i.dealId != null) a(href = "/deal/${i.dealId}", classes = "oa-item-title") { +i.title } else span("oa-item-title") { +i.title }
            span("oa-price num") {
                span("oa-pay") { +cop(i.cardPrice ?: i.price ?: BigDecimal.ZERO) }
                // Under the price paid, what it costs without the card: the label plain, the amount struck.
                i.cardPrice?.let { _ -> i.price?.let { span("oa-shelf") { +tr("shelf ", "góndola "); span("oa-struck") { +cop(it) } } } }
            }
        }
        val under = i.usual?.takeIf { it.signum() > 0 }?.let { u ->
            val paid = i.cardPrice ?: i.price ?: return@let null
            u.subtract(paid).multiply(BigDecimal(100)).divide(u, 0, RoundingMode.HALF_UP)
        }
        if (i.byOperator) {
            p("oa-reason") { span("chip oa-yours") { +tr("added by you", "agregado por ti") } }
            return@li
        }
        p("oa-reason") {
            i.usual?.let { u ->
                span("oa-usual") {
                    +(tr("usual ", "habitual ") + cop(u))
                    under?.takeIf { it.signum() > 0 }?.let { +" (−$it%)" }
                    +" · "
                }
            }
            +i.reason
        }
    }
}

/** The feedback box. After a save it says so, and the text stays editable. */
internal fun FlowContent.feedbackForm(runId: Long, text: String?, saved: Boolean) {
    form(classes = "oa-feedback") {
        id = "oa-feedback"
        attributes["hx-post"] = orderFeedbackPath(runId)
        attributes["hx-target"] = "#oa-feedback"
        attributes["hx-swap"] = "outerHTML"
        label {
            attributes["for"] = "oa-feedback-text"
            +tr("What did it get right or wrong? The next runs read this.", "¿Qué hizo bien o mal? Las próximas corridas lo leen.")
        }
        textArea(rows = "3", classes = "oa-feedback-text") {
            id = "oa-feedback-text"
            attributes["name"] = "feedback"
            attributes["maxlength"] = "1000"
            attributes["placeholder"] = tr(
                "e.g. good call on the fruit; never buy soda; I'd rather pick up at Colombia",
                "p. ej. bien la fruta; nunca compres gaseosa; prefiero recoger en Colombia",
            )
            +(text.orEmpty())
        }
        div("oa-feedback-row") {
            button(type = ButtonType.submit, classes = "sugg-btn") { +tr("Save note", "Guardar nota") }
            if (saved) span("co-yes oa-saved") {
                attributes["role"] = "status"
                +if (text.isNullOrBlank()) tr("Cleared.", "Borrada.") else tr("Saved. The next run will read it.", "Guardada. La próxima corrida la leerá.")
            }
        }
    }
}

internal fun feedbackFragment(runId: Long, text: String?): String =
    createHTML(prettyPrint = false).div { feedbackForm(runId, text, saved = true) }.removePrefix("<div>").removeSuffix("</div>")
