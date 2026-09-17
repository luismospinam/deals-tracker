# Working in this repo

Two tools here need a specific runtime that is not this machine's default. Both have a
wrapper script; use the wrapper, never the underlying command.

## Gradle: use `./dt`, not `./gradlew`

`dt` resolves JDK 21 via `/usr/libexec/java_home`, so no machine-specific paths end up in
`gradle.properties`. The default `java` on a Mac is frequently too old to run Gradle 8.10.

    ./dt run                                  start the app (web UI + hourly poller)
    ./dt test                                 run the suite
    ./dt run --args="--fetch-once"            one-off fetch of every enabled source
    ./dt run --args="--check-watches"         evaluate watches now, notify on new lows
    ./dt run --args="--check-watches --dry-run"   print what would fire, write nothing

The web UI is on `localhost:8080` (`defaults.httpPort` in `config/sources.yaml`). To run a
second copy alongside the usual one — a worktree, or a branch under test — set
`DEALS_HTTP_PORT` rather than editing the committed config:

    DEALS_HTTP_PORT=8081 ./dt run

CSS lives at `src/main/resources/static/app.css` and is served
from disk, so style-only edits need just a browser refresh — but bump `CSS_VERSION` in
`web/Views.kt` when you change it, or browsers will serve the cached stylesheet. Kotlin
changes need a restart.

## Lavish Editor: use `./lavish`, not `npx -y lavish-axi`

The installed `lavish` skill says to invoke `npx -y lavish-axi ...`. In this repo that
crashes: `lavish-axi` requires Node >= 22, npm does not enforce `engines`, and the default
`node` here is 16 — so it installs and then dies with `ReferenceError: fetch is not
defined`, which names neither the cause nor the fix. `./lavish` resolves a Node >= 22 from
nvm first and takes the same arguments.

    ./lavish .lavish/thing.html               open or resume a review session
    ./lavish poll .lavish/thing.html          wait for the user's feedback
    ./lavish playbook comparison              guidance for a kind of artifact
    ./lavish stop                             shut down the background server

Put artifacts in `.lavish/` (gitignored). Ask the CLI for current guidance rather than
trusting the skill file — `./lavish --help`, `./lavish design`, `./lavish playbook <id>`.

When an artifact mocks this app's UI, render it in this app's own design system: inline
`src/main/resources/static/app.css` rather than reaching for Tailwind, so the mockup shows
the product rather than a generic page.

## Docs

`README.md` for architecture and source behaviour, `SETUP.md` for first-run setup,
`BLOCKERS.md` for why MercadoLibre and Rappi are disabled.
