# Amazon → Colombia shipping verification — Phase 0/1 findings

Status: **blocked by Amazon anti-automation**, on this machine's network. The mechanics are
solved; the delivery route is not. Recorded so none of it is re-learned.

Date: 2026-08-27. Goal prompt: [goal-amazon-co-shipping.md](goal-amazon-co-shipping.md).

---

## Phase 0 — stack decision: Playwright for Java 1.62.0

Chosen because it runs in-process with the Kotlin app and **bundles its own Node runtime**
(`driver/mac-arm64/node`, 121MB inside a 209MB jar), so the system Node — v16, EOL — is
never used. That invalidated the main concern in the original prompt.

Selenium 4.33 was the alternative and is worse here: no first-class `storageState`, so
persisting the Colombia delivery address becomes hand-rolled cookie management, and that
persistence is the one thing the whole feature rests on.

Verified on this machine, not assumed: Chromium 151 launches, `storageState` round-trips,
an Amazon product page returns HTTP 200 with real content.

**Install only Chromium.** `Playwright.create()` otherwise downloads every browser —
1.1GB. The `installChromium` Gradle task pulls ~554MB instead.

## Environment: TLS interception breaks Node

This machine is behind Zscaler, which MITMs all TLS. The Zscaler root CA is in the macOS
keychain, so `curl` works — but **Node ships its own CA store and ignores the keychain**,
so every Playwright download failed with `UNABLE_TO_GET_ISSUER_CERT_LOCALLY`.

Fixed in `./dt`: it exports the system roots to a temp PEM and sets `NODE_EXTRA_CA_CERTS`.
Regenerated per run, never committed. Harmless on a machine without such a proxy.

This would have hit Playwright-on-Node identically — it is a Node problem, not a
Playwright one.

## Phase 1 — the mechanics (all working)

### Setting the delivery country

1. The location control is behind `#nav-global-location-popover-link`. Its label is
   **"Delivering to"**, *not* "Deliver to" — searching for the latter finds nothing, which
   is what made the first probe look like the control was absent.
2. Inside the popover, under a heading `#GLUXCountryListLabel` reading *"or ship outside
   the US"*, is a **native `<select id="GLUXCountryList">`** with 247 options including
   `<option value="CO">Colombia</option>`.
   - It is a real `<select>`, so `page.selectOption("#GLUXCountryList", "CO")` works.
   - There is also a JS mirror (`a.a-dropdown-link` with
     `data-value='{"stringVal":"CO"}'`), which is *not* needed.
   - Querying `#GLUXCountryList a` returns 0 — the options are `#GLUXCountryList option`.
3. No confirm click is required; it applies on select.
4. **Confirmation it worked**: the session cookie `sp-cdn` becomes `"L5Z9:CO"`. Saving
   `storageState` therefore lets later pages skip the address dance entirely.

### Reading eligibility — the trap

**`#deliveryBlockMessage` is empty on load and populated by AJAX.** The page ships a
`<script type="a-state" data-a-state='{"key":"delivery-block-ajax-params-0"}'>` carrying
`{"merchantId":…,"asin":…}` and fetches the delivery copy afterwards.

Reading it synchronously **always returns an empty string**. Any implementation must poll
until it has content, or it will silently report "unknown" for every single deal.

Also note: on the page served after the location change, `#glow-ingress-line1/2` were
absent altogether — the nav rendered differently. Do not depend on those to confirm the
location; check the `sp-cdn` cookie instead.

## What blocked it

After roughly **6–7 page loads over ~15 minutes** — already well below one request per 30
seconds — Amazon stopped serving product pages and returned its interstitial:

```
Click the button below to continue shopping
```

### Probable cause: datacenter IP, not request rate

Amazon geolocated the session to **"Miami 33166"**, a datacenter — the Zscaler proxy's
egress. Traffic from those ranges is treated as presumptively automated regardless of
pacing, so slowing down further is unlikely to help. Requests from a residential IP are a
different proposition.

Unproven, and worth stating as such: this has not been tested from another network, and
whether the block is transient (minutes) or sticky (hours) is unknown — establishing
either would mean making more requests.

### A detector bug this exposed

The first version of `botWallMarkers` checked only for CAPTCHA phrasings and `Robot Check`.
The wall that actually fired says **"Click the button below to continue shopping"**, which
matched nothing — so the block was invisible and the page merely looked empty. Unattended,
a batch would have recorded hundreds of deals as unverified while reporting success. The
marker is now included.

## Viable path forward: headed browser with human-in-the-loop

The wall is a single button, not a CAPTCHA, so a person can clear it in one click. The
workable shape:

- `launchPersistentContext(userDataDir)` so trust cookies survive between runs.
- Run **headed**. On detecting a wall, **pause and ask the operator to click**, then resume.
- Do **not** log into the Amazon account: a persistent profile without login keeps the
  account out of it.

Consequences to accept before building this:

- It does not fix IP reputation. Challenges may recur every N requests; at ~30
  interventions for 300 deals the automation argument weakens considerably.
- **Phase 3 as originally specified becomes impossible** — an unattended overnight batch
  cannot ask a human to click. This is *assisted bulk checking*, not automation. Still
  plausibly better than checking 300 items entirely by hand, but it is a different thing.
- Automating amazon.com remains against their terms whether or not a human clicks.

## Still outstanding

- **No verdicts were obtained.** The Phase 1 gate — four ASINs verified against what a
  human sees — was never reached, so nothing here is validated end to end.
- The eligibility *phrasing* for a shippable vs non-shippable item is still unknown, since
  the delivery block never populated before the block. Extraction cannot be written until
  real examples of both exist.
- The state-model fix (Phase 2) is untouched: `CoShipping` still has only
  `UNKNOWN`/`YES`/`NO`, so "checked and failed" is indistinguishable from "never checked".
  That must be solved before any batch, or it will retry the same failures forever.

## Files

- `src/main/kotlin/com/luis/dealstracker/verify/AmazonProbe.kt` — explorer: dumps page
  structure, finds location controls, sets the country, saves the session.
- `src/main/kotlin/com/luis/dealstracker/verify/AmazonProbe2.kt` — reads eligibility using
  a saved Colombia session, polling for the async delivery block.

Both are **throwaway probes**, not the verifier. Gradle tasks: `probe`, `verifyProbe`,
`installChromium`.

Probe output lands in `build/amazon-probe/` — gitignored, which matters because
`amazon-session.json` holds real Amazon session tokens.
