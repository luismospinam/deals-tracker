package com.luis.dealstracker.web

import kotlinx.html.img

/**
 * The address to load for a row's thumbnail, which is not always the one stored.
 *
 * The stores serve product photos at 1000 x 1000 (VTEX: Exito, Carulla, Olimpica...) or 768 x 768
 * (Falabella), and a list row shows them at 56 to 64px. A browser keeps a decoded picture at 4 bytes
 * a pixel whatever its size on screen, so a 500-row grocery page asked an iPhone for 2 GB of pictures:
 * part way down it stopped drawing them, and every row below was a blank plate (2026-11-13). Both CDNs
 * resize on request, so the lists ask for [THUMB_PX] instead -- a seventeenth of the memory and about
 * a tenth of the download. An address this does not recognise is returned as it is.
 *
 * Only for the lists. The detail page shows the picture large and keeps the stored address.
 */
internal const val THUMB_PX = 240

private val VTEX_IMAGE = Regex("""^(https://[^/]+\.(?:vtexassets\.com|vteximg\.com\.br)/arquivos/ids/\d+)(?:-\d+-\d+)?(/.*)$""")
private val FALABELLA_IMAGE = Regex("""^(https://media\.falabella\.com(?:\.co)?/.+)/public$""")

internal fun thumbSrc(url: String): String {
    VTEX_IMAGE.matchEntire(url)?.let { return "${it.groupValues[1]}-$THUMB_PX-$THUMB_PX${it.groupValues[2]}" }
    FALABELLA_IMAGE.matchEntire(url)?.let { return "${it.groupValues[1]}/w=$THUMB_PX,h=$THUMB_PX,fit=pad" }
    return url
}

/**
 * A row's thumbnail: an `<img class="thumb">` with its address in `data-src`, not `src`.
 *
 * The lists used `loading="lazy"`. On an iPhone, part way down a long list, the pictures stopped
 * appearing and every row below stayed a blank plate -- on Tracked, Groceries and Household alike,
 * and still after the pictures were made small ([thumbSrc]), so it was not memory. What the browser's
 * own lazy loading does there cannot be seen from the server, so the page no longer depends on it:
 * [THUMB_LOADER_JS] gives each picture its `src` as it nears the screen. Every page has scripts on
 * (htmx), so a picture without one is not a case this handles.
 */
internal fun kotlinx.html.FlowOrInteractiveOrPhrasingContent.thumbImg(url: String) {
    img(classes = "thumb", alt = "") { attributes["data-src"] = thumbSrc(url) }
}

/**
 * Starts each thumbnail as it comes within [1800px] of the screen, and looks after the ones that go wrong.
 *
 *  - **When to load** is decided by measuring, on scroll, resize, an accordion opening, an htmx swap and
 *    a slow timer -- not by `loading="lazy"` or an IntersectionObserver, either of which may be the part
 *    that stalls. A picture that is hidden (a closed aisle) measures 0 x 0 and waits until it is shown.
 *  - **A failed picture** is asked for again, twice at most.
 *  - **A picture still loading after eight seconds** is asked for once more.
 *  - **Both are reported** to `/client-log` (at most every 30 seconds), with what the browser knows about
 *    the connection, so the next time a phone shows blank plates `logs/app.log` says which it was.
 */
internal const val THUMB_LOADER_JS = """
(function () {
  var MARGIN = 1800, STALL_MS = 8000, queued = false, errors = 0, stalls = 0, lastReport = 0;
  function start(img, src) {
    img.dataset.started = String(Date.now());
    img.setAttribute('src', src);
  }
  function sweep() {
    queued = false;
    var h = window.innerHeight, now = Date.now();
    var waiting = document.querySelectorAll('img.thumb[data-src]');
    for (var i = 0; i < waiting.length; i++) {
      var img = waiting[i], r = img.getBoundingClientRect();
      if (!r.width && !r.height) continue;
      if (r.bottom < -MARGIN || r.top > h + MARGIN) continue;
      var src = img.getAttribute('data-src');
      img.removeAttribute('data-src');
      start(img, src);
    }
    var going = document.querySelectorAll('img.thumb[data-started]:not([data-stalled])');
    for (var j = 0; j < going.length; j++) {
      var g = going[j];
      if (g.complete) { g.removeAttribute('data-started'); continue; }
      if (now - Number(g.dataset.started) < STALL_MS) continue;
      g.dataset.stalled = '1';
      stalls++;
      var again = g.getAttribute('src');
      g.removeAttribute('src');
      start(g, again);
    }
    if (errors || stalls) report();
  }
  function soon() {
    if (queued) return;
    queued = true;
    (window.requestAnimationFrame || setTimeout)(sweep);
  }
  function report() {
    var now = Date.now();
    if (now - lastReport < 30000 || !navigator.sendBeacon) return;
    lastReport = now;
    var c = navigator.connection || {};
    navigator.sendBeacon('/client-log', JSON.stringify({
      what: 'thumbs', page: location.pathname, errors: errors, stalls: stalls,
      waiting: document.querySelectorAll('img.thumb[data-src]').length,
      loading: document.querySelectorAll('img.thumb[data-started]').length,
      total: document.querySelectorAll('img.thumb').length,
      online: navigator.onLine, net: c.effectiveType || '', saveData: !!c.saveData,
      visible: document.visibilityState, y: Math.round(window.scrollY), height: document.documentElement.scrollHeight
    }));
    errors = 0; stalls = 0;
  }
  document.addEventListener('error', function (e) {
    var img = e.target;
    if (!img || img.tagName !== 'IMG' || !img.classList.contains('thumb') || !img.getAttribute('src')) return;
    errors++;
    var tries = Number(img.dataset.retry || 0);
    if (tries >= 2) return;
    img.dataset.retry = String(tries + 1);
    var src = img.getAttribute('src');
    setTimeout(function () { img.removeAttribute('src'); start(img, src); }, 1500 * (tries + 1));
  }, true);
  window.addEventListener('scroll', soon, { passive: true });
  window.addEventListener('resize', soon);
  window.addEventListener('pageshow', soon);
  document.addEventListener('toggle', soon, true);
  document.addEventListener('htmx:afterSwap', soon);
  document.addEventListener('visibilitychange', soon);
  document.addEventListener('DOMContentLoaded', soon);
  // The safety net: whatever event was missed, a picture near the screen waits two seconds at most.
  setInterval(soon, 2000);
  soon();
})();
"""

/** One line for `logs/app.log` from a page's report: no line breaks or control characters, and not long. */
internal fun clientLogLine(body: String): String =
    body.replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(600).ifEmpty { "(empty)" }
