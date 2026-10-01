package com.luis.dealstracker.web

import com.luis.dealstracker.db.FeedFilter
import com.luis.dealstracker.i18n.Lang
import com.luis.dealstracker.i18n.tr
import com.luis.dealstracker.model.Deal
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Excel export of whatever the feed is currently showing.
 *
 * Two decisions worth understanding before changing anything here.
 *
 * **It exports every matching row, not the page.** [FeedFilter.limit] caps the *rendered*
 * page at 1000 so the browser stays responsive, and the UI says "showing 1000 of 1420"
 * when that bites. A file cannot say that in passing, so inheriting the display cap would
 * hand over a spreadsheet that looks complete and silently isn't. The route raises the
 * limit to [MAX_ROWS] instead, and the Export info sheet records both counts so the one
 * remaining ceiling is visible in the file itself.
 *
 * **Unknown stays blank, never zero.** The same rule the feed UI follows: Slickdeals
 * publishes no list price, so its discount is *unknown*, and Éxito publishes no community
 * score. Writing 0 into those cells would make them sort and average as though the facts
 * were known. Nullable values leave the cell uncreated.
 */
object DealsExport {

    const val CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /**
     * Ceiling on rows in one file, well above the current volume (~370 deals). Exists only
     * so a runaway filter can't try to stream an unbounded result set into memory; when it
     * does bite, the Export info sheet flags the file as truncated rather than letting the
     * shortfall pass unnoticed.
     */
    const val MAX_ROWS = 50_000

    /** Row cap plus the unfiltered total, so a truncated file can say so about itself. */
    data class Meta(
        val generatedAt: Instant,
        val filter: FeedFilter,
        val totalMatching: Int,
        val rowsExported: Int,
    ) {
        val truncated: Boolean get() = rowsExported < totalMatching
    }

    fun filename(today: LocalDate): String = "deals-$today.xlsx"

    /**
     * One column: its header, its width in characters, and how a deal fills its cell.
     *
     * Declarative so the header row and the data rows cannot fall out of step — the
     * failure mode with hand-written `createCell(0)` / `createCell(1)` sequences is a
     * column whose heading describes the value one place to its left.
     */
    private class Col(
        val header: String,
        /** The Spanish heading. The English [header] stays the one tests and [headers] use. */
        val headerEs: String,
        val widthChars: Int,
        val write: (Cell, Deal, Styles) -> Unit,
    ) {
        fun header(lang: Lang) = lang.tr(header, headerEs)
    }

