package com.luis.dealstracker.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.html.div

class ThumbsTest {
    @Test
    fun `a VTEX photo is asked for at thumbnail size, keeping its name and version`() {
        assertEquals(
            "https://carulla.vtexassets.com/arquivos/ids/25663762-240-240/Crema-Queso-Cheddar-125gr-586726_a.jpg?v=639211354511130000",
            thumbSrc("https://carulla.vtexassets.com/arquivos/ids/25663762/Crema-Queso-Cheddar-125gr-586726_a.jpg?v=639211354511130000"),
        )
        assertEquals(
            "https://olimpica.vteximg.com.br/arquivos/ids/2451550-240-240/23462931.jpg?v=1",
            thumbSrc("https://olimpica.vteximg.com.br/arquivos/ids/2451550/23462931.jpg?v=1"),
        )
    }

    @Test
    fun `a VTEX address that already names a size gets this one instead`() {
        assertEquals(
            "https://exitocol.vtexassets.com/arquivos/ids/34390424-240-240/a.jpg",
            thumbSrc("https://exitocol.vtexassets.com/arquivos/ids/34390424-500-500/a.jpg"),
        )
    }

    @Test
    fun `a Falabella photo is asked for at thumbnail size`() {
        assertEquals(
            "https://media.falabella.com.co/falabellaCO/130872538_01/w=240,h=240,fit=pad",
            thumbSrc("https://media.falabella.com.co/falabellaCO/130872538_01/public"),
        )
        assertEquals(
            "https://media.falabella.com/falabellaCO/73581824_1/w=240,h=240,fit=pad",
            thumbSrc("https://media.falabella.com/falabellaCO/73581824_1/public"),
        )
    }

    @Test
    fun `an address it does not recognise is left alone`() {
        listOf(
            "https://m.media-amazon.com/images/I/41DYBbk3CCL._AC_UL320_.jpg",
            "https://static.slickdealscdn.com/attachment/2/3/4/4/9/300x300/22340061.thumb",
            "https://d31f1ehqijlcua.cloudfront.net/n/0/0/5/6/0056e_Fruits_512231_01.jpg",
            "https://evil.example/arquivos/ids/1/a.jpg",
            "https://media.falabella.com.co/falabellaCO/130872538_01/other",
            "",
        ).forEach { assertEquals(it, thumbSrc(it)) }
    }

    @Test
    fun `a thumbnail carries its address in data-src and has no src of its own`() {
        val html = kotlinx.html.stream.createHTML().div {
            thumbImg("https://carulla.vtexassets.com/arquivos/ids/25663762/a.jpg?v=1")
        }
        assertTrue("data-src=\"https://carulla.vtexassets.com/arquivos/ids/25663762-240-240/a.jpg?v=1\"" in html, html)
        assertTrue("class=\"thumb\"" in html && "alt=\"\"" in html, html)
        assertFalse(" src=" in html, "the loader sets src; the browser must not start it early: $html")
        assertFalse("loading=" in html, "native lazy loading is what stalled on the phone")
    }

    /** The script is plain text in a Kotlin string, so the test pins what it must keep doing. */
    @Test
    fun `the loader starts pictures near the screen, retries, and reports what went wrong`() {
        val js = THUMB_LOADER_JS
        assertTrue("img.thumb[data-src]" in js && "getBoundingClientRect" in js)
        assertTrue("if (!r.width && !r.height) continue;" in js, "a hidden picture waits until it is shown")
        assertFalse("IntersectionObserver" in js || "loading = 'lazy'" in js, "neither may be relied on")
        assertTrue("tries >= 2" in js, "a picture that is really gone is not retried for ever")
        assertTrue("STALL_MS" in js && "data-stalled" in js)
        assertTrue("setInterval(soon, 2000)" in js, "the safety net for a missed event")
        listOf("'scroll'", "'toggle'", "'htmx:afterSwap'", "'pageshow'").forEach { assertTrue(it in js, it) }
        assertTrue("sendBeacon('/client-log'" in js)
    }

    @Test
    fun `a page's report is logged as one short line`() {
        assertEquals("{\"a\":1} second line", clientLogLine("{\"a\":1}\n\rsecond line\u0007"))
        assertEquals(600, clientLogLine("x".repeat(5000)).length)
        assertEquals("(empty)", clientLogLine("  \n"))
    }
}
