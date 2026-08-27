# Goal prompt — Amazon → Colombia shipping verification

Paste the block below as the agent's task. Written to be **resumable and idempotent**, so
re-running it (including under `/loop`) is safe.

Usage notes are at the bottom.

---

## The prompt

> ## Goal
>
> Make the Deals Tracker resolve, automatically, whether an Amazon deal can be shipped to
> Colombia — replacing the manual per-deal verdict I record by hand today.
>
> **Done means:** I can run one command, walk away, and come back to find previously
> `UNKNOWN` deals carrying a `YES` or `NO` verdict that I spot-checked against what
> amazon.com actually shows me in my own browser for the same items. Verdicts persist,
> never overwrite a verdict I set by hand, and a deal that fails verification is
> distinguishable from one that was never attempted.
>
> ## Context — established facts, do not rediscover these
>
> Read `README.md`, `SETUP.md` and `BLOCKERS.md` first. `BLOCKERS.md` has a section on this
> exact problem under "Amazon → Colombia shipping is still manual". Key points:
>
> - **Stack**: Kotlin, JDK 21, Ktor, Postgres in Docker, Flyway migrations. Run everything
>   with `./dt` (never `./gradlew` directly — it resolves JDK 21 via `/usr/libexec/java_home`).
>   Next migration is `V5__`.
> - **The data**: 308 active deals are `co_shipping = 'UNKNOWN'`; **303 have an ASIN**, 5 do
>   not. Colombian sources (Éxito, Carulla) are already `YES` and must be left alone —
>   only Amazon/Slickdeals deals need verifying.
> - **Existing seams to use, not reinvent**: `CoShipping` enum in `model/Model.kt`,
>   `DealRepository.updateCoShipping(...)`, and the UNKNOWN badge with ships/no buttons in
>   `web/Views.kt`. The UI is already built to display a verdict.
> - **Node is v16 (EOL)** on this machine. Factor that into the stack choice.
> - **Project conventions**: behaviour is config-driven (see how `config/sources.yaml` and
>   `config/categories.yaml` are used) rather than hardcoded; anything skipped or dropped
>   gets **logged with a count**, never silently; tests run against captured fixtures and
>   must need no network or database.
>
> ## Phase 0 — decide the approach, and justify it
>
> Pick a browser automation stack and state the reasoning in one short paragraph before
> writing code. Playwright's Java binding and Playwright on Node are both plausible; the
> Kotlin codebase and the EOL Node install pull in different directions. If you find a
> better option, say so. **Do not** proceed past this until you've committed to a choice
> and said why.
>
> ## Phase 1 — prove one ASIN end to end, headed
>
> Before any batching, get a single deal right with a **visible** browser window so I can
> watch it:
>
> 1. Open a product page. `https://www.amazon.com/dp/{ASIN}` is the entry point.
> 2. Set the delivery location to Colombia and **persist the session** (Playwright's
>    `storageState`) so it isn't re-done on every page.
> 3. Extract the shipping eligibility and print it alongside the raw text you based it on.
>
> Test ASINs from my current data: `B09C5RG6KV` (Anker charger), `B0CKN1N2YH` (MagSafe
> phone mount), `B0D4M581B3` (AA batteries), `B0GKDL92X4` (garage door screens — bulky, a
> good candidate for *not* shipping).
>
> **Verification gate:** tell me the verdict for each of those four, and I will check them
> in my own browser. Do not build the batch until these agree with what I see.
>
> Expect eligibility to be phrased inconsistently. Amazon may say the item ships to
> Colombia, may say it cannot be shipped to the selected address, or may be silent and
> only reveal it at checkout. **Capture the real HTML of at least 3 product pages as test
> fixtures** and write the extractor against those, so the parsing logic is unit-testable
> offline like every other parser in this project.
>
> ## Phase 2 — fix the state model first
>
> `CoShipping` has only `UNKNOWN`, `YES`, `NO`. That is insufficient and will cause an
> infinite retry loop: a **failed** check is currently indistinguishable from one that was
> **never attempted**, so a batch would re-check the same broken deals forever.
>
> Solve this before batching. At minimum you need to know when a deal was last checked and
> whether a verdict came from automation or from me. Design it as you see fit, but these
> two properties are non-negotiable:
>
> - **A verdict I set by hand is never overwritten by automation.** I have already
>   verified deals manually; that work must survive.
> - **Repeated failures back off** instead of being retried every cycle.
>
> Add a Flyway migration. Do not modify existing migration files.
>
> ## Phase 3 — batch, politely
>
> Work through the `UNKNOWN` backlog headlessly, with the rate limiting treated as a
> correctness requirement rather than a nicety. Amazon is not a cooperative JSON API like
> Éxito; it will throttle and challenge you. Make the batch size, delay and daily cap
> config-driven so I can tune them without a rebuild, and start conservative.
>
> The 5 deals with no ASIN need an explicit decision — resolve them another way or skip
> them, but **log the count either way**. Do not let them vanish silently.
>
> ## Hard constraints
>
> - **Never attempt to defeat or evade bot detection.** No CAPTCHA solving, no fingerprint
>   spoofing, no proxy rotation, no pretending to be a browser you are not. If Amazon
>   presents a CAPTCHA or a "continue shopping" wall: **stop, back off, and report it.**
>   Being blocked is a valid finding and I need to know about it.
> - **Do not log into my Amazon account.** Use an anonymous session with a delivery address
>   set. If eligibility genuinely cannot be determined without logging in, stop and tell
>   me — that is my decision, not yours.
> - **Do not commit credentials, cookies or session state.** `storageState` files go in
>   `.gitignore`. Read how `.env` and `local.properties` are handled and follow it.
> - **Do not break the existing 84 tests.** Run `./dt test` before you claim done.
> - **Do not touch the Colombian sources' verdicts.** Éxito and Carulla are correctly `YES`.
> - Keep request volume low enough that a human browsing the same pages would generate
>   more traffic than you do.
>
> ## Stop and report — do not push through these
>
> 1. A CAPTCHA or bot wall appears.
> 2. Eligibility cannot be read without logging in.
> 3. Eligibility turns out to be genuinely unavailable before checkout for most items.
> 4. Two consecutive attempts at the same sub-problem fail for the same reason. Report what
>    you tried rather than trying a third variation.
> 5. The approach needs a dependency or architectural change I haven't agreed to.
>
> A clear "this doesn't work and here's the evidence" is a **successful** outcome. I would
> much rather read that than have a plausible-looking verifier that produces wrong verdicts.
>
> ## Report back with
>
> - The stack you chose and why.
> - The four Phase 1 verdicts, with the raw page text each was based on, so I can check them.
> - How you distinguish "not checked", "checked: ships", "checked: doesn't", "check failed".
> - Real counts: verified, failed, skipped — and for skips, why.
> - What is fragile and what will break first when Amazon changes.
> - Anything you were unsure about, stated as uncertainty rather than smoothed over.

