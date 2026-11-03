package com.luis.dealstracker.web

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.config.ConfigOverrides
import com.luis.dealstracker.config.LiveConfig
import com.luis.dealstracker.db.CardChoice
import com.luis.dealstracker.db.CardProgramRepository
import com.luis.dealstracker.db.DealRepository
import com.luis.dealstracker.db.SettingsRepository
import com.luis.dealstracker.db.cardChoices
import com.luis.dealstracker.db.heldCardsValue
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.ingest.nextWindowSlot
import com.luis.dealstracker.ingest.runWindows
import com.luis.dealstracker.model.CardScope
import com.luis.dealstracker.model.HeldCards
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.MAIN
import kotlinx.html.ThScope
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Instant
import java.time.ZoneId

/** Everything the Config page shows, gathered by the route so the page itself is a pure render. */
internal data class ConfigModel(
    /** sources.yaml as written: each field's placeholder, and what clearing it goes back to. */
    val file: AppConfig,
    /** In force now: the file with [overrides] applied. */
    val live: AppConfig,
    /** `cfg.*` rows, keyed without the prefix. */
    val overrides: Map<String, String>,
    val cards: List<CardChoice>,
    /** The cards as ticked (STORE_CARD alone, not the MASTERCARD it implies). */
    val picked: Set<CardScope>,
    /** Readings whose card this app cannot name: shown so the gap is visible, never pickable. */
    val unnamedCardReadings: Long,
    val lastRuns: Map<String, Instant?>,
    val prunedAt: Instant?,
    val now: Instant = Instant.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** What the last save did, or why it was refused. */
    val notice: String? = null,
    val error: String? = null,
)

