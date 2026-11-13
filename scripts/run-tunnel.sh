#!/usr/bin/env bash
#
# Keeps the Cloudflare quick tunnel to the app alive, and posts its address to Discord whenever the
# address changes.
#
# A quick tunnel (`cloudflared tunnel --url ...`, no account) gets a random trycloudflare.com address,
# and Cloudflare can forget it after a network outage. cloudflared then retries for ever with
# "Unauthorized: Tunnel not found" and never recovers: on 2026-11-13 the phone could not reach the app
# for six hours while the process looked healthy. The only fix is a new tunnel, which means a new
# address, so this script starts one and says where it is.
#
#   nohup caffeinate -i scripts/run-tunnel.sh > logs/tunnel-watch.log 2>&1 &
#
# A tunnel that is already running and still registered is adopted rather than replaced, so starting
# this script does not change the address. cloudflared's own output goes to logs/tunnel.log.
set -uo pipefail

PORT="${DEALS_HTTP_PORT:-8080}"
LOG="logs/tunnel.log"
CHECK_SECONDS="${CHECK_SECONDS:-60}"

# tunnel_url <log>  ->  the address cloudflared printed, or nothing
tunnel_url() {
    grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$1" 2>/dev/null | head -1
}

# tunnel_registered <log>  ->  true once Cloudflare has accepted a connection
tunnel_registered() {
    grep -q 'Registered tunnel connection' "$1" 2>/dev/null
}

# tunnel_lost <log>  ->  true when Cloudflare no longer knows the tunnel. An ordinary outage ("Failed
# to dial") is not that: cloudflared reconnects from those by itself, and the address survives.
tunnel_lost() {
    local last
    last="$(grep -E 'Registered tunnel connection|Tunnel not found' "$1" 2>/dev/null | tail -1)"
    case "$last" in *"Tunnel not found"*) return 0 ;; esac
    return 1
}

# address_message <url> <why the old one went, or empty>
address_message() {
    if [ -n "$2" ]; then
        echo "🌐 Deals Tracker has a new address: $1 — the old one stopped working ($2)."
    else
        echo "🌐 Deals Tracker is reachable at $1"
    fi
}

# Sourced by scripts/test-tunnel.sh for the functions above.
[ "${TUNNEL_LIB:-}" = 1 ] && return 0
cd "$(dirname "$0")/.."

say() { echo "[tunnel] $(date '+%Y-%m-%d %H:%M:%S') $*"; }

# The same channel as the watchdog, with the same fallback. Read from .env and never printed.
post() {
    local hook body
    get() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2-; }
    hook="$(get DISCORD_HEALTH_WEBHOOK_URL)"
    [ -n "$hook" ] || hook="$(get DISCORD_WATCHES_WEBHOOK_URL)"
    [ -n "$hook" ] || { say "no Discord webhook in .env; not posted: $1"; return 0; }
    body="$(printf '%s' "$1" | python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read()}))')"
    for _ in 1 2 3; do
        curl -s -m 15 -f -H 'Content-Type: application/json' -d "$body" "$hook" >/dev/null && { say "posted: $1"; return 0; }
        sleep 20
    done
    say "could not post: $1"
}

pid=""
trap '[ -n "$pid" ] && kill "$pid" 2>/dev/null; exit 0' INT TERM

# Starts cloudflared and waits for Cloudflare to accept it. False when it did not come up (no network).
start() {
    [ -f "$LOG" ] && mv "$LOG" "logs/tunnel.prev.log"
    cloudflared tunnel --no-autoupdate --url "http://localhost:$PORT" > "$LOG" 2>&1 &
    pid=$!
    for _ in $(seq 1 90); do
        kill -0 "$pid" 2>/dev/null || return 1
        tunnel_registered "$LOG" && [ -n "$(tunnel_url "$LOG")" ] && return 0
        sleep 1
    done
    return 1
}

stop() {
    [ -n "$pid" ] || return 0
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null
    pid=""
}

why=""
pid="$(pgrep -x cloudflared | head -1)"
if [ -n "$pid" ] && tunnel_registered "$LOG" && ! tunnel_lost "$LOG"; then
    say "adopted the running tunnel (pid $pid) at $(tunnel_url "$LOG")"
else
    [ -n "$pid" ] && why="Cloudflare no longer knew the tunnel"
    stop
fi

while true; do
    if [ -z "$pid" ]; then
        if start; then
            url="$(tunnel_url "$LOG")"
            say "tunnel up (pid $pid) at $url"
            post "$(address_message "$url" "$why")"
            why=""
        else
            say "the tunnel did not come up; trying again in ${CHECK_SECONDS}s"
            stop
        fi
    elif ! kill -0 "$pid" 2>/dev/null; then
        say "cloudflared exited"
        why="cloudflared exited"
        pid=""
        continue
    elif tunnel_lost "$LOG"; then
        say "Cloudflare no longer knows the tunnel; replacing it"
        why="Cloudflare no longer knew the tunnel"
        stop
        continue
    fi
    sleep "$CHECK_SECONDS"
done
