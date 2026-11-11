#!/usr/bin/env bash
#
# What launchd runs (scripts/com.luis.dealstracker.plist.template): makes sure Postgres is up, then
# runs the app under `caffeinate -i` so an idle Mac does not sleep through the hourly poll.
#
# Docker Desktop is not set to start at login on this Mac, so after a reboot the database is not
# there. Without this the app would exit at boot and launchd would retry it every minute, forever,
# without anything ever starting Docker.
set -uo pipefail
cd "$(dirname "$0")/.."

# launchd's PATH is /usr/bin:/bin:/usr/sbin:/sbin; Docker's CLI lives outside it.
export PATH="/usr/local/bin:/opt/homebrew/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"

log() { echo "[launchd] $(date '+%Y-%m-%d %H:%M:%S') $*"; }

if ! docker info >/dev/null 2>&1; then
    log "Docker is not running; starting Docker Desktop"
    open -ga Docker
    for _ in $(seq 1 90); do docker info >/dev/null 2>&1 && break; sleep 2; done
fi
if ! docker info >/dev/null 2>&1; then
    # Non-zero, so launchd retries after its ThrottleInterval.
    log "Docker did not come up in 3 minutes; giving up this attempt"
    exit 1
fi

docker compose up -d postgres >/dev/null 2>&1
for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' deals-tracker-db 2>/dev/null)" = healthy ] && break
    sleep 2
done
log "database $(docker inspect -f '{{.State.Health.Status}}' deals-tracker-db 2>/dev/null); starting the app"

exec /usr/bin/caffeinate -i ./dt --quiet run
