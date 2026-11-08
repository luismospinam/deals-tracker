package com.luis.dealstracker.agent

import com.luis.dealstracker.config.AppConfig
import com.luis.dealstracker.db.GroceryFilter
import com.luis.dealstracker.db.GroceryRepository
import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.ingest.unitPriceLabel
import com.luis.dealstracker.model.HeldCards
import com.luis.dealstracker.promo.ActivePromos
import com.luis.dealstracker.promo.offerText
import com.luis.dealstracker.web.GroceryStore
import com.luis.dealstracker.web.groceryStores
import com.luis.dealstracker.web.mergeStores
import com.luis.dealstracker.web.withRivals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/** One day's cheapest reading of a row, the unit every history question is asked in. */
internal data class DayPrice(val day: LocalDate, val price: BigDecimal, val cardPrice: BigDecimal?)

/** A purchase as the tools report it. */
internal data class Purchase(val dealId: Long?, val source: String, val productId: String, val title: String, val quantity: Int, val unitPrice: BigDecimal?, val currency: String, val at: Instant)

/** How often a product is bought, from its purchase dates. */
internal data class Cadence(val times: Int, val units: Int, val medianDaysBetween: Int?, val last: LocalDate, val daysSinceLast: Int) {
    /** Due when it has been at least the usual gap since the last one; unknown with one purchase. */
    val due: Boolean? get() = medianDaysBetween?.let { daysSinceLast >= it }
}

/**
 * [purchases] of one product as a buying rhythm. Purchases on the same day count once (two cart adds
 * of the same thing in one shop are one purchase of two).
 */
internal fun cadence(purchases: List<Purchase>, today: LocalDate, zone: ZoneId): Cadence? {
    if (purchases.isEmpty()) return null
    val days = purchases.map { it.at.atZone(zone).toLocalDate() }.distinct().sorted()
    val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.sorted()
    val median = if (gaps.isEmpty()) null else gaps[gaps.size / 2]
    return Cadence(days.size, purchases.sumOf { it.quantity }, median, days.last(), ChronoUnit.DAYS.between(days.last(), today).toInt())
}

/** The days a price changed, from a daily series: what a chart shows, at a fraction of the tokens. */
internal fun changePoints(series: List<DayPrice>): List<DayPrice> {
    val days = series.sortedBy { it.day }
    val points = days.filterIndexed { i, d -> i == 0 || days[i - 1].let { it.price != d.price || it.cardPrice != d.cardPrice } }
    // The last day too, so "unchanged since" reads as still true today rather than as the last reading.
    return if (days.isNotEmpty() && points.last() != days.last()) points + days.last() else points
}

/** Min, median and max of a daily series, and where [today] sits in it (0 = the low, 100 = the high). */
internal data class Range(val low: BigDecimal, val median: BigDecimal, val high: BigDecimal, val days: Int, val lowOn: LocalDate)

internal fun range(series: List<DayPrice>): Range? {
    if (series.isEmpty()) return null
    val sorted = series.map { it.price }.sorted()
    val lowDay = series.filter { it.price == sorted.first() }.maxOf { it.day }
    return Range(sorted.first(), sorted[sorted.size / 2], sorted.last(), series.size, lowDay)
}

private fun money(v: BigDecimal?) = v?.setScale(0, RoundingMode.HALF_UP)?.toPlainString() ?: ""

/** The header of [groceryLine]. */
internal const val GROCERY_HEADER =
    "id\ttitle\tbrand\tsize\taisle\tstore\tprice\tunit_price\tusual_30d\tlow_30d\tvs_usual_pct\tcard_price\trival\tpinned\ton_shelf"

/**
 * One grocery row as a tab-separated line. `vs_usual_pct` is positive when cheaper than usual; `rival`
 * is the cheapest copy of the same barcode at another chain ("Carulla 10080 #123").
 */
internal fun groceryLine(r: GroceryRow): String {
    val size = r.size?.let { "${it.quantity.stripTrailingZeros().toPlainString()} ${it.measure}" }.orEmpty()
    val unit = r.unitPrice?.let { "${it.toPlainString()}${unitPriceLabel(r.size)}" }.orEmpty()
    val held = HeldCards.current
    val card = r.cardOffers.filter { held.isEmpty() || it.scope in held }.minByOrNull { it.price }
        ?.let { "${money(it.price)} ${it.scope.name.lowercase()}" }.orEmpty()
    val rival = r.rival?.let { "${it.store} ${money(it.price)} #${it.dealId}" }.orEmpty()
    return listOf(
        r.deal.id.toString(), r.deal.title, r.deal.brand.orEmpty(), size, r.group.orEmpty(),
        r.store.ifEmpty { r.deal.source }, money(r.deal.price), unit, money(r.usual), money(r.low),
        r.belowUsual?.toPlainString().orEmpty(), card, rival, if (r.pinnedAt != null) "yes" else "", if (r.onShelf) "yes" else "no",
    ).joinToString("\t") { it.replace('\t', ' ') }
}

