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
| `easy` | `vtex` | working — Cencosud home improvement, config-only |
| `olimpica` | `vtex` | working — config-only |
| `fullhogar` | `shopify` | working — public `products.json` |
| `grupomansion` | `prestashop` | working — theme grid; selectors are config |
| `alkomprar` | `algolia` | working — public search key; **100% GTIN coverage** |
| `homecenter` | `falabella-next` | working — Sodimac; **category crawl only**, robots disallows its search |
| `falabella` | `falabella-next` | working — keyword search allowed |
| `amazon` | `amazon` | working — tracks named items **in your size**; heavily rate-limited |
| `amazon-watches` | `amazon-watch` | working — searches amazon.com for each watch's **Amazon terms** |
| `mercadolibre-co` | `mercadolibre` | **disabled** — search API policy-blocked |
| `rappi-medellin` | `rappi` | **disabled** — no adapter |
| Makro | — | **deferred** — RSC payload, store-scoped, see BLOCKERS.md |
| Corona | — | **blocked** — publishes no list price at all, see BLOCKERS.md |

Ten sources across eight independent owners, which is what makes the cross-site comparison
mean anything: Grupo Éxito (Éxito, Carulla), Falabella (Falabella, Homecenter), Cencosud
(Easy), Olímpica, Fullhogar, Grupo Mansión, Alkomprar, and Amazon via Slickdeals.

### "Cheaper elsewhere" means "cheaper among these nine"

It has never meant "cheapest in Colombia", and it cannot. The limit is worth knowing because
it is easy to forget: a Google search for `RS57DG4000B4CO` turned up **Grupo Mansión at
2,999,000** while the cheapest tracked source had it at 3,299,900. That is how Olímpica,
Fullhogar, Grupo Mansión and Alkomprar came to be added — Google as a *discovery* tool for which
retailers matter, then each one added as a first-class source.

**Google itself is deliberately not a source.** Its `robots.txt` disallows `/search`, and this
app respects robots elsewhere (Homecenter's search is off for exactly that reason; Rappi and
Corona are shelved over it). A paid SERP API would be legitimate but gives strictly worse data
than a direct fetch: a price snapshot with no dependable list price, no stock truth, and no
history — and `LOWEST_IN_WINDOW` is built from history that can only be accumulated by polling.

So when a watch fires, the wording names the rival it actually measured against rather than
claiming a market-wide best price.

See **[BLOCKERS.md](BLOCKERS.md)** for why the two disabled sources are blocked, the
evidence behind each conclusion, reproduction commands, and the V2 notes for tackling
them with a headless browser.

### Adding or removing a source

Adapters model a *protocol*, not a site, so most changes are config-only in
`config/sources.yaml`:

- another VTEX retailer → new block with `type: vtex` (Éxito, Carulla, Easy, Olímpica)
- another Shopify store → new block with `type: shopify` and its collection handles
- another PrestaShop store → `type: prestashop`, plus selector overrides if its theme differs
- another Algolia-backed store → `type: algolia` with its app id, public search key and index
- another Falabella-group store → `type: falabella-next`
- another Slickdeals feed → new block with `type: rss-slickdeals`
- another MercadoLibre country → change `siteId`
- turn any provider off → `enabled: false`

Four of the ten sources arrived as config alone. That is the whole return on modelling a
protocol rather than a site.

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

## Favorites

`☆ favorite` on any feed row marks it; `/favorites` shows those and nothing else. Marking
survives every poll — `upsertDeal` never touches `state` on conflict — and the toggle
reverses itself, writing `SEEN` rather than `NEW` because you have plainly seen a row you
once favorited.

The screen reuses the feed's own `filterForm` and `dealTable` via `FeedScreen`, so search,
sort, the chip presets and the Excel export all work there unchanged. Favorites *is* the
feed restricted to rows you chose, not a second presentation of them.

Three things here are load-bearing:

- **A retired favorite is kept, marked `expired`.** This is the only query in the app that
  does not filter on `is_active`. Retirement is keyed on `last_seen_at` alone —
  `deactivateStale` never reads `state` — so without the exception a favorite would vanish
  48h after its source stopped returning it, which reads as "it was never saved" rather
  than "the offer ended". The price on an expired row is the last one recorded, not a
  current one, which is why the chip is there at all.
