# Deals Tracker

Tracks discounts across several sites, keeps every price observation so a real
discount can be told apart from an inflated list price, and shows everything in one
filterable local page.

## Quick start

Setting up on a new machine? See **[SETUP.md](SETUP.md)** — prerequisites, secret files,
verification and troubleshooting.

```bash
docker compose up -d          # Postgres (start Docker Desktop first)
./dt run                      # migrates on boot, serves http://localhost:8080
```

Use `./dt`, not `./gradlew`. It's a one-line wrapper that pins JDK 21 via macOS's
`/usr/libexec/java_home`, so it works regardless of which `java` is on your PATH — the
original machine's default was JDK 8, which cannot run Gradle 8.10. Nothing
machine-specific lives in `gradle.properties`.

One-off fetch without starting the server:

```bash
./dt run --args="--fetch-once"
./dt run --args="--fetch-once --source=exito"
```

Tests (no database or network needed — they run against captured fixtures):

```bash
./dt test
```

## Sources

| Provider | Type | Status |
|---|---|---|
| `slickdeals` | `rss-slickdeals` | working, filtered to Amazon-only |
| `exito` | `vtex` | working |
| `carulla` | `vtex` | working |
| `mercadolibre-co` | `mercadolibre` | **disabled** — search API policy-blocked |
| `rappi-medellin` | `rappi` | **disabled** — no adapter |

See **[BLOCKERS.md](BLOCKERS.md)** for why the two disabled sources are blocked, the
evidence behind each conclusion, reproduction commands, and the V2 notes for tackling
them with a headless browser.

### Adding or removing a source

Adapters model a *protocol*, not a site, so most changes are config-only in
`config/sources.yaml`:

- another VTEX retailer → new block with `type: vtex`
- another Slickdeals feed → new block with `type: rss-slickdeals`
- another MercadoLibre country → change `siteId`
- turn any provider off → `enabled: false`

A genuinely new platform needs a new `DealSource` implementation plus one line in
the registry in `App.kt`. Everything downstream is provider-agnostic.

## Reading the columns: how do you know it's actually a deal?

Three independent signals, because no single one is available for every source.

**"Off" (discount %)** — computed from the source's own list price. Reliable for Éxito and
Carulla, which publish a real `ListPrice`. **Empty (`—`) for every Slickdeals row**: that
feed publishes a sale price with nothing to compare it against, so no discount can be
computed. An empty cell means *unknown*, not *zero*.

**"Score" (community vote)** — Slickdeals' Thumb Score, present on 100% of its items
(observed range −14..+384, median ≈20). This is the only deal-quality evidence available
where the discount is unknown: it's what the site's community concluded. **0 or below
means they voted it down** — several "Renewed MacBook Pro" listings score −14, and without
this column they looked identical to a +384 deal. Empty for Éxito, Carulla and
MercadoLibre, which publish no equivalent. Filter with **Min score**, sort by
**community score**.

**"Signals" (our own price history)** — the only signal we compute ourselves, and the one
that can't be gamed by an inflated list price. "lowest yet" and "↓ $X" require an actually
observed decline from a recorded peak. Worth little on day one and more every day the
poller runs.

So for a Slickdeals row today: the discount is unknown, the score tells you what the
community thought, and price history will tell you the truth in a few days.

## What counts as a deal

Éxito flags roughly **94%** of its catalog as discounted, so `ListPrice > Price` is
not a signal by itself. A deal is promoted when **either**:

1. the advertised discount clears `minDiscountPercent` (default 25%), or
2. the price is below the lowest value ever recorded for that item.

Rule 2 is immune to inflated list prices but produces nothing on the first run, so
expect the first day or two to be threshold-driven and noisier. Everything ingested
is stored and browsable regardless; the threshold only governs what gets flagged.

Slickdeals is a special case: it publishes no list price at all, so neither rule can
fire. Because the frontpage is already community-curated, it sets
`promoteAllNew: true` — otherwise the source would be silently filtered to nothing.

## UI

Styles live in `src/main/resources/static/app.css`, served via Ktor `staticResources` —
**not** compiled into a Kotlin constant, so a style change needs only a browser refresh
rather than a Gradle rebuild. Bump `CSS_VERSION` in `Views.kt` when editing it, to defeat
browser caching.

The deal list is built for scanning several hundred rows. Three facts are meant to read
at a glance and everything else is deliberately quieter: discount magnitude, whether the
price genuinely came down, and Colombia shipping status.

Design decisions worth not undoing:

- **Do not put `overflow: hidden` on `.deals`.** It establishes a new containing block
  and breaks `position: sticky` on the header, which then covers the first row. Corners
  are rounded on the edge cells instead.
