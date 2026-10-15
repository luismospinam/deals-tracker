package com.luis.dealstracker.exito

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitoCartTest {

    private fun jwt(exp: Long?): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"ES256"}""".toByteArray())
        val payload = enc.encodeToString((if (exp != null) """{"sub":"a@b.c","exp":$exp}""" else """{"sub":"a@b.c"}""").toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun `a pasted header or value is reduced to the bare token`() {
        assertEquals("eyJabc.def.ghi", cleanCookie("eyJabc.def.ghi\n"))
        assertEquals("eyJabc.def.ghi", cleanCookie("VtexIdclientAutCookie_exitocol=eyJabc.def.ghi; Path=/"))
        assertEquals("eyJabc.def.ghi", cleanCookie("VtexIdclientAutologin=eyJabc.def.ghi"))
        assertEquals("eyJabc.def.ghi", cleanCookie("  \"eyJabc.def.ghi\"  "))
        assertEquals("", cleanCookie("   "))
    }

    @Test
    fun `a first add adopts the cart Exito creates`() {
        assertEquals("new", cartToAdopt(saved = null, answered = "new"))
    }

    @Test
    fun `an open cart is kept, and a replacement after an order is adopted`() {
        assertNull(cartToAdopt(saved = "same", answered = "same"))
        assertEquals("fresh", cartToAdopt(saved = "closed-by-order", answered = "fresh"))
        assertNull(cartToAdopt(saved = "same", answered = ""))
    }

    @Test
    fun `the cart link names the cart and opens it at the cart step`() {
        assertEquals(
            "https://www.exito.com/checkout/?orderFormId=4f0a2af089ea4e6694849f77456b3956#/cart",
            cartLink("https://www.exito.com", "4f0a2af089ea4e6694849f77456b3956"),
        )
    }

    @Test
    fun `expiry is read from the token payload, and an unreadable token is not called expired`() {
        val exp = Instant.parse("2026-10-15T12:00:00Z")
        val token = jwt(exp.epochSecond)
        assertEquals(exp, cookieExpiry(token))
        assertFalse(cookieExpired(token, exp.minusSeconds(1)))
        assertTrue(cookieExpired(token, exp))
        assertNull(cookieExpiry("not-a-jwt"))
        assertNull(cookieExpiry(jwt(null)))
        assertFalse(cookieExpired("not-a-jwt", exp))
    }

    private fun offer(qty: Int) = """"commertialOffer":{"Price":1600,"AvailableQuantity":$qty}"""

    /** Shaped like the real 2026-10-14 answer for 3172937 at Laureles: free text, so strangers ride along. */
    private val search = """
        {"products":[
          {"productId":"3171337","productName":"Televisor","items":[{"itemId":"3550418","sellers":[{"sellerId":"1",${offer(5)}}]}]},
          {"productId":"3172937","productName":"Bebida VITAD melocoton","items":[
            {"itemId":"3555951","sellers":[{"sellerId":"1",${offer(0)}}]},
            {"itemId":"3555952","sellers":[
              {"sellerId":"14748",${offer(9)}},
              {"sellerId":"1","sellerDefault":true,${offer(10000)}}]}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `the sku is the exact product's stocked item, under the seller Exito itself would use`() {
        assertEquals(CartSku("3555952", "1", "Bebida VITAD melocoton"), skuFromSearch(search, "3172937"))
    }

    @Test
    fun `the default seller wins over an earlier one, and the first stocked one is used without a default`() {
        val noDefault = search.replace(""""sellerDefault":true,""", "")
        assertEquals("14748", skuFromSearch(noDefault, "3172937")?.seller)
    }

    @Test
    fun `an absent, unstocked or unreadable product has no sku`() {
        assertNull(skuFromSearch(search, "999"))
        // A prefix of a real id is not that product.
        assertNull(skuFromSearch(search, "3171"))
        assertNull(skuFromSearch("""{"products":[{"productId":"5","items":[{"itemId":"6","sellers":[{"sellerId":"1",${offer(0)}}]}]}]}""", "5"))
        assertNull(skuFromSearch("""{"products":[]}""", "5"))
        assertNull(skuFromSearch("not json", "5"))
    }

    @Test
    fun `the add payload names sku, quantity and seller`() {
        assertEquals(
            """{"orderItems":[{"id":"901","quantity":2,"seller":"exitocol037"}]}""",
            addItemPayload(CartSku("901", "exitocol037", null), 2),
        )
    }

    private val sku = CartSku("901", "exitocol037", "Leche entera 1L")

    @Test
    fun `success is the sku being in the cart, with its quantity`() {
        val body = """{"orderFormId":"f","items":[{"id":"7","quantity":1},{"id":"901","name":"Leche Alpina","quantity":3}],"messages":[]}"""
        assertEquals(CartResult.Added("Leche Alpina", 3), interpretAdd(200, body, sku))
    }

    @Test
    fun `a 200 that left the item out is a refusal carrying Exito's own message`() {
        val body = """{"items":[],"messages":[{"code":"withoutStock","text":"Sin stock","status":"error"}]}"""
        assertEquals(CartResult.Refused("Sin stock"), interpretAdd(200, body, sku))
        assertIs<CartResult.Refused>(interpretAdd(200, """{"items":[]}""", sku))
    }

    @Test
    fun `auth failures read as expired, other failures as failed`() {
        assertEquals(CartResult.Expired, interpretAdd(401, null, sku))
        assertEquals(CartResult.Expired, interpretAdd(403, "{}", sku))
        assertIs<CartResult.Failed>(interpretAdd(500, null, sku))
        assertIs<CartResult.Failed>(interpretAdd(200, "<html>", sku))
    }

    @Test
    fun `logged-in is read from the order form`() {
        fun form(s: String) = cartJson.parseToJsonElement(s)
        assertTrue(isLoggedIn(form("""{"loggedIn":true}""")))
        assertFalse(isLoggedIn(form("""{"loggedIn":false,"clientProfileData":{"email":"a@b.c"}}""")))
        assertTrue(isLoggedIn(form("""{"clientProfileData":{"email":"a@b.c"}}""")))
        assertFalse(isLoggedIn(form("""{"orderFormId":"f"}""")))
    }
}
