# Setup — running Deals Tracker on a new Mac

Everything needed to get this running from scratch. For what the app *does* and why it
works the way it does, see [README.md](README.md).

---

## 1. Prerequisites

Only the first three are needed to run the app and use watches. The rest are per-feature.

| Requirement | Version | Why |
|---|---|---|
| **macOS** | any recent | `./dt` uses `/usr/libexec/java_home`; alerts use Notification Center |
| **JDK** | **21** | `build.gradle.kts` sets `jvmToolchain(21)` |
| **Docker Desktop** | any current | runs Postgres |
| **Chromium** (via Playwright) | ~170MB | **only** for Amazon → Colombia shipping verification. See below — do not skip the targeted install |
| **terminal-notifier** | 3.1.0 | *optional.* Makes watch alerts clickable. Alerts work without it |
| **Gradle** | *not needed* | the wrapper (`./gradlew`) downloads 8.10.2 itself |
| **Node / npm** | *not needed to run* | no JavaScript build step; HTMX loads from a CDN. (The `./lavish` dev tool wants Node ≥ 22 — see CLAUDE.md.) |

Install the JDK:

```bash
brew install --cask corretto@21     # or: brew install openjdk@21
```

Verify it's visible. This must print a path — it does **not** matter whether JDK 21 is
your default `java`, because `./dt` resolves it explicitly:

```bash
/usr/libexec/java_home -v 21
```

Install Docker Desktop from <https://www.docker.com/products/docker-desktop/> (or
`brew install --cask docker`), then **launch it** — the daemon must actually be running,
not just installed:

```bash
docker info        # should print server info, not "Cannot connect to the Docker daemon"
```

### Ports

`5432` (Postgres) and `8080` (web UI) must be free:

```bash
lsof -nP -iTCP:5432 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

If something already uses 5432 (another Postgres is common), change the host port in
`docker-compose.yml` and set `DATABASE_URL` accordingly. For 8080, either change `httpPort`
in `config/sources.yaml` or set `DEALS_HTTP_PORT` — the env var wins, and is the right choice
for a temporary second copy, since it leaves committed config alone:

```bash
DEALS_HTTP_PORT=8081 ./dt run
```

### Chromium, if you want automated Amazon shipping verification

Skip this unless you plan to run `--verify-co-shipping`; everything else works without it.

**Install the targeted build, not the default one.** Calling `Playwright.create()` with no
browsers present downloads Chromium, Firefox *and* WebKit — about 1.1GB, two of which this app
never opens. There is a task that fetches only what is used:

```bash
./dt installChromium        # ~170MB into ~/Library/Caches/ms-playwright
```

The first `--verify-co-shipping` run then opens a **headed** Chrome and asks you to set the
delivery country once. See the Colombia-shipping section of `README.md` for why it runs headed
and pauses for a human.

---

## 2. Get the project onto the new machine

This is a git repository but **has no remote configured** (`git remote -v` is empty), so
there is nothing to clone from yet. Two options:

**Copy the directory** — simplest, and brings the git history with it:

```bash
rsync -a --exclude build/ --exclude .gradle/ --exclude .kotlin/ \
      --exclude .amazon-profile/ --exclude logs/ \
      ~/Documents/projects/luis/deals-tracker/ new-machine:~/deals-tracker/
```

**Or add a remote first** and clone normally on the other machine — better if you expect to
work on both. Note the repo has local branches (`main`, `develop`) and no upstream, so push
with `-u`.

Either way, **three things do not come across** and must be recreated:

| Missing | Why | Fix |
|---|---|---|
| `.env` | gitignored — Postgres password | step 3 |
| `local.properties` | gitignored — MercadoLibre credentials | step 3 |
| `.amazon-profile/` | gitignored Chrome profile, and it holds a logged-in Amazon session | re-created on first `--verify-co-shipping`; you set the delivery country again |

`build/`, `.gradle/`, `.kotlin/` and `logs/` are all regenerated — skip them.

**The database does not travel either.** It lives in a Docker named volume (`pgdata`), not in
the project directory, so a new machine starts with an empty feed and no price history. That
matters more than it sounds: `LOWEST_IN_WINDOW` needs history to have something to beat, so a
fresh install is deliberately quiet for the first day or two. To carry history over, dump and
restore:

```bash
# old machine
docker exec deals-tracker-db pg_dump -U deals -d deals -f /tmp/deals.sql
docker cp deals-tracker-db:/tmp/deals.sql .
# new machine, after step 4
docker cp deals.sql deals-tracker-db:/tmp/deals.sql
docker exec deals-tracker-db psql -q -U deals -d deals -f /tmp/deals.sql
```

---

## 3. Create the secret files

```bash
cd deals-tracker

