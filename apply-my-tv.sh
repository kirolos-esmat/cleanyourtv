#!/usr/bin/env bash
# CleanYourTV One-Click Apply Script for Xiaomi Mi Box S 2nd Gen
# Configures custom debloat, launcher accessibility, privacy-friendly location, and ambient weather.

set -euo pipefail
cd "$(dirname "$0")"

TV_HOST="${TV_HOST:-192.168.1.126}"
echo "Connecting to TV at $TV_HOST..."
adb connect "$TV_HOST:5555" || true

echo "Applying debloat list (my-debloat.txt)..."
TV_HOST="$TV_HOST" ./tools/disable.sh my-debloat.txt

echo "Ensuring Google Cast & Ambient MediaShell is enabled and running..."
adb shell pm enable com.google.android.apps.mediashell || true
adb shell am start-service -a com.google.cast.action.BIND -n com.google.android.apps.mediashell/.CastReceiverService || true

echo "Setting system network location (approximate city weather for screensaver)..."
adb shell settings put secure location_mode 3

echo "Enforcing minimal app-level location privacy (no direct app sensor access)..."
adb shell pm revoke --user 0 com.google.android.apps.tv.dreamx android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb shell pm revoke --user 0 com.google.android.apps.tv.dreamx android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb shell pm revoke --user 10 com.google.android.apps.tv.dreamx android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb shell pm revoke --user 10 com.google.android.apps.tv.dreamx android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb shell pm revoke --user 0 com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb shell pm revoke --user 0 com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb shell pm revoke --user 10 com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb shell pm revoke --user 10 com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>&1 || true

echo "Enabling HomeKeyService accessibility service..."
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
adb shell settings put secure accessibility_enabled 1

echo "Setup complete! TV state successfully synchronized."
