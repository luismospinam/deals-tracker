# Blocked sources — notes for V2 (headless-browser scraper)

Findings from probing the live services on **2026-08-23**. These are the reasons two
providers are `enabled: false` in `config/sources.yaml`.

Re-verify before building on any of it: all of this is other people's infrastructure and
policy, and it changes. Reproduction commands are included for that reason.

---

## 1. MercadoLibre — search API is policy-blocked

**Status:** `mercadolibre-co` disabled. Auth works; every catalog read is refused.

### What works

Credentials are correct and the OAuth flow is fine — this is not a credentials problem.

| Endpoint | Result |
|---|---|
| `POST /oauth/token` (`client_credentials`) | **200** — returns a user-scoped token, valid 6h |
| `/users/me` | **200** — returns the app owner's account |
| `/categories/{id}` | **200** — category metadata |
| `/seller-promotions/users/{me}` | **200** — your own seller promotions |

`client_credentials` needs no redirect URI, so no `authorization_code` flow is required.
`MlAuth.kt` implements this and is working code — keep it.

### What is blocked

All return `403` / `PA_UNAUTHORIZED_RESULT_FROM_POLICIES` / `blocked_by: PolicyAgent`:

- `/sites/{site}/search`
- `/products/search`
- `/highlights/{site}/category/{id}`
- `/items/{id}` and `/items?ids=`
- `/users/{id}/items/search`
- `/trends/{site}`
- `/sites`, `/sites/MCO`, `/currencies/{id}`

> Credentials (`ml.clientId`, `ml.clientSecret`) live in gitignored `local.properties`.
> They are deliberately absent from this file and from `config/sources.yaml`.

### It is NOT a missing app permission

This was checked directly, because the app console does present a permission list.

The app's granted scopes are:

```
urn:ml:mktp:offers:/read-write      urn:ml:mktp:ads:/read-only
urn:ml:mktp:orders-shipments:/read-only
read  write  offline_access
urn:global:admin:{info,oauth,users}
```

There is no items/catalog scope among them, which makes "a permission is missing" a
reasonable guess. But the decisive test rules it out:

```bash
# All three return 403. A bogus token would return 401 if auth were being evaluated.
curl -s -o /dev/null -w "%{http_code}\n" https://api.mercadolibre.com/sites/MCO
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $VALID_TOKEN" \
  https://api.mercadolibre.com/sites/MCO
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer NOTAREALTOKEN" \
  https://api.mercadolibre.com/sites/MCO
```

Identical `403`s for valid / absent / deliberately-invalid tokens mean the request is
refused **before** authentication is considered. No scope toggle changes that.

The pattern across working vs blocked endpoints is consistent: reads scoped to *your own
account* succeed; reads of *other sellers' catalog data* are refused. That is a platform
policy against third-party catalog harvesting, not a configuration gap.

### V2 path: scrape `/ofertas`

Verified feasible:

- `https://www.mercadolibre.com.co/ofertas` → HTTP 200, ~627KB
- **230 `poly-card` blocks, 177 server-rendered prices** in the list HTML
- Extracted title + link + **both** original and sale price for **46+ items**
- Price pair order is `[original, sale]` — e.g. `159.990` then `99.990`

Two traps found while measuring this:

1. **Individual product pages return ~24KB with no price, no `og:title`, no
   schema.org markup** — a minimal/bot response. So per-product fetching does *not*
   work; the list page is where the data is. A headless browser may change this.
2. **Count occurrences with `grep -o | wc -l`, never `grep -c`.** `grep -c` counts
   *lines*, and these pages are minified onto one line — which made a page with 177
   prices appear to have 1, and led me to briefly report the page as unscrapable.

Policy to weigh before shipping this: `robots.txt` does **not** disallow `/ofertas` for
generic user agents, but it does have a blanket `Disallow: /` for AI-crawler agents
(`ClaudeBot`, `GPTBot`, `Perplexity*`, `Amazonbot`, `ChatGPT-User`). Markup will also
break periodically — that fragility is exactly why the API was preferred first.

---

## 2. Rappi — no usable endpoint

**Status:** `rappi-medellin` disabled, no adapter written.

- `services.rappi.com.co` **does not resolve** (DNS failure, not a 403)
- `https://www.rappi.com.co/tiendas` → 301
- `robots.txt` explicitly disallows `/api`, `/api-services`, `/base-api`, plus
  `/*(route:*)` and `/*(global:*)`
- Blanket `Disallow: /` for `GPTBot`
- Catalogs are scoped to a delivery address, so a session with a Medellín address is
  required before any store content is visible

```bash
curl -s https://www.rappi.com.co/robots.txt | head -20
```

### V2 path

A headless browser with a logged-in session and a Medellín delivery address set is the
only realistic approach. Luis has said he can provide a login. Expect this to be the
most fragile adapter and the one needing the most maintenance — budget accordingly, and
note it runs against Rappi's stated robots policy, which is a call to make explicitly
rather than by default.

---

## 3. Éxito — working, but on ambiguous footing

**Status:** `exito` and `carulla` are **enabled and working**. Recorded here only so the
reasoning isn't lost.