/** The Config tab: GET /config and the three forms it posts. */
internal fun Route.configRoutes(settings: SettingsRepository, repo: DealRepository) {
    val programs = CardProgramRepository(com.luis.dealstracker.db.Db.dataSource)

    fun reload() {
        LiveConfig.file?.let { LiveConfig.set(it, settings.withPrefix(ConfigOverrides.PREFIX)) }
    }

    get("/config") {
        val file = LiveConfig.file ?: return@get call.respondRedirect("/")
        val all = programs.all()
        val picked = SettingsRepository.pickedCards(settings.get(SettingsRepository.HELD_CARDS))
        val live = LiveConfig.currentOr(file)
        val model = ConfigModel(
            file = file,
            live = live,
            overrides = settings.withPrefix(ConfigOverrides.PREFIX),
            cards = cardChoices(all, picked),
            picked = picked,
            unnamedCardReadings = all.filter { it.scope == null || it.scope == CardScope.OTHER }.sumOf { it.readings },
            lastRuns = file.providers.associate { it.id to repo.lastSuccessfulRun(it.id) },
            prunedAt = settings.get(SettingsRepository.HISTORY_PRUNED_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            notice = call.request.queryParameters["saved"]?.let { savedNotice(it) },
            error = call.request.queryParameters["error"],
        )
        call.respondPage { page(tr("Config", "Configuración")) { configPage(model) } }
    }

    post("/config/cards") {
        val value = heldCardsValue(call.receiveParameters().getAll("card").orEmpty())
        settings.set(mapOf(SettingsRepository.HELD_CARDS to value))
        HeldCards.current = SettingsRepository.heldCards(value)
        call.respondRedirect("/config?saved=cards#cards")
    }

    post("/config/settings") {
        val file = LiveConfig.file ?: return@post call.respondRedirect("/")
        val params = call.receiveParameters()
        val submitted = params.names().associateWith { params[it].orEmpty() }
        val result = overrideChanges(file, submitted)
        if (result.errors.isNotEmpty()) {
            val msg = result.errors.entries.joinToString("; ") { (k, e) -> "$k: $e" }
            return@post call.respondRedirect("/config?error=${msg.encodeURLParameter()}#schedules")
        }
        settings.delete(result.cleared.map { ConfigOverrides.PREFIX + it })
        settings.set(result.set.mapKeys { ConfigOverrides.PREFIX + it.key })
        reload()
        call.respondRedirect("/config?saved=settings#schedules")
    }

    post("/config/sources") {
        val file = LiveConfig.file ?: return@post call.respondRedirect("/")
        val on = call.receiveParameters().getAll("source").orEmpty().toSet()
        val pausable = file.providers.filter { it.enabled }.map { it.id }
        settings.delete(pausable.filter { it in on }.map { ConfigOverrides.PREFIX + ConfigOverrides.enabledKey(it) })
        settings.set(pausable.filter { it !in on }.associate { ConfigOverrides.PREFIX + ConfigOverrides.enabledKey(it) to "false" })
        reload()
        call.respondRedirect("/config?saved=sources#sources")
    }
}

private fun savedNotice(what: String): String = when (what) {
    "cards" -> tr("Cards saved. Every tab now prices with them.", "Tarjetas guardadas. Todas las pestañas usan ahora esos precios.")
    "sources" -> tr("Sources saved. A paused source is skipped from the next poll.", "Fuentes guardadas. Una fuente en pausa se omite desde la próxima consulta.")
    else -> tr("Saved. Jobs pick it up on their next tick.", "Guardado. Las tareas lo toman en su siguiente ciclo.")
}

/** What a settings form submission changes: rows to write, rows to delete, and fields refused. */
internal data class OverrideChanges(val set: Map<String, String>, val cleared: List<String>, val errors: Map<String, String>)

/**
 * Turns a submitted settings form into override rows. Only known fields are read -- the global ones and
 * the windows of providers the file gives windows -- so a forged field cannot write an arbitrary key.
 * A blank field, or one equal to the file's value, clears its override: the file stays in charge, and
 * a later edit to sources.yaml is not silently shadowed by a copy of its old value.
 */
internal fun overrideChanges(file: AppConfig, submitted: Map<String, String>): OverrideChanges {
    val set = mutableMapOf<String, String>()
    val cleared = mutableListOf<String>()
    val errors = mutableMapOf<String, String>()
    fun consider(key: String, fileValue: String, parse: (String) -> Result<String>) {
        val raw = submitted[key] ?: return
        if (raw.isBlank()) { cleared += key; return }
        parse(raw).fold(
            onSuccess = { v -> if (v == parse(fileValue).getOrNull()) cleared += key else set[key] = v },
            onFailure = { errors[key] = it.message ?: "invalid" },
        )
    }
    ConfigOverrides.fields.forEach { f -> consider(f.key, f.fileValue(file), f.parse) }
    file.providers.filter { it.listOptions.containsKey("runWindows") }.forEach { p ->
        consider(ConfigOverrides.windowsKey(p.id), p.list("runWindows").joinToString(","), ConfigOverrides::parseWindows)
    }
    return OverrideChanges(set, cleared, errors)
}

/** The page's names for each global field: label, and the one line that says what it does. */
private fun fieldText(key: String): Pair<String, String> = when (key) {
    "pollIntervalMinutes" -> tr("Poll every (minutes)", "Consultar cada (minutos)") to
        tr("The main cycle: every non-grocery source, tracked items, watches, then shipping checks.", "El ciclo principal: todas las fuentes salvo mercado, seguimiento, búsquedas y verificación de envío.")
    "coShippingEveryNPolls" -> tr("Shipping checks every N polls", "Verificar envío cada N consultas") to
        tr("Amazon → Colombia checks in a headed browser. 0 turns them off.", "Verificación Amazon → Colombia en un navegador visible. 0 las apaga.")
    "coShippingPollBatch" -> tr("Shipping checks per run", "Verificaciones por corrida") to
        tr("Items per automatic run. Step down if the log shows Amazon blocking.", "Productos por corrida automática. Bájalo si el registro muestra bloqueos de Amazon.")
    "watchDigestTimes" -> tr("Watch digest at", "Resumen de búsquedas a las") to
        tr("One banner and Discord post for all watch alerts. \"off\" sends one per watch per cycle.", "Un aviso y un post de Discord para todas las alertas. \"off\" envía uno por búsqueda en cada ciclo.")
    "pinnedAlertTimes" -> tr("Pinned-on-sale posts at", "Posts de fijados en oferta a las") to
        tr("Discord posts of pinned groceries and tracked items on sale. \"off\" sends none.", "Posts en Discord de fijados en oferta. \"off\" no envía ninguno.")
    "pruneHistoryAfterDays" -> tr("Thin history older than (days)", "Adelgazar historial de más de (días)") to
        tr("Unchanged readings past this age are thinned; changes and pinned rows are kept. 0 turns it off.", "Las lecturas sin cambios más viejas se adelgazan; los cambios y los fijados se conservan. 0 lo apaga.")
    "pruneHistoryEveryDays" -> tr("Thin history every (days)", "Adelgazar historial cada (días)") to ""
    "askModel" -> tr("Ask Claude model", "Modelo de Preguntar a Claude") to "haiku, sonnet, opus"
    "askEffort" -> tr("Ask Claude effort", "Esfuerzo de Preguntar a Claude") to "low, medium, high"
    "askMaxBudgetUsd" -> tr("Ask Claude budget per question (US$)", "Presupuesto por pregunta (US$)") to ""
    else -> key to ""
}

internal fun MAIN.configPage(m: ConfigModel) {
    h2 { +tr("Config", "Configuración") }
    p("hint") {
        +tr(
            "Changes here sit on top of config/sources.yaml. Clear a field to go back to the file's value, shown greyed. ",
            "Los cambios aquí se superponen a config/sources.yaml. Vacía un campo para volver al valor del archivo, en gris. ",
        )
    }
    m.notice?.let { p("config-notice") { +it } }
    m.error?.let { p("config-error") { attributes["role"] = "alert"; +tr("Not saved — $it", "No se guardó — $it") } }

    cardsSection(m)
    schedulesSection(m)
    sourcesSection(m)
    notificationsSection(m)
}

private fun FlowContent.cardsSection(m: ConfigModel) {
    section("config-section") {
        attributes["id"] = "cards"
        h3 { +tr("My cards", "Mis tarjetas") }
        p("hint") {
            +tr(
                "Every card and payment method a crawl has found a price for. Ticked ones are yours: every tab shows " +
                    "the price they pay (✓), fades offers for cards you don't hold, sorts card savings by yours, and " +
                    "watches alert on a new low with them.",
                "Cada tarjeta y medio de pago con el que algún rastreo encontró un precio. Las marcadas son tuyas: todas las " +
                    "pestañas muestran el precio que pagan (✓), atenúan las ofertas de tarjetas que no tienes, ordenan el ahorro " +
                    "por las tuyas, y las búsquedas avisan de un nuevo mínimo con ellas.",
            )
        }
        form(action = "/config/cards", method = FormMethod.post) {
            table("config-table") {
                caption("sr-only") { +tr("Cards found in the crawls", "Tarjetas encontradas") }
                thead {
                    tr {
                        th(scope = ThScope.col) { +tr("Card", "Tarjeta") }
                        th(scope = ThScope.col) { +tr("Seen at", "Vista en") }
                        th(scope = ThScope.col) { +tr("Last seen", "Última vez") }
                        th(scope = ThScope.col) { +tr("Readings", "Lecturas") }
                    }
                }
                tbody {
                    m.cards.forEach { c ->
                        tr {
                            td {
                                label {
                                    checkBoxInput(name = "card") { value = c.scope.name; checked = c.scope in m.picked }
                                    +" "
                                    +cardName(c.scope)
                                }
                            }
                            td { +c.sources.joinToString(", ").ifEmpty { "—" } }
                            td { +if (c.readings == 0L) "—" else relativeAgo(c.lastSeenAt) }
                            td("num") { +"%,d".format(c.readings) }
                        }
                    }
                }
            }
            if (m.unnamedCardReadings > 0) {
                p("hint") {
                    +tr(
                        "%,d readings carry a card offer this app cannot name yet (a bank's BIN list, or older readings with two offers). They are shown as \"Card\" and never count as yours.".format(m.unnamedCardReadings),
                        "%,d lecturas traen una oferta con tarjeta que la app aún no identifica (lista de BIN de un banco, o lecturas viejas con dos ofertas). Se muestran como \"Tarjeta\" y nunca cuentan como tuyas.".format(m.unnamedCardReadings),
                    )
                }
            }
            button(type = ButtonType.submit, classes = "primary") { +tr("Save cards", "Guardar tarjetas") }
        }
    }
}

/** A card's name on the picker: the store card is named for what it is. */
internal fun cardName(scope: CardScope): String = when (scope) {
    CardScope.STORE_CARD -> tr("Tarjeta Éxito y Carulla (also pays Mastercard offers)", "Tarjeta Éxito y Carulla (también paga ofertas Mastercard)")
    CardScope.MASTERCARD -> tr("Mastercard (any)", "Mastercard (cualquiera)")
    CardScope.VISA -> tr("Visa (any)", "Visa (cualquiera)")
    CardScope.CMR -> "CMR (Falabella, Homecenter)"
    CardScope.TMC -> "Tarjeta Más Cencosud (Easy)"
    CardScope.CODENSA -> "Codensa (Easy)"
    CardScope.ADDI -> tr("Addi (pay later)", "Addi (paga después)")
    CardScope.OTHER -> tr("Card", "Tarjeta")
}

private fun FlowContent.schedulesSection(m: ConfigModel) {
    section("config-section") {
        attributes["id"] = "schedules"
        h3 { +tr("Schedules and jobs", "Horarios y tareas") }
        form(action = "/config/settings", method = FormMethod.post) {
            table("config-table") {
                caption("sr-only") { +tr("Job settings", "Ajustes de tareas") }
                thead {
                    tr {
                        th(scope = ThScope.col) { +tr("Setting", "Ajuste") }
                        th(scope = ThScope.col) { +tr("Value", "Valor") }
                        th(scope = ThScope.col) { +tr("Next / status", "Próxima / estado") }
                    }
                }
                tbody {
                    ConfigOverrides.fields.forEach { f ->
                        val (label, hint) = fieldText(f.key)
                        tr {
                            td {
                                label { htmlFor = "cfg-${f.key}"; +label }
                                if (hint.isNotEmpty()) div("dim") { +hint }
                            }
                            td {
                                input(InputType.text, name = f.key) {
                                    attributes["id"] = "cfg-${f.key}"
                                    value = m.overrides[f.key].orEmpty()
                                    placeholder = f.fileValue(m.file)
                                }
                                if (f.applies == ConfigOverrides.Applies.RESTART) span("chip") { +tr("after restart", "tras reiniciar") }
                            }
                            td("dim") { +jobStatus(f.key, m) }
                        }
                    }
                    m.file.providers.filter { it.listOptions.containsKey("runWindows") }.forEach { p ->
                        val key = ConfigOverrides.windowsKey(p.id)
                        val live = m.live.providers.firstOrNull { it.id == p.id } ?: p
                        tr {
                            td {
                                label { htmlFor = "cfg-$key"; +tr("${p.id} crawl windows", "Ventanas de ${p.id}") }
                                div("dim") { +tr("Runs once in each window, at a minute fixed for the day.", "Corre una vez en cada ventana, a un minuto fijo del día.") }
                            }
                            td {
                                input(InputType.text, name = key) {
                                    attributes["id"] = "cfg-$key"
                                    value = m.overrides[key].orEmpty()
                                    placeholder = p.list("runWindows").joinToString(", ")
                                }
                            }
                            td("dim") {
                                val next = runWindows(live)?.let { nextWindowSlot(it, p.id, m.now, m.zone) }
                                val last = m.lastRuns[p.id]
                                +listOfNotNull(
                                    if (!live.enabled) tr("paused", "en pausa") else next?.let { tr("next ${clock(it, m)}", "próxima ${clock(it, m)}") },
                                    last?.let { tr("last ok ${relativeAgo(it)}", "última ok ${relativeAgo(it)}") },
                                ).joinToString(" · ")
                            }
                        }
                    }
                }
            }
            button(type = ButtonType.submit, classes = "primary") { +tr("Save", "Guardar") }
        }
    }
}

/** "today 19:00", "tomorrow 08:00", or a date. */
private fun clock(at: Instant, m: ConfigModel): String {
    val local = at.atZone(m.zone)
    val today = m.now.atZone(m.zone).toLocalDate()
    val hhmm = "%02d:%02d".format(local.hour, local.minute)
    return when (local.toLocalDate()) {
        today -> tr("today $hhmm", "hoy $hhmm")
        today.plusDays(1) -> tr("tomorrow $hhmm", "mañana $hhmm")
        else -> "${local.toLocalDate()} $hhmm"
    }
}

/** The next run of a time-of-day job, from [times] ("19:00"), after [ConfigModel.now]. */
internal fun nextTimeOfDay(times: List<String>, now: Instant, zone: ZoneId): Instant? {
    val parsed = com.luis.dealstracker.notify.parseSlotTimes(times).sorted()
    if (parsed.isEmpty()) return null
    val local = now.atZone(zone)
    return (0L..1L).flatMap { d -> parsed.map { local.toLocalDate().plusDays(d).atTime(it).atZone(zone).toInstant() } }
        .firstOrNull { it.isAfter(now) }
}

private fun jobStatus(key: String, m: ConfigModel): String {
    val d = m.live.defaults
    return when (key) {
        "pollIntervalMinutes" -> tr("every ${d.pollIntervalMinutes} min", "cada ${d.pollIntervalMinutes} min")
        "coShippingEveryNPolls" -> if (d.coShippingEveryNPolls == 0) tr("off", "apagado")
        else tr("every ${d.coShippingEveryNPolls * d.pollIntervalMinutes} min", "cada ${d.coShippingEveryNPolls * d.pollIntervalMinutes} min")
        "watchDigestTimes" -> nextTimeOfDay(d.watchDigestTimes, m.now, m.zone)?.let { tr("next ${clock(it, m)}", "próximo ${clock(it, m)}") }
            ?: tr("off — a banner per watch per cycle", "apagado — un aviso por búsqueda en cada ciclo")
        "pinnedAlertTimes" -> nextTimeOfDay(d.pinnedAlertTimes, m.now, m.zone)?.let { tr("next ${clock(it, m)}", "próximo ${clock(it, m)}") }
            ?: tr("off", "apagado")
        "pruneHistoryAfterDays" -> if (d.pruneHistoryAfterDays == 0) tr("off", "apagado")
        else m.prunedAt?.let { tr("last ${relativeAgo(it)}", "último ${relativeAgo(it)}") } ?: tr("not run yet", "aún no ha corrido")
        "pruneHistoryEveryDays" -> m.prunedAt?.plus(java.time.Duration.ofDays(d.pruneHistoryEveryDays.toLong()))
            ?.let { tr("next ${clock(it, m)}", "próximo ${clock(it, m)}") }.orEmpty()
        else -> ""
    }
}

private fun FlowContent.sourcesSection(m: ConfigModel) {
    section("config-section") {
        attributes["id"] = "sources"
        h3 { +tr("Sources", "Fuentes") }
        p("hint") {
            +tr(
                "Untick to pause a source: it is skipped from the next poll and its rows stay. Sources off in sources.yaml stay off here; BLOCKERS.md says why.",
                "Desmarca para pausar una fuente: se omite desde la próxima consulta y sus filas se conservan. Las apagadas en sources.yaml siguen apagadas; BLOCKERS.md explica por qué.",
            )
        }
        form(action = "/config/sources", method = FormMethod.post) {
            table("config-table") {
                caption("sr-only") { +tr("Sources", "Fuentes") }
                thead {
                    tr {
                        th(scope = ThScope.col) { +tr("Source", "Fuente") }
                        th(scope = ThScope.col) { +tr("Type", "Tipo") }
                        th(scope = ThScope.col) { +tr("Last successful run", "Última corrida exitosa") }
                    }
                }
                tbody {
                    m.file.providers.forEach { p ->
                        val live = m.live.providers.firstOrNull { it.id == p.id } ?: p
                        tr(if (live.enabled) null else "dim") {
                            td {
                                label {
                                    checkBoxInput(name = "source") {
                                        value = p.id
                                        checked = live.enabled
                                        disabled = !p.enabled
                                    }
                                    +" ${p.id}"
                                }
                                if (!p.enabled) span("chip") { +tr("off in sources.yaml", "apagada en sources.yaml") }
                            }
                            td { code { +p.type } }
                            td { +(m.lastRuns[p.id]?.let(::relativeAgo) ?: "—") }
                        }
                    }
                }
            }
            button(type = ButtonType.submit, classes = "primary") { +tr("Save sources", "Guardar fuentes") }
        }
    }
}

private fun FlowContent.notificationsSection(m: ConfigModel) {
    section("config-section") {
        attributes["id"] = "notifications"
        h3 { +tr("Notifications", "Notificaciones") }
        p("hint") {
            +tr(
                "Discord webhooks are secrets, so they live in .env and are only reported here. Set one there and restart to turn its posts on.",
                "Los webhooks de Discord son secretos: viven en .env y aquí solo se informa su estado. Configúralo allí y reinicia para activarlo.",
            )
        }
        table("config-table") {
            caption("sr-only") { +tr("Discord channels", "Canales de Discord") }
            tbody {
                listOf(
                    Triple(tr("Pinned groceries on sale", "Mercado fijado en oferta"), "DISCORD_GROCERIES_WEBHOOK_URL", m.file.discordGroceriesWebhook),
                    Triple(tr("Pinned tracked items on sale", "Seguimiento fijado en oferta"), "DISCORD_TRACKED_WEBHOOK_URL", m.file.discordTrackedWebhook),
                    Triple(tr("Watch digest", "Resumen de búsquedas"), "DISCORD_WATCHES_WEBHOOK_URL", m.file.discordWatchesWebhook),
                ).forEach { (what, env, url) ->
                    tr {
                        td { +what }
                        td { code { +env } }
                        td { if (url.isNullOrBlank()) span("co-no") { +tr("not set", "sin configurar") } else span("co-yes") { +tr("set", "configurado") } }
                    }
                }
            }
        }
    }
}
