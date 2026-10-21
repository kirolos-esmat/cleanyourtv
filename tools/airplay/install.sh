#!/usr/bin/env bash
# AirPlay Receiver installer for CleanYourTV
# Companion standalone setup based on jqssun/android-airplay-server (GPL-3.0)
# CleanYourTV itself contains NO GPL code; this script installs the separate standalone server.

set -euo pipefail

MODE="install"
if [ "${1:-}" = "--verify" ] || [ "${1:-}" = "-v" ] || [ "${1:-}" = "--status" ]; then
    MODE="verify"
    shift
fi

TV_HOST="${TV_HOST:-${1:-}}"
DEFAULT_DEVICE_NAME="Kirolos's Apple TV"
DEVICE_NAME="${DEVICE_NAME:-$DEFAULT_DEVICE_NAME}"

if [ -z "$TV_HOST" ]; then
    echo "Usage: TV_HOST=<ip> [DEVICE_NAME=\"name\"] $0" >&2
    echo "   or: $0 <ip> [\"name\"]" >&2
    echo "   or: $0 --verify <ip>" >&2
    exit 1
fi
if [ $# -ge 2 ]; then
    DEVICE_NAME="$2"
fi

# Parse target IP and port
TV_IP="${TV_HOST%:*}"
TV_PORT="${TV_HOST##*:}"
if [ "$TV_PORT" = "$TV_IP" ]; then
    TV_PORT="5555"
fi
TV_TARGET="$TV_IP:$TV_PORT"

# Tuning options (defaults match upstream jqssun/android-airplay-server v0.0.36 safe behaviors)
AIRPLAY_LOW_LATENCY="${AIRPLAY_LOW_LATENCY:-true}"
AIRPLAY_SCHEDULED_RELEASE="${AIRPLAY_SCHEDULED_RELEASE:-false}"
AIRPLAY_AUDIO_AUTO_BUFFER="${AIRPLAY_AUDIO_AUTO_BUFFER:-true}"
AIRPLAY_AUDIO_CUSHION_MS="${AIRPLAY_AUDIO_CUSHION_MS:-40}"
AIRPLAY_AUDIO_LATENCY_MS="${AIRPLAY_AUDIO_LATENCY_MS:--1}"
AIRPLAY_FORCE_SW_AAC="${AIRPLAY_FORCE_SW_AAC:-false}"

TOOLS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AIRPLAY_DIR="$TOOLS_DIR/airplay"
CACHE_APK="$AIRPLAY_DIR/airplay-server.apk"
PKG_NAME="io.github.jqssun.airplay"
RECEIVER_COMPONENT="$PKG_NAME/.SettingsReceiver"

set_setting() {
    local key="$1"
    local value="$2"
    local escaped_value
    escaped_value=$(printf '%s' "$value" | sed "s/'/'\\\\''/g")
    local output
    output=$(adb -s "$TV_TARGET" shell \
        "am broadcast -n '$RECEIVER_COMPONENT' -a '$PKG_NAME.SET_SETTING' --es key '$key' --es value '$escaped_value'" 2>&1) || true
    if echo "$output" | grep -q 'result=-1'; then
        echo "   [ok] $key=$value"
    else
        echo "   [warn] failed to set $key=$value ($output)" >&2
    fi
}

verify_receiver_settings() {
    echo "==> Verifying active receiver settings on $TV_TARGET..."
    local list_output
    list_output=$(adb -s "$TV_TARGET" shell am broadcast \
        -n "$RECEIVER_COMPONENT" \
        -a "$PKG_NAME.LIST_SETTINGS" 2>&1) || true
    local json_data
    json_data=$(echo "$list_output" | sed -n 's/.*data="\(.*\)".*/\1/p')

    if [ -z "$json_data" ]; then
        echo "   [warn] Could not read settings list from receiver." >&2
        return 0
    fi

    if command -v python3 >/dev/null 2>&1; then
        echo "$json_data" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    watched = [
        ("server_name", "Device Name"),
        ("low_latency", "Low Latency Pipeline"),
        ("scheduled_output_buffer_release", "Scheduled VSYNC Release"),
        ("audio_auto_buffer", "Adaptive Audio Cushion"),
        ("audio_cushion_ms", "Fixed Cushion (ms)"),
        ("audio_latency_ms", "Sender Audio Latency (ms)"),
        ("force_sw_aac", "Force Software AAC"),
    ]
    print("---------------------------------------------------------")
    print(f"{chr(32)*3}{chr(35)} Active Receiver Configuration:")
    for key, label in watched:
        item = data.get(key, {})
        val = item.get("value")
        default = item.get("default")
        custom_note = "" if val == default else f" (custom; default: {default})"
        print(f"   - {label} ({key}): {val}{custom_note}")
    print("---------------------------------------------------------")
except Exception as e:
    print("Raw settings:", file=sys.stderr)
    print(sys.stdin.read(), file=sys.stderr)
'
    else
        echo "Active settings JSON: $json_data"
    fi
}

echo "==> Connecting to TV at $TV_TARGET..."
adb connect "$TV_TARGET" || true

if [ "$MODE" = "verify" ]; then
    verify_receiver_settings
    exit 0
fi

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
adb -s "$TV_TARGET" install -r "$CACHE_APK"

echo "==> Configuring background permissions and system dark mode..."
# Prevent TV power management from freezing background AirPlay receiver
adb -s "$TV_TARGET" shell "dumpsys deviceidle whitelist +$PKG_NAME" || true
# Allow overlay presentation over launcher
adb -s "$TV_TARGET" shell "appops set $PKG_NAME SYSTEM_ALERT_WINDOW allow" || true
# Enforce system dark mode for TV UI and receiver
adb -s "$TV_TARGET" shell "cmd uimode night yes; settings put secure ui_night_mode 2" || true

echo "==> Initializing AirPlay receiver..."
adb -s "$TV_TARGET" shell "monkey -p $PKG_NAME -c android.intent.category.LAUNCHER 1" || true
sleep 2

echo "==> Applying receiver configuration..."
set_setting "server_name" "$DEVICE_NAME"
set_setting "low_latency" "$AIRPLAY_LOW_LATENCY"
set_setting "scheduled_output_buffer_release" "$AIRPLAY_SCHEDULED_RELEASE"
set_setting "audio_auto_buffer" "$AIRPLAY_AUDIO_AUTO_BUFFER"
set_setting "audio_cushion_ms" "$AIRPLAY_AUDIO_CUSHION_MS"
set_setting "audio_latency_ms" "$AIRPLAY_AUDIO_LATENCY_MS"
set_setting "force_sw_aac" "$AIRPLAY_FORCE_SW_AAC"

echo "==> Restarting server process to ensure all preferences take effect..."
adb -s "$TV_TARGET" shell am force-stop "$PKG_NAME" || true
sleep 1
adb -s "$TV_TARGET" shell "monkey -p $PKG_NAME -c android.intent.category.LAUNCHER 1" || true
sleep 2

# Inspect and verify active receiver settings
verify_receiver_settings

# Return to home screen so installer doesn't leave settings screen in front
adb -s "$TV_TARGET" shell input keyevent KEYCODE_HOME || true

echo "==> Done! Your TV should now appear as \"$DEVICE_NAME\" in Control Center on your Apple device."
echo "Note: DRM-protected content (Netflix, Apple TV+) cannot be mirrored due to Apple FairPlay encryption."
