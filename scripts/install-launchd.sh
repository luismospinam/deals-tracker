#!/usr/bin/env bash
#
# Installs the background job that keeps Deals Tracker running and polling hourly.
# Substitutes this machine's project path into the plist template, so nothing
# machine-specific is committed.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="com.luis.dealstracker"
TARGET="$HOME/Library/LaunchAgents/$LABEL.plist"

mkdir -p "$PROJECT_DIR/logs" "$HOME/Library/LaunchAgents"
sed "s|__PROJECT_DIR__|$PROJECT_DIR|g" \
    "$PROJECT_DIR/scripts/$LABEL.plist.template" > "$TARGET"

# Unload first so re-running this script updates an existing job instead of erroring.
launchctl unload "$TARGET" 2>/dev/null || true
launchctl load "$TARGET"

echo "installed $TARGET"
echo "  project: $PROJECT_DIR"
echo "  logs:    $PROJECT_DIR/logs/"
echo
echo "check:     launchctl list | grep $LABEL"
echo "stop:      launchctl unload $TARGET"
