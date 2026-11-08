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
import com.luis.dealstracker.agent.AgentJobs
import com.luis.dealstracker.agent.AgentRun
import com.luis.dealstracker.agent.AgentRunRepository
import com.luis.dealstracker.agent.GROCERY_AGENT
import com.luis.dealstracker.ops.BackupFile
import com.luis.dealstracker.ops.HealthStatus
import com.luis.dealstracker.ops.OpsJobs
import com.luis.dealstracker.ops.SourceHealth
import com.luis.dealstracker.ops.brokeLine
import com.luis.dealstracker.ops.formatBytes
import com.luis.dealstracker.ops.listBackups
import com.luis.dealstracker.promo.Promo
import com.luis.dealstracker.promo.PromoRepository
import com.luis.dealstracker.promo.offerText
import com.luis.dealstracker.promo.parsePromoForm
import com.luis.dealstracker.promo.ruleText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import kotlinx.html.option
import kotlinx.html.section
import kotlinx.html.select
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
    /** Each source's health, by id; empty where the jobs are not running. */
    val health: Map<String, SourceHealth> = emptyMap(),
    val backup: BackupView? = null,
    val promos: List<Promo> = emptyList(),
    /** The grocery agent's recent runs; null where this copy does not run agents. */
    val agentRuns: List<AgentRun>? = null,
    val agentRunning: Boolean = false,
)

/** The Backups section: where the dumps go, what is there, and how the last attempt went. */
internal data class BackupView(
    val dir: String,
    val files: List<BackupFile>,
    val lastAt: Instant?,
    val lastError: String?,
    val failedAt: Instant?,
    val running: Boolean,
)