# Postgres password. Any value -- the DB is local only, but docker compose fails without it.
cp .env.example .env
printf 'POSTGRES_PASSWORD=%s\n' "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)" >> .env

cp local.properties.example local.properties
chmod 600 .env local.properties
```

Then edit `.env` so it has exactly one `POSTGRES_PASSWORD` line, and put the MercadoLibre
app credentials in `local.properties`:

```properties
ml.clientId=<your-app-id>
ml.clientSecret=<your-secret>
```

Both live here rather than in the committed `config/sources.yaml`, so no app credentials
are ever tracked by git.

**The MercadoLibre secret is optional right now.** That provider is `enabled: false`
because MercadoLibre policy-blocks its search API (see [BLOCKERS.md](BLOCKERS.md)), so the
app runs fine without it. If you don't have the secret to hand, leave the placeholder.

> If you're reusing the old machine's Postgres password, the values must match whatever is
> inside the Docker volume — or just start with a fresh volume, which is the simpler path
> and costs only the price history collected so far.

---

## 4. Start the database

```bash
docker compose up -d
docker compose ps          # STATUS should reach "healthy" (about 10s)
```

Schema migrations run automatically when the app boots — no manual SQL.

---

## 5. Run it

```bash
./dt run
```

Then open **<http://localhost:8080>**.

Use `./dt`, not `./gradlew`. It's a thin wrapper that pins JDK 21 via
`/usr/libexec/java_home`, so it works even when your default `java` is a different
version. (On the original machine the default was JDK 8, which cannot run Gradle 8.10.)

First run takes a few minutes — Gradle downloads its distribution and dependencies.

The feed starts empty. The app polls hourly on its own, but to populate it immediately:

```bash
./dt run --args="--fetch-once"
```

…or press **Refresh now** in the UI.

---

## 6. Verify it works

```bash
./dt test                                        # 233 tests, no DB or network needed
curl -s localhost:8080/health                    # -> ok
./dt run --args="--fetch-once --source=exito"    # exercise one source live
```

Expect roughly 2,600–3,100 deals across ten sources after a full fetch. The **Recent fetch runs** table at the bottom of the page shows
per-source status and any errors.

Then add a watch at **<http://localhost:8080/watches>** and preview it before letting it
notify you:

```bash
./dt run --args="--check-watches --dry-run"
```

Expect **no alerts on a fresh install** — see the watches section of `README.md` for why that
is correct rather than broken.

---

## Command reference

| Command | Purpose |
|---|---|
| `./dt run` | Web UI + hourly poller |
| `./dt run --args="--fetch-once"` | Fetch every enabled source once, then exit |
| `./dt run --args="--fetch-once --source=slickdeals"` | Fetch one source |
| `./dt run --args="--purge-old --dry-run"` | Preview removal of deals past `maxAgeDays` |
| `./dt run --args="--purge-old --include-undated"` | Also remove rows with no posting date |
| `./dt run --args="--purge-excluded --dry-run"` | Preview removal of grocery/consumable deals |
| `./dt run --args="--recategorize"` | Re-apply `config/categories.yaml` to stored deals |
| `./dt run --args="--verify-co-shipping --limit=10"` | Check Amazon → Colombia shipping (headed; needs you for first setup) |
| `./dt run --args="--recategorize --show-other"` | Also list deals that stayed uncategorised |
| `./dt run --args="--check-watches"` | Evaluate every watch now; notify on anything new |
| `./dt run --args="--check-watches --dry-run"` | Print what *would* fire, writing nothing — how you tune a watch |
| `./dt installChromium` | Download only the Chromium build the verifier needs |
| `./dt test` | Test suite |
| `docker compose up -d` / `down` | Start / stop Postgres (data survives `down`) |

---

## Optional: clickable watch alerts

Watch alerts work with no setup — the app falls back to AppleScript, which shows a banner.
But **AppleScript notifications carry no click action**, and the banner is posted as *Script
Editor*, so clicking one opens an empty Script Editor window. Installing
`terminal-notifier` makes the click open the watch page instead, and makes a new alert for a
watch replace that watch's previous banner rather than stacking:

```bash
brew install terminal-notifier
```

**Then grant it notification permission, which is fiddlier than it should be.** On macOS 26
the CLI cannot ask for permission itself — it fails with
`Could not request notification permission` — because the request has to come from the app
bundle. Launch the bundle once:

```bash
open /opt/homebrew/Cellar/terminal-notifier/*/terminal-notifier.app \
  --args -title 'Deals Tracker' -message 'allow notifications'
terminal-notifier -title 'Deals Tracker' -message 'test'   # should now exit 0
```

If it still refuses, allow *terminal-notifier* under System Settings → Notifications.
(`tccutil reset UserNotification fr.julienxx.oss.terminal-notifier` is suggested by the tool
itself but failed here.)

The app looks for the binary at `/opt/homebrew/bin`, `/usr/local/bin`, and then on `PATH` —
the explicit paths matter because a launchd-started process does not inherit Homebrew's `PATH`,
which is exactly how this app runs day to day.

A failed banner is not a lost alert: `notified_at` stays null, so the next cycle retries.

---

## Optional: run in the background

Starts at login and polls hourly without a terminal open:

```bash
./scripts/install-launchd.sh
```

The script substitutes this machine's project path into
`scripts/com.luis.dealstracker.plist.template`, so nothing machine-specific is committed.

```bash
launchctl list | grep dealstracker                                  # check
tail -f logs/dealstracker.out.log                                   # logs
launchctl unload ~/Library/LaunchAgents/com.luis.dealstracker.plist  # stop
```

---

## Configuration

`config/categories.yaml` defines the product categories behind the Category filter. Edit
it, then run `./dt run --args="--recategorize"` to re-apply the rules to stored deals.

`config/sources.yaml` holds everything tunable — which sources to poll, the discount
floor, deal age limit, exclusion patterns, poll interval, HTTP port. It's heavily
commented, including the measurements behind each default. Adding a site that speaks a
protocol already implemented (another VTEX retailer, another Slickdeals feed) is a config
entry with no code.

`src/main/resources/static/app.css` holds the styles. It's served as a static file, so
editing it needs only a browser refresh — but bump `CSS_VERSION` in `web/Views.kt` to
defeat browser caching.

---

## Troubleshooting

**`no JDK 21 found`** — `./dt` lists the JDKs it can see. Install one with
`brew install --cask corretto@21`.

**`Cannot connect to the Docker daemon`** — Docker Desktop is installed but not running.
Launch it and wait for the whale icon to settle.

**`migration failed` / `Connection refused` on startup** — Postgres isn't up. Run
`docker compose up -d` and wait for `healthy`.

**`POSTGRES_PASSWORD not set`** — missing or empty `.env`. See step 3.

**`port is already allocated`** — something else holds 5432. Change the host port in
`docker-compose.yml`.

**Empty feed** — nothing has been fetched yet. Run
`./dt run --args="--fetch-once"` and check the fetch-runs table for errors.

**Slickdeals returns nothing** — the RSS feed shape may have changed. Run
`./dt test`; the parser tests run against a captured fixture and will still pass, which
tells you the code is fine and the live feed changed. Compare against
`src/test/resources/fixtures/slickdeals-frontpage.xml`.

**`WARNING: categories [...] are not in this store's tree`** — a VTEX category slug in
`config/sources.yaml` doesn't exist for that store. Worth fixing: VTEX returns an
*unfiltered* product list for unknown slugs rather than a 404, so the data looks
plausible but is arbitrary. Carulla's taxonomy differs from Éxito's.

**A watch fires nothing at all** — usually correct, not broken. A watch is silent until it has
history to beat, so a fresh install says nothing for a day or two. Confirm with
`--check-watches --dry-run`, which prints the candidate count: a healthy watch shows *many
candidates, zero alerts*. **Zero candidates** is the real problem — check the terms against
what the retailers actually call the thing (`nevecón` is not `nevera`), and check that
`min_price` is not above everything.

**A watch fires constantly, on junk** — it has no minimum price. See the watches section of
`README.md`; unfiltered, `nevera` matches fridge magnets at 15.900 which beat any floor
trivially.

**`Could not request notification permission`** — `terminal-notifier` has not been granted
permission, and on macOS 26 the CLI cannot ask for itself. Launch its app bundle once; see
*Optional: clickable watch alerts* above.

**Playwright starts downloading ~1.1GB** — `Playwright.create()` fetches Chromium, Firefox and
WebKit when no browser is cached. Cancel it and run `./dt installChromium` instead, which takes
only the ~170MB build that is actually used.

**`Detected applied migration not resolved locally`** — Flyway found a migration recorded in
the database with no matching file. This means the database was migrated by a *newer* branch
than the one checked out. Either switch back to that branch, or point this checkout at its own
database with `POSTGRES_DB` in `.env`.

**Everything is a fresh `docker compose down -v`** if the database gets into a bad state —
that deletes the volume and all price history, and the schema is recreated on next boot.