    private val COLUMNS: List<Col> = listOf(
        Col("Deal ID", "ID de oferta", 8) { c, d, s -> c.setNum(d.id.toDouble(), s.integer) },
        Col("Source", "Fuente", 13) { c, d, _ -> c.setCellValue(d.source) },
        Col("Title", "Título", 60) { c, d, _ -> c.setCellValue(d.title) },
        Col("URL", "URL", 44) { c, d, _ -> c.setCellValue(d.url) },
        Col("Merchant", "Vendedor", 16) { c, d, _ -> d.merchant?.let { c.setCellValue(it) } },
        // The normalized bucket the Category filter uses, then the source's own raw path.
        // Both, because the bucket is comparable across sources and the raw path is what
        // you check when a bucket looks wrong.
        Col("Category", "Categoría", 18) { c, d, _ -> d.categoryGroup?.let { c.setCellValue(it) } },
        Col("Source category", "Categoría en la fuente", 30) { c, d, _ -> d.category?.let { c.setCellValue(it) } },
        Col("Price", "Precio", 14) { c, d, s -> c.setNum(d.price.toDouble(), s.money(d.currency)) },
        // Mixed currencies share the Price column (USD from Slickdeals, COP from the
        // Colombian retailers), so the code has to travel with them to stay meaningful.
        Col("Currency", "Moneda", 9) { c, d, _ -> c.setCellValue(d.currency) },
        Col("List price", "Precio de lista", 14) { c, d, s -> d.listPrice?.let { c.setNum(it.toDouble(), s.money(d.currency)) } },
        // Written as a true fraction under a percent format: 0.25 displayed as "25.0%".
        // Writing 25.0 instead would render as 2500% and break any average over the column.
        Col("Discount", "Descuento", 10) { c, d, s ->
            d.discountPercent?.let { c.setNum(it.divide(HUNDRED, 6, RoundingMode.HALF_UP).toDouble(), s.percent) }
        },
        Col("Community score", "Puntaje de la comunidad", 10) { c, d, s -> d.communityScore?.let { c.setNum(it.toDouble(), s.integer) } },
        Col("Lowest recorded", "Mínimo registrado", 14) { c, d, s -> d.minPriceEver?.let { c.setNum(it.toDouble(), s.money(d.currency)) } },
        Col("Highest recorded", "Máximo registrado", 14) { c, d, s -> d.maxPriceEver?.let { c.setNum(it.toDouble(), s.money(d.currency)) } },
        Col("Drop from peak", "Baja desde el máximo", 14) { c, d, s -> d.dropFromPeak?.let { c.setNum(it.toDouble(), s.money(d.currency)) } },
        Col("At lowest ever", "En su mínimo histórico", 12) { c, d, _ -> c.setCellValue(d.isAtLowestEver) },
        Col("Observations", "Observaciones", 11) { c, d, s -> c.setNum(d.observationCount.toDouble(), s.integer) },
        Col("Colombia shipping", "Envío a Colombia", 16) { c, d, _ -> c.setCellValue(d.coShipping.name) },
        // The verdict alone is misleading: "ships to Colombia" covers both free shipping
        // and a $112 fee that erases any discount. Numeric so it sorts and sums in Excel;
        // blank when Amazon quoted nothing, which is distinct from a quoted zero.
        // Always USD: Amazon quotes the international fee in dollars regardless of the
        // item's own currency, so it is not d.currency here.
        Col("Ship fee (USD)", "Costo de envío (USD)", 13) { c, d, s -> d.coShippingCost?.let { c.setNum(it.toDouble(), s.money("USD")) } },
        Col("State", "Estado", 11) { c, d, _ -> c.setCellValue(d.state.name) },
        Col("Promoted", "Destacada", 10) { c, d, _ -> c.setCellValue(d.promoted) },
        // Posted by the source, versus when we first saw it. Distinct facts: Slickdeals'
        // paginated search reaches back over a year, and VTEX publishes no posting date
        // at all -- which is why this column is blank for the Colombian sources.
        Col("Posted at", "Publicada", 18) { c, d, s -> d.postedAt?.let { c.setDate(it, s) } },
        Col("First seen", "Vista por primera vez", 18) { c, d, s -> c.setDate(d.firstSeenAt, s) },
        Col("Last seen", "Vista por última vez", 18) { c, d, s -> c.setDate(d.lastSeenAt, s) },
        Col("ASIN", "ASIN", 12) { c, d, _ -> d.asin?.let { c.setCellValue(it) } },
    )

    /** Column headers in order. Exposed so a test can assert against them by name. */
    val headers: List<String> get() = COLUMNS.map { it.header }

    /** [lang] names the sheets, headings and info labels; the deals' own data is written as stored. */
    fun build(deals: List<Deal>, meta: Meta, zone: ZoneId = ZoneId.systemDefault(), lang: Lang = Lang.EN): ByteArray {
        // 200 rows held in memory at a time; the rest are flushed to a temp file. Bounded
        // memory regardless of what the filter matches.
        val book = SXSSFWorkbook(200)
        try {
            val styles = Styles(book, zone)
            writeDealsSheet(book, styles, deals, lang)
            writeInfoSheet(book, styles, meta, lang)

            val out = ByteArrayOutputStream()
            book.write(out)
            return out.toByteArray()
        } finally {
            // Deletes SXSSF's backing temp files. Skipping this leaks them into /tmp on
            // every export.
            book.dispose()
            book.close()
        }
    }