- **Hide is not offered on a favorited row.** `state` holds one value, so `DISMISSED` would
  overwrite `FAVORITE`. Rather than warn about that, the button is absent until you
  un-favorite — the conflict is made impossible to express.
- **Un-favoriting from `/favorites` posts to `/favorites/{id}/remove`**, which answers with
  an empty body so htmx's `outerHTML` swap deletes the row. `POST /deal/{id}/state` performs
  the same update but answers with a rendered row, which on this page would leave something
  claiming to be a favorite among the favorites.

`FAVORITE` was called `SAVED` until `V14__favorite_state.sql`, which renamed the stored
value too — `state` is visible in the State dropdown, the export's `State` column and the
query string, so a column saying one thing under a UI saying another is a discrepancy the
operator would see.

## Tracking Amazon items in one size

Slickdeals only carries what its community posted, so a Nike hoodie you actually want is
absent unless a stranger submitted it. The `amazon` provider queries amazon.com directly for
terms you name, and tracks **the child ASIN matching your size**.

Configure it entirely in `config/sources.yaml`:

```yaml
options:
  sizeShoes: "8"        # Amazon's own label, exactly
  sizeTops: "Medium"    # spelled out, NOT "M"
  sizeBottoms: "Small"
listOptions:
  trackSearches: ["nike men running shoes", "adidas men hoodie", ...]
```

Garment type is inferred from each term (`shoe`/`sneaker` → shoes, `hoodie`/`shirt` → tops,
`pant`/`jogger` → bottoms), so adding a search does not mean declaring its type.

### Why a size is a whole feature and not a filter

**On Amazon a size is a distinct child ASIN with its own price and stock.** "The price of this
hoodie" is not a fact; only "the price of this hoodie in Medium" is. Every other adapter here
deliberately collapses variants to the cheapest one — right for a fridge, wrong for clothing,
where it faithfully reports the price of a garment that does not fit. So one deal row is one
child ASIN is one size, the same call `FalabellaNextSource` makes keying on `skuId`.

The size is appended to the title (`… — Medium`) because `deals` has no size column, and two
sizes of one garment must not read as duplicate rows.

### Things that are easy to get wrong here

Each of these was a real bug caught by a live run, not a hypothetical:

- **Matching is exact — never a prefix.** One Nike listing offers `8`, `8 Wide`, `8 X-Wide`,
  `8.5` and `8 Women/7 Men`. A `startsWith("8")` matches all five, four of them the wrong shoe.
- **`M` is not translated to `Medium`.** Amazon spells sizes out. An alias table would be a
  guess that resolves to a real ASIN of the wrong size — a silent failure at a real price,
  strictly worse than tracking nothing.
- **`Accept-Language` must not be sent twice.** Ktor's `header()` *appends*, so `PoliteHttp`'s
  hardcoded `es-CO` plus a caller's `en-US` sent both and let the server choose. Amazon read
  the pair as Spanish and served `8,5 mujer/7,5 hombre`, so an exact match on `8.5` found
  nothing and **33 garments were cached as "size unavailable" when they stock it**. `PoliteHttp`
  now omits its default when the caller sets one; `SizeMatcher` also reads a decimal comma as a
  decimal point, so a Spanish page still resolves.
- **A page with no readable size dimension is a miss, not a one-size item.** Tracking the
  parent directly stored an adidas hoodie whose own title reads `X-Large` as the Medium being
  tracked.
- **Never conclude anything from a page that is not demonstrably a product page.** Amazon's
  interstitial replaces the listing, so it has no `#productTitle` and no variation payload —
  indistinguishable from "this size is not made" unless you check. A verdict is only cached
  when `#productTitle` is present.
- **Watch terms are deliberately ignored here.** `IngestService` merges every watch's terms
  into every provider's `searches`; this provider reads **`trackSearches`** instead. Before
  that, a run searched Amazon for `inodoro` and `televisor` and stored 33 TVs and toilets as
  `Fashion`, spending most of a scarce request budget on items it cannot size.