/** Sort orders for [AgentTools] searches. */
internal enum class GrocerySort { BELOW_USUAL, UNIT_PRICE, PRICE, TITLE }

internal fun sortGroceries(rows: List<GroceryRow>, sort: GrocerySort): List<GroceryRow> = when (sort) {
    GrocerySort.BELOW_USUAL -> rows.sortedWith(compareByDescending<GroceryRow, BigDecimal?>(nullsFirst()) { it.belowUsual })
    GrocerySort.UNIT_PRICE -> rows.sortedWith(compareBy(nullsLast()) { it.unitPrice })
    GrocerySort.PRICE -> rows.sortedBy { it.deal.price }
    GrocerySort.TITLE -> rows.sortedBy { it.deal.title }
}

/**
 * The tools the MCP server offers. The SQL here is kept thin; the shaping ([groceryLine], [cadence],
 * [changePoints], [range]) is plain functions with tests.
 */
class AgentTools(
    private val ds: DataSource,
    private val agentDb: () -> AgentDb?,
    private val groceries: GroceryRepository,
    private val app: () -> AppConfig,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private fun stores(): Map<String, GroceryStore> = groceryStores(app())
    private fun sources() = stores().keys.sorted()

    /** Rows for [filter], one per product per chain, with the other chain's price where the barcode matches. */
    private fun rows(filter: GroceryFilter): List<GroceryRow> {
        val stores = stores()
        val merged = mergeStores(groceries.rows(filter), stores)
        val keys = merged.mapNotNull { it.deal.matchKey }
        return if (keys.isEmpty()) merged else withRivals(merged, groceries.shelfPrices(keys, stores.keys.toList()), stores)
    }

    private fun table(rows: List<GroceryRow>, limit: Int, total: Int = rows.size): String = buildString {
        appendLine(GROCERY_HEADER)
        rows.take(limit).forEach { appendLine(groceryLine(it)) }
        if (total > limit) appendLine("… $limit of $total shown; narrow the search or raise limit")
        if (rows.isEmpty()) appendLine("(no rows)")
    }

    fun tools(): List<McpTool> = listOf(
        McpTool(
            "grocery_search",
            "Search the grocery catalogue (Exito Laureles, Exito Colombia, Carulla, PriceSmart; ~25k products) by title. " +
                "One row per product per chain, at its cheapest store today. Query syntax: words all must match, `a|b` either, `!word` excludes. " +
                "Columns: price and usual_30d (median daily price over 30 days) are COP; vs_usual_pct > 0 means cheaper than usual; " +
                "unit_price is per 100 g, per L or per unit; rival is the same barcode at another chain.",
            listOf(
                Param("query", "string", "Title words, e.g. \"arroz diana\" or \"cafe|tinto !descafeinado\""),
                Param("aisle", "string", "Exact aisle name to restrict to, as the aisle column shows it"),
                Param("chain", "string", "Only this chain", enum = listOf("exito", "carulla", "pricesmart")),
                Param("deals_only", "boolean", "Only rows at least 10% under their usual price"),
                Param("sort", "string", "Default below_usual", enum = GrocerySort.entries.map { it.name.lowercase() }),
                Param("limit", "integer", "Rows to return, 1-200 (default 40)"),
            ),
        ) { a, _ ->
            val query = a.str("query")
            val aisle = a.str("aisle")
            val dealsOnly = a.bool("deals_only")
            if (query == null && aisle == null && !dealsOnly) throw ToolError("give a query, an aisle, or deals_only; for the whole pinned list use grocery_pinned")
            val chain = a.str("chain")
            // The whole catalogue only when filtering to deals, which leaves a few hundred rows; otherwise a
            // search is narrow enough that 3,000 rows covers it.
            var rows = rows(GroceryFilter(sources(), listOfNotNull(aisle), query, limit = if (query == null && aisle == null) 30_000 else 3000))
            if (chain != null) rows = rows.filter { it.chain == chain }
            if (dealsOnly) rows = rows.filter { it.isDeal }
            val sort = a.str("sort")?.let { s -> GrocerySort.entries.firstOrNull { it.name.equals(s, true) } } ?: GrocerySort.BELOW_USUAL
            table(sortGroceries(rows, sort), a.int("limit", 40, 1..200))
        },
        McpTool(
            "grocery_pinned",
            "The operator's pinned groceries: their standing shopping list, with today's price, usual price, unit price and the other chain's price. Same columns as grocery_search.",
            emptyList(),
        ) { _, _ ->
            table(sortGroceries(rows(GroceryFilter(sources(), pinnedOnly = true)), GrocerySort.TITLE), 1000)
        },
        McpTool(
            "grocery_aisles",
            "The grocery aisles (subcategories) and how many products each has, for narrowing grocery_search.",
            emptyList(),
        ) { _, _ -> groceries.groups(sources()).joinToString("\n") { (g, n) -> "$g\t$n" } },
        McpTool(
            "product",
            "One product in depth, by id: today's row at every store, its 90- and 365-day price range from daily lows, " +
                "the days its price changed in the last 90 days, how often the operator buys it, and past suggestions about it.",
            listOf(Param("id", "integer", "A product id from another tool", required = true)),
        ) { a, _ -> product(a.long("id") ?: throw ToolError("`id` must be a number")) },
        McpTool(
            "price_history",
            "A product's daily lowest price over the last N days, as the days it changed (plus the last day). card_price is the price with a store card where one applied.",
            listOf(
                Param("id", "integer", "Product id", required = true),
                Param("days", "integer", "1-730, default 120"),
            ),
        ) { a, _ ->
            val id = a.long("id") ?: throw ToolError("`id` must be a number")
            val series = dailyPrices(id, a.int("days", 120, 1..730))
            buildString {
                appendLine("day\tprice\tcard_price")
                changePoints(series).forEach { appendLine("${it.day}\t${money(it.price)}\t${money(it.cardPrice)}") }
                if (series.isEmpty()) appendLine("(no readings)")
            }
        },
        McpTool(
            "purchases",
            "What the operator bought (from items added to the tracker's Exito cart), with each product's rhythm: " +
                "times bought, median days between purchases, days since the last, and whether it is due. History started 2026-11-03, so early on most products have one purchase or none.",
            listOf(
                Param("days", "integer", "Look back this many days, 1-730, default 180"),
                Param("query", "string", "Only products whose title contains this"),
            ),
        ) { a, _ -> purchasesText(a.int("days", 180, 1..730), a.str("query")) },
        McpTool(
            "promos",
            "Upcoming payment-promo days (bank codes like BANCOLMARTES) in the next N days, with the rule and the stores they apply to.",
            listOf(Param("days", "integer", "1-90, default 45")),
        ) { a, _ ->
            val today = LocalDate.now(zone)
            val end = today.plusDays(a.int("days", 45, 1..90).toLong())
            val lines = ActivePromos.current.filter { it.enabled }.flatMap { p ->
                generateSequence(p.nextOn(today)) { p.nextOn(it.plusDays(1)) }.takeWhile { !it.isAfter(end) }
                    .map { "$it\t${p.name}\t${p.offerText()}\t${p.sourcePrefix ?: "any store"}" }.toList()
            }.sorted()
            if (lines.isEmpty()) "(no promo days in range)" else "day\tpromo\toffer\tstores\n" + lines.joinToString("\n")
        },
        McpTool(
            "past_suggestions",
            "Suggestions made before and how the operator answered (accepted, dismissed with a note, or still open). " +
                "Read these before suggesting: do not repeat a dismissed idea, and learn from the notes.",
            listOf(
                Param("days", "integer", "1-365, default 60"),
                Param("status", "string", "Only this status", enum = listOf("new", "accepted", "dismissed", "expired")),
            ),
        ) { a, _ -> pastSuggestions(a.int("days", 60, 1..365), a.str("status")) },
        McpTool(
            "sql",
            "Read-only SQL (PostgreSQL 16) for what the other tools do not answer. At most 500 rows, 10 s. Main tables: " +
                "deals(id, source, title, brand, grocery_group, unit_measure g|ml|un, unit_quantity, match_key = barcode, pinned_at, is_active, usual_price, usual_low, currency); " +
                "price_observations(deal_id, observed_at, price, list_price, card_price, card_scope) -- 3.3M rows, always filter by deal_id or a recent observed_at; " +
                "purchases(deal_id, title, quantity, unit_price, bought_at); suggestions(kind, deal_id, title, reason, status, note, created_at); " +
                "promotions/observation_promotions (store promotions per reading); fetch_runs(source, started_at, item_count). " +
                "Grocery sources: exito-grocery (Exito Laureles), exito-colombia-grocery, carulla-grocery, pricesmart-*-grocery. Times are UTC; the operator is in America/Bogota.",
            listOf(Param("query", "string", "One SELECT statement", required = true)),
        ) { a, _ ->
            val db = agentDb() ?: throw ToolError("the read-only database connection is not available")
            db.query(a.require("query")).toTsv()
        },
        McpTool(
            "suggest",
            "Add one suggestion to the operator's inbox on the Groceries tab, to accept or dismiss. The only tool that writes. " +
                "kind: buy_now (cheap now and due), stock_up (keeps, unusually cheap: buy several), wait (above usual, likely to drop), " +
                "switch (cheaper per unit at another store or size; deal_id is the one to switch TO), pin (worth adding to the pinned list). " +
                "The reason must cite the numbers (price, usual, low, unit price, days since last bought). One suggestion per product per kind while it is open.",
            listOf(
                Param("kind", "string", "What to do", required = true, enum = listOf("buy_now", "stock_up", "wait", "switch", "pin", "other")),
                Param("deal_id", "integer", "The product it is about", required = true),
                Param("reason", "string", "One or two sentences with the numbers, in the operator's language", required = true),
            ),
            writes = true,
        ) { a, caller -> suggest(a.require("kind"), a.long("deal_id") ?: throw ToolError("`deal_id` must be a number"), a.require("reason"), caller) },
    )

    // --- data, kept thin -------------------------------------------------------------------------------

    internal fun dailyPrices(id: Long, days: Int): List<DayPrice> = ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT (observed_at AT TIME ZONE ?)::date AS day, min(price), min(card_price)
              FROM price_observations
             WHERE deal_id = ? AND observed_at >= now() - make_interval(days => ?)
             GROUP BY 1 ORDER BY 1
            """.trimIndent(),
        ).use { st ->
            st.setString(1, zone.id); st.setLong(2, id); st.setInt(3, days)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(DayPrice(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getBigDecimal(3))) }
            }
        }
    }

    private fun product(id: Long): String {
        val all = rows(GroceryFilter(sources(), id = id))
        val row = all.firstOrNull() ?: return productOutsideGroceries(id)
        val y = dailyPrices(id, 365)
        val q = y.filter { !it.day.isBefore(LocalDate.now(zone).minusDays(90)) }
        return buildString {
            appendLine(GROCERY_HEADER)
            all.forEach { r -> appendLine(groceryLine(r)); r.others.forEach { appendLine(groceryLine(it)) } }
            range(q)?.let { appendLine("90d: low ${money(it.low)} (${it.lowOn}), median ${money(it.median)}, high ${money(it.high)}, ${it.days} days") }
            range(y)?.let { appendLine("365d: low ${money(it.low)} (${it.lowOn}), median ${money(it.median)}, high ${money(it.high)}, ${it.days} days") }
            appendLine("price changes, 90d:")
            changePoints(q).forEach { appendLine("  ${it.day} ${money(it.price)}${it.cardPrice?.let { c -> " (card ${money(c)})" }.orEmpty()}") }
            val bought = purchasesOf(row.deal.source, row.deal.sourceDealId)
            appendLine(
                cadence(bought, LocalDate.now(zone), zone)?.let {
                    "bought ${it.times}x (${it.units} units), last ${it.last} (${it.daysSinceLast}d ago)" +
                        (it.medianDaysBetween?.let { m -> ", usually every ${m}d" } ?: "")
                } ?: "never bought (since 2026-11-03)",
            )
            appendLine(pastSuggestions(365, null, dealId = id).let { if (it.startsWith("(")) "no past suggestions" else "past suggestions:\n$it" })
        }
    }

    private fun productOutsideGroceries(id: Long): String = ds.connection.use { conn ->
        conn.prepareStatement("SELECT id, source, title, currency, is_active FROM deals WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw ToolError("no product with id $id")
                "#${rs.getLong(1)} ${rs.getString(3)} (${rs.getString(2)}, ${rs.getString(4)}, active=${rs.getBoolean(5)}) is not a grocery row; use price_history or sql for it."
            }
        }
    }

    private fun purchasesOf(source: String, productId: String): List<Purchase> =
        loadPurchases("source = ? AND product_id = ?", listOf(source, productId), 3650)

    private fun loadPurchases(where: String, params: List<String>, days: Int): List<Purchase> = ds.connection.use { conn ->
        conn.prepareStatement(
            "SELECT deal_id, source, product_id, title, quantity, unit_price, currency, bought_at FROM purchases " +
                "WHERE $where AND bought_at >= now() - make_interval(days => ?) ORDER BY bought_at",
        ).use { st ->
            params.forEachIndexed { i, p -> st.setString(i + 1, p) }
            st.setInt(params.size + 1, days)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Purchase(
                                rs.getLong(1).takeIf { !rs.wasNull() }, rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getInt(5), rs.getBigDecimal(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun purchasesText(days: Int, query: String?): String {
        val list = loadPurchases(
            if (query == null) "TRUE" else "lower(title) LIKE ?",
            listOfNotNull(query?.let { "%${it.lowercase().replace("%", "").replace("_", "")}%" }),
            days,
        )
        if (list.isEmpty()) return "(no purchases recorded in the last $days days; the log started 2026-11-03)"
        val today = LocalDate.now(zone)
        return buildString {
            appendLine("deal_id\ttitle\ttimes\tunits\tlast\tdays_since\tmedian_days_between\tdue\tlast_price")
            list.groupBy { it.source to it.productId }.values
                .map { it to cadence(it, today, zone)!! }
                .sortedByDescending { it.second.times }
                .forEach { (ps, c) ->
                    val last = ps.last()
                    appendLine(
                        listOf(
                            last.dealId?.toString().orEmpty(), last.title, c.times, c.units, c.last, c.daysSinceLast,
                            c.medianDaysBetween ?: "", c.due?.let { if (it) "yes" else "no" } ?: "", money(last.unitPrice),
                        ).joinToString("\t"),
                    )
                }
        }
    }

    private fun pastSuggestions(days: Int, status: String?, dealId: Long? = null): String = ds.connection.use { conn ->
        val where = buildList {
            add("created_at >= now() - make_interval(days => ?)")
            if (status != null) add("status = ?")
            if (dealId != null) add("deal_id = ?")
        }.joinToString(" AND ")
        conn.prepareStatement(
            "SELECT created_at, agent, kind, deal_id, title, reason, status, note FROM suggestions WHERE $where ORDER BY created_at DESC LIMIT 200",
        ).use { st ->
            var i = 1
            st.setInt(i++, days)
            if (status != null) st.setString(i++, status)
            if (dealId != null) st.setLong(i, dealId)
            st.executeQuery().use { rs ->
                val lines = buildList {
                    while (rs.next()) {
                        add(
                            listOf(
                                rs.getTimestamp(1).toInstant().atZone(zone).toLocalDate(), rs.getString(2), rs.getString(3),
                                rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8).orEmpty(),
                            ).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') },
                        )
                    }
                }
                if (lines.isEmpty()) "(none)" else "date\tagent\tkind\tdeal_id\ttitle\treason\tstatus\tnote\n" + lines.joinToString("\n")
            }
        }
    }

    private fun suggest(kind: String, dealId: Long, reason: String, caller: McpCaller): String {
        if (kind !in SUGGESTION_KINDS) throw ToolError("kind must be one of ${SUGGESTION_KINDS.joinToString()}")
        if (reason.length < 15) throw ToolError("the reason must say why, with the numbers")
        return ds.connection.use { conn ->
            val title = conn.prepareStatement("SELECT title FROM deals WHERE id = ?").use { st ->
                st.setLong(1, dealId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else throw ToolError("no product with id $dealId: use an id a tool returned") }
            }
            val open = conn.prepareStatement("SELECT 1 FROM suggestions WHERE deal_id = ? AND kind = ? AND status = 'new'").use { st ->
                st.setLong(1, dealId); st.setString(2, kind)
                st.executeQuery().use { it.next() }
            }
            if (open) throw ToolError("there is already an open $kind suggestion for #$dealId")
            conn.prepareStatement(
                "INSERT INTO suggestions (run_id, agent, kind, deal_id, title, reason) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            ).use { st ->
                if (caller.runId != null) st.setLong(1, caller.runId) else st.setNull(1, java.sql.Types.BIGINT)
                st.setString(2, caller.agent); st.setString(3, kind); st.setLong(4, dealId); st.setString(5, title)
                st.setString(6, reason.take(600))
                st.executeQuery().use { rs -> rs.next(); "added suggestion #${rs.getLong(1)}: $kind $title" }
            }
        }
    }

    companion object {
        val SUGGESTION_KINDS = listOf("buy_now", "stock_up", "wait", "switch", "pin", "other")
    }
}