- **`width` on a `th` is only a hint** under table auto-layout. The actions column needs
  `flex-wrap: nowrap` to stop its buttons wrapping onto two lines.
- **Meta items are each wrapped in a `span`.** Bare text nodes are not flex items, so the
  container's `gap` doesn't separate them and values ran together as
  `exito exito.comAccesorios de computador`.
- **Signal chips require evidence.** `price <= minPriceEver` is trivially true for any
  deal whose price never moved (the minimum includes the current observation), so it put
  "lowest yet" on every row. It now also requires `maxPriceEver > price` — an actually
  observed decline. Likewise "new" is time-bounded to 24h rather than keyed on
  `state = NEW`, which never changes until you act on a row.
- **Colour is never the only cue.** The discount ramp always renders the number, and
  shipping states carry a glyph plus text.
- **Slickdeals' `category` is a forum name** ("Hot Deals" on nearly every row), so
  generic values are suppressed via `GENERIC_CATEGORIES`.

## Categories

The **Category** dropdown filters across every source, using 13 broad English buckets
(plus `Other`) defined in `config/categories.yaml`.

A normalized bucket is necessary because the sources categorise incompatibly:

- **Éxito and Carulla** publish real 3-level taxonomies — but *different* ones, in Spanish,
  and they spell the same top level differently: **`Tecnología` vs `Tecnologia`**. 125
  distinct paths combined, far too many for a dropdown.
- **Slickdeals** publishes only its *forum* name — `Hot Deals` on 212 of 220 deals — so its
  rows carry no product information at all.

So each bucket matches two ways, case- and accent-insensitively:

| | matched against | used for |
|---|---|---|
| `sourcePatterns` | the source's own category path | Éxito, Carulla — tried **first**, since a retailer's taxonomy beats guessing |
| `titlePatterns` | the product title | Slickdeals, which has nothing else |

**Order in the file is priority — the first match wins.** Each of these orderings was
forced by a real misclassification, so don't reshuffle casually:

- *Gaming* before *Computers* — "gaming mouse" is a gaming deal.
- *Beauty* before *Tools* — an "LED face mask" is not an LED light.
- *Beauty* before *Fashion* — "Men's Bar Soap" is not clothing.
- *Books* before *Audio* — **"Player Piano: A Novel (eBook)"** matched Audio's `piano`.
- *TV & Video* before *Audio* — a "Soporte Para Televisor" sits under Éxito's
  "Accesorios audio y video", which *Audio* would otherwise claim.

Every ingest logs the category spread, so an over-broad or missing rule shows up as a
suspicious `Other` count rather than passing unnoticed.

After editing the rules, re-apply them to stored deals:

```bash
./dt run --args="--recategorize"                 # re-apply and print the spread
./dt run --args="--recategorize --show-other"    # also list what stayed unmatched
```

`--show-other` is the feedback loop: it lists uncategorised titles so you can decide
whether a new pattern is worth adding. That loop took `Other` from 73 down to 33 of 612.

## Searching

The search box matches titles. Separate terms with `|` to match **any** of them:

```
airpods | airtag | ssd          -> deals matching any of the three
apple watch | galaxy buds       -> multi-word terms work
airpods                         -> a query with no pipe behaves as a plain search
```

Terms are trimmed and deduplicated, stray or trailing pipes are ignored, and the list is
capped at 20 terms. `%` and `_` are matched literally rather than as SQL wildcards, so
searching `%` finds titles containing a percent sign instead of returning everything.

Backed by the `pg_trgm` GIN index on `title`, with an `ILIKE` clause alongside so short
terms still match exactly where trigram similarity is weak.

## Deal age

`maxAgeDays` (default **14**) drops anything the source published longer ago than that.

This is not cosmetic. Slickdeals' paginated `q=` search reaches back **over 400 days** —
a deal from 2025 turned up in the feed — and `first_seen_at` only records our own
discovery time, so it cannot tell a fresh deal from an old one found on a deep page.

Measured age of `q=apple` results by page, which is why `searchPages` is 3:

| page | median age | within 14 days |
|---|---|---|
| 1 | 2d | 25/25 |
| 2 | 14d | 13/25 |
| 3 | 34d | 0/25 |
| 8 | **437d** | 0/25 |

**Capping page depth is not sufficient on its own** — results are not ordered strictly by
date (page 7 came back *newer* than page 6), so the published date is the real guard and
the page cap is just an efficiency win.

Two gotchas the tests pin:

- **`pubDate` uses RFC 822 with a two-digit year** (`Wed, 01 Oct 25 22:06:29 +0000`),
  which `DateTimeFormatter.RFC_1123_DATE_TIME` rejects. Parsing it wrong nulls every date
  and silently disables the age filter altogether.
- **The cutoff compares whole elapsed days, not raw instants.** Comparing instants makes a
  deal posted exactly `maxAgeDays` ago land either side of the boundary depending on
  microsecond timing.

Deals with no `posted_at` are kept — VTEX and MercadoLibre publish no posting date, so
excluding them would empty the Colombian sources.

To clear stale rows already stored:

```bash
./dt run --args="--purge-old --dry-run"                    # preview
./dt run --args="--purge-old"                              # dated rows past the cutoff
./dt run --args="--purge-old --include-undated"            # also rows we could never date
```

`--include-undated` exists for rows ingested before `posted_at` existed. Since page depth
is now lower those are never re-fetched, so they can never acquire a date and would
otherwise linger forever. Anything still live comes back on the next fetch, with a date.

## Excluding things Amazon won't ship to Colombia

Amazon ships internationally, but not groceries, liquids or medicines — and bulky
low-value consumables aren't worth shipping even when permitted. Surfacing them wastes
the manual shipping check.

`excludeTitlePatterns` in `config/sources.yaml` is a list of case-insensitive regexes
matched against the title. It covers Amazon's grocery private labels (Amazon Grocery /
Fresh / Saver, Happy Belly, Wickedly Prime), food and drink nouns, supplements and OTC
medicine, and the `S&S` / `SnS` Subscribe & Save marker.

That `S&S` marker turned out to be the single most useful signal: it flags consumables
generally — food, OTC medicine, paper goods, cleaning supplies, personal care. Broader
than "grocery", but nearly everything it catches is restricted or uneconomic to ship.
Delete those two lines to get household consumables back.

Excluded titles are **logged, not dropped silently**, so an over-broad pattern shows up:

```
[slickdeals] 54 excluded by title patterns (e.g. [6-Pk 20-Oz Gatorade Lower Sugar...])
```

To remove deals already in the database, using the **same patterns** so ingest and
cleanup can't drift:

```bash
./dt run --args="--purge-excluded --dry-run"   # preview
./dt run --args="--purge-excluded"             # delete (cascades price history)
./dt run --args="--purge-excluded --source=slickdeals"
```

Two gotchas the tests pin down: `S&H` (free shipping) must not be read as `S&S`, and word
boundaries are required so "tea" doesn't match "Steam" or "rice" match "Price".

## Colombia shipping

Colombian retailers default to `co_shipping = YES`. Amazon deals start `UNKNOWN` and are
resolved either by clicking **ships / doesn't** in the UI, or by the automated verifier:

```bash
./dt run --args="--verify-co-shipping --limit=10"
```

It opens a **headed** Chrome using a persistent profile (`.amazon-profile/`, gitignored).
The first run asks you to set the delivery country — and optionally sign in, which you type
yourself; nothing about credentials is read or stored. After that the profile carries the
session, so later runs need no interaction.

Verdicts are stored with provenance, visible in the UI: an **auto** marker distinguishes an
automated call from one you made, hovering shows the exact page text it rests on, and a
**no import fees** flag appears when Amazon says charges are covered.

### Things that are easy to get wrong here

- **`#availability` carries the refusal, not the delivery block.** A refused item leaves
  `#deliveryBlockMessage` **empty**, so reading only the delivery block returns "unclear"
  for every non-shippable item instead of NO.
- **Both regions are AJAX-filled and empty on load.** They must be polled; reading once
  yields `""`, which is indistinguishable from "the page said nothing".
- **A YES requires the country to be named** ("… to Colombia"), never merely the absence of
  a refusal. An absent signal usually means a partly rendered page, and a wrong YES sends
  you to buy something that cannot arrive.
- **Manual verdicts are never overwritten**, enforced in SQL rather than by the caller
  remembering. `co_shipping_source` records who decided.
- **Failures back off exponentially** (`2^attempts` hours, capped at a week). Without this,
  an unreadable deal is retried on every cycle forever — the original 3-state enum could
  not express "checked and could not tell".
- **Amazon will block unattended traffic.** From a datacenter IP it blocked us after ~7
  page loads regardless of pacing. The wall is a single button, which is why this runs
  headed and pauses for you rather than trying to get around it. It never solves a
  challenge and never spoofs anything.

## Colombia shipping (manual fallback)

Colombian retailers default to `co_shipping = YES`. Amazon deals from Slickdeals
default to `UNKNOWN`: v1 does not determine eligibility automatically. Each unverified
row has **ships / doesn't** buttons, and the verdict is stored, so an item is only
ever checked once.

