package com.luis.dealstracker.i18n

import com.luis.dealstracker.exito.CartResult
import com.luis.dealstracker.web.cartToast
import com.luis.dealstracker.web.shippingProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Spanish pass of 2026-11-03: the watch alerts' evidence was stored in English and shown as stored,
 * and anything built on the IO pool missed the request's language altogether.
 */
class EvidenceTranslationTest {

    @Test
    fun `cross-site evidence reads in Spanish`() {
        assertEquals(
            "51.3% más barato que falabella a \$2,299,900 (mismo mdl:lg|43lb6500psb)",
            localizeEvidence("51.3% cheaper than falabella at \$2,299,900 (same mdl:lg|43lb6500psb)", Lang.ES),
        )
    }

    @Test
    fun `a new low reads in Spanish, with its currency note`() {
        assertEquals(
            "superó el mínimo de 30 días de \$1,500,000 de exito, por \$100,000 — convertido: amazon-watches US\$399.99 a 3,264.39 COP/USD (TRM 2026-09-23)",
            localizeEvidence(
                "beat the \$1,500,000 30-day floor held by exito, by \$100,000 — converted: amazon-watches US\$399.99 at 3,264.39 COP/USD (TRM 2026-09-23)",
                Lang.ES,
            ),
        )
    }

    @Test
    fun `a card low reads in Spanish`() {
        assertEquals(
            "CMR \$2,179,900 superó el mínimo de 30 días de \$2,329,900 (de contado, falabella) por \$150,000; de contado \$2,449,900",
            localizeEvidence("CMR \$2,179,900 beat the \$2,329,900 30-day floor (cash, falabella) by \$150,000; cash \$2,449,900", Lang.ES),
        )
        assertTrue(localizeEvidence("Card \$1 beat the \$2 30-day floor (with card, easy) by \$1; cash \$3", Lang.ES).startsWith("Tarjeta \$1"))
    }

    @Test
    fun `English stays as stored, and an unknown shape is shown rather than mistranslated`() {
        val en = "51.3% cheaper than falabella at \$2,299,900 (same gtin:1)"
        assertEquals(en, localizeEvidence(en, Lang.EN))
        assertEquals("something new", localizeEvidence("something new", Lang.ES))
    }

    /** The bug behind the screenshot: text built on the IO pool came out in English. */
    @Test
    fun `the request's language follows the coroutine onto the IO pool`() = runBlocking {
        val seen = withContext(I18n.element(Lang.ES)) {
            withContext(Dispatchers.IO) { tr("new low", "nuevo mínimo") }
        }
        assertEquals("nuevo mínimo", seen)
        assertEquals("new low", tr("new low", "nuevo mínimo"), "outside it, the default is untouched")
    }

    @Test
    fun `the shipping tooltip frames Amazon's words in the reader's language`() {
        I18n.with(Lang.ES) {
            assertEquals(
                "Verificado automáticamente. La página de Amazon dice: FREE delivery to Colombia",
                shippingProvenance("AUTO", "FREE delivery to Colombia"),
            )
            assertEquals("Marcado a mano: ok", shippingProvenance("MANUAL", "ok"))
        }
    }

    @Test
    fun `a refusal with no reason is worded once, in the reader's language`() {
        val html = I18n.with(Lang.ES) { cartToast(CartResult.Refused(""), null) }
        assertTrue(html.contains("Exito no lo agregó (¿agotado en esta tienda?)"), html)
        assertTrue(!html.contains("did not add"))
    }
}
