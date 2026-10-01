package com.luis.dealstracker.web

import kotlinx.html.ButtonType
import kotlinx.html.MAIN
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.InputType
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.textArea
import kotlinx.html.title
import kotlinx.html.unsafe
import com.luis.dealstracker.i18n.tr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Ask panel: a question about the rows on this screen, answered by Claude.
 *
 * Collapsed by default, and rendered *above* the table rather than in a side rail, because the
 * tables are already as wide as the page and a rail would squeeze every column for a feature used
 * occasionally.
 *
 * [screen] names the screen to the server (`feed`, `favorites`, `tracked`, `watch:<id>`). The
 * filters are NOT rendered in here: the script reads `location.search` at submit time, so a panel
 * always asks about the page as it currently stands — including after htmx has changed nothing in
 * the URL — and there is no second copy of the filter state to fall out of step.
 */
fun MAIN.askPanel(screen: String, placeholder: String) {
    details("ask") {
        id = "ask"
        summary {
            span("ask-mark") { +"✦" }
            +tr(" Ask Claude about these rows", " Pregúntale a Claude sobre estas filas")
        }
        div("ask-body") {
            div("ask-log") {
                id = "ask-log"
                attributes["aria-live"] = "polite"
            }
            form(classes = "ask-form") {
                id = "ask-form"
                input(type = InputType.hidden, name = "screen") { value = screen }
                input(type = InputType.hidden, name = "conversation") { id = "ask-conversation" }
                textArea(classes = "ask-q") {
                    name = "question"
                    id = "ask-q"
                    rows = "2"
                    attributes["placeholder"] = placeholder
                    attributes["maxlength"] = "2000"
                    attributes["required"] = ""
                }
                div("ask-actions") {
                    button(type = ButtonType.submit, classes = "ask-send") {
                        id = "ask-send"
                        +tr("Ask", "Preguntar")
                    }
                    button(type = ButtonType.button, classes = "ask-reset") {
                        id = "ask-reset"
                        title = tr(
                            "Start a new conversation. The next question re-sends the rows.",
                            "Empieza una conversación nueva. La siguiente pregunta vuelve a enviar las filas.",
                        )
                        +tr("New conversation", "Nueva conversación")
                    }
                    // Says what a question costs before it is asked, since the cost is real and
                    // otherwise invisible: it comes out of the same plan as Claude Code.
                    span("ask-hint") {
                        id = "ask-hint"
                        +tr(
                            "Answers use only the rows on this screen, with your Claude plan. ⌘↵ to send.",
                            "Las respuestas usan solo las filas de esta pantalla, con tu plan de Claude. ⌘↵ para enviar.",
                        )
                    }
                }
            }
        }
    }
    // The script is a constant; its words arrive as data, rendered in this request's language.
    // Built as JSON so no translation can break out of the string it lands in.
    val words = buildJsonObject {
        put("thinking", tr("thinking…", "pensando…"))
        put("productsMentioned", tr("Products mentioned", "Productos mencionados"))
        put("history", tr("history", "historial"))
        put("newConversation", tr(
            "New conversation — the next question sends this screen's rows again.",
            "Nueva conversación: la siguiente pregunta vuelve a enviar las filas de esta pantalla.",
        ))
        put("rows", tr("rows", "filas"))
        put("ofPlanUsage", tr("of plan usage", "de uso del plan"))
        put("moreRowsNotSent", tr("more rows not sent", "filas más no enviadas"))
        put("followUps", tr("follow-ups continue this conversation", "las preguntas siguientes continúan esta conversación"))
        put("couldNotFinish", tr("Could not finish: ", "No se pudo terminar: "))
    }
    // `</` is escaped so no string can close the script element early.
    script { unsafe { +("window.ASK_I18N=" + words.toString().replace("</", "<\\/") + ";") } }
    script { unsafe { +ASK_JS } }
}

/**
 * Client side of the panel. Plain JS on purpose — htmx swaps HTML, and this needs to append text as
 * it streams, which htmx has no model for.
 *
 * Answer text is inserted with `textContent`, never `innerHTML`: it is model output about product
 * titles written by third-party sellers, and must not be able to inject markup into this page. The
 * only formatting applied is turning `[#123]` into a link to that deal's history, done by building
 * elements rather than by string-to-HTML.
 */
