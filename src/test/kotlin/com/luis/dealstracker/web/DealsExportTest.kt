package com.luis.dealstracker.web

import com.luis.dealstracker.db.FeedFilter
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export tests. No database and no network: the workbook is built from hand-made [Deal]
 * values and read back with POI, so the column layout and the blank-vs-zero rule are
 * verifiable offline like every other parser here.
 */
class DealsExportTest {

    private val zone = ZoneId.of("America/Bogota")
    private val now = Instant.parse("2026-08-27T15:00:00Z")

    private fun deal(
        id: Long = 1,
        source: String = "exito",
        title: String = "Televisor 55 pulgadas",
        currency: String = "COP",
        price: BigDecimal = BigDecimal("1899900.00"),
        listPrice: BigDecimal? = BigDecimal("2999900.00"),
        communityScore: Int? = null,
        minPriceEver: BigDecimal? = BigDecimal("1899900.00"),
        maxPriceEver: BigDecimal? = BigDecimal("2999900.00"),
        postedAt: Instant? = null,
        coShippingCost: BigDecimal? = null,
    ) = Deal(
        id = id,
        source = source,
        sourceDealId = "sd-$id",
        title = title,
        url = "https://www.exito.com/producto/p",
        merchant = "exito.com",
        asin = null,
        category = "Tecnología > Televisores",
        imageUrl = null,
        currency = currency,
        firstSeenAt = now,
        lastSeenAt = now,
        isActive = true,
        state = DealState.NEW,
        coShipping = CoShipping.YES,
        promoted = true,
        promotedAt = now,
        communityScore = communityScore,
        postedAt = postedAt,
        categoryGroup = "TV & Video",
        coShippingSource = "AUTO",
        coShippingEvidence = "FREE delivery to Colombia",
        coShippingImportFree = true,
        coShippingCost = coShippingCost,
        price = price,
        listPrice = listPrice,
        minPriceEver = minPriceEver,
        maxPriceEver = maxPriceEver,
        observationCount = 4,
    )

    private fun meta(
        filter: FeedFilter = FeedFilter(),
        totalMatching: Int = 1,
        rowsExported: Int = 1,
    ) = DealsExport.Meta(now, filter, totalMatching, rowsExported)

    private fun read(bytes: ByteArray) = WorkbookFactory.create(ByteArrayInputStream(bytes))

    private fun Row.text(header: String): String? {
        val index = DealsExport.headers.indexOf(header)
        assertTrue(index >= 0, "no such column: $header")
        return getCell(index)?.let { if (it.cellType == CellType.BLANK) null else it.toString() }
    }

    private fun Row.number(header: String): Double? {
        val index = DealsExport.headers.indexOf(header)
        assertTrue(index >= 0, "no such column: $header")
        return getCell(index)?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue
    }

    @Test
    fun `header row matches the declared columns`() {
        read(DealsExport.build(listOf(deal()), meta(), zone)).use { book ->
            val header = book.getSheet("Deals").getRow(0)
            DealsExport.headers.forEachIndexed { i, name ->
                assertEquals(name, header.getCell(i).stringCellValue)
            }
        }
    }

    @Test
    fun `one row per deal, values under the right headers`() {
        val deals = listOf(deal(id = 1), deal(id = 2, title = "Nevera"), deal(id = 3, title = "Lavadora"))
        read(DealsExport.build(deals, meta(rowsExported = 3, totalMatching = 3), zone)).use { book ->
            val sheet = book.getSheet("Deals")
            assertEquals(3, sheet.lastRowNum, "3 data rows plus a header")
            assertEquals("Nevera", sheet.getRow(2).text("Title"))
            assertEquals(2.0, sheet.getRow(2).number("Deal ID"))
            assertEquals("exito", sheet.getRow(2).text("Source"))
        }
    }

    /**
     * Prices must be numeric cells, not text. Written as strings they neither sum nor sort,
     * which is the whole reason for producing .xlsx rather than CSV.
     */
    @Test
    fun `prices are numeric cells carrying full precision`() {
        read(DealsExport.build(listOf(deal()), meta(), zone)).use { book ->
            val row = book.getSheet("Deals").getRow(1)
            assertEquals(1_899_900.0, row.number("Price"))
            assertEquals(2_999_900.0, row.number("List price"))
            assertEquals(4.0, row.number("Observations"))
        }
    }

