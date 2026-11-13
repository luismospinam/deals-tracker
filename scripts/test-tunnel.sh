#!/usr/bin/env bash
#
# Tests for the rules in scripts/run-tunnel.sh. Shell for the same reason as test-watchdog.sh: the
# tunnel script runs outside the app.
#
#   scripts/test-tunnel.sh
set -u
cd "$(dirname "$0")"
export TUNNEL_LIB=1
source ./run-tunnel.sh

failed=0
check() {
    if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected '$3', got '$2'"; failed=1; fi
}
yes_no() { if "$@"; then echo yes; else echo no; fi; }

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

REGISTERED='2026-11-13T13:28:10Z INF Registered tunnel connection connIndex=0 location=mia08 protocol=quic'
NOT_FOUND='2026-11-13T07:40:51Z ERR Register tunnel error from server side error="Unauthorized: Tunnel not found" connIndex=0'
DIAL='2026-11-13T07:31:48Z ERR Failed to dial a quic connection error="failed to dial to edge with quic: timeout: no recent network activity"'

: > "$log"
check "an empty log has no address" "$(tunnel_url "$log")" ""
check "an empty log is not registered" "$(yes_no tunnel_registered "$log")" "no"
check "an empty log is not lost" "$(yes_no tunnel_lost "$log")" "no"
check "a missing log is not lost" "$(yes_no tunnel_lost /nonexistent/tunnel.log)" "no"

printf '%s\n' '2026-11-13T13:28:07Z INF |  https://stewart-mainland-bugs-dose.trycloudflare.com  |' "$REGISTERED" > "$log"
check "the address is read from the banner" "$(tunnel_url "$log")" "https://stewart-mainland-bugs-dose.trycloudflare.com"
check "a registered tunnel is registered" "$(yes_no tunnel_registered "$log")" "yes"
check "a registered tunnel is not lost" "$(yes_no tunnel_lost "$log")" "no"

printf '%s\n' "$DIAL" "$DIAL" >> "$log"
check "an ordinary outage is not a lost tunnel" "$(yes_no tunnel_lost "$log")" "no"

printf '%s\n' "$NOT_FOUND" "$DIAL" >> "$log"
check "Tunnel not found is a lost tunnel" "$(yes_no tunnel_lost "$log")" "yes"
check "the address is still the first one printed" "$(tunnel_url "$log")" "https://stewart-mainland-bugs-dose.trycloudflare.com"

printf '%s\n' "$REGISTERED" >> "$log"
check "registering again after it clears it" "$(yes_no tunnel_lost "$log")" "no"

check "a first start just gives the address" "$(address_message https://a.trycloudflare.com '')" \
    "🌐 Deals Tracker is reachable at https://a.trycloudflare.com"
check "a replacement says why the old one went" "$(address_message https://a.trycloudflare.com 'cloudflared exited')" \
    "🌐 Deals Tracker has a new address: https://a.trycloudflare.com — the old one stopped working (cloudflared exited)."

exit $failed
