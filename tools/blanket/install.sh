#!/usr/bin/env bash
# Blanket Ambient Sound Player installer for CleanYourTV
# Companion standalone setup based on vinberg88/blanketforandroid & rafaelmardojai/blanket (GPL-3.0)
# CleanYourTV itself contains NO GPL code; this script installs the separate standalone sound player.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="com.vinberg88.blanket"
MAIN_ACTIVITY="com.vinberg88.blanketforandroid.MainActivity"
BLANKET_APK="$SCRIPT_DIR/blanket.apk"
UPSTREAM_URL="https://github.com/vinberg88/blanketforandroid/releases/download/v1.1.7/blanket.apk"

MODE="install"
if [ "${1:-}" = "--verify" ] || [ "${1:-}" = "-v" ] || [ "${1:-}" = "--status" ]; then
    MODE="verify"
    shift
fi

if [ -n "${1:-}" ]; then
    TV_HOST="$1"
else
    TV_HOST="${TV_HOST:-}"
fi

if [ -z "$TV_HOST" ]; then
    echo "Usage: TV_HOST=<ip> $0" >&2
    echo "   or: $0 <ip>" >&2
    echo "   or: $0 --verify <ip>" >&2
    exit 1
fi

TV_IP="${TV_HOST%:*}"
TV_PORT="${TV_HOST##*:}"
if [ "$TV_PORT" = "$TV_IP" ]; then
    TV_PORT="5555"
fi
TARGET="$TV_IP:$TV_PORT"

adb_cmd() {
    adb -s "$TARGET" "$@"
}

echo "Connecting to TV at $TARGET..."
adb connect "$TARGET" >/dev/null 2>&1 || true

if [ "$MODE" = "verify" ]; then
    echo "Checking Blanket installation on $TARGET..."
    if adb_cmd shell pm list packages | grep -q "$PACKAGE"; then
        echo "✓ Blanket package ($PACKAGE) is installed."
        PID=$(adb_cmd shell pidof "$PACKAGE" 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo "✓ Blanket background service is active (PID: $PID)."
        else
            echo "○ Blanket is not currently running."
        fi
        exit 0
    else
        echo "✗ Blanket is NOT installed on $TARGET."
        exit 1
    fi
fi

# Ensure APK is downloaded
if [ ! -f "$BLANKET_APK" ]; then
    echo "Downloading Blanket APK from upstream release..."
    curl -L -f -o "$BLANKET_APK" "$UPSTREAM_URL"
fi

echo "Installing Blanket on $TARGET..."
adb_cmd install -r "$BLANKET_APK"

echo "Configuring battery optimization whitelist for continuous ambient playback..."
adb_cmd shell dumpsys deviceidle whitelist +"$PACKAGE" >/dev/null 2>&1 || true

echo "Granting notification permissions..."
adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

echo "Starting Blanket..."
adb_cmd shell am start -n "$PACKAGE/$MAIN_ACTIVITY" >/dev/null 2>&1 || true

echo "✓ Blanket installed and launched successfully on $TARGET!"