## Known limitations

- **No pagination.** The feed renders up to 1000 rows in one page, which covers the
  current volume (~370 deals across three sources). When a page is capped the header
  says "showing X of Y" rather than reporting the page size as a total, so truncation
  is visible — but real pagination is the fix once volume outgrows this.
- **Default sort is `relevance`** (promoted first, then recency, then discount).
  Sorting purely by discount buried Slickdeals entirely, since Amazon deals have no
  list price and therefore a NULL discount.
- **`first_seen_at` records when *we* first saw a deal**, not when it was posted. On a
  first bulk load it mostly reflects ingest order, so "newest" is only meaningful once
  the poller has been running a while.
- **Postgres 16-alpine**, not 17: it was already present locally while pulling 17
  stalled. Nothing used here is version-specific. Override `POSTGRES_IMAGE` in `.env`.

## Notes on the sources

Things learned by probing the live APIs that are easy to get wrong:

- **Slickdeals merchant detection** rests on the body text (`"<Merchant> has …"`),
  not on links. A deal can name its primary merchant in prose while linking only a
  secondary one, and `data-outclick-typeofoutclick` is `"Post Content Link"` on every
  link, so it cannot discriminate. See `SlickdealsSource.resolveMerchant`.
- **VTEX returns 206**, not 200, for `_from`/`_to` paged requests.
- **VTEX does not 404 on unknown categories.** It returns an unfiltered product list,
  so a wrong slug yields plausible but arbitrary data. `VtexSource` validates
  configured slugs against each store's live category tree and warns.
- **Carulla's taxonomy differs from Éxito's** despite the shared platform — it has no
  `mercado` category at all.
- **Prices never pass through `Double`.** COP amounts reach ~9,700,900 while USD needs
  cents; JSON numbers deserialize straight to `BigDecimal` and columns are
  `NUMERIC(14,2)`.
- **Never use VTEX's `link` field for the product URL.** It returns the store's
  *internal* host (`tienda.exito.com`, `secure.carulla.com`). Those hosts answer HTTP
  200 — so curl says the link is fine — but serve a bare SPA shell with no
  canonical/og:url/title, and a real browser bounces to the homepage. URLs are built
  from the configured public `baseUrl` instead. There's a regression test for this.
- **Slickdeals RSS returns 25 items per request** and `pp=100` does not raise it. Two
  ways past that, both established by probing:
  - Different feeds carry different sets — `frontpage` overlaps `popdeals`/`newdeals`
    by *zero*, and `newdeals` is identical to the unfiltered feed.
  - **`q=<term>` searches paginate; `mode=` feeds do not.** `page` is silently ignored
    on `mode=frontpage` (6 pages returned 150 rows but only 25 unique), while
    `q=apple` over 5 pages gave 114 unique deals. Paginated search is the only route
    to deals older than the ~25 most recent — a deal shown on the site as "1w ago"
    sat on page 8.
  - `filters[store][]` from the web UI is **ignored** by the RSS endpoint, so merchant
    filtering has to happen locally.

  Together these took Slickdeals from 18 Amazon deals to ~390.
- **A single poll is not an authoritative snapshot.** Slickdeals' search window shifts
  between requests, so a deal present one poll is absent the next while still live on
  the site. Deactivation is therefore age-based (`staleAfterHours`, default 48) rather
  than "absent from the latest fetch" — the latter made deals flap between active and
  inactive every cycle. A side effect: repeated polls *accumulate* coverage, since each
  one sees a different slice.
- **Slickdeals has more than one body template.** Besides
  `"<Merchant> has <product> for $X"` there is a bare form with no verb:
  `"Apple Watch Band - Sport Loop - 46mm $39.00"`. Both the link-slug merchant fallback
  and the title-price fallback are load-bearing for it.
- **When counting matches in minified HTML, use `grep -o | wc -l`, not `grep -c`.**
  `grep -c` counts lines, and these pages put everything on one line — which made a
  page with 177 prices look like it had 1.

## Running in the background

```bash
./scripts/install-launchd.sh
```

The script fills this machine's project path into the plist template, so no absolute
paths are committed. The app runs its own hourly poll internally, so launchd only keeps
the one process alive. Logs land in `logs/`.

## Secrets

`.env` (Postgres password) and `local.properties` (MercadoLibre client secret) are
gitignored and `chmod 600`. `.gitignore` exists even though this isn't a git repo yet,
so that a future `git init` can't commit them. `.env.example` and
`local.properties.example` show the expected shape.