/** The Config tab: GET /config and the three forms it posts. */
internal fun Route.configRoutes(
    settings: SettingsRepository,
    repo: DealRepository,
    ops: OpsJobs? = null,
    scope: CoroutineScope? = null,
    agents: AgentJobs? = null,
) {
    val agentRunRepo = AgentRunRepository(com.luis.dealstracker.db.Db.dataSource)
    val programs = CardProgramRepository(com.luis.dealstracker.db.Db.dataSource)
    val promoRepo = PromoRepository(com.luis.dealstracker.db.Db.dataSource)

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
            health = withContext(Dispatchers.IO) { ops?.health().orEmpty().associateBy { it.id } },
            promos = promoRepo.refresh(),
            agentRuns = agents?.let { withContext(Dispatchers.IO) { agentRunRepo.recent(GROCERY_AGENT) } },
            agentRunning = agents?.running == true,
            backup = ops?.let { o ->
                fun at(key: String) = settings.get(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                BackupView(
                    dir = o.backupDir().absolutePath,
                    files = listBackups(o.backupDir()),
                    lastAt = at(SettingsRepository.BACKUP_LAST_AT),
                    lastError = settings.get(SettingsRepository.BACKUP_LAST_ERROR),
                    failedAt = at(SettingsRepository.BACKUP_FAILED_AT),
                    running = o.backupRunning,
                )
            },
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

    // Runs in the background: the dump takes a few seconds, and the page shows "running" meanwhile.
    post("/config/backup") {
        if (ops != null && scope != null) scope.launch(Dispatchers.IO) { ops.backupNow()?.let(::println) }
        call.respondRedirect("/config?saved=backup#backups")
    }

    // In the background: a run takes a minute or two. The page shows it running meanwhile.
    post("/config/agents/grocery/run") {
        if (agents != null && scope != null && !agents.running) scope.launch(Dispatchers.IO) { println(agents.runGrocery("manual")) }
        call.respondRedirect("/config?saved=agent#agents")
    }

    post("/config/promos") {
        val params = call.receiveParameters()
        parsePromoForm(params.names().associateWith { params[it].orEmpty() }).fold(
            onSuccess = { promoRepo.insert(it); promoRepo.refresh(); call.respondRedirect("/config?saved=promos#promos") },
            onFailure = { call.respondRedirect("/config?error=${(it.message ?: "invalid").encodeURLParameter()}#promos") },
        )
    }

    post("/config/promos/{id}/{action}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respondRedirect("/config#promos")
        when (call.parameters["action"]) {
            "enable" -> promoRepo.setEnabled(id, true)
            "disable" -> promoRepo.setEnabled(id, false)
            "delete" -> promoRepo.delete(id)
        }
        promoRepo.refresh()
        call.respondRedirect("/config?saved=promos#promos")
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
    "agent" -> tr("Grocery agent started. Reload in a minute or two to see the run.", "Agente de mercado iniciado. Recarga en uno o dos minutos para ver la corrida.")
    "promos" -> tr("Promo reminders saved.", "Recordatorios de promociones guardados.")
    "backup" -> tr("Backup started. Reload in a few seconds to see it listed.", "Respaldo iniciado. Recarga en unos segundos para verlo en la lista.")
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
    "backupTimes" -> tr("Back up the database at", "Respaldar la base de datos a las") to
        tr("A time slept through runs when the Mac wakes. \"off\" turns backups off.", "Si el Mac dormía a esa hora, corre al despertar. \"off\" apaga los respaldos.")
    "backupKeep" -> tr("Backups to keep", "Respaldos a conservar") to
        tr("The oldest is deleted once a newer one has been checked.", "El más viejo se borra cuando uno nuevo ya fue verificado.")
    "healthFailAfterRuns" -> tr("Alert after N empty runs", "Alertar tras N corridas vacías") to
        tr("Runs in a row with no results or an error before a source is reported broken. Scheduled crawls use 2.", "Corridas seguidas sin resultados o con error antes de reportar una fuente como caída. Los rastreos programados usan 2.")
    "groceryAgentTimes" -> tr("Grocery agent runs at", "El agente de mercado corre a las") to
        tr("After the morning crawl, so it reads today's prices. \"off\" turns it off.", "Después del rastreo de la mañana, para leer los precios de hoy. \"off\" lo apaga.")
    "agentModel" -> tr("Agent model", "Modelo de los agentes") to "haiku, sonnet, opus"
    "agentEffort" -> tr("Agent effort", "Esfuerzo de los agentes") to "low, medium, high"
    "agentMaxBudgetUsd" -> tr("Agent budget per run (US$)", "Presupuesto por corrida (US$)") to
        tr("Counted against your Claude plan; a run stops at this.", "Cuenta contra tu plan de Claude; una corrida se detiene al llegar aquí.")
    "groceryAgentMaxSuggestions" -> tr("Most suggestions per run", "Máximo de sugerencias por corrida") to ""
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
    promosSection(m)
    sourcesSection(m)
    agentsSection(m)
    backupsSection(m)
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
        "groceryAgentTimes" -> nextTimeOfDay(d.groceryAgentTimes, m.now, m.zone)?.let { tr("next ${clock(it, m)}", "próximo ${clock(it, m)}") }
            ?: tr("off", "apagado")
        "backupTimes" -> nextTimeOfDay(d.backupTimes, m.now, m.zone)?.let { tr("next ${clock(it, m)}", "próximo ${clock(it, m)}") }
            ?: tr("off", "apagado")
        "pruneHistoryEveryDays" -> m.prunedAt?.plus(java.time.Duration.ofDays(d.pruneHistoryEveryDays.toLong()))
            ?.let { tr("next ${clock(it, m)}", "próximo ${clock(it, m)}") }.orEmpty()
        else -> ""
    }
}

private fun FlowContent.sourcesSection(m: ConfigModel) {
    section("config-section") {
        attributes["id"] = "sources"
        h3 { +tr("Sources", "Fuentes") }
        // The anchor the health alerts' banner opens.
        span { attributes["id"] = "health" }
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
                        th(scope = ThScope.col) { +tr("Health", "Estado") }
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
                            td { m.health[p.id]?.let { healthCell(it) } ?: +"—" }
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
                    Triple(
                        tr("Source health and backup alerts (else the watch digest's channel)", "Alertas de fuentes y respaldos (si no, el canal del resumen)"),
                        "DISCORD_HEALTH_WEBHOOK_URL", m.file.discordHealthWebhook,
                    ),
                    Triple(
                        tr("Grocery agent suggestions (else the groceries channel)", "Sugerencias del agente de mercado (si no, el canal de mercado)"),
                        "DISCORD_AGENTS_WEBHOOK_URL", m.file.discordAgentsWebhook,
                    ),
                    Triple(
                        tr("Promo reminders (else the watch digest's channel)", "Recordatorios de promociones (si no, el canal del resumen)"),
                        "DISCORD_PROMOS_WEBHOOK_URL", m.file.discordPromosWebhook,
                    ),
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

/** A source's health on the Sources table: a chip, and why when it is not fine. */
internal fun FlowContent.healthCell(h: SourceHealth) {
    val (css, label) = when (h.status) {
        HealthStatus.OK -> "co-yes" to tr("ok", "ok")
        HealthStatus.DEGRADED -> "co-unk" to tr("last run empty", "última corrida vacía")
        HealthStatus.FAILING -> "co-no" to tr("not working", "sin funcionar")
        HealthStatus.OVERDUE -> "co-no" to tr("overdue", "atrasada")
        HealthStatus.PAUSED -> "dim" to tr("paused", "en pausa")
        HealthStatus.OFF -> "dim" to tr("off", "apagada")
        HealthStatus.NEW -> "dim" to tr("not run yet", "aún no corre")
    }
    span(css) { +label }
    if (h.status.unhealthy || h.status == HealthStatus.DEGRADED) {
        div("dim") { +brokeLine(h, ::tr, ::relativeAgo).substringAfter(": ") }
    }
}

private fun FlowContent.backupsSection(m: ConfigModel) {
    val b = m.backup ?: return
    section("config-section") {
        attributes["id"] = "backups"
        h3 { +tr("Backups", "Respaldos") }
        p("hint") {
            +tr("Dumps go to ", "Los respaldos van a ")
            code { +b.dir }
            +tr(
                ". Times and how many to keep are under Schedules. To restore one, see README → Backups.",
                ". La hora y cuántos conservar están en Horarios. Para restaurar uno, mira README → Backups.",
            )
        }
        val last = b.files.firstOrNull()
        p {
            when {
                b.running -> span("co-unk") { +tr("Backing up now…", "Respaldando ahora…") }
                last != null -> {
                    span("co-yes") { +tr("Last backup", "Último respaldo") }
                    +" ${relativeAgo(b.lastAt ?: last.takenAt)} · ${last.name} · ${formatBytes(last.bytes)}"
                }
                else -> span("co-unk") { +tr("No backup yet", "Aún no hay respaldos") }
            }
        }
        b.lastError?.let { err ->
            p("config-error") {
                attributes["role"] = "alert"
                +tr("The last attempt failed", "El último intento falló")
                b.failedAt?.let { +" (${relativeAgo(it)})" }
                +": $err"
            }
        }
        if (b.files.isNotEmpty()) {
            table("config-table") {
                caption("sr-only") { +tr("Backup files", "Archivos de respaldo") }
                thead {
                    tr {
                        th(scope = ThScope.col) { +tr("File", "Archivo") }
                        th(scope = ThScope.col) { +tr("Size", "Tamaño") }
                    }
                }
                tbody {
                    b.files.forEach { f ->
                        tr {
                            td { code { +f.name } }
                            td("num") { +formatBytes(f.bytes) }
                        }
                    }
                }
            }
        }
        form(action = "/config/backup", method = FormMethod.post) {
            button(type = ButtonType.submit, classes = "primary") {
                if (b.running) attributes["disabled"] = "disabled"
                +tr("Back up now", "Respaldar ahora")
            }
        }
    }
}

private fun FlowContent.promosSection(m: ConfigModel) {
    section("config-section") {
        attributes["id"] = "promos"
        h3 { +tr("Promo reminders", "Recordatorios de promociones") }
        p("hint") {
            +tr(
                "Bank and payment codes that recur on a fixed day, which no crawl can see. On the day and the day before: a strip on every page, " +
                    "the price after the discount on qualifying rows, a desktop banner, and a Discord post listing your pinned and tracked items that qualify.",
                "Códigos de bancos y medios de pago que se repiten un día fijo, y que ningún rastreo ve. El día y el anterior: una franja en cada página, " +
                    "el precio con descuento en las filas que califican, un aviso de escritorio y un post en Discord con tus fijados y seguimientos que califican.",
            )
        }
        if (m.promos.isNotEmpty()) {
            table("config-table") {
                caption("sr-only") { +tr("Promo reminders", "Recordatorios de promociones") }
                thead {
                    tr {
                        th(scope = ThScope.col) { +tr("Promo", "Promoción") }
                        th(scope = ThScope.col) { +tr("When", "Cuándo") }
                        th(scope = ThScope.col) { +tr("Next", "Próxima") }
                        th(scope = ThScope.col) { +"" }
                    }
                }
                tbody {
                    m.promos.forEach { p ->
                        tr(if (p.enabled) null else "dim") {
                            td {
                                +p.name
                                div("dim") {
                                    +p.offerText()
                                    p.sourcePrefix?.let { +tr(" · on $it", " · en $it") }
                                }
                            }
                            td {
                                +p.ruleText()
                                div("dim") {
                                    +tr("reminder ${p.notifyAt}", "recordatorio ${p.notifyAt}")
                                    if (p.remindDayBefore) +tr(", and the day before", ", y el día anterior")
                                }
                            }
                            td { +if (p.enabled) p.nextOn(m.now.atZone(m.zone).toLocalDate()).toString() else tr("off", "apagada") }
                            td {
                                form(action = "/config/promos/${p.id}/${if (p.enabled) "disable" else "enable"}", method = FormMethod.post, classes = "inline") {
                                    button(type = ButtonType.submit) { +if (p.enabled) tr("Turn off", "Apagar") else tr("Turn on", "Encender") }
                                }
                                form(action = "/config/promos/${p.id}/delete", method = FormMethod.post, classes = "inline") {
                                    button(type = ButtonType.submit) { +tr("Delete", "Borrar") }
                                }
                            }
                        }
                    }
                }
            }
        }
        form(action = "/config/promos", method = FormMethod.post, classes = "promo-form") {
            h3 { +tr("Add one", "Agregar") }
            fun field(name: String, label: String, ph: String, value: String? = null) {
                label {
                    +label
                    input(InputType.text, name = name) { placeholder = ph; value?.let { this.value = it } }
                }
            }
            field("name", tr("Name", "Nombre"), "Bancolombia first Tuesday")
            field("code", tr("Code", "Código"), "BANCOLMARTES")
            field("payment", tr("Pay with", "Pagar con"), "Bancolombia")
            field("sourcePrefix", tr("Store (source id or prefix)", "Tienda (id de fuente o prefijo)"), "amazon")
            label {
                +tr("Week", "Semana")
                select {
                    name = "nth"
                    listOf(1 to tr("first", "primer"), 2 to tr("second", "segundo"), 3 to tr("third", "tercer"), 4 to tr("fourth", "cuarto"), -1 to tr("last", "último"))
                        .forEach { (v, t) -> option { value = v.toString(); +t } }
                }
            }
            label {
                +tr("Day", "Día")
                select {
                    name = "weekday"
                    java.time.DayOfWeek.entries.forEach { d ->
                        option {
                            value = d.value.toString()
                            if (d == java.time.DayOfWeek.TUESDAY) selected = true
                            +d.getDisplayName(java.time.format.TextStyle.FULL, if (com.luis.dealstracker.i18n.I18n.lang == com.luis.dealstracker.i18n.Lang.ES) java.util.Locale.forLanguageTag("es") else java.util.Locale.ENGLISH)
                        }
                    }
                }
            }
            field("discount", tr("Discount (amount)", "Descuento (valor)"), "10")
            field("minAmount", tr("Minimum purchase", "Compra mínima"), "60")
            field("currency", tr("Currency", "Moneda"), "USD", "USD")
            field("notifyAt", tr("Remind at", "Recordar a las"), "08:00", "08:00")
            label {
                checkBoxInput(name = "remindDayBefore") { checked = true }
                +tr(" also the day before", " también el día anterior")
            }
            field("notes", tr("Notes", "Notas"), tr("e.g. once per card per month", "p. ej. una vez por tarjeta al mes"))
            button(type = ButtonType.submit, classes = "primary") { +tr("Add reminder", "Agregar recordatorio") }
        }
    }
}

/** The grocery agent: when it runs, how the last runs went and what they cost, and Run now. */
internal fun FlowContent.agentsSection(m: ConfigModel) {
    val runs = m.agentRuns ?: return
    section("config-section") {
        attributes["id"] = "agents"
        h3 { +tr("Agents", "Agentes") }
        p("hint") {
            +tr(
                "The grocery agent reads your groceries, pins and purchases through the read-only MCP server and leaves suggestions on the Groceries tab. " +
                    "It runs on your Claude Code login, so its cost counts against your plan. Its time, model and budget are under Schedules.",
                "El agente de mercado lee tu mercado, fijados y compras a través del servidor MCP de solo lectura y deja sugerencias en la pestaña Mercado. " +
                    "Corre con tu sesión de Claude Code, así que su costo cuenta contra tu plan. Su hora, modelo y presupuesto están en Horarios.",
            )
        }
        p {
            if (m.agentRunning) span("co-unk") { +tr("Running now…", "Corriendo ahora…") }
            else runs.firstOrNull()?.let { r ->
                span(if (r.status == "ok") "co-yes" else "co-no") { +if (r.status == "ok") tr("Last run ok", "Última corrida ok") else tr("Last run failed", "Última corrida falló") }
                +" ${relativeAgo(r.startedAt)}"
            } ?: span("dim") { +tr("Not run yet", "Aún no ha corrido") }
        }
        if (runs.isNotEmpty()) {
            table("config-table") {
                caption("sr-only") { +tr("Recent agent runs", "Corridas recientes") }
                thead {
                    tr {
                        th(scope = ThScope.col) { +tr("Started", "Inicio") }
                        th(scope = ThScope.col) { +tr("Result", "Resultado") }
                        th(scope = ThScope.col) { +tr("Suggestions", "Sugerencias") }
                        th(scope = ThScope.col) { +tr("Cost", "Costo") }
                    }
                }
                tbody {
                    runs.forEach { r ->
                        tr {
                            td {
                                +relativeAgo(r.startedAt)
                                if (r.trigger == "manual") span("dim") { +tr(" · by hand", " · a mano") }
                            }
                            td {
                                when (r.status) {
                                    "ok" -> span("co-yes") { +"ok" }
                                    "running" -> span("co-unk") { +tr("running", "corriendo") }
                                    else -> span("co-no") { +tr("failed", "falló") }
                                }
                                (r.error ?: r.summary)?.let { div("dim") { +it.take(240) } }
                            }
                            td("num") { +r.suggestions.toString() }
                            td("num") { +(r.costUsd?.let { "US$" + it.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "—") }
                        }
                    }
                }
            }
        }
        form(action = "/config/agents/grocery/run", method = FormMethod.post) {
            button(type = ButtonType.submit, classes = "primary") {
                if (m.agentRunning) attributes["disabled"] = "disabled"
                +tr("Run the grocery agent now", "Correr el agente de mercado ahora")
            }
        }
    }
}