### Currency is read per row, not assumed

Amazon served this machine **COP** on some runs (`COP 234,124.82`) and **USD** on others
(`$49.00`), so neither is a safe constant and the currency is parsed from each price string.
That makes this a fourth money format: comma groups thousands *and* dot is the decimal, which
neither existing parser handles — applying one is a factor-of-100 error, and a test pins it.

Because one source can carry mixed currencies, watches convert before they compare — see
[Comparing across currencies](#comparing-across-currencies). A currency the rate does not cover
is still never compared: US$189.99 against COP 700,000 read raw is a ~100% "saving".

### Expect it to be partly refused

Measured with the project's own user agent and no spoofing: two requests returned full markup,
the **third returned 503**. `robots.txt` permits `/s` and `/dp/` for a generic agent (the
blanket `Disallow: /` entries there target named AI crawlers), so the approach is to identify
ourselves, go slowly, and stop when refused.

When plain HTTP is throttled the adapter escalates to the **headed Chrome profile the shipping
verifier already maintains**, sharing its `VerifySlot` lock so the two can never fight over the
profile. Sustained use gets that walled too, and then a run tracks few items or none. It
degrades to *fewer* items rather than wrong ones, and repeated polls accumulate coverage the
way Slickdeals does — so `maxItemsPerSearch` is deliberately small.

The size → child-ASIN mapping is cached in `build/amazon-size-cache.tsv` (gitignored, safe to
delete) because it is a fact about the catalogue rather than about price. That makes the steady
state roughly one request per tracked item.

## Watches — hunting one thing over weeks

The feed answers "what is discounted?". A **watch** answers the question you actually have:
*I want a fridge, keep looking and tell me when a good one turns up.* Manage them at
`/watches`; they live in Postgres, so adding one needs no restart.

A watch does two jobs, and the second is what makes it more than a saved search:

1. **It steers fetching.** Its terms are handed to every adapter that can search — VTEX via
   `?ft=`, Slickdeals via `q=` — so a fridge gets found even though nobody configured an
   appliance category on every site. Config-declared searches are merged, not replaced.
2. **It scores everything ingested**, on every source. Sources that cannot keyword-search
   (Homecenter's robots.txt disallows its search URLs) are covered by this half alone.

Two rules fire alerts, and both are deliberately hard to satisfy:

| Rule | Fires when |
|---|---|
| `LOWEST_IN_WINDOW` | the price beats the cheapest price *previously recorded* for anything the watch matches, across all sources, inside `lookback_days` |
| `CROSS_SITE_SPREAD` | the same product — same validated GTIN, or brand + model code — is at least `cross_site_min_spread`% cheaper here than at another retailer |

```bash
./dt run --args="--check-watches"              # evaluate now instead of waiting an hour
./dt run --args="--check-watches --dry-run"    # print what would fire, write nothing
```

Alerts arrive as a macOS notification, one per watch per cycle rather than one per deal.

### Searching Amazon for a watch

A watch can also search amazon.com: fill in **Amazon terms** on its form, in English
(`55 inch 4k tv | smart tv 65`). The `amazon-watches` provider runs those searches each poll —
20 items per term, at most 2 pages — and links every row it finds to the watch that asked for it
(`watch_sourced_deals`, V15). Blank means the watch does not search Amazon.

- **A separate field, not the watch's terms.** Those are Spanish, for Colombian retailers, and
  amazon.com answers `televisor` poorly. Every Amazon page load is also scarce — the host starts
  refusing after a few — so each term should be one worth spending a request on.
- **Linked rows are candidates even though their titles do not match.** "SAMSUNG 55-Inch Class
  Crystal UHD" contains no Spanish term, so matching on the title alone would fetch rows for the
  watch and never show them to it. Only the matching is widened: exclusions, the price band and
  the category still apply.
- **Each row carries the watch's category**, so a TV found for a "TV & Video" watch lands there
  whatever its English title matches. `Categorizer` takes a source category that is already a
  bucket name as it stands.
- **Separate from the `amazon` provider** so that its rows stay off Tracked, and its requests are
  not spent from the clothing searches' budget.
- **Neither Amazon provider appears on the feed**, which ranks offers; both fetch items whether or
  not they are discounted. Favorites is unaffected. Slickdeals' Amazon deals are still on the feed.
- **Shipping is not counted.** An Amazon TV is compared at its item price, even though freight
  to Colombia can nearly match it (`$248.68` on a `$267.99` TV). The rows get the usual
  automatic shipping check, and it shows on the feed and the Tracked tab.

### Comparing across currencies

A watch compares every candidate in its own currency. A USD listing is converted at Colombia's
official **TRM**, fetched once a day from datos.gov.co (dataset `32sa-8pi3`, no key). If that
fails, the app uses `defaults.usdCopRate` in `config/sources.yaml` (3200). The price band, the
30-day floor and the cross-site rule all use the converted price, so an Amazon TV can fire
"undercuts exito".

- **The alert says which rate it used**: `… — converted: amazon-watches US$399.99 at 3,264.39
  COP/USD (TRM 2026-09-24)`. A claim that rests on the fallback says `(config)`.
- **The ledger stores the quoted price**, in the deal's own currency. Storing the converted one
  would re-announce an unchanged dollar price every time the peso strengthened.
- **The watch page shows both**: `US$399.99 ≈ $1,305,723`. It sorts and highlights by the
  converted figure.
- **A currency the rate does not cover** (neither COP nor USD) is still judged only against its own kind.

### Set a minimum price. This is not optional.

Searching `nevera` on Éxito returns **fridge magnets, organiser trays, clamps and cooler
bags** — ranked above actual fridges. Measured on the real feed:

| Watch config | Candidates | Alerts |
|---|---|---|
| `nevera \| refrigerador`, no floor | 110 | **74, every one junk** |
| `+ min price 800.000, category Appliances` | 37 | 2 |
| `+ exclude "dispensador \| organizador \| portatil \| plegable"` | 34 | 0 |

Every one of those 74 "beat the floor" — a 15.900 fridge magnet beats a 949.900 floor
trivially. Without a floor the feature is pure noise on day one.

### Things that are easy to get wrong here

- **`min_price` is tested against the highest price the listing has ever *actually* reached**,
  not today's price. Testing today's price throws away exactly the deal you wanted: a fridge
  marked down from 1.09M to 849k falls below an 800k junk floor and disappears. It is
  deliberately **not** compared against `list_price` either — that let an 8-bottle wine
  cooler selling at 529.900 clear a 1.000.000 fridge floor on the strength of a self-declared
  1.399.900 "before" price. `max_price` is your budget, so that one *does* test the current
  price.
- **A watch is silent on its first run, by design.** The floor is built only from
  observations recorded *before* the one being judged, so with one observation per listing
  there is nothing to beat. "The cheapest of forty fridges" is a minimum, not news. This is
  the same trap `Deal.isAtLowestEver` fell into, where `price <= minPriceEver` was trivially
  true and put "lowest yet" on every row.
- **A brand-new listing with no history of its own still fires**, because the floor comes
  from the other candidates. That is the most valuable alert there is.
- **Term matching is accent-insensitive** (`unaccent`, V9). Éxito writes `Nevecon`, Carulla
  writes `Nevecón`, and the same Electrolux side-by-side is listed at both — the exact pair
  the cross-site rule exists to find. Note also that **`nevecón` is a different word from
  `nevera`**: 14 active listings matched neither `ILIKE` nor trigram until the term was added.
- **Alerts are announced once.** The `watch_matches` ledger re-announces only on a *lower*
  price, so a standing bargain does not banner every hour. A failed send leaves
  `notified_at` null so the next cycle retries.

## Ask Claude about the rows on screen

The feed, favorites, tracked and watch-detail screens each carry a collapsed **✦ Ask Claude
about these rows** panel. Ask "which of these is the best full-motion mount for a 55" TV?" and
the answer streams in, ranked, with every product it mentions linked to the retailer's page.

It runs the local `claude` CLI (`claude -p`) on the Claude Code login already on this machine —
there is no API key here, so the panel spends plan usage rather than a separate bill. Configure
it under `defaults:` in `config/sources.yaml`: `askModel` (default `sonnet`), `askEffort`
(`low`), `askMaxRows` (500), `askMaxBudgetUsd` (0.30), `askClaudePath` (blank = find it).

### What it sees

The server rebuilds the rows from the page's own query string, using the same functions the page
calls (`feedFilterFrom`, `favoritesFilter`, `trackedShown`, `watches.candidates`). The browser
never sends rows, so an answer is always about what the page shows. A watch sends its whole
"Matching now" list, not the 15 on the current page.

Rows go as a compact pipe-separated table at ~50 tokens a row: the 335 candidates of the
"Soporte TV" watch are ~17K tokens. If a screen exceeds `askMaxRows`, the prompt says how many
were left out.

### Why these settings

Each was measured, not guessed:

- **Sonnet, not Haiku.** On the same 139-row watch question, Haiku 4.5 spent 9.4K output tokens
  and cost $0.054 in 65s; Sonnet 5 answered in 1.8K tokens for $0.035 in 22s, and was the one that
  flagged the USD row. Cheaper per token is not cheaper per answer.
- **`--safe-mode` + our own `--system-prompt`.** Claude Code's default prompt was 8.5K tokens
  on a one-word reply — more than the rows. Replaced, the fixed overhead is ~350.
- **`--tools ""`.** It reads the rows and nothing else: no commands, files or web. A
  seller-written title cannot steer it into doing anything.
- **Follow-ups `--resume` the session**, so the rows are not re-sent. A follow-up after a filter
  change, or after 8 turns, starts fresh instead — resuming would answer about the old rows.

Typical cost: 3–10 cents per question depending on row count.

### Things that are easy to get wrong here

- **Links come from the database, never from the model.** URLs are not in the prompt (they would
  cost tokens on every row), so the server finds which sent rows the answer mentions — `[#id]`,
  `#id` or `id N` — and returns their real URLs. Only ids of rows actually sent count, so a
  screen size or price after a `#` never becomes a link.
- **The system prompt carries this README's rules** — blank is unknown, a list price is a claim,
  compare COP with USD only at the rate the screen states, Éxito vs Carulla is weak evidence — because a model reading these
  rows falls into the same traps the app already did.
- **Answers are inserted as text, never HTML**: they are model output about third-party titles.

## Cross-site product identity

The strongest evidence a price is good is a different retailer charging more for the same
item — a discount percentage is only a claim about the seller's own past price.

Establishing "same item" rests on published codes, never on titles looking alike:

1. **GTIN/EAN**, when it passes its GS1 check digit. Colombian appliances really do carry
   them (`7705191041773`, Challenger; `7909569417232`, Electrolux).
2. **Brand + model code**, from a published `model` field where there is one (Homecenter) or
   lifted from the title otherwise (`RMA247FXCG`, `CR290`, Haceb's bare `9003541`).

**Both are stored for every deal, and two listings match if they share either.** That is not
redundancy — it is the only reason the six sources can be compared at all, because they
disagree about which identifier they publish:

| | publishes |
|---|---|
| Éxito, Carulla, Easy | real GS1 barcodes |
| Falabella, Homecenter | no barcode; brand + model only |
| Slickdeals | neither |

With one key per listing, the same MABE side-by-side carried `gtin:…` at Éxito and `mdl:…` at
Homecenter and never matched itself. Measured before the fix: 119 multi-source clusters
existed and **not one** joined a Falabella-group store to an Éxito-group store — which is
exactly the comparison worth having, since one inside a single group is weak evidence. See
`V10__model_key`.

Two normalisations that each fixed a real miss:

- **Barcodes are left-padded to GTIN-14.** Éxito publishes the same fridge as
  `757638435765` (UPC-12) *and* `0757638435765` (EAN-13). Unpadded, one product had two keys.
  Padding cannot break the checksum — GS1 anchors the weights at the right — which is why GS1
  permits it. EAN-8 is a separate scheme and is left alone.
- **A published model is run through the same extractor as a title.** Homecenter sends
  `RS23T5B00S9/CO` where another retailer's title yields `RS23T5B00S9`. One canonicalisation
  path means those are one key.

Coverage on the live feed is 82–87% for the barcode-publishing retailers, and 0% for
Slickdeals. A known miss: Homecenter's `LUMINA CR 317` yields no code, because a spaced model
cannot be told from marketing words. Missing a match is the safe failure — a wrong one sends
you shopping for the wrong appliance.

**The check digit is the whole point, not a nicety.** Éxito publishes real GS1 codes and its
own internal references *in the same `ean` field* — `14636` and `IZ20000899908` alongside
valid ones. Both fail the checksum, so no heuristic about Éxito is needed.

Two things this deliberately refuses to do:

- **Cluster on title similarity.** A wrong "cheaper elsewhere" sends you shopping for the
  wrong appliance, which is worse than silence. Near-matches can be shown; they never alert.
- **Cluster on null.** Most groceries and every Slickdeals row have no usable key. NULL never
  matches NULL — hence the partial index in `V8__product_identity`.
- **Compare two listings at one retailer.** That is a duplicate, not a comparison.

A real result from the live feed:

```
CROSS_SITE_SPREAD $2,587,455  Nevecon Side By Side ELECTROLUX 528 LITROS ERSA53K3
    16.0% cheaper than carulla at $3,079,900 (same gtin:7909569417232)
```

### Éxito vs Carulla is weaker evidence than either vs Easy

They are the same corporate group on the same VTEX backend, and it shows. The largest gap
found so far — 42% on a MABE side-by-side — is the same product id (`3246410`) and GTIN at
both, where **Carulla's price is exactly Éxito's list price**:

| | price | list price |
|---|---|---|
| exito | 2,553,682 | 4,402,900 |
| carulla | 4,402,900 | — |

The saving is real and worth acting on, but the *signal* is not two independent retailers
disagreeing — it is one banner running a promotion the other is not, so it restates the
discount rather than corroborating it. A gap involving **Easy** (Cencosud, a different
company) is the genuinely independent comparison. Worth remembering before treating a big
number as proof.

## Categories

The **Category** dropdown filters across every source, using 14 broad English buckets
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
- *Bathroom & Plumbing* before *Tools & Outdoor* and *Home & Kitchen* — Homecenter files taps
  under "griferias de bano y cocina", which *Home & Kitchen*'s `cocina` matches, and bathroom
  fittings sit under paths *Tools & Outdoor*'s `ferreteria` reaches. The bucket exists because
  two of the nine sources are home-improvement retailers: without it every toilet, basin and
  tap landed in `Other`, which also hid them from a watch pinned to a category.

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

## Exporting to Excel

**Export to Excel** downloads the current selection as a real `.xlsx` — `deals-YYYY-MM-DD.xlsx`,
two sheets, 24 columns.

The button is a submit button on the filter form with `formaction="/export.xlsx"`, so it
sends the controls exactly as they stand — including edits you haven't clicked **Apply**
on yet. Both the page and the export build their `FeedFilter` from
`feedFilterFrom(params)`, so what you're looking at and what you download cannot drift
apart.

### What you get if you export without filtering anything

**An untouched form is not "no filter".** Three constraints apply before any input:

| Constraint | Effect |
|---|---|
| `d.is_active` | retired deals excluded — anything unseen for `staleAfterHours` (48h) |
| `state IN (NEW, SEEN, FAVORITE)` | **dismissed deals excluded** — reachable only via the State filter |
| relevance sort | promoted first, then recency, then discount |

So it's every active, non-dismissed deal — not a dump of the `deals` table. Rather than
leave that to be rediscovered, the **Export info** sheet writes it into every file, along
with the generated timestamp, the row counts, and each filter's value.

The one exception is `/favorites/export.xlsx`, which keeps retired deals (see
[Favorites](#favorites)). Its **Export info** sheet says so explicitly rather than
repeating the guarantee above: a file whose rows include dead offers while its own sheet
promises "active deals only" would be read as evidence those offers are live.

### It exports all matching rows, not the page

`FeedFilter.limit` caps the *rendered* page at 1000 so the browser stays responsive, and
the header says "showing 1000 of 1420" when that bites. A spreadsheet can't say that in
passing, so inheriting the display cap would hand you a file that looks complete and
silently isn't. The export raises the limit to `DealsExport.MAX_ROWS` (50,000) instead. If
that ever bites, `Truncated` on the Export info sheet says so and names the shortfall —
and the `no` case is written too, so its absence is never ambiguous.

### Reading the sheet

- **Blank means unknown, never zero** — the same rule the feed UI follows. Slickdeals
  publishes no list price, so its **List price**, **Discount** and **Drop from peak** cells
  are empty; Éxito and Carulla publish no **Community score**, and neither publishes a
  **Posted at**. A `0` in any of those would sort and average as though the value were
  known to be zero.
- **Prices are typed numeric cells**, which is the reason for `.xlsx` over CSV: they sum
  and sort natively, and Excel can't reinterpret an accented category on open. COP and USD
  share the **Price** column with per-row currency formats (`$1,899,900` vs `US$18.99`), so
  the **Currency** column is what keeps a mixed export meaningful — don't total across it.
- The header row is frozen and every column carries an autofilter dropdown.

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

### Cost, not just eligibility

A YES verdict alone is misleading. Amazon's global-shipping widget reads either

```
No Import Charges & FREE Shipping to Colombia
No Import Charges & $60.66 Shipping to Colombia
```

Both are the same verdict with the same import-charge status, but a $60.66 fee usually
erases whatever discount made the deal interesting. So `co_shipping_cost` is stored
separately (NUMERIC; `0` = free, NULL = Amazon quoted nothing) and shown in the UI as an
amber **+US$60.66 ship** chip. Import charges get an amber **import fees** chip when they
*do* apply — previously only the harmless "no import fees" case was shown, which was
backwards.

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

- **No pagination, and the cap is close.** The feed renders up to 1000 rows in one page.
  That covered the volume comfortably at ~370 deals; as of 2026-08-27 it is **920 active
  deals across three sources**, so the cap is roughly 80 deals away from biting. When a
  page is capped the header says "showing X of Y" rather than reporting the page size as a
  total, so truncation stays visible — but real pagination is the fix, and it is now
  closer to needed than optional. The Excel export deliberately ignores this cap, so it
  is unaffected.
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

- **`%20`, not `+`, for a space in a query.** `URLEncoder` does *form* encoding, where a space
  is `+`. olimpica.com answers **HTTP 400** to `ft=smart+tv` and 206 to `ft=smart%20tv`, while
  exito.com tolerates both — so a watch term like "smart tv" silently returned nothing on one
  source. Shared `encodeQueryValue` now handles it for every adapter.
- **Two opposite money conventions, one codebase.** Shopify sends `"1645900.00"` where the dot
  is a **decimal point**; Falabella and PrestaShop send `"1.829.900"` where dots **group
  thousands**. The strings look alike, and applying the wrong parser is a factor-of-100 error.
  Each adapter has a test pinning its own convention against the other's.
- **Shopify is the easiest source here.** `/collections/<handle>/products.json` is documented,
  first-party and stable, with `price`, `compare_at_price`, `sku` and `available`. `barcode` is
  absent from the public feed, so identity falls to brand + model. Roughly two thirds of a
  collection comes back out of stock, and unbuyable rows are dropped — otherwise they would
  plant a phantom cheaper rival in the cross-site comparison.
- **PrestaShop is read from the theme, not an API — the one adapter here that is.** Its
  *product* pages carry lovely JSON-LD with `gtin13` and `offers.price`, but a *category* page
  emits only an `ItemList` of names and URLs with **no prices**, so that route would cost one
  request per product against a small retailer. The default theme's grid has everything
  instead: an unformatted integer in `span.product-price[content]`, the was-price, the
  manufacturer reference as its own field, and the EAN appended to the URL slug. Because themes
  vary, the selectors are `options` rather than constants.
- **Grupo Mansión publishes no brand in its grid**, so those rows join the comparison on GTIN
  alone. Left null rather than guessed from the title: a wrong brand makes a wrong identity
  key, whereas a missing one only costs a match.

- **An Algolia search key in a page is meant to be public — but check it anyway.** Alkomprar
  ships its app id and API key to every browser, as every Algolia site does. Before using it,
  `GET /1/keys/<key>` confirmed the ACL is exactly `["search"]` and an `addObject` attempt was
  refused 403. Worth the two requests: the same-looking string could have been an admin key
  leaked by mistake, and that would be somebody else's incident, not a source.
- **Alkomprar publishes the EAN as its product code**, so `code_string` is both the id and the
  identity key — 100% GTIN coverage, the best of any source here, and it clusters with Éxito,
  Easy, Olímpica and Grupo Mansión on the first fetch.
- **It is browsed by category facet, not keyword.** Its robots.txt disallows `/search/` and
  `*?q=`. Those rules govern the storefront rather than the Algolia host, but honouring the
  site's evident intent is the same call made for Homecenter. An empty `query` plus a
  `allcategories_string_mv` filter is a category browse and returns the same rows its own
  category pages show.
- **`PoliteHttp` gained a `postJson`** for this, rather than the adapter reaching for a bare
  HTTP client: Algolia takes the query in a POST body, and an adapter that bypassed the shared
  layer would silently opt out of the per-host throttle and backoff that keep this tool's
  footprint small. It skips ETag caching deliberately — ETags are keyed by URL, and every query
  to an index shares one URL with a different body.
- **`cmrPrice` is not the price.** Falabella publishes several prices per product, and
  `cmrPrice` — the price with its own store credit card — is usually the lowest and appears on
  32 of 56 real rows. Reading it as the sale price would not merely be inaccurate: it would
  make Falabella win every cross-site comparison it takes part in. The adapter excludes it and
  reads `eventPrice`, then `internetPrice`. There is a test whose only job is this.
- **Homecenter and Falabella are one adapter, not two.** Same `falabella-b2c-ui` Next.js
  build, same product record shape. They differ in exactly two config values: where the
  results sit in `__NEXT_DATA__` (`props.pageProps.results` vs
  `props.pageProps.searchProps.searchData.results`) and how a product URL is formed.
- **Homecenter must not be keyword-searched.** Its robots.txt disallows `*/search?` while
  allowing category pages. Since a watch injects its terms into every provider's `searches`,
  the adapter gates search behind `searchEnabled`, defaulting to **off** — so forgetting the
  flag fails safe rather than quietly hitting disallowed URLs.
- **Neither publishes a category on the product.** A category *listing* implies one by
  construction, and the adapter attaches it. Without that, 98% of a Homecenter fetch landed in
  `Other`, which also made every row invisible to a watch pinned to a category.
- **Homecenter publishes no product URL.** `/{store}/product/{skuId}/` 302s to the canonical
  slug, which is what the adapter builds. Falabella does publish an absolute `url`.
- **`skuId` is the id, not `productId`.** They genuinely differ (`136275849` vs `136275851` on
  one real row), and the URL is built from the sku.
- **Read the merchant column on these two.** Both carry marketplace sellers, and one of
  Falabella's is Homecenter itself — so a "Falabella is cheaper than Homecenter" gap may be
  the same seller twice. The adapter stores `sellerName` so this is visible.
- **Don't chase the underlying BFF.** `/s/browse/v1/search/co` and `/s/browse/v1/listing/co`
  exist and even leak their schemas (`must have required property 'Ntt'`), but every
  well-formed call returns `404 {"responseType":"Bad Request"}`. Homecenter has no
  `/s/browse` at all.
- **Two VTEX false positives.** `www.makro.com.co/api/catalog_system/...` returns **200**
  (a legacy PHP site answering 200 with its homepage for any path) and
  `www.falabella.com.co/api/catalog_system/...` returns **200** after redirecting to their
  jobs site. Check the content type and `%{url_effective}`, not just the status code.

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
gitignored and `chmod 600`. `.env.example` and `local.properties.example` show the expected
shape.

This is now a git repository, and `.gitignore` predates it — written before `git init` so the
first `git add .` could never have committed either file. There is **no remote configured**,
so nothing has ever left this machine; see `SETUP.md` for moving the project to another Mac.
