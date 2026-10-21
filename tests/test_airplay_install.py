"""Unit tests for tools/airplay/install.sh."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
INSTALL_SCRIPT = ROOT / "tools" / "airplay" / "install.sh"


class AirPlayInstallTest(unittest.TestCase):
    def test_script_exists_and_executable(self):
        self.assertTrue(INSTALL_SCRIPT.is_file(), "install.sh must exist")
        self.assertTrue(os.access(INSTALL_SCRIPT, os.X_OK), "install.sh must be executable")

    def test_bash_syntax(self):
        result = subprocess.run(
            ["bash", "-n", str(INSTALL_SCRIPT)],
            capture_output=True,
            text=True
        )
        self.assertEqual(result.returncode, 0, f"Syntax error in install.sh: {result.stderr}")

    def test_shellcheck(self):
        shellcheck = shutil.which("shellcheck")
        if not shellcheck:
            self.skipTest("shellcheck not found in PATH")
        result = subprocess.run(
            [shellcheck, str(INSTALL_SCRIPT)],
            capture_output=True,
            text=True
        )
        self.assertEqual(result.returncode, 0, f"shellcheck failed: {result.stdout}\n{result.stderr}")

    def test_missing_host_fails_with_usage(self):
        result = subprocess.run(
            [str(INSTALL_SCRIPT)],
            capture_output=True,
            text=True,
            env={k: v for k, v in os.environ.items() if k != "TV_HOST"}
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("Usage:", result.stderr)

    def test_mock_adb_execution_flow_and_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            mock_adb = bin_dir / "adb"
            log_file = tmp_path / "adb_calls.log"

            # Create mock adb that logs calls and simulates SettingsReceiver responses
            mock_adb.write_text(f"""#!/bin/sh
echo "$@" >> "{log_file}"
if echo "$@" | grep -q "LIST_SETTINGS"; then
    echo 'Broadcasting: Intent {{ act=io.github.jqssun.airplay.LIST_SETTINGS }}'
    echo 'Broadcast completed: result=-1, data="{{"server_name":{{"value":"Test TV","default":"Android AirPlay"}},"low_latency":{{"value":true,"default":false}},"scheduled_output_buffer_release":{{"value":false,"default":false}},"audio_auto_buffer":{{"value":true,"default":true}},"audio_cushion_ms":{{"value":40,"default":40}},"audio_latency_ms":{{"value":-1,"default":-1}}}}"'
    exit 0
fi
if echo "$@" | grep -q "SET_SETTING"; then
    echo 'Broadcasting: Intent {{ act=io.github.jqssun.airplay.SET_SETTING }}'
    echo 'Broadcast completed: result=-1, data="ok"'
    exit 0
fi
exit 0
""")
            mock_adb.chmod(0o755)

            test_env = os.environ.copy()
            test_env["PATH"] = f"{bin_dir}:{test_env.get('PATH', '')}"
            test_env["TV_HOST"] = "192.0.2.1"
            test_env["DEVICE_NAME"] = "Custom AirPlay"
            test_env["AIRPLAY_LOW_LATENCY"] = "true"
            test_env["AIRPLAY_SCHEDULED_RELEASE"] = "false"
            test_env["AIRPLAY_AUDIO_AUTO_BUFFER"] = "true"
            test_env["AIRPLAY_AUDIO_CUSHION_MS"] = "40"
            test_env["AIRPLAY_AUDIO_LATENCY_MS"] = "-1"

            result = subprocess.run(
                [str(INSTALL_SCRIPT)],
                capture_output=True,
                text=True,
                env=test_env
            )
            self.assertEqual(result.returncode, 0, f"Script failed: {result.stderr}\n{result.stdout}")

            logged_calls = log_file.read_text()
            self.assertIn("connect 192.0.2.1:5555", logged_calls)
            self.assertIn("dumpsys deviceidle whitelist +io.github.jqssun.airplay", logged_calls)
            self.assertIn("appops set io.github.jqssun.airplay SYSTEM_ALERT_WINDOW allow", logged_calls)
            self.assertIn("cmd uimode night yes", logged_calls)
            self.assertIn("key 'server_name' --es value 'Custom AirPlay'", logged_calls)
            self.assertIn("key 'low_latency' --es value 'true'", logged_calls)
            self.assertIn("key 'scheduled_output_buffer_release' --es value 'false'", logged_calls)
            self.assertIn("key 'audio_auto_buffer' --es value 'true'", logged_calls)
            self.assertIn("key 'audio_cushion_ms' --es value '40'", logged_calls)
            self.assertIn("key 'audio_latency_ms' --es value '-1'", logged_calls)
            self.assertNotIn("force_sw_aac", logged_calls)
            self.assertIn("LIST_SETTINGS", logged_calls)
            self.assertIn("am force-stop io.github.jqssun.airplay", logged_calls)

    def test_verify_flag_with_mock_adb(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            mock_adb = bin_dir / "adb"
            log_file = tmp_path / "adb_calls.log"

            mock_adb.write_text(f"""#!/bin/sh
