#!/usr/bin/env bash
# CleanYourTV One-Click Restore Script for Xiaomi Mi Box S 2nd Gen
# Restores all disabled packages, re-enables stock Google TV launcher and setup wizard,
# and resets default Home.

set -euo pipefail
cd "$(dirname "$0")"

TV_HOST="${TV_HOST:-192.168.1.126}"
echo "Restoring TV at $TV_HOST..."

# Explicitly ensure setupwraith and stock launcher are enabled
adb -s "$TV_HOST:5555" shell pm enable com.google.android.tungsten.setupwraith 2>/dev/null || true
adb -s "$TV_HOST:5555" shell pm enable com.google.android.apps.tv.launcherx 2>/dev/null || true

# Revert Private DNS to opportunistic (default)
adb -s "$TV_HOST:5555" shell settings put global private_dns_mode opportunistic 2>/dev/null || true

# Run standard recovery tool
TV_HOST="$TV_HOST" ./tools/restore.sh my-debloat.txt com.google.android.apps.tv.launcherx

# Ensure Home is pointed back to stock launcher
adb -s "$TV_HOST:5555" shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity 2>/dev/null || true

echo "Restoration complete! Stock Google TV launcher restored."
