# Project Handover: CleanYourTV (Custom Living Room Fork)

**Device**: Xiaomi Mi Box S (2nd Gen) (`aquaman` / `xun` / Amlogic S905X4)  
**Target Platform**: Android TV 11 / Google TV (`density: 2.0`, 1080p @ 60Hz)  
**Target User**: Eng. Kirolos Esmat  
**Repo**: `/Users/kirolosesmat/Desktop/Projects/cleanyourtv`  
**Git Remote**: `https://github.com/kirolos-esmat/cleanyourtv.git` (Branch: `main`)  
**Upstream Tracking**: [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (`v3.1.0` merged)  
**Last Verified State**: Commit `b89530d0`, Release APK 579,945 B (< 576 KB gate), 100% tests passing.

---

## 1. Executive Summary & Architecture Overview

This repository is a customized fork of **CleanYourTV**, a privacy-focused, zero-telemetry, lightweight Android TV launcher (`com.gathin.tvhome`) built in pure Java and AndroidX. It replaces bloated, ad-ridden stock interfaces (like Google TV and PatchWall) on budget streaming boxes while retaining essential hardware features (HDMI, tuner, input selector, ambient screensaver, and quick settings).

The fork has been tailored for Eng. Kirolos Esmat's Xiaomi Mi Box S (2nd Gen) setup. It incorporates:
1. **Upstream CleanYourTV v3.1.0** features and architecture.
2. **Personalized Branding & UI**:
   - Greeting: `"Good evening, Eng. Kirolos Esmat"` (`greeting_name` in `config.xml`).
   - Dock: Frosted glass dock band (`dock_glass = true`), symmetrical padding (`dock_even_padding = false`), 4 columns with standard grid below (`single_row = false`).
   - Top Bar: Live RAM & Storage Control Center pill (`btnControlCenter`) with built-in task manager.
   - Weather: Live Celsius weather above the clock with approximate network location.
3. **Hardware Remote Key Mappings**:
   - Dedicated remote buttons remapped directly to preferred apps (SmartTube, Plex, IPTV Smarters Pro, Spotify).
4. **Hold-Home Quick Settings Panel Integration**:
   - Stock Google TV launcher (`launcherx`) is kept enabled specifically for its `DashboardHandler`. `HomeKeyService` routes single Home clicks to CleanYourTV while passing through long-presses to open Google TV's quick settings overlay.
5. **Companion Add-ons & Services**:
   - **AdGuard Home / Ad Blocking Add-on** (`addons/ad-blocking`): Connects to local AdGuard server at `http://192.168.1.112:30004` to display live DNS block metrics on the home screen.
   - **TV Static Add-on** (`addons/tv-static`): White noise audio generator.
   - **Blanket TV Edition** (`/Users/kirolosesmat/Desktop/Projects/blanket`): Ambient noise generator ported with full D-pad remote support.
   - **AirPlay Mirroring Server** (`tools/airplay/`): Real-time iOS/macOS screen mirroring and audio streaming.

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
- **Rule**: These three packages must never be enabled in `my-debloat.txt`.

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

### Invariant 5: APK Size Gate
- `launcher/app/build/outputs/apk/release/app-release.apk` must stay strictly under **589,824 B (576 KB)**.
- Current size: **579,945 B**.
- Check size on macOS using `stat -f %z <path>`.

### Invariant 6: Disable, Never Uninstall
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
│   │   │   ├── GlassView.java       # Translucent floating focus highlight (glow + rim)
│   │   │   ├── DockDecoration.java  # ItemDecoration drawing frosted glass dock band
│   │   │   ├── HomeKeyService.java  # Accessibility service capturing & routing remote keys
│   │   │   ├── TaskManager.java     # RAM/Storage monitor and process manager dialog
│   │   │   └── Config.java          # Configuration parser reading config.xml and themes
│   │   └── res/
│   │       ├── values/config.xml    # Active custom TV settings (greeting, keys, dock)
│   │       └── layout/activity_main.xml # Root TV layout with Control Center pill
├── addons/
│   ├── ad-blocking/                # AdGuard Home stats module card app
│   └── tv-static/                  # TV static / white noise module card app
├── tools/
│   ├── airplay/                    # AirPlay receiver APK and configuration script
│   ├── disable.sh                  # Batch debloat script with safety checks
│   └── tv.py                       # TV ADB control & screenshot tool
└── tests/
    └── test_menu_screensaver_guards.py # Unit test suite asserting invariants
```

---

## 5. Summary of Recent Fixes (v3.1.0 & Focus Glass Alignment)

### 1. Upstream v3.1.0 Merge
- Merged upstream `d043b6f9` (`v3.1.0`) into `main` (commit `30e4747e`).
- Retained custom Control Center task manager, greeting name, and hardware key mappings.

### 2. Dock Focus Glass Misalignment & Shadow Gap Fix (Commit `b89530d0`)
- **Problem**: In upstream v3.1.0, dock cards stopped lifting on focus (`translationY = 0f`), growing in place to remain centered in the dock band. However, `MainActivity.java:1827` was still subtracting `4f * density` from `GlassView`'s target Y. On 1080p (density 2.0), the floating focus glass hovered ~8 pixels above the card, exposing an 8px dark gap (the wallpaper/dock showing through) at the top of pure white tiles (e.g. YouTube), while the bottom of the card hung out of the glass.
- **Fix**:
  1. `MainActivity.java:1827`: Made `lift = (advancedEffects && !inDock) ? 4f * density : 0f`. `GlassView` now stays concentric with dock tiles.
  2. `DockDecoration.java:151`: Removed obsolete `upwardShift` so the frosted dock band padding is vertically symmetric.
  3. `GlassView.java`: Reverted to clean upstream design. Removed custom `shadowPaint` (which was drawing black fill over cards) and `darkRim` (which was drawing dark strokes), restoring the smooth 12-ring outer glow and crisp white specular sheen.
  4. Real drop shadow elevation continues to be cleanly handled by Android's hardware elevation (`card.setTranslationZ(8f * density)`).

---

## 6. Daily Workflows & Commands

### 1. Build the Release APK
```bash
cd /Users/kirolosesmat/Desktop/Projects/cleanyourtv/launcher
./gradlew --no-daemon assembleRelease
stat -f %z app/build/outputs/apk/release/app-release.apk
# Verify: Size MUST be < 589824 bytes
```

### 2. Run Test Suites
```bash
cd /Users/kirolosesmat/Desktop/Projects/cleanyourtv
# Launcher unit tests & lint:
(cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease)

# Invariant & Safety Guard tests:
tools/venv/bin/python -m unittest tests/test_menu_screensaver_guards.py tests/test_setupwraith_guard.py tests/test_markdown_links.py
```

### 3. Deploy to TV (One-Click)
```bash
cd /Users/kirolosesmat/Desktop/Projects/cleanyourtv
./apply-my-tv.sh
```
*Note: This automatically ensures ADB connection, applies debloat, configures permissions, installs the APK, re-binds HomeKeyService, and brings CleanYourTV to front.*

### 4. Capture Verification Screenshot
```bash
adb -s 192.168.1.126:5555 exec-out screencap -p > /tmp/tv_screen.png
```

### 5. Safe Restart of Launcher
```bash
adb -s 192.168.1.126:5555 shell am start -n com.gathin.tvhome/.MainActivity
# REMINDER: DO NOT use am force-stop com.gathin.tvhome!
```

---

## 7. Open Status & Next Steps

- **Current Status**: Production clean, fully deployed on TV, all tests green, APK size gate verified.
- **TV Appearance**: YouTube and dock tiles render perfectly concentric with bright white specular rim and smooth glow, zero top shadow or vertical gap.
- **Future Tasks (if requested by user)**:
  - Any future upstream CleanYourTV updates can be rebased/merged cleanly using `git merge upstream/main`.
  - Additional remote key remappings can be added in `launcher/app/src/main/res/values/config.xml` under `<string-array name="app_keys">`.