echo "$@" >> "{log_file}"
if echo "$@" | grep -q "LIST_SETTINGS"; then
    echo 'Broadcasting: Intent {{ act=io.github.jqssun.airplay.LIST_SETTINGS }}'
    echo 'Broadcast completed: result=-1, data="{{"server_name":{{"value":"Verified TV","default":"Android AirPlay"}}}}"'
    exit 0
fi
exit 0
""")
            mock_adb.chmod(0o755)

            test_env = os.environ.copy()
            test_env["PATH"] = f"{bin_dir}:{test_env.get('PATH', '')}"

            result = subprocess.run(
                [str(INSTALL_SCRIPT), "--verify", "192.0.2.2"],
                capture_output=True,
                text=True,
                env=test_env
            )
            self.assertEqual(result.returncode, 0, f"Verify failed: {result.stderr}\n{result.stdout}")
            self.assertIn("Active Receiver Configuration", result.stdout)

            logged_calls = log_file.read_text()
            self.assertIn("connect 192.0.2.2:5555", logged_calls)
            self.assertIn("LIST_SETTINGS", logged_calls)
            # Must NOT reinstall or configure in verify mode
            self.assertNotIn("install -r", logged_calls)
            self.assertNotIn("SET_SETTING", logged_calls)

    def test_setting_broadcast_failure_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            mock_adb = bin_dir / "adb"

            # SET_SETTING fails with result=0
            mock_adb.write_text("""#!/bin/sh
if echo "$@" | grep -q "SET_SETTING"; then
    echo 'Broadcasting: Intent { act=io.github.jqssun.airplay.SET_SETTING }'
    echo 'Broadcast completed: result=0'
    exit 0
fi
exit 0
""")
            mock_adb.chmod(0o755)

            test_env = os.environ.copy()
            test_env["PATH"] = f"{bin_dir}:{test_env.get('PATH', '')}"
            test_env["TV_HOST"] = "192.0.2.3"

            result = subprocess.run(
                [str(INSTALL_SCRIPT)],
                capture_output=True,
                text=True,
                env=test_env
            )
            self.assertNotEqual(result.returncode, 0, "Installer must exit non-zero when a setting broadcast fails")
            self.assertIn("failed to set", result.stderr)

    def test_verify_failed_broadcast_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            mock_adb = bin_dir / "adb"

            # LIST_SETTINGS fails
            mock_adb.write_text("""#!/bin/sh
if echo "$@" | grep -q "LIST_SETTINGS"; then
    echo 'Broadcasting: Intent { act=io.github.jqssun.airplay.LIST_SETTINGS }'
    echo 'Broadcast completed: result=0, data=""'
    exit 0
fi
exit 0
""")
            mock_adb.chmod(0o755)

            test_env = os.environ.copy()
            test_env["PATH"] = f"{bin_dir}:{test_env.get('PATH', '')}"

            result = subprocess.run(
                [str(INSTALL_SCRIPT), "--verify", "192.0.2.4"],
                capture_output=True,
                text=True,
                env=test_env
            )
            self.assertNotEqual(result.returncode, 0, "--verify must exit non-zero when broadcast query fails")
            self.assertIn("Failed to query receiver settings", result.stderr)

    def test_verify_invalid_json_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            mock_adb = bin_dir / "adb"

            # LIST_SETTINGS returns invalid JSON
            mock_adb.write_text("""#!/bin/sh
if echo "$@" | grep -q "LIST_SETTINGS"; then
    echo 'Broadcasting: Intent { act=io.github.jqssun.airplay.LIST_SETTINGS }'
    echo 'Broadcast completed: result=-1, data="{corrupted_json: true"'
    exit 0
fi
exit 0
""")
            mock_adb.chmod(0o755)

            test_env = os.environ.copy()
            test_env["PATH"] = f"{bin_dir}:{test_env.get('PATH', '')}"

            result = subprocess.run(
                [str(INSTALL_SCRIPT), "--verify", "192.0.2.5"],
                capture_output=True,
                text=True,
                env=test_env
            )
            self.assertNotEqual(result.returncode, 0, "--verify must exit non-zero when JSON payload is invalid")
            self.assertIn("Failed to parse receiver settings JSON", result.stderr)

    def test_verify_empty_data_exits_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            bin_dir = tmp_path / "bin"
            bin_dir.mkdir()
            mock_adb = bin_dir / "adb"

            # LIST_SETTINGS returns empty data payload
            mock_adb.write_text("""#!/bin/sh
if echo "$@" | grep -q "LIST_SETTINGS"; then
    echo 'Broadcasting: Intent { act=io.github.jqssun.airplay.LIST_SETTINGS }'
    echo 'Broadcast completed: result=-1'
    exit 0
fi
exit 0
""")
            mock_adb.chmod(0o755)

            test_env = os.environ.copy()
            test_env["PATH"] = f"{bin_dir}:{test_env.get('PATH', '')}"

            result = subprocess.run(
                [str(INSTALL_SCRIPT), "--verify", "192.0.2.6"],
                capture_output=True,
                text=True,
                env=test_env
            )
            self.assertNotEqual(result.returncode, 0, "--verify must exit non-zero when data payload is empty")
            self.assertIn("Receiver returned empty settings payload", result.stderr)


if __name__ == "__main__":
    unittest.main()