Éxito's `robots.txt` is malformed. `User-agent: *` receives only `Allow: /`; the
`Disallow: /api/` lines trail after a series of single-agent `Allow` groups, so under
correct robots grouping they bind only `Applebot-Extended`. Éxito also explicitly
**allows** `ClaudeBot`, `GPTBot` and `CCBot` at `/`, with
`Content-Signal: search=yes, ai-train=no` — and this tool does no training.

HTML scraping is not an option regardless: the site is a client-rendered SPA, so
`/tecnologia` returns 200 with **zero** prices in the markup. The JSON API is the only
practical route, which is what we use — hourly, small pages, descriptive User-Agent,
conditional requests.

If Éxito ever fixes that robots file to clearly disallow `/api/` for `*`, revisit this.

---

## 4. Makro — deferred, no public catalog API

**Status:** deferred. Probed 2026-09-07.

`www.makro.com.co` is a legacy PHP brochure site. The real storefront is
`tienda.makro.com.co`, a Next.js App Router front end on **Instaleap**.

- No public JSON API: `/api` answers `{"data":"alive"}`; `/api/graphql`,
  `/api/products/search` and `/api/catalog/search` all fall through to the SPA. The real
  `apiBaseUrl` is server-only config.
- Product data arrives as **React Server Component flight chunks**
  (`self.__next_f.push`) with `$xx` reference indirection that would have to be resolved.
  The data *is* all there, including the before/after pair via
  `promotion[].benefit.unitPrice`, plus `ean`.
- **Search server-renders zero products**, so only category crawling is possible
  (`/ca/<slug>/CT_47` returned 100 products).
- Store-scoped (`storeReference: "08DEL"`, `/selectStore` in robots) — the same
  address-scoping trap that killed Rappi. It works cookieless today, but that is a default,
  not a guarantee.

robots.txt is permissive (`/api`, `/search`, `/ca/*` all allowed).

---

## 5. Corona — blocked on data, not just policy

**Status:** blocked. Probed 2026-09-07. `tienda.corona.co` does not resolve;
`corona.co` is the real storefront.

**SAP Commerce Cloud (Hybris)**, `baseSite=corona-co`, confirmed by DNS: `dev-co.corona.co`
→ `accstorefront.ckrkw-coronaind1-d1-public.model-t.cc.commerce.ondemand.com`.

Why this is not worth building, in order of severity:

1. **The only server-rendered price is PDP JSON-LD, and it carries no list price and no
   discount.** That is the one thing a deals tracker exists to compute. It would be one HTTP
   request per SKU across a multi-thousand-product sitemap to learn nothing about whether a
   price moved.
2. Category pages are client-rendered — 2 prices in 518KB of HTML.
3. `robots.txt` disallows `/search` and `/*?q=`, so keyword search is off-limits for a
   generic agent. `/productos/**/p/**` is allowed.
4. The OCC API is not exposed on `corona.co` (`/occ/v2/...` and `/rest/v2/...` fall through
   to the Next 404 shell). The real API host,
   `api.ckrkw-coronaind1-p1-public.model-t.cc.commerce.ondemand.com`, completes TLS then
   **hangs** — mTLS or an IP allowlist.

If Corona ever opens the OCC API, that is the right target; the host pattern and baseSite
above are the starting point.

---

## Two VTEX false positives worth remembering

A status-code-only VTEX probe lies on both of these, in different ways:

- **`www.makro.com.co/api/catalog_system/pub/products/search/` returns HTTP 200.** The legacy
  PHP site answers 200 with its homepage HTML for *any* path.
- **`www.falabella.com.co/api/catalog_system/...` returns HTTP 200** after redirecting to
  `trabajando.falabella.com.co` (their jobs site).

Check the content type and `%{url_effective}`, not just `%{http_code}`.

---

## Also worth keeping in mind

- **VTEX does not 404 on unknown categories** — it returns an *unfiltered* product list.
  A copy-pasted slug therefore yields plausible but arbitrary data. `VtexSource`
  validates configured slugs against each store's live category tree and warns.
  Carulla has **no `mercado` category at all** despite sharing the platform with Éxito.
- **Slickdeals RSS: 25 items per request, but `q=` searches paginate.** `page` is
  ignored on `mode=` feeds; on `q=<term>` it works, which is the only route to deals
  older than the ~25 most recent. Combining 3 mode feeds with paginated searches took
  this from 18 Amazon deals to ~390. `filters[store][]` from the web UI is ignored by
  RSS. **Remaining V2 gap:** RSS exposes no store or category filter and no
  price/rating filters, so `slickdeals.net/deals/<category>/?filters[store][]=...` can
  still only be reproduced approximately. A headless browser could read those filtered,
  paginated category pages directly and would also let a search be pinned to a store.
- **Amazon → Colombia shipping is still manual**, by design in v1. Automating it needs a
  browser session with a Colombian delivery address set, then reading the shipping block
  on the product page. That is a natural fit for the V2 headless-browser work, and the
  `co_shipping` column plus its UI already exist to receive the result.
