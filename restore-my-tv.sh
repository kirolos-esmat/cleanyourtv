#!/usr/bin/env bash
# CleanYourTV One-Click Restore Script for Xiaomi Mi Box S 2nd Gen
# Restores all disabled packages and sets stock Google TV launcher back as default Home.

set -euo pipefail
cd "$(dirname "$0")"

TV_HOST="${TV_HOST:-192.168.1.126}"
echo "Restoring TV at $TV_HOST..."

TV_HOST="$TV_HOST" ./tools/restore.sh my-debloat.txt com.google.android.apps.tv.launcherx

echo "Restoration complete! Stock launcher restored."
