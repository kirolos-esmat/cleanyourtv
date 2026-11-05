# Project Handover: CleanYourTV (Custom Living Room Fork)

**Device**: Xiaomi Mi Box S (2nd Gen) (`aquaman` / `xun` / Amlogic S905X4)  
**Target Platform**: Android TV 11 / Google TV (`density: 2.0`, 1080p @ 60Hz)  
**Target User**: Eng. Kirolos Esmat  
**Repo**: `/Users/kirolosesmat/Desktop/Projects/cleanyourtv`  
**Git Remote**: `https://github.com/kirolos-esmat/cleanyourtv.git` (Branch: `main`)  
**Upstream Tracking**: [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (`v3.4.0` merged)  
**Last Verified State**: Commit `fc271cb8`, 100% tests passing (Python unit tests + Launcher & Game Day Java unit tests).

---

## 1. Executive Summary & Architecture Overview

This repository is a customized fork of **CleanYourTV**, a privacy-focused, zero-telemetry, lightweight Android TV launcher (`com.gathin.tvhome`) built in pure Java and AndroidX. It replaces bloated, ad-ridden stock interfaces (like Google TV and PatchWall) on budget streaming boxes while retaining essential hardware features (HDMI, tuner, input selector, ambient screensaver, and quick settings).

The fork has been tailored for Eng. Kirolos Esmat's Xiaomi Mi Box S (2nd Gen) setup. It incorporates:
1. **Upstream CleanYourTV v3.4.0** (`versionCode 13`, `versionName "3.4"`) features and architecture:
   - Added `inputs_cec_state_only` configuration switch for TVs that misreport port status.
   - `ClockRefresh` keeps clocks moving when firmware drops `ACTION_TIME_TICK`.
   - `MemInfo` parser providing reliable memory calculations even on older kernels lacking `MemAvailable`.
   - Hold-OK tile menu (`tile_menu = true`), configurable clock formats (`clock_format`), `back_opens_clock`, `glow_snap`, and cold-start focus target refit.
   - Upstream themes added (`aerial`, `christmas`, `dock-glass`, `stockholm`, `weeb`) and expanded device compatibility.
2. **Seasonal Halloween Theme & Styling**:
   - `seasonal_skin="halloween"`, active until `2026-11-02`.
   - Pumpkin orange focus glow (`#FF7A1A`).
   - Bat icon on Inputs pill, Ghost icon on Settings pill.
   - High-resolution spooky scenery wallpapers in the background rotation.
3. **Personalized Branding & UI**:
   - Greeting: `"HAPPY HALLOWEEN, ENG. KIROLOS ESMAT"` (`greeting_name` in `config.xml`).
   - Top Bar: Wi-Fi pill hidden (`show_network_pill = false`), Inputs (Bat), Live RAM & Storage Control Center pill (`btnControlCenter`), and Settings (Ghost).
   - Weather: Live Celsius weather above the clock (`21° · Alexandria`) with approximate network location.
4. **Game Day Shelf Panel (FC Barcelona)**:
   - Built and installed `addons/game-day` (`io.github.justinnewman.gameday`).
   - Sync script configured with FC Barcelona iCal feed (`https://ics.fixtur.es/v2/fc-barcelona.ics`).
   - Enhanced `Titles.java` to parse European soccer matches formatted as `Home - Away`, formatting them as `Home vs Away` and returning `sport.word()` (`"Kickoff"`).
   - Sits cleanly at `layout_marginTop="122dp"` in the clear space between the clock and hero dock title with crisp text drop shadows and vivid Blaugrana accent bar (`#FF1565C0`).
   - Placed in front of `heroScrim` and `topScrim` so the shelf text remains 100% bright, crisp, and unaffected by cinematic darkening.
5. **Hold-Home Quick Settings Panel Integration**:
   - Stock Google TV launcher (`launcherx`) is kept enabled specifically for its `DashboardHandler`. `HomeKeyService` routes single Home clicks to CleanYourTV while passing through long-presses to open Google TV's quick settings overlay.
6. **Hardware Remote Key Mappings**:
   - Dedicated remote buttons remapped directly to preferred apps (SmartTube, Plex, IPTV Smarters Pro, Spotify).

---

## 2. Target Hardware & Network Environment

| Parameter | Value | Notes |
|---|---|---|
| **Device Model** | Xiaomi Mi Box S (2nd Gen) | Codename: `aquaman` / `xun` (Amlogic S905X4 Quad-Core Cortex-A55, 2GB RAM, 8GB eMMC) |
| **Android Version** | Android TV 11 (API 30 / 31) | Build with `minSdk 26`, `targetSdk 34` |
| **Display Mode** | 1920x1080 @ 60Hz | Density `2.0` (320 dpi). 1dp = 2.0px. |
| **TV ADB Address** | `192.168.1.126:5555` | Wireless ADB connection |
| **AdGuard Home Instance** | `http://192.168.1.112:30004/` | Username: `admin`, Password: `Kokokoky1` |
| **Private DNS on TV** | `off` | Kept off via `settings put global private_dns_mode off` to ensure IPTV stream compatibility |

---

## 3. Mandatory Invariants (CRITICAL — NEVER BREAK)

These rules are enforced by unit tests in `tests/test_menu_screensaver_guards.py` and device scripts. Violating them will break the TV setup:

### Invariant 1: DO NOT DISABLE `com.google.android.apps.tv.launcherx`
- **Why**: `launcherx` provides Google TV's hold-Home Quick Settings and Dashboard panel (`DashboardHandler`).
- **Mechanism**: CleanYourTV's `HomeKeyService` intercepts single Home key events and redirects them to `com.gathin.tvhome`. However, when Home is held down, the service allows `launcherx` to display the stock Dashboard.
- **Rule**: `com.google.android.apps.tv.launcherx` MUST remain commented out in `my-debloat.txt` and must NEVER be disabled by any script.

### Invariant 2: DO NOT DISABLE Ambient Screensaver & Cast Services
- **Packages**: `com.google.android.apps.tv.dreamx`, `com.google.android.apps.mediashell`, `com.google.android.tungsten.setupwraith`.
- **Why**:
  - `dreamx` provides Google TV Ambient screensaver (photos, art, clock, weather).
  - `mediashell` provides Google Cast receiver services.
  - `setupwraith` provides `CastInfoProvider`, which supplies device auth tokens and weather slideshow feeds.
- **Rule**: These three packages must never be disabled in `my-debloat.txt` and must remain enabled.

### Invariant 3: Weather & Screensaver Permissions
- `dreamx` and `tvhome` require approximate network location permissions (`ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`) alongside system `location_mode 3` to resolve city forecast data for both daytime and nighttime weather.
- `apply-my-tv.sh` automatically grants these permissions.

### Invariant 4: NEVER Run `am force-stop com.gathin.tvhome`
- **Why**: Force-stopping `com.gathin.tvhome` immediately unbinds and kills `com.gathin.tvhome/.HomeKeyService` in Android Accessibility (moving it to "Crashed services"). Because `launcherx` is still active on the device, killing `HomeKeyService` immediately causes the TV remote's Home button to revert to the stock Google TV launcher!
- **Rule**: Always start/refresh the launcher using:
  ```bash
  adb -s 192.168.1.126:5555 shell am start -n com.gathin.tvhome/.MainActivity
  ```
- If `HomeKeyService` ever unbinds or crashes, re-bind it by running `./apply-my-tv.sh`.

### Invariant 5: Disable, Never Uninstall
- Only use `pm disable-user --user 0 <pkg>`.
- Never use `pm uninstall`, root exploits, or custom recovery flashing. Everything must be reversible via `tools/restore.sh`.

---

## 4. Key Codebase Components & File Map

```
/Users/kirolosesmat/Desktop/Projects/cleanyourtv/
├── apply-my-tv.sh                  # One-click master script to deploy APK & sync TV state
├── my-debloat.txt                  # Custom TV package disable list (safeguarded)
├── GEMINI.md / CLAUDE.md / AGENTS.md # Instructions and invariants for coding agents
├── launcher/                       # CleanYourTV Reference Launcher (Android Studio project)
│   ├── app/src/main/
│   │   ├── java/com/gathin/tvhome/
│   │   │   ├── MainActivity.java    # Home activity, top bar, focus animation & glass target
│   │   │   ├── AppAdapter.java      # Tile RecyclerView adapter, focus scaling & elevation
│   │   │   ├── GameDay.java         # Game Day provider reader & turn rotator
│   │   │   ├── RaceWeekend.java     # Countdown string formatter
│   │   │   └── HomeKeyService.java  # Accessibility service capturing Home clicks & hold-home passthrough
│   │   └── res/
│   │       ├── layout/activity_main.xml       # Main layout (L-to-R)
│   │       ├── layout-iw/activity_main.xml    # Mirrored layout
│   │       └── values/config.xml              # Launcher config (dock, pills, greeting, theme)
├── addons/
│   └── game-day/                   # Game Day shelf companion app
│       ├── calendars.txt           # Configured team calendars
│       ├── setup.sh                # Push calendars to TV via FilesProvider
│       └── app/src/main/java/io/github/justinnewman/gameday/
│           ├── Titles.java         # European & American matchup & countdown word parser
│           ├── GamesProvider.java  # Exposes turns cursor to CleanYourTV launcher
│           └── Palette.java        # Accent color palette
└── tests/                          # 2,269 unit tests ensuring zero regressions
```

---

## 5. Deployment Commands & Verification

### Synchronize & Deploy Everything
```bash
./apply-my-tv.sh
```

### Refresh Game Day Calendars
```bash
TV_HOST=192.168.1.126 addons/game-day/setup.sh
```

### Capture Verification Screenshot
```bash
adb -s 192.168.1.126:5555 exec-out screencap -p > verification.png
```

### Wi-Fi Auto-Join Network Management
On Xiaomi Mi Box S (Android 11), Android's `WifiConnectivityManager` evaluates all saved networks with `allowAutojoin=true`. To enforce connecting **only** to the primary active Wi-Fi network without auto-switching to or roaming among secondary saved networks, `allowAutojoin` is managed via Binder transaction 20 on `android.net.wifi.IWifiManager`:

- Disable auto-join for a saved network ID (without deleting the saved password):
  ```bash
  adb -s 192.168.1.126:5555 shell service call wifi 20 i32 <networkId> i32 0
  ```
- Enable auto-join for a network ID:
  ```bash
  adb -s 192.168.1.126:5555 shell service call wifi 20 i32 <networkId> i32 1
  ```
- Verify status across all networks:
  ```bash
  adb -s 192.168.1.126:5555 shell dumpsys wifi | grep -E "ID: [0-9]|allowAutojoin="
  ```
- Changes are automatically committed permanently to `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` via `WifiConfigManager.saveToStore(true)` and persist across reboots.

### Run All Automated Tests
```bash
# Python test suite
tools/venv/bin/python -m unittest discover -s tests -v

# Launcher build & tests
cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease

# Game Day tests
cd addons/game-day && ./gradlew testReleaseUnitTest
```

---

## 9. NASA ISS Space Wallpapers Pack & Date Auto-Sizing

- **NASA ISS Space Wallpapers Pack**:
  - All 10 high-resolution NASA astronaut orbital photographs are bundled into a single unified pack (`NASA ISS Space (10 ISS Orbital Wallpapers)`) within `LauncherSettingsActivity`.
  - Selecting the pack automatically rotates across all 10 space wallpapers every 15 minutes (`wallpaper_rotation_minutes=15`, order `shuffle`) and renders live mission captions under the top bar (e.g. `The Nile from Luxor to Cairo at night · NASA, Expedition 73`).
  - Eliminates individual wallpaper clutter from the wallpaper picker grid.
- **Date Display Auto-Sizing**:
  - Implemented Android framework uniform text auto-sizing (`android:autoSizeTextType="uniform"`, min `10sp`, max `13sp`, step `0.5sp`, `letterSpacing="0.02"`).
  - Eliminates date ellipsis truncation (`...`) across all months and days of the year (e.g. `THURSDAY 29 OCTOBER`, `WEDNESDAY 30 SEPTEMBER`).
