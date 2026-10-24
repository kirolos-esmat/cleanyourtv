Gemini CLI reads this file. The guide for every coding agent in this repo is AGENTS.md, imported below.

@AGENTS.md

## MANDATORY INVARIANTS FOR THIS DEVICE (Xiaomi Mi Box S 2nd Gen)

Every coding agent working on this TV setup MUST follow these rules without exception:

1. **DO NOT DISABLE THE HOLD-HOME MENU (`com.google.android.apps.tv.launcherx`)**:
   - `com.google.android.apps.tv.launcherx` provides the hold-Home Quick Settings / Dashboard panel (`DashboardHandler`).
   - CleanYourTV's `HomeKeyService` handles redirecting normal/single Home presses to `com.gathin.tvhome` while allowing the hold-Home dashboard panel to open.
   - Disabling `com.google.android.apps.tv.launcherx` completely breaks the hold-Home dashboard menu.
   - `com.google.android.apps.tv.launcherx` MUST remain commented out in `my-debloat.txt` and must NEVER be disabled in any script.

2. **DO NOT DISABLE THE AMBIENT SCREENSAVER (`com.google.android.apps.tv.dreamx`, `com.google.android.apps.mediashell`)**:
   - `com.google.android.apps.tv.dreamx` provides the Google TV Ambient screensaver (photos, artwork, clock).
   - `com.google.android.apps.mediashell` provides Google Cast and background screensaver receiver services.
   - `dreamx` depends on `launcherx` ContentProviders and `mediashell` `CastReceiverService`. Disabling either breaks the screensaver with "Unable to load content".
   - `com.google.android.apps.tv.dreamx` and `com.google.android.apps.mediashell` MUST NOT be active in `my-debloat.txt` and must remain enabled.

3. **SCREENSAVER PRIVACY (INTERNET-ONLY)**:
   - Location permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) must NOT be granted to `com.google.android.apps.tv.dreamx`.
   - `dreamx` fetches ambient content and regional weather purely via internet/IP without hardware location sensors.
   - `apply-my-tv.sh` revokes location permissions from `com.google.android.apps.tv.dreamx` while granting location permissions to `com.gathin.tvhome` for launcher top-bar weather.

These rules are enforced by unit tests in `tests/test_menu_screensaver_guards.py`.
