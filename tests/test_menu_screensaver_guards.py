"""Guards the hold-Home Quick Settings menu and Google TV Ambient screensaver against regressions.

The user's setup on Xiaomi Mi Box S 2nd gen (Google TV 11) requires:
1. Hold-Home Menu / Dashboard: Provided by com.google.android.apps.tv.launcherx (DashboardHandler).
   HomeKeyService redirects single Home presses to com.gathin.tvhome while allowing the dashboard
   to open on long-press. Disabling launcherx breaks the hold-Home menu completely.
2. Ambient Screensaver & Google Cast: com.google.android.apps.tv.dreamx, com.google.android.apps.mediashell,
   and com.google.android.tungsten.setupwraith must remain enabled. dreamx relies on launcherx ContentProviders,
   setupwraith CastInfoProvider, and mediashell CastReceiverService for slideshow weather and ambient display.
3. Ambient Weather: dreamx and tvhome require approximate network location permissions (ACCESS_COARSE_LOCATION
   and ACCESS_FINE_LOCATION) to retrieve weather data and prevent nighttime/expiration weather loss.

Any edit to debloat lists or setup scripts that disables launcherx, dreamx, mediashell, setupwraith, or
revokes screensaver weather permissions, fails this test suite.
"""
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import recovery  # noqa: E402

MENU_PACKAGE = "com.google.android.apps.tv.launcherx"
DREAMX_PACKAGE = "com.google.android.apps.tv.dreamx"
MEDIASHELL_PACKAGE = "com.google.android.apps.mediashell"
SETUPWRAITH_PACKAGE = "com.google.android.tungsten.setupwraith"


class MenuScreensaverGuardsTest(unittest.TestCase):
    def setUp(self):
        self.debloat_path = ROOT / "my-debloat.txt"
        self.apply_script = ROOT / "apply-my-tv.sh"
        self.gemini_md = ROOT / "GEMINI.md"
        self.claude_md = ROOT / "CLAUDE.md"

    def test_my_debloat_exists(self):
        self.assertTrue(self.debloat_path.exists(), "my-debloat.txt must exist")

    def test_my_debloat_does_not_disable_hold_home_menu(self):
        active_pkgs = recovery.read_list(self.debloat_path)
        self.assertNotIn(
            MENU_PACKAGE,
            active_pkgs,
            f"{MENU_PACKAGE} must NOT be active in my-debloat.txt: it provides the hold-Home Quick Settings dashboard",
        )

    def test_my_debloat_does_not_disable_screensaver_or_cast(self):
        active_pkgs = recovery.read_list(self.debloat_path)
        self.assertNotIn(
            DREAMX_PACKAGE,
            active_pkgs,
            f"{DREAMX_PACKAGE} must NOT be active in my-debloat.txt: it provides the Ambient screensaver",
        )
        self.assertNotIn(
            MEDIASHELL_PACKAGE,
            active_pkgs,
            f"{MEDIASHELL_PACKAGE} must NOT be active in my-debloat.txt: it provides Google Cast and screensaver backing",
        )
        self.assertNotIn(
            SETUPWRAITH_PACKAGE,
            active_pkgs,
            f"{SETUPWRAITH_PACKAGE} must NOT be active in my-debloat.txt: it provides CastInfoProvider for screensaver weather",
        )

    def test_apply_script_enables_screensaver_and_cast(self):
        self.assertTrue(self.apply_script.exists(), "apply-my-tv.sh must exist")
        content = self.apply_script.read_text(encoding="utf-8")
        self.assertIn(
            f"pm enable {DREAMX_PACKAGE}",
            content,
            "apply-my-tv.sh must explicitly ensure dreamx (Ambient screensaver) is enabled",
        )
        self.assertIn(
            f"pm enable {MEDIASHELL_PACKAGE}",
            content,
            "apply-my-tv.sh must explicitly ensure mediashell (Google Cast) is enabled",
        )
        self.assertIn(
            f"pm enable {SETUPWRAITH_PACKAGE}",
            content,
            "apply-my-tv.sh must explicitly ensure setupwraith (CastInfoProvider) is enabled",
        )

    def test_apply_script_does_not_disable_menu_or_screensaver(self):
        content = self.apply_script.read_text(encoding="utf-8")
        for pkg in (MENU_PACKAGE, DREAMX_PACKAGE, MEDIASHELL_PACKAGE, SETUPWRAITH_PACKAGE):
            pattern = rf"pm\s+(?:disable(?:-user)?|clear)(?:\s+--user\s+\d+)?\s+{re.escape(pkg)}"
            self.assertIsNone(
                re.search(pattern, content),
                f"apply-my-tv.sh must not disable or clear data for {pkg}",
            )

    def test_apply_script_grants_screensaver_and_launcher_weather_location(self):
        content = self.apply_script.read_text(encoding="utf-8")
        self.assertIn(
            f"pm grant {DREAMX_PACKAGE} android.permission.ACCESS_FINE_LOCATION",
            content,
            "apply-my-tv.sh must grant FINE_LOCATION to dreamx for ambient weather",
        )
        self.assertIn(
            f"pm grant {DREAMX_PACKAGE} android.permission.ACCESS_COARSE_LOCATION",
            content,
            "apply-my-tv.sh must grant COARSE_LOCATION to dreamx for ambient weather",
        )
        self.assertIn(
            "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION",
            content,
            "apply-my-tv.sh must grant FINE_LOCATION to com.gathin.tvhome for top bar weather",
        )

    def test_apply_script_rebinds_home_key_service_and_never_force_stops_launcher(self):
        content = self.apply_script.read_text(encoding="utf-8")
        self.assertNotIn(
            "force-stop com.gathin.tvhome",
            content,
            "apply-my-tv.sh must never force-stop com.gathin.tvhome (doing so unbinds HomeKeyService)",
        )
        self.assertIn(
            "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS",
            content,
            "apply-my-tv.sh must grant WRITE_SECURE_SETTINGS so HomeKeyService can self-repair",
        )
        self.assertIn(
            "settings put secure enabled_accessibility_services ''",
            content,
            "apply-my-tv.sh must clear enabled_accessibility_services before re-enabling so crashed services re-bind",
        )
        self.assertIn(
            "settings put secure accessibility_enabled 0",
            content,
            "apply-my-tv.sh must toggle accessibility_enabled 0 -> 1 so HomeKeyService re-binds",
        )

    def test_agent_guidelines_document_mandatory_menu_and_screensaver(self):
        for doc in (self.gemini_md, self.claude_md):
            self.assertTrue(doc.exists(), f"{doc.name} must exist")
            text = doc.read_text(encoding="utf-8")
            self.assertIn(MENU_PACKAGE, text, f"{doc.name} must document mandatory protection for {MENU_PACKAGE}")
            self.assertIn(DREAMX_PACKAGE, text, f"{doc.name} must document mandatory protection for {DREAMX_PACKAGE}")
            self.assertIn(MEDIASHELL_PACKAGE, text, f"{doc.name} must document mandatory protection for {MEDIASHELL_PACKAGE}")
            self.assertIn(SETUPWRAITH_PACKAGE, text, f"{doc.name} must document mandatory protection for {SETUPWRAITH_PACKAGE}")
            self.assertIn(
                "am force-stop com.gathin.tvhome",
                text,
                f"{doc.name} must explicitly forbid running 'am force-stop com.gathin.tvhome'",
            )


if __name__ == "__main__":
    unittest.main()