---

## How to run it

**Phases 0–2 are one-shot engineering work with a finish line.** Run them as a normal task
or a single agent, not on an interval — an interval loop would restart work already in
progress.

**Phase 3 is the loop-shaped part.** Once the verifier exists and Phase 1 verdicts have
been checked, `/loop` fits the backlog:

```
/loop 30m verify the next batch of UNKNOWN Amazon deals, then report counts
```

Long intervals are deliberate. The constraint is Amazon's tolerance, not our throughput,
and a slow drip over days is both safer and less likely to get blocked than a fast sweep.

## Why the prompt is shaped this way

Worth understanding if you're going to write more of these:

- **The outcome is observable, not a description of work.** "I can run one command and come
  back to verdicts I spot-checked" can be judged true or false. "Build a verifier" cannot.
- **Phase 1 is a hard gate with human verification.** The single highest risk is a verifier
  that runs cleanly and produces *confidently wrong* verdicts. Four known ASINs you check
  yourself catches that before it scales to 303.
- **The state-model flaw is called out explicitly.** An agent would likely build the happy
  path, hit the retry loop in production, and only then discover it. Naming the trap up
  front is cheaper than letting it be found.
- **Stop conditions include giving up.** Without "a clear no is a success", agents grind at
  blocked problems and produce something that looks finished instead of reporting the wall.
- **The no-evasion constraint is stated as a rule, not a preference**, because it's exactly
  where an agent optimising for "make it work" would drift.
- **Fixtures are demanded up front**, matching how every other parser here is tested — that
  is what makes the extractor verifiable without hitting Amazon on every test run.
