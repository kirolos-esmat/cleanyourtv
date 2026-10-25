#!/usr/bin/env bash
# CleanYourTV One-Click Apply Script for Xiaomi Mi Box S 2nd Gen
# Configures custom debloat, launcher accessibility, privacy-friendly location, ambient weather, and AdGuard DNS.

set -euo pipefail
cd "$(dirname "$0")"

TV_HOST="${TV_HOST:-192.168.1.126}"
echo "Connecting to TV at $TV_HOST..."
adb connect "$TV_HOST:5555" || true

echo "Applying debloat list (my-debloat.txt)..."
TV_HOST="$TV_HOST" ./tools/disable.sh my-debloat.txt

echo "Ensuring com.gathin.tvhome is default Home..."
adb -s "$TV_HOST:5555" shell cmd package set-home-activity com.gathin.tvhome/.MainActivity || true

echo "Ensuring Google Cast & Ambient MediaShell, Screensaver (dreamx), and SetupWraith are enabled..."
adb -s "$TV_HOST:5555" shell pm enable com.google.android.apps.mediashell || true
adb -s "$TV_HOST:5555" shell pm enable com.google.android.apps.tv.dreamx || true
adb -s "$TV_HOST:5555" shell pm enable com.google.android.tungsten.setupwraith || true
adb -s "$TV_HOST:5555" shell am start-service -a com.google.cast.action.BIND -n com.google.android.apps.mediashell/.CastReceiverService || true

ENABLE_WEATHER_LOCATION="${ENABLE_WEATHER_LOCATION:-1}"
if [ "$ENABLE_WEATHER_LOCATION" = "1" ]; then
    echo "Setting system network location (approximate city weather for screensaver & clock)..."
    adb -s "$TV_HOST:5555" shell settings put secure location_mode 3 || true
fi

echo "Granting location permissions for screensaver ambient weather and launcher clock/weather..."
adb -s "$TV_HOST:5555" shell pm grant com.google.android.apps.tv.dreamx android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant com.google.android.apps.tv.dreamx android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant --user 10 com.google.android.apps.tv.dreamx android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant --user 10 com.google.android.apps.tv.dreamx android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>&1 || true

echo "Granting task manager & usage stats permissions..."
adb -s "$TV_HOST:5555" shell appops set com.gathin.tvhome GET_USAGE_STATS allow 2>/dev/null || true
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.FORCE_STOP_PACKAGES 2>/dev/null || true

if [ -f "launcher/app/build/outputs/apk/release/app-release.apk" ]; then
    echo "Installing latest CleanYourTV release APK..."
    adb -s "$TV_HOST:5555" install -r launcher/app/build/outputs/apk/release/app-release.apk
fi

echo "Enabling HomeKeyService accessibility service and self-repair permissions..."
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
adb -s "$TV_HOST:5555" shell cmd appops set com.gathin.tvhome AUTO_START allow 2>/dev/null || true
CURRENT_SERVICES=$(adb -s "$TV_HOST:5555" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n' || true)
OTHER_SERVICES=$(printf '%s' "$CURRENT_SERVICES" | tr ':' '\n' | grep -v -E '^($|null$|com\.gathin\.tvhome/(\.HomeKeyService|com\.gathin\.tvhome\.HomeKeyService)$)' | paste -sd ':' - || true)
NEW_SERVICES="${OTHER_SERVICES:+$OTHER_SERVICES:}com.gathin.tvhome/.HomeKeyService"
adb -s "$TV_HOST:5555" shell "settings put secure enabled_accessibility_services '' && settings put secure accessibility_enabled 0"
sleep 1
adb -s "$TV_HOST:5555" shell "settings put secure enabled_accessibility_services '$NEW_SERVICES' && settings put secure accessibility_enabled 1"

echo "Ensuring Private DNS is disabled (for IPTV compatibility)..."
adb -s "$TV_HOST:5555" shell settings put global private_dns_mode off
adb -s "$TV_HOST:5555" shell settings delete global private_dns_specifier 2>/dev/null || true

echo "Bringing CleanYourTV Home to front..."
adb -s "$TV_HOST:5555" shell am start -n com.gathin.tvhome/.MainActivity >/dev/null 2>&1 || true

echo "Setup complete! TV state successfully synchronized."