    private fun writeDealsSheet(book: Workbook, styles: Styles, deals: List<Deal>, lang: Lang) {
        val sheet = book.createSheet(lang.tr("Deals", "Ofertas"))
        COLUMNS.forEachIndexed { i, col -> sheet.setColumnWidth(i, col.widthChars * 256) }

        sheet.createRow(0).let { header ->
            COLUMNS.forEachIndexed { i, col ->
                header.createCell(i).apply {
                    setCellValue(col.header(lang))
                    cellStyle = styles.header
                }
            }
        }

        deals.forEachIndexed { rowIndex, deal ->
            val row = sheet.createRow(rowIndex + 1)
            COLUMNS.forEachIndexed { i, col -> col.write(row.createCell(i), deal, styles) }
        }

        // Header stays visible while scrolling, and every column gets a filter dropdown --
        // the point of exporting to a spreadsheet rather than reading the web page.
        sheet.createFreezePane(0, 1)
        if (deals.isNotEmpty()) {
            sheet.setAutoFilter(CellRangeAddress(0, deals.size, 0, COLUMNS.size - 1))
        }
    }

    /**
     * Records what produced this file.
     *
     * On its own sheet rather than as banner rows above the data, which would sit inside
     * the autofilter range and sort along with the deals. It also answers, in the file,
     * the question the filter form cannot: an untouched form is not "no filter" — active
     * deals only, dismissed excluded.
     */
    private fun writeInfoSheet(book: Workbook, styles: Styles, meta: Meta, lang: Lang) {
        val sheet = book.createSheet(lang.tr("Export info", "Información de la exportación"))
        sheet.setColumnWidth(0, 26 * 256)
        sheet.setColumnWidth(1, 52 * 256)

        var r = 0
        fun pair(label: String, value: String, style: CellStyle? = null) {
            val row = sheet.createRow(r++)
            row.createCell(0).apply { setCellValue(label); cellStyle = styles.label }
            row.createCell(1).apply { setCellValue(value); style?.let { cellStyle = it } }
        }

        pair(lang.tr("Generated", "Generado"), styles.formatDateTime(meta.generatedAt))
        pair(lang.tr("Rows in this file", "Filas en este archivo"), meta.rowsExported.toString())
        pair(lang.tr("Total matching the filter", "Total que coincide con el filtro"), meta.totalMatching.toString())
        // Always written, including the FALSE case: a flag that appears only on failure is
        // indistinguishable from a flag nobody remembered to write.
        pair(
            lang.tr("Truncated", "Recortado"),
            if (meta.truncated) {
                lang.tr(
                    "YES -- capped at $MAX_ROWS rows, ${meta.totalMatching - meta.rowsExported} row(s) omitted",
                    "SÍ -- limitado a $MAX_ROWS filas, ${meta.totalMatching - meta.rowsExported} fila(s) omitida(s)",
                )
            } else {
                lang.tr("no", "no")
            },
        )

        r++
        sheet.createRow(r++).createCell(0).apply { setCellValue(lang.tr("Filters applied", "Filtros aplicados")); cellStyle = styles.header }
        meta.filter.describe(lang).forEach { (label, value) -> pair(label, value) }
    }

    private val HUNDRED = BigDecimal(100)

    private fun Cell.setNum(value: Double, style: CellStyle) {
        setCellValue(value)
        cellStyle = style
    }

    private fun Cell.setDate(instant: Instant, styles: Styles) {
        setCellValue(LocalDateTime.ofInstant(instant, styles.zone))
        cellStyle = styles.dateTime
    }

    /**
     * Cell styles, created once per workbook.
     *
     * Not per cell: a workbook has a hard 64,000-style limit, and creating a style inside
     * the row loop hits it at a few thousand deals.
     */
    // `book` is a val because money() creates styles lazily, after construction.
    private class Styles(private val book: Workbook, val zone: ZoneId) {
        private val formats = book.createDataFormat()
        private fun style(pattern: String): CellStyle =
            book.createCellStyle().apply { dataFormat = formats.getFormat(pattern) }

        val header: CellStyle = book.createCellStyle().apply {
            setFont(book.createFont().apply { bold = true })
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.LEFT
        }
        val label: CellStyle = book.createCellStyle().apply {
            setFont(book.createFont().apply { bold = true })
        }
        val integer: CellStyle = style("#,##0")
        val percent: CellStyle = style("0.0%")
        val dateTime: CellStyle = style("yyyy-mm-dd hh:mm")

