#!/usr/bin/env bash
#
# Tests for the rules in scripts/watchdog.sh. Shell, not Kotlin: the watchdog has to run when the app
# (and so the JVM suite's code) is not running.
#
#   scripts/test-watchdog.sh
set -u
cd "$(dirname "$0")"
export WATCHDOG_LIB=1 STALE_SECONDS=7200
source ./watchdog.sh

failed=0
check() {
    if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected '$3', got '$2'"; failed=1; fi
}

check "a first problem only marks it suspect" "$(decide ok 1)" "suspect none"
check "the second check in a row alerts" "$(decide suspect 1)" "alerted alert"
check "an ongoing outage alerts once" "$(decide alerted 1)" "alerted none"
check "a suspect that clears posts nothing" "$(decide suspect 0)" "ok none"
check "recovery after an alert is posted" "$(decide alerted 0)" "ok recover"
check "all quiet stays quiet" "$(decide ok 0)" "ok none"
check "a missing state file reads as ok" "$(decide '' 1)" "suspect none"

now=1000000
check "a poll an hour ago is fine" "$(problem_of ok $((now - 3600)) $now)" ""
check "exactly at the limit is fine" "$(problem_of ok $((now - 7200)) $now)" ""
check "past the limit is stale" "$(problem_of ok $((now - 9000)) $now)" "no poll has finished in 150 minutes"
check "no answer from /health" "$(problem_of '' $((now - 60)) $now)" "the app is not answering on localhost:8080"
check "no answer from the database" "$(problem_of ok '' $now)" "the database is not answering"

exit $failed
