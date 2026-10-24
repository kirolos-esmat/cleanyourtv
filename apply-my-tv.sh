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

echo "Ensuring Google Cast & Ambient MediaShell and Screensaver (dreamx) are enabled..."
adb -s "$TV_HOST:5555" shell pm enable com.google.android.apps.mediashell || true
adb -s "$TV_HOST:5555" shell pm enable com.google.android.apps.tv.dreamx || true
adb -s "$TV_HOST:5555" shell am start-service -a com.google.cast.action.BIND -n com.google.android.apps.mediashell/.CastReceiverService || true

echo "Setting system network location (approximate city weather for screensaver & clock)..."
adb -s "$TV_HOST:5555" shell settings put secure location_mode 3

echo "Enforcing screensaver privacy while granting launcher location for weather..."
adb -s "$TV_HOST:5555" shell pm revoke --user 0 com.google.android.apps.tv.dreamx android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm revoke --user 0 com.google.android.apps.tv.dreamx android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm revoke --user 10 com.google.android.apps.tv.dreamx android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm revoke --user 10 com.google.android.apps.tv.dreamx android.permission.ACCESS_COARSE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>&1 || true
adb -s "$TV_HOST:5555" shell pm grant com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>&1 || true

echo "Enabling HomeKeyService accessibility service..."
adb -s "$TV_HOST:5555" shell settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
adb -s "$TV_HOST:5555" shell settings put secure accessibility_enabled 1

echo "Configuring AdGuard ad-blocking Private DNS..."
adb -s "$TV_HOST:5555" shell settings put global private_dns_specifier dns.adguard-dns.com
adb -s "$TV_HOST:5555" shell settings put global private_dns_mode hostname

echo "Setup complete! TV state successfully synchronized."