    /**
     * The UI's rule, carried into the spreadsheet: an empty cell means *unknown*, not zero.
     * Slickdeals publishes no list price and Éxito no community score; a 0 in either column
     * would sort and average as though the value were known to be zero.
     */
    @Test
    fun `unknown values leave the cell blank rather than writing zero`() {
        val slickdeals = deal(
            id = 9,
            source = "slickdeals",
            title = "Anker USB-C Charger",
            currency = "USD",
            price = BigDecimal("18.99"),
            listPrice = null,
            communityScore = 42,
            minPriceEver = BigDecimal("18.99"),
            maxPriceEver = BigDecimal("18.99"),
        )
        read(DealsExport.build(listOf(slickdeals), meta(), zone)).use { book ->
            val row = book.getSheet("Deals").getRow(1)
            assertNull(row.number("List price"), "no list price published")
            assertNull(row.number("Discount"), "unknown discount, not 0%")
            assertNull(row.number("Drop from peak"), "price never moved")
            assertEquals(42.0, row.number("Community score"))
            // VTEX and MercadoLibre are the mirror image -- a real discount but no score --
            // and neither publishes a posting date, so this stays blank rather than
            // falling back to first_seen_at, which records only our own discovery time.
            assertNull(row.number("Posted at"), "no posting date published")
        }
    }

    /**
     * Written as a fraction under a percent format. Storing 26.7 instead would render as
     * 2670% and make any average over the column meaningless.
     */
    @Test
    fun `discount is stored as a fraction, not as a percentage number`() {
        read(DealsExport.build(listOf(deal()), meta(), zone)).use { book ->
            val row = book.getSheet("Deals").getRow(1)
            val discount = assertNotNull(row.number("Discount"))
            // 1 - 1899900/2999900 = 36.7%
            assertTrue(discount > 0.36 && discount < 0.37, "expected ~0.367, got $discount")
            assertTrue(
                row.getCell(DealsExport.headers.indexOf("Discount")).cellStyle.dataFormatString.contains("%"),
                "must carry a percent format so it displays as 36.7%",
            )
        }
    }

    /** Empty result: still a valid workbook with headers, not a corrupt or zero-byte file. */
    @Test
    fun `an empty selection still produces a readable sheet`() {
        read(DealsExport.build(emptyList(), meta(totalMatching = 0, rowsExported = 0), zone)).use { book ->
            val sheet = book.getSheet("Deals")
            assertEquals(0, sheet.lastRowNum, "header row only")
            assertEquals("Deal ID", sheet.getRow(0).getCell(0).stringCellValue)
        }
    }

    /**
     * The counts that make a short file self-explanatory. Without these, an export capped
     * at MAX_ROWS looks identical to a complete one.
     */
    @Test
    fun `export info records the row counts and flags truncation`() {
        val bytes = DealsExport.build(
            listOf(deal()),
            meta(totalMatching = 1420, rowsExported = 1),
            zone,
        )
        read(bytes).use { book ->
            val info = book.getSheet("Export info").asSequence()
                .associate { it.getCell(0)?.toString().orEmpty() to it.getCell(1)?.toString().orEmpty() }
            assertEquals("1", info["Rows in this file"])
            assertEquals("1420", info["Total matching the filter"])
            assertTrue(info["Truncated"]!!.startsWith("YES"), "truncation must be stated: ${info["Truncated"]}")
        }
    }

    @Test
    fun `export info states truncation is absent when the file is complete`() {
        read(DealsExport.build(listOf(deal()), meta(totalMatching = 1, rowsExported = 1), zone)).use { book ->
            val info = book.getSheet("Export info").asSequence()
                .associate { it.getCell(0)?.toString().orEmpty() to it.getCell(1)?.toString().orEmpty() }
            assertEquals("no", info["Truncated"], "the FALSE case is written too, so its absence isn't ambiguous")
        }
    }

    /**
     * The answer to "what did I get if I exported without filtering anything?" has to be
     * in the file, because the defaults are not "everything".
     */
    @Test
    fun `an unfiltered export documents the implicit constraints`() {
        val described = FeedFilter().describe().toMap()
        assertEquals("(none)", described["Search"])
        assertEquals("all", described["Source"])
        assertEquals("all", described["Category"])
        assertEquals("any", described["Min discount %"])
        assertEquals("everything", described["Only"])
        // The two constraints no form control relaxes.
        assertEquals("NEW, SEEN, SAVED", described["State"], "dismissed deals are excluded by default")
        assertTrue(
            described["Always applied"]!!.contains("active deals only"),
            "retired deals are never exported, and the file must say so",
        )
    }

    @Test
    fun `describe reports an applied filter rather than the default`() {
        val filter = FeedFilter(
            sources = listOf("slickdeals"),
            search = "airpods | airtag",
            minDiscount = BigDecimal("25"),
            minScore = 20,
            coShipping = listOf(CoShipping.YES),
            categoryGroups = listOf("Audio"),
            onlyPromoted = true,
            sort = "score",
        )
        val described = filter.describe().toMap()
        assertEquals("airpods | airtag", described["Search"])
        assertEquals("slickdeals", described["Source"])
        assertEquals("Audio", described["Category"])
        assertEquals("25", described["Min discount %"])
        assertEquals("20", described["Min community score"])
        assertEquals("YES", described["Colombia shipping"])
        assertEquals("promoted", described["Only"])
        assertEquals("score", described["Sort"])
    }

    @Test
    fun `filename carries the export date`() {
        assertEquals("deals-2026-08-27.xlsx", DealsExport.filename(LocalDate.of(2026, 8, 27)))
    }
}
