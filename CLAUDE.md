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
`web/Views.kt` when you change it, or browsers will serve the cached stylesheet.

## Every new feature ships with tests

Any new functionality gets tests in the same change, not afterwards and not on request. Put them
in `src/test/kotlin/...` mirroring the package of the code (a view in `web/`, a rule in `ingest/`).
The suite needs no database or network, so keep logic that touches SQL thin and test the rules and
rendering around it: pull a rule out of a route into a plain function (as `newlyUnderUsual` was) so
it can be tested. Run `./dt test` and report the result before calling the work done.

## Every UI change gets a frontend-design review

Any change a user can see — a view in `web/`, `app.css`, a fragment HTMX swaps in, a toast — gets
reviewed with the `frontend-design` skill before the work is called done, unprompted, the same way
tests are. The plugin is installed at project scope (`frontend-design@claude-plugins-official`, enabled
in `.claude/settings.json`); if the skill will not load, install it with
`claude plugin install frontend-design@claude-plugins-official --scope project`, rather than skipping
the review.

The review works inside this app's existing design system, not a new one: same tokens, chips, tables
and type in `app.css`. Restart the app, then screenshot the changed screen at desktop width and at
390px, in every state the change has (open and closed, empty, long titles, Spanish), and check
mobile, keyboard focus, reduced motion and the copy. Fix what it finds and say what was checked.

Screenshots: use Playwright's headless shell, not regular headless Chrome, which will not go below
500px wide and so fakes mobile clipping:

    ~/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-mac-arm64/chrome-headless-shell \
        --screenshot=out.png --window-size=390,1000 http://127.0.0.1:8080/watches

## Every change needs the local app restarted

There is no hot reload — Ktor's `development mode` is off, and the log says so on boot
("Autoreload is disabled"). A Kotlin edit, a migration, or a `config/sources.yaml` change is
invisible to the running process, so a change that looks broken in the browser is usually
just a change that isn't loaded. The exception is CSS, which is read from disk per request.

So after editing, restart it. Check how it is running first: since 2026-11-08 the app is meant to
run under launchd (`scripts/install-launchd.sh`), which restarts it on its own if it dies.

    launchctl list | grep com.luis.dealstracker     # a line here means launchd runs it

Under launchd, restart through launchd. Do **not** `pkill` and `nohup` a new copy: launchd starts its
own copy again within a minute, and the two fight over port 8080.

    launchctl kickstart -k gui/$(id -u)/com.luis.dealstracker

Without launchd (no line above), the old way:

    pkill -f 'com.luis.dealstracker.AppKt'    # see the warning below before running this
    nohup caffeinate -i ./dt run > logs/app.log 2>&1 &

Either way the log is `logs/app.log`. The launchd job runs `scripts/run-app.sh`, which starts Docker
Desktop and Postgres if they are down (Docker does not start at login on this Mac) and then runs the
app under `caffeinate -i`. A second job, `scripts/watchdog.sh`, checks every 15 minutes and posts to
Discord when no poll has finished in two hours or the app or database stops answering; its rules are
tested by `scripts/test-watchdog.sh`.

`caffeinate -i` stops the Mac idle-sleeping for as long as the app runs, and no longer. Without
it the poller simply stops when the lid is idle: on 2026-09-25 the Mac slept from ~09:30 to 14:03,
and in those four and a half hours no poll finished and no shipping check ran. It does not keep
the display on, and a closed lid on battery still sleeps.

Leave the app running when you're done — Luis keeps it up on 8080, and the hourly poller only
collects prices while the process lives. A dead app is not a neutral state: price history has
gaps for exactly as long as it was down, and nothing backfills them.

**Restart the instance you started, not every instance.** `pkill -f` matches on the main
class, which is identical across copies, so the pattern above kills the 8080 app too. If you
started a second copy on another port, stop that one by its own pid:

    DEALS_HTTP_PORT=8099 ./dt run > /tmp/probe.log 2>&1 &
    kill %1          # or the pid, never a class-wide pkill

A migration is worth one extra check after the restart, since Flyway applies it at boot and a
failure there leaves the app dead rather than degraded:

    grep -E 'Migrating|Successfully applied|schema at version' logs/app.log

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
