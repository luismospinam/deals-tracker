#!/usr/bin/env bash
#
# Run every 15 minutes by launchd (scripts/com.luis.dealstracker.watchdog.plist.template). Posts to
# Discord when the app stops collecting prices, and once more when it recovers.
#
# It has to live outside the app: every alert the app sends comes from the app, so a dead app was
# silent, and a gap in price history is permanent -- nothing backfills it.
#
# A problem is any of: /health not answering, the database not answering, or no poll finished in
# STALE_SECONDS. It must be seen on two checks in a row before it alerts, so a Mac waking from sleep
# -- when the last poll is hours old but the next one is already under way -- does not post a false
# alarm. The state between runs is one word in logs/.watchdog-state.
#
#   scripts/watchdog.sh            check, and post if the state calls for it
#   scripts/watchdog.sh --dry-run  check and print what it would post; changes nothing
set -uo pipefail

STALE_SECONDS="${STALE_SECONDS:-7200}"
STATE_FILE="logs/.watchdog-state"

# decide <state> <problem: 0|1>  ->  "<new state> <action: none|alert|recover>"
# ok -> (problem) suspect -> (problem again) alerted -> (fine) ok, posting on the last two steps.
decide() {
    local state="$1" problem="$2"
    if [ "$problem" = 1 ]; then
        case "$state" in
            suspect) echo "alerted alert" ;;
            alerted) echo "alerted none" ;;
            *)       echo "suspect none" ;;
        esac
    else
        case "$state" in
            alerted) echo "ok recover" ;;
            *)       echo "ok none" ;;
        esac
    fi
}

# problem_of <health: ok|other> <last poll epoch or empty> <now epoch>  ->  a reason, or nothing
problem_of() {
    local health="$1" last="$2" now="$3"
    if [ "$health" != ok ]; then echo "the app is not answering on localhost:8080"; return; fi
    if [ -z "$last" ]; then echo "the database is not answering"; return; fi
    local age=$(( now - last ))
    if [ "$age" -gt "$STALE_SECONDS" ]; then echo "no poll has finished in $(( age / 60 )) minutes"; fi
}

# Sourced by scripts/test-watchdog.sh for the two functions above.
[ "${WATCHDOG_LIB:-}" = 1 ] && return 0
cd "$(dirname "$0")/.."

dry_run=0
[ "${1:-}" = "--dry-run" ] && dry_run=1

health="$(curl -s -m 10 http://127.0.0.1:8080/health 2>/dev/null)"
last="$(./psql -tAc "select extract(epoch from max(finished_at))::bigint from fetch_runs" 2>/dev/null | tr -d '[:space:]')"
now="$(date +%s)"
reason="$(problem_of "$health" "$last" "$now")"

state="$(cat "$STATE_FILE" 2>/dev/null || echo ok)"
read -r next action <<<"$(decide "$state" "$([ -n "$reason" ] && echo 1 || echo 0)")"

case "$action" in
    alert)   message="⚠️ Deals Tracker has stopped collecting prices: $reason. Price history has a gap until it is back." ;;
    recover) message="✅ Deals Tracker is collecting prices again." ;;
    *)       message="" ;;
esac

if [ "$dry_run" = 1 ]; then
    echo "state $state -> $next; problem: ${reason:-none}; would post: ${message:-nothing}"
    exit 0
fi

if [ -n "$message" ]; then
    # The same channel the app's own health alerts use, with the same fallback. Read from .env and
    # never printed.
    get() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2-; }
    hook="$(get DISCORD_HEALTH_WEBHOOK_URL)"
    [ -n "$hook" ] || hook="$(get DISCORD_WATCHES_WEBHOOK_URL)"
    if [ -n "$hook" ]; then
        body="$(printf '%s' "$message" | python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read()}))')"
        # Only a delivered post moves the state on; a failed one is retried in 15 minutes.
        curl -s -m 15 -f -H 'Content-Type: application/json' -d "$body" "$hook" >/dev/null || exit 1
    fi
    echo "[watchdog] $(date '+%Y-%m-%d %H:%M:%S') posted: $message"
fi
echo "$next" > "$STATE_FILE"
