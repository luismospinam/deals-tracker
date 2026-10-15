package com.luis.dealstracker.web

import com.luis.dealstracker.db.GroceryRow
import com.luis.dealstracker.exito.CartResult
import com.luis.dealstracker.model.CoShipping
import com.luis.dealstracker.model.Deal
import com.luis.dealstracker.model.DealState
import io.ktor.http.parametersOf
import kotlinx.html.FlowContent
import kotlinx.html.html
import kotlinx.html.main
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitoCartViewsTest {

    private fun row(
        id: Long,
        source: String,
        onShelf: Boolean = true,
        others: List<GroceryRow> = emptyList(),
        pinned: Boolean = false,
    ) = GroceryRow(
        deal = Deal(
            id = id, source = source, sourceDealId = "p$id", title = "Leche", url = "https://www.exito.com/x/p",
            merchant = "exito.com", asin = null, category = null, imageUrl = null, brand = null, gtin = null,
            modelCode = null, matchKey = null, modelKey = null, currency = "COP", firstSeenAt = Instant.EPOCH,
            lastSeenAt = Instant.EPOCH, isActive = true, state = DealState.NEW, coShipping = CoShipping.YES,
            promoted = false, promotedAt = null, communityScore = null, postedAt = null, categoryGroup = "Groceries",
            coShippingSource = null, coShippingEvidence = null, coShippingImportFree = null, coShippingCost = null,
            price = BigDecimal(5000), listPrice = null, minPriceEver = null, maxPriceEver = null, observationCount = 3,
        ),
        size = null, group = "Dairy & eggs", pinnedAt = if (pinned) Instant.EPOCH else null, usual = null, low = null, days = 0, onShelf = onShelf,
        others = others,
    )

    private fun html(block: FlowContent.() -> Unit) = createHTML(prettyPrint = false).div { block() }

    @Test
    fun `laureles gets the button, every other store gets none`() {
        assertTrue("/groceries/1/cart" in html { cartButton(row(1, "exito-grocery")) })
        listOf("exito-colombia-grocery", "carulla-grocery", "pricesmart-poblado-grocery").forEach {
            assertFalse("cart-btn" in html { cartButton(row(2, it)) }, it)
        }
    }

    @Test
    fun `a laureles copy hidden behind a cheaper colombia row still gets its own button`() {
        val laureles = row(10, "exito-grocery")
        val shown = row(11, "exito-colombia-grocery", others = listOf(laureles))
        assertEquals(laureles, cartCopy(shown))
        val out = html { cartButton(shown) }
        assertTrue("/groceries/10/cart" in out)
        assertFalse("/groceries/11/cart" in out)
    }

    @Test
    fun `the cart button leads the row's actions and the pin comes last, never side by side`() {
        val html = groceryRowFragment(row(1, "exito-grocery"), GroceryView.PINNED)
        val cartAt = html.indexOf("cart-btn")
        val historyAt = html.indexOf("/deal/1")
        val pinAt = html.indexOf("fav-btn")
        assertTrue(cartAt in 0 until historyAt, "cart before history")
        assertTrue(historyAt < pinAt, "pin last")
        assertTrue("grocery-actions" in html)
    }

    @Test
    fun `the cart button shows on Pinned only, never on Deals or All items`() {
        val laureles = row(1, "exito-grocery", pinned = true)
        assertTrue("cart-btn" in groceryRowFragment(laureles, GroceryView.PINNED))
        assertFalse("cart-btn" in groceryRowFragment(laureles, GroceryView.DEALS))
        assertFalse("cart-btn" in groceryRowFragment(laureles, GroceryView.ALL))
    }

    private fun page(view: String?): String = createHTML().main {
        groceryPage(
            listOf(row(1, "exito-grocery", pinned = true)), listOf("Dairy & eggs" to 1),
            GroceryControls.from(if (view == null) parametersOf() else parametersOf("view" to listOf(view))),
            listOf("exito-grocery"), pinnedCount = 1,
        )
    }

    @Test
    fun `the open-cart link is under the Pinned tabs and absent from the other views`() {
        val pinned = page("pinned")
        assertTrue("/groceries/exito-cart/link" in pinned)
        assertTrue(pinned.indexOf("class=\"scopes\"") < pinned.indexOf("/groceries/exito-cart/link"), "under the tabs")
        assertFalse("/groceries/exito-cart/link" in page(null))
        assertFalse("/groceries/exito-cart/link" in page("all"))
    }

    @Test
    fun `the cookie panel stays on every view, since the login is not a Pinned matter`() {
        listOf(null, "all", "pinned").forEach { assertTrue("hx-get=\"/groceries/exito-cart\"" in page(it), "$it") }
    }

    @Test
    fun `the cart button carries the words it will show for success and failure`() {
        val html = html { cartButton(row(1, "exito-grocery")) }
        assertTrue("data-done=\"✓ Added\"" in html)
        assertTrue("data-fail=\"! Check\"" in html)
        val es = com.luis.dealstracker.i18n.I18n.with(com.luis.dealstracker.i18n.Lang.ES) { html { cartButton(row(1, "exito-grocery")) } }
        assertTrue("data-done=\"✓ Agregado\"" in es)
    }

    @Test
    fun `every page ships the feedback script, and it keys on the success toast the server really sends`() {
        val page = createHTML().html { page("t") {} }
        assertTrue("htmx:afterRequest" in page && "cart-btn" in page)
        assertTrue("id=\"toast\"" in page)
        // The script decides success by this marker, so the toast must carry it and no failure may.
        assertTrue("toast-ok" in CART_FEEDBACK_JS)
        assertTrue("toast-ok" in cartToast(CartResult.Added("x", 1), null))
        listOf(CartResult.Expired, CartResult.NotConnected, CartResult.Refused("r"), CartResult.Failed("f")).forEach {
            assertFalse("toast-ok" in cartToast(it, null), it.toString())
        }
    }

    @Test
    fun `only removing a pin asks first`() {
        val unpinned = groceryRowFragment(row(1, "exito-grocery"), GroceryView.DEALS)
        assertFalse("hx-confirm" in unpinned)
        val pinned = groceryRowFragment(row(1, "exito-grocery").copy(pinnedAt = Instant.EPOCH), GroceryView.PINNED)
        assertTrue("hx-confirm" in pinned)
        assertTrue("/groceries/1/unpin" in pinned)
    }

    @Test
    fun `an off-shelf laureles copy is not offered`() {
        assertNull(cartCopy(row(1, "exito-grocery", onShelf = false)))
        assertFalse("cart-btn" in html { cartButton(row(1, "exito-grocery", onShelf = false)) })
    }

    @Test
    fun `each outcome has its own toast`() {
        val added = cartToast(CartResult.Added("Leche Alpina", 2), "Leche")
        assertTrue("toast-ok" in added && "Leche Alpina" in added && "×2" in added)
        assertTrue("toast-warn" in cartToast(CartResult.Expired, null))
        assertTrue("toast-warn" in cartToast(CartResult.NotConnected, null))
        assertTrue("Sin stock" in cartToast(CartResult.Refused("Sin stock"), null))
        assertTrue("toast-err" in cartToast(CartResult.Failed("timeout"), null))
    }

    @Test
    fun `a title is escaped in the toast`() {
        assertFalse("<script>" in cartToast(CartResult.Added("<script>alert(1)</script>", 1), null))
    }

    private fun jwt(exp: Instant): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." +
            enc.encodeToString("""{"exp":${exp.epochSecond}}""".toByteArray()) + ".sig"
    }

    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private val cart = "4f0a2af089ea4e6694849f77456b3956"

    @Test
    fun `the panel never claims a connection it cannot back`() {
        assertEquals("Not connected.", cookieStatus(null, null, now))
        assertEquals("Not connected.", cookieStatus(null, cart, now))
        assertTrue("valid for about 3h" in cookieStatus(jwt(now.plusSeconds(3 * 3600 + 60)), cart, now))
        assertTrue("expired" in cookieStatus(jwt(now.minusSeconds(5)), cart, now))
        assertTrue("unreadable" in cookieStatus("opaque", cart, now))
    }

    @Test
    fun `a login with no cart yet is connected, and says the cart appears on the first add`() {
        val status = cookieStatus(jwt(now.plusSeconds(7200)), null, now)
        assertTrue(status.startsWith("Connected"))
        assertTrue("created on the first add" in status)
    }

    @Test
    fun `the panel opens itself when the login is missing or lapsed, and offers Forget only once saved`() {
        val none = exitoCartPanel(null, null, now)
        assertTrue("open" in none && "name=\"clear\"" !in none)
        val live = exitoCartPanel(jwt(now.plusSeconds(7200)), cart, now)
        assertFalse("<details open" in live)
        assertTrue("name=\"clear\"" in live)
        assertTrue(exitoCartPanel(jwt(now.minusSeconds(1)), cart, now).contains("<details open"))
        // No cart yet is normal, not a reason to open the panel.
        assertFalse(exitoCartPanel(jwt(now.plusSeconds(7200)), null, now).contains("<details open"))
    }

    @Test
    fun `the panel asks for the login only`() {
        val page = exitoCartPanel(null, null, now)
        assertTrue("name=\"cookie\"" in page)
        assertFalse("name=\"cart\"" in page)
    }

    @Test
    fun `neither saved value is written into the page, only the cart id's last characters`() {
        val token = jwt(now.plusSeconds(7200))
        val page = exitoCartPanel(token, cart, now)
        assertFalse(token in page)
        assertFalse(cart in page)
        assertTrue("…${cart.takeLast(6)}" in page)
    }

    private val link = "https://www.exito.com/checkout/?orderFormId=$cart#/cart"

    @Test
    fun `a success toast carries the open-cart link, and no other outcome does`() {
        val added = cartToast(CartResult.Added("Leche", 1), null, link)
        assertTrue("toast-link" in added && link in added && "_blank" in added)
        assertFalse("toast-link" in cartToast(CartResult.Added("Leche", 1), null, null))
        listOf(CartResult.Expired, CartResult.NotConnected, CartResult.Refused("x"), CartResult.Failed("y")).forEach {
            assertFalse("toast-link" in cartToast(it, null, link), it.toString())
        }
    }

    @Test
    fun `the top-of-page link exists only once there is a cart`() {
        assertEquals("", openCartLink(null))
        val shown = openCartLink(link)
        assertTrue("open-cart" in shown && link in shown && "_blank" in shown)
        assertTrue("""id="open-cart"""" in shown, "keeps the slot's id so a later swap still finds it")
    }
}
