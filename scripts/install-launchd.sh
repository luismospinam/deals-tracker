#!/usr/bin/env bash
#
# Installs the two background jobs: the app itself (started at login, restarted if it dies) and the
# watchdog that posts to Discord when it stops collecting prices. Substitutes this machine's project
# path into the plist templates, so nothing machine-specific is committed.
#
# Stop a hand-started copy first (`pkill -f com.luis.dealstracker.AppKt`): two copies cannot both
# have port 8080, and the launchd one would keep failing and retrying.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$PROJECT_DIR/logs" "$HOME/Library/LaunchAgents"

for LABEL in com.luis.dealstracker com.luis.dealstracker.watchdog; do
    TARGET="$HOME/Library/LaunchAgents/$LABEL.plist"
    sed "s|__PROJECT_DIR__|$PROJECT_DIR|g" "$PROJECT_DIR/scripts/$LABEL.plist.template" > "$TARGET"
    # Unload first so re-running this script updates an existing job instead of erroring.
    launchctl unload "$TARGET" 2>/dev/null || true
    launchctl load "$TARGET"
    echo "installed $TARGET"
done

echo
echo "  project:  $PROJECT_DIR"
echo "  logs:     logs/app.log, logs/watchdog.log"
echo
# macOS does not let a background job read ~/Documents, ~/Desktop or ~/Downloads unless the program
# it runs has Full Disk Access. The job then exits 78 before writing a line to the log.
case "$PROJECT_DIR" in
    "$HOME/Documents"/*|"$HOME/Desktop"/*|"$HOME/Downloads"/*)
        echo "NOTE: this project is under a folder macOS protects. If 'launchctl list | grep dealstracker'"
        echo "      shows 78 in the middle column, give /bin/bash Full Disk Access (System Settings >"
        echo "      Privacy & Security > Full Disk Access > +, then Cmd+Shift+G and type /bin/bash) and"
        echo "      run this script again."
        echo
        ;;
esac
echo "check:      launchctl list | grep dealstracker"
echo "restart:    launchctl kickstart -k gui/\$(id -u)/com.luis.dealstracker"
echo "stop:       launchctl unload ~/Library/LaunchAgents/com.luis.dealstracker.plist"
