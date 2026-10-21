#!/usr/bin/env bash
# AirPlay Receiver installer for CleanYourTV
# Companion standalone setup based on jqssun/android-airplay-server (GPL-3.0)
# CleanYourTV itself contains NO GPL code; this script installs the separate standalone server.

set -euo pipefail

TV_HOST="${TV_HOST:-${1:-}}"
DEFAULT_DEVICE_NAME="Kirolos's Apple TV"
DEVICE_NAME="${DEVICE_NAME:-$DEFAULT_DEVICE_NAME}"
if [ -z "$TV_HOST" ]; then
    echo "Usage: TV_HOST=<ip> [DEVICE_NAME=\"name\"] $0" >&2
    echo "   or: $0 <ip> [\"name\"]" >&2
    exit 1
fi
if [ $# -ge 2 ]; then
    DEVICE_NAME="$2"
fi

TOOLS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AIRPLAY_DIR="$TOOLS_DIR/airplay"
CACHE_APK="$AIRPLAY_DIR/airplay-server.apk"
PKG_NAME="io.github.jqssun.airplay"

echo "==> Target TV: $TV_HOST"
adb connect "$TV_HOST:5555" || true

if [ ! -f "$CACHE_APK" ]; then
    echo "==> Fetching latest release of jqssun/android-airplay-server..."
    LATEST_URL=$(curl -s "https://api.github.com/repos/jqssun/android-airplay-server/releases/latest" \
        | grep -o 'https://github.com/jqssun/android-airplay-server/releases/download/[^"]*/app-release\.apk' \
        | head -n 1)
    if [ -z "$LATEST_URL" ]; then
        LATEST_URL="https://github.com/jqssun/android-airplay-server/releases/download/v0.0.36/app-release.apk"
    fi
    echo "Downloading from: $LATEST_URL"
    curl -L -o "$CACHE_APK" "$LATEST_URL"
fi

echo "==> Installing AirPlay receiver ($CACHE_APK)..."
adb -s "$TV_HOST:5555" install -r "$CACHE_APK"

echo "==> Configuring background permissions and autostart..."
# Whitelist battery optimizations so background receiver does not get killed
adb -s "$TV_HOST:5555" shell "dumpsys deviceidle whitelist +$PKG_NAME" || true

# Start service/app
echo "==> Launching AirPlay receiver..."
adb -s "$TV_HOST:5555" shell "monkey -p $PKG_NAME -c android.intent.category.LAUNCHER 1" || true

echo "==> Setting AirPlay device name to '$DEVICE_NAME'..."
adb -s "$TV_HOST:5555" shell am broadcast \
    -n "$PKG_NAME/.SettingsReceiver" \
    -a "$PKG_NAME.SET_SETTING" \
    --es key server_name \
    --es value "$DEVICE_NAME" || true

echo "==> Done! Your TV should now appear in the AirPlay / Screen Mirroring menu on your iPhone/iPad/Mac."
echo "Note: DRM-protected content (Netflix, Disney+) cannot be mirrored due to Apple FairPlay encryption."
