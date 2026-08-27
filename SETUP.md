# Setup — running Deals Tracker on a new Mac

Everything needed to get this running from scratch. For what the app *does* and why it
works the way it does, see [README.md](README.md).

---

## 1. Prerequisites

| Requirement | Version | Why |
|---|---|---|
| **macOS** | any recent | `./dt` uses `/usr/libexec/java_home` |
| **JDK** | **21** | `build.gradle.kts` sets `jvmToolchain(21)` |
| **Docker Desktop** | any current | runs Postgres |
| **Gradle** | *not needed* | the wrapper (`./gradlew`) downloads 8.10.2 itself |
| **Node / npm** | *not needed* | no JavaScript build step; HTMX loads from a CDN |

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
`docker-compose.yml` and set `DATABASE_URL` accordingly. For 8080, change `httpPort` in
`config/sources.yaml`.

---

## 2. Copy the project

Copy the whole project directory. **Two files are excluded by `.gitignore` and will not
come across** — they hold secrets and must be recreated in step 3:

- `.env` — Postgres password
- `local.properties` — MercadoLibre app id and client secret

You can safely skip `build/` and `.gradle/` (regenerated).

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
./dt test                                        # 81 tests, no DB or network needed
curl -s localhost:8080/health                    # -> ok
./dt run --args="--fetch-once --source=exito"    # exercise one source live
```

Expect roughly 500–800 deals across Slickdeals, Éxito and Carulla after a full fetch. The
**Recent fetch runs** table at the bottom of the page shows per-source status and any
errors.

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
| `./dt test` | Test suite |
| `docker compose up -d` / `down` | Start / stop Postgres (data survives `down`) |

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

**Everything is a fresh `docker compose down -v`** if the database gets into a bad state —
that deletes the volume and all price history, and the schema is recreated on next boot.
