Claude Code reads this file. The guide for every coding agent in this repo is AGENTS.md, imported below.

@AGENTS.md

## MANDATORY INVARIANTS FOR THIS DEVICE (Xiaomi Mi Box S 2nd Gen)

Every coding agent working on this TV setup MUST follow these rules without exception:

1. **DO NOT DISABLE THE HOLD-HOME MENU (`com.google.android.apps.tv.launcherx`)**:
   - `com.google.android.apps.tv.launcherx` provides the hold-Home Quick Settings / Dashboard panel (`DashboardHandler`).
   - CleanYourTV's `HomeKeyService` handles redirecting normal/single Home presses to `com.gathin.tvhome` while allowing the hold-Home dashboard panel to open.
   - Disabling `com.google.android.apps.tv.launcherx` completely breaks the hold-Home dashboard menu.
   - `com.google.android.apps.tv.launcherx` MUST remain commented out in `my-debloat.txt` and must NEVER be disabled in any script.

2. **DO NOT DISABLE THE AMBIENT SCREENSAVER & CAST SERVICES (`com.google.android.apps.tv.dreamx`, `com.google.android.apps.mediashell`, `com.google.android.tungsten.setupwraith`)**:
   - `com.google.android.apps.tv.dreamx` provides the Google TV Ambient screensaver (photos, artwork, clock, weather).
   - `com.google.android.apps.mediashell` provides Google Cast and background screensaver receiver services.
   - `com.google.android.tungsten.setupwraith` provides `CastInfoProvider` required for cast device authentication and weather slideshow data.
   - `com.google.android.apps.tv.dreamx`, `com.google.android.apps.mediashell`, and `com.google.android.tungsten.setupwraith` MUST NOT be active in `my-debloat.txt` and must remain enabled.

3. **SCREENSAVER & LAUNCHER WEATHER PERMISSIONS**:
   - `com.google.android.apps.tv.dreamx` and `com.gathin.tvhome` require approximate network location permissions (`ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`) alongside system `location_mode 3` to resolve city forecast data for both daytime and nighttime weather.
   - `apply-my-tv.sh` grants these location permissions to both `dreamx` and `tvhome` so weather does not get hidden when cache expires at night.

4. **NEVER FORCE-STOP `com.gathin.tvhome` (`am force-stop com.gathin.tvhome`)**:
   - Force-stopping `com.gathin.tvhome` immediately kills and unbinds `com.gathin.tvhome/.HomeKeyService` (moving it to `Crashed services` in Android Accessibility).
   - Because `com.google.android.apps.tv.launcherx` remains enabled for the hold-Home menu, unbinding `HomeKeyService` causes pressing Home to revert to the stock Google TV launcher (`launcherx`).
   - Never run `am force-stop com.gathin.tvhome` when adding/editing apps or themes. CleanYourTV automatically reloads apps and config when started via `am start -n com.gathin.tvhome/.MainActivity`. If `HomeKeyService` ever unbinds, run `./apply-my-tv.sh` to reset and re-bind it.

These rules are enforced by unit tests in `tests/test_menu_screensaver_guards.py`.
