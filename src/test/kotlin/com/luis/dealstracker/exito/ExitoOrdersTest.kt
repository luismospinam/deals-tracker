package com.luis.dealstracker.exito

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitoOrdersTest {

    /** The shape `/api/oms/user/orders` answered on 2026-11-11, with invented ids and no personal fields. */
    private val listBody = """
        {"list":[
          {"orderId":"1580000000001-01","creationDate":"2026-11-11T02:48:10.0000000+00:00","status":"payment-approved",
           "statusDescription":"Pagamento Aprovado","lastChange":"2026-11-11T02:50:00.0000000+00:00","orderFormId":"aaaa3956",
           "hostname":"exitocol","totalValue":18540000,"paymentNames":"Mastercard","currencyCode":"COP","items":null},
          {"orderId":"1580000000002-01","creationDate":"2026-11-08T20:40:11.0000000+00:00","status":"invoiced",
           "statusDescription":"Faturado","lastChange":null,"orderFormId":"bbbbbf15","totalValue":189900000,"currencyCode":"COP"},
          {"creationDate":"2026-11-01T00:00:00.0000000+00:00","status":"invoiced"}
        ],"paging":{"total":29,"pages":6,"currentPage":1,"perPage":5}}
    """.trimIndent()

    /** One order's detail: the 423 L Electrolux as ordered, and a second line with a discount. */
    private val detailBody = """
        {"orderId":"1580000000002-01","value":190390000,
         "totals":[{"id":"Items","name":"Total dos Itens","value":320480000},{"id":"Discounts","value":-130090000},{"id":"Shipping","value":0}],
         "items":[
           {"productId":"3231539","id":"3774885","ean":"7909569483831","name":"Nevecón 423 Litros ELECTROLUX Side By Side",
            "quantity":1,"price":319990000,"sellingPrice":189900000,"listPrice":319990000,"measurementUnit":"un","unitMultiplier":1.0,"seller":"1"},
           {"productId":"100200","id":"100201","ean":null,"name":"Limón Tahití","quantity":3,"price":490000,"listPrice":490000,
            "measurementUnit":"kg","unitMultiplier":0.5,"seller":"exitocol037"},
           {"name":"a line with no product id"}
         ],
         "paymentData":{"transactions":[{"payments":[{"paymentSystemName":"Tarjeta Éxito","lastDigits":"1234"},{"paymentSystemName":"Tarjeta Éxito"}]}]},
         "shippingData":{"address":{"street":"never read"}},"clientProfileData":{"firstName":"never read"}}
    """.trimIndent()

    @Test
    fun `the history list gives each order's id, time, status and total in pesos`() {
        val page = parseOrderList(listBody)!!
        assertEquals(6, page.pages)
        assertEquals(2, page.orders.size, "an entry with no order id is not an order")
        val first = page.orders[0]
        assertEquals("1580000000001-01", first.orderId)
        assertEquals(Instant.parse("2026-11-11T02:48:10Z"), first.placedAt)
        assertEquals("payment-approved", first.status)
        assertEquals(BigDecimal("185400.00"), first.total)
        assertEquals("aaaa3956", first.cartId)
        assertEquals("Mastercard", first.paymentNames)
        assertNull(page.orders[1].lastChange)
    }

    @Test
    fun `a body that is not the history list is not read as an empty one`() {
        assertNull(parseOrderList("<!DOCTYPE html><html>"))
        assertNull(parseOrderList("""{"type":"https://tools.ietf.org/html/rfc9110","title":"Unauthorized","status":401}"""))
        assertNull(parseOrderList(null))
    }

    @Test
    fun `an order's lines carry the price paid, the shelf price and the list price`() {
        val d = parseOrderDetail(detailBody)!!
        assertEquals(BigDecimal("1903900.00"), d.total)
        assertEquals(BigDecimal("3204800.00"), d.itemsTotal)
        assertEquals(BigDecimal("-1300900.00"), d.discountsTotal)
        assertEquals(BigDecimal("0.00"), d.shippingTotal)
        assertEquals("Tarjeta Éxito", d.paymentNames, "the same card on two payments is named once")
        assertEquals(2, d.lines.size, "a line with no product id is dropped")

        val fridge = d.lines[0]
        assertEquals("3231539", fridge.productId)
        assertEquals("3774885", fridge.skuId)
        assertEquals("7909569483831", fridge.ean)
        assertEquals(BigDecimal("1899000.00"), fridge.unitPrice)
        assertEquals(BigDecimal("3199900.00"), fridge.storePrice)
        assertEquals(BigDecimal("3199900.00"), fridge.listPrice)
        assertEquals("1", fridge.seller)
    }

    @Test
    fun `a line with no selling price was paid at its shelf price, and a weighed line keeps its pack`() {
        val lemon = parseOrderDetail(detailBody)!!.lines[1]
        assertEquals(BigDecimal("4900.00"), lemon.unitPrice)
        assertEquals(3, lemon.quantity)
        assertEquals("kg", lemon.measurementUnit)
        assertEquals(0, BigDecimal("0.5").compareTo(lemon.unitMultiplier))
        assertNull(lemon.ean)
    }

    @Test
    fun `a body that is not an order has no detail`() {
        assertNull(parseOrderDetail("""{"title":"Unauthorized","status":401}"""))
        assertNull(parseOrderDetail("not json"))
    }

    private fun summary(lastChange: String?) = OrderSummary(
        "o1", Instant.parse("2026-11-08T20:40:11Z"), "invoiced", null, lastChange?.let(Instant::parse), null, null, null, null, "COP",
    )

    @Test
    fun `an order is read when it is new or the list says it changed, and left alone otherwise`() {
        val at = "2026-11-09T10:00:00Z"
        assertTrue(needsDetail(summary(at), known = false, stored = null), "new")
        assertFalse(needsDetail(summary(at), known = true, stored = Instant.parse(at)), "unchanged")
        assertTrue(needsDetail(summary("2026-11-10T08:00:00Z"), known = true, stored = Instant.parse(at)), "its status or lines moved")
        assertFalse(needsDetail(summary(null), known = true, stored = null), "stored, and the list gives no change time")
    }

    private val stores = listOf(
        ExitoStore("exito-grocery", "exitocol037"),
        ExitoStore("exito-colombia-grocery", "exitocol031"),
        ExitoStore("exito", null),
    )

    private fun line(productId: String, seller: String?, ean: String? = null) =
        OrderLine(0, productId, null, ean, "x", 1, null, null, null, seller, null, null)

    @Test
    fun `a line is the row of the store that sold it`() {
        val rows = listOf(
            DealCandidate(10, "exito-grocery", "100200", null),
            DealCandidate(11, "exito-colombia-grocery", "100200", null),
        )
        assertEquals(11, pickDeal(line("100200", "exitocol031"), rows, stores))
        assertEquals(10, pickDeal(line("100200", "exitocol037"), rows, stores))
    }

    @Test
    fun `with no store to go by, the product's own id wins, in the order the stores are configured`() {
        val rows = listOf(
            DealCandidate(11, "exito-colombia-grocery", "100200", null),
            DealCandidate(10, "exito-grocery", "100200", null),
            DealCandidate(12, "exito", "999", "gtin:07701234567897"),
        )
        assertEquals(10, pickDeal(line("100200", "1", ean = "7701234567897"), rows, stores))
    }

    /** The fridge was ordered as product 3231539 and is tracked by Exito's search as 124173981. */
    @Test
    fun `a barcode finds the row when the product id does not, and only among Exito's rows`() {
        val rows = listOf(
            DealCandidate(137257, "carulla", "3231539", "gtin:07909569483831"),
            DealCandidate(122040, "exito", "124173981", "gtin:07909569483831"),
        )
        assertEquals(122040, pickDeal(line("3231539", "1", ean = "7909569483831"), rows, stores))
        assertNull(pickDeal(line("3231539", "1", ean = "7909569483831"), rows.take(1), stores), "another retailer's row is not the one ordered")
    }

    @Test
    fun `a product that is not tracked matches nothing`() {
        assertNull(pickDeal(line("555", "1"), listOf(DealCandidate(1, "exito", "556", null)), stores))
        assertNull(pickDeal(line("555", "1", ean = "not-a-barcode"), listOf(DealCandidate(1, "exito", "556", "gtin:not-a-barcode")), stores))
    }

    @Test
    fun `the Exito providers are the ones on exito's own site, store-scoped ones first`() {
        val found = exitoStores(
            listOf(
                Triple("slickdeals", null, null),
                Triple("exito", "https://www.exito.com", null),
                Triple("carulla", "https://www.carulla.com", null),
                Triple("exito-grocery", "https://www.exito.com", "exitocol037"),
                Triple("fake", "https://notexito.com", null),
            ),
        )
        assertEquals(listOf(ExitoStore("exito-grocery", "exitocol037"), ExitoStore("exito", null)), found)
    }
}