        private val money = mutableMapOf<String, CellStyle>()

        /** Mirrors the UI's formatting: COP has no cents in practice, USD does. */
        fun money(currency: String): CellStyle = money.getOrPut(currency) {
            style(
                when (currency) {
                    "COP" -> "\"${'$'}\"#,##0"
                    "USD" -> "\"US${'$'}\"#,##0.00"
                    else -> "#,##0.00"
                },
            )
        }

        fun formatDateTime(instant: Instant): String =
            LocalDateTime.ofInstant(instant, zone).withNano(0).toString().replace('T', ' ')
    }
}

/**
 * The filter, in the terms the form presents it, for the Export info sheet.
 *
 * Every field is listed even when unset, and the constraints no form control exposes are
 * spelled out at the end. "I exported without filtering anything" then has a written
 * answer in the file rather than requiring a read of `criteria()`.
 */
internal fun FeedFilter.describe(lang: Lang = Lang.EN): List<Pair<String, String>> = buildList {
    add(lang.tr("Search", "Búsqueda") to (search?.takeIf { it.isNotBlank() } ?: lang.tr("(none)", "(ninguna)")))
    add(
        lang.tr("Source", "Fuente") to when {
            sources.isNotEmpty() -> sources.joinToString(", ")
            excludeSources.isNotEmpty() -> lang.tr("all except ", "todas excepto ") + excludeSources.joinToString(", ")
            else -> lang.tr("all", "todas")
        },
    )
    add(lang.tr("Category", "Categoría") to categoryGroups.ifEmpty { listOf(lang.tr("all", "todas")) }.joinToString(", "))
    add(lang.tr("Min discount %", "Descuento mínimo %") to (minDiscount?.toPlainString() ?: lang.tr("any", "cualquiera")))
    add(lang.tr("Min price", "Precio mínimo") to (minPrice?.toPlainString() ?: lang.tr("any", "cualquiera")))
    add(lang.tr("Max price", "Precio máximo") to (maxPrice?.toPlainString() ?: lang.tr("any", "cualquiera")))
    add(lang.tr("Min community score", "Puntaje mínimo de la comunidad") to (minScore?.toString() ?: lang.tr("any", "cualquiera")))
    add(lang.tr("Colombia shipping", "Envío a Colombia") to (coShipping.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: lang.tr("any", "cualquiera")))
    add(lang.tr("State", "Estado") to states.joinToString(", ") { it.name })
    add(
        lang.tr("Only", "Solo") to when {
            onlyPromoted -> lang.tr("promoted", "destacadas")
            onlyLowestEver -> lang.tr("at lowest ever", "en su mínimo histórico")
            else -> lang.tr("everything", "todo")
        },
    )
    add(lang.tr("Sort", "Orden") to sort)
    add(lang.tr("Max age (days)", "Antigüedad máxima (días)") to (maxAgeDays?.toString() ?: lang.tr("any", "cualquiera")))
    // Applied by DealRepository.criteria() on every query, with no form control to relax
    // it. Without saying so, an export missing a deal Luis remembers looks like a bug.
    //
    // The favorites export is the one caller that relaxes it, and that has to be stated rather
    // than left as a stale absolute: a file whose rows include dead offers while its own info
    // sheet promises "active deals only" is worse than one that says nothing, because it would be
    // read as evidence the offers are live.
    add(
        lang.tr("Always applied", "Siempre aplicado") to if (includeInactive) {
            lang.tr(
                "favorites only -- retired deals (unseen for staleAfterHours) ARE included, and their " +
                    "price is the last one recorded rather than a current one",
                "solo favoritas -- las ofertas retiradas (sin verse durante staleAfterHours) SÍ se incluyen, y su " +
                    "precio es el último registrado, no uno actual",
            )
        } else {
            lang.tr(
                "active deals only -- retired deals (unseen for staleAfterHours) are never exported",
                "solo ofertas activas -- las ofertas retiradas (sin verse durante staleAfterHours) nunca se exportan",
            )
        },
    )
}