private val ASK_JS = """
(() => {
  const form = document.getElementById('ask-form');
  if (!form) return;
  const T = window.ASK_I18N || {};
  const log = document.getElementById('ask-log');
  const q = document.getElementById('ask-q');
  const send = document.getElementById('ask-send');
  const conv = document.getElementById('ask-conversation');
  const hint = document.getElementById('ask-hint');

  function bubble(cls, text) {
    const el = document.createElement('div');
    el.className = 'ask-msg ' + cls;
    if (text) el.textContent = text;
    log.appendChild(el);
    el.scrollIntoView({block: 'nearest'});
    return el;
  }

  function extLink(href, text) {
    const a = document.createElement('a');
    a.href = href;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    a.textContent = text;
    return a;
  }

  // Re-renders an answer with its product references as links to the retailer's page. Only ids the
  // server resolved become links -- it matched them against the rows actually sent, so a price or
  // a screen size that happens to follow a '#' stays text. Built node by node so no model text is
  // ever parsed as HTML.
  function linkify(el, text, links) {
    const byId = new Map(links.map(l => [String(l.id), l]));
    el.textContent = '';
    const re = /\[?#(\d+)\]?/g;
    let last = 0, m;
    while ((m = re.exec(text)) !== null) {
      const hit = byId.get(m[1]);
      if (!hit) continue;
      el.appendChild(document.createTextNode(text.slice(last, m.index)));
      const a = extLink(hit.url, '↗ #' + m[1]);
      a.title = hit.title + ' — ' + hit.source + ' — ' + hit.price;
      el.appendChild(a);
      last = re.lastIndex;
    }
    el.appendChild(document.createTextNode(text.slice(last)));
  }

  // The list that makes every recommendation clickable even when the prose names a product without
  // its id in a linkable spot: one line per product the answer mentions, from the database.
  function productList(links) {
    if (!links.length) return null;
    const box = document.createElement('div');
    box.className = 'ask-products';
    const h = document.createElement('div');
    h.className = 'ask-products-h';
    h.textContent = T.productsMentioned;
    box.appendChild(h);
    links.forEach(l => {
      const row = document.createElement('div');
      row.className = 'ask-product';
      row.appendChild(extLink(l.url, l.title));
      const meta = document.createElement('span');
      meta.className = 'ask-product-meta';
      meta.textContent = ' ' + l.source + ' · ' + l.price + ' · ';
      row.appendChild(meta);
      const hist = document.createElement('a');
      hist.href = '/deal/' + l.id;
      hist.textContent = T.history;
      row.appendChild(hist);
      box.appendChild(row);
    });
    return box;
  }

  document.getElementById('ask-reset').addEventListener('click', () => {
    conv.value = '';
    log.textContent = '';
    hint.textContent = T.newConversation;
    q.focus();
  });

  q.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); form.requestSubmit(); }
  });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const question = q.value.trim();
    if (!question || send.disabled) return;
    bubble('ask-you', question);
    const answer = bubble('ask-claude ask-pending', '');
    const status = document.createElement('div');
    status.className = 'ask-status';
    status.textContent = T.thinking;
    answer.appendChild(status);
    q.value = '';
    send.disabled = true;

    const body = new URLSearchParams(new FormData(form));
    body.set('question', question);
    body.set('qs', location.search);

    let text = '';
    try {
      const res = await fetch('/ask', {method: 'POST', body});
      if (!res.ok) throw new Error(await res.text());
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      for (;;) {
        const {value, done} = await reader.read();
        if (done) break;
        buf += dec.decode(value, {stream: true});
        let nl;
        while ((nl = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, nl); buf = buf.slice(nl + 1);
          if (!line.trim()) continue;
          const msg = JSON.parse(line);
          if (msg.status && !text) status.textContent = msg.status + '…';
          if (msg.t) {
            if (!text) answer.textContent = '';
            text += msg.t;
            answer.textContent = text;
          }
          if (msg.error) throw new Error(msg.error);
          if (msg.done) {
            conv.value = msg.conversation;
            answer.classList.remove('ask-pending');
            const links = msg.links || [];
            linkify(answer, text, links);
            const list = productList(links);
            if (list) answer.appendChild(list);
            const cost = msg.cost != null ? ' · ~$' + msg.cost.toFixed(3) + ' ' + T.ofPlanUsage : '';
            const cut = msg.omitted > 0 ? ' · ' + msg.omitted + ' ' + T.moreRowsNotSent : '';
            hint.textContent = msg.rows + ' ' + T.rows + ' · ' + (msg.ms / 1000).toFixed(1) + 's' + cost + cut +
              ' · ' + T.followUps;
          }
        }
      }
    } catch (err) {
      answer.classList.remove('ask-pending');
      answer.classList.add('ask-error');
      // Keep any partial answer, and say it stopped rather than replacing it with the error.
      answer.textContent = (text ? text + '\n\n' : '') + T.couldNotFinish + err.message;
      conv.value = '';
    } finally {
      send.disabled = false;
      q.focus();
    }
  });
})();
""".trimIndent()
