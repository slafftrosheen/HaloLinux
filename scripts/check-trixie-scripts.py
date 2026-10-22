#!/usr/bin/env python3
"""Exercise deployment checks with fake host commands and temporary config files.

No Gradle build, installation, service change, package operation or real audio
command is run. HOME and the current user's configuration remain untouched.
"""

import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "scripts/lib/host-platform.sh"


class TrixieScriptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="halo-trixie-fixtures-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bin = self.base / "bin"
        self.jdk = self.base / "jdk"
        self.bin.mkdir()
        (self.jdk / "bin").mkdir(parents=True)
        (self.base / "runtime").mkdir()
        self.log = self.base / "commands.log"
        self.env = os.environ.copy()
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            self.env.pop(name, None)
        self.env.update({
            "JAVA_HOME": str(self.jdk),
            "PATH": str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
            "XDG_RUNTIME_DIR": str(self.base / "runtime"),
            "HALO_CONFIG_FILE": str(self.base / "config/config.yaml"),
            "HALO_TEST_LOG": str(self.log),
        })
        self.java(21)
        self.javac(21)
        self.command("id", 'printf "1000\\n"')
        self.command("uname", 'case "$1" in -m) echo aarch64;; *) echo "Linux fixture";; esac')
        self.command("dpkg", 'echo arm64')
        self.command("systemctl", 'printf "systemctl %s\\n" "$*" >> "$HALO_TEST_LOG"; echo running')
        self.command("loginctl", 'printf "loginctl %s\\n" "$*" >> "$HALO_TEST_LOG"; echo yes')
        self.command("wpctl", 'printf "wpctl %s\\n" "$*" >> "$HALO_TEST_LOG"; echo "Fixture audio server"')
        for command in ("free", "df", "lsblk", "aplay", "arecord"):
            self.command(command, 'printf "fixture listing\\n"')
        for command in ("sudo", "apt", "apt-get", "reboot", "mount", "raspi-config", "usermod"):
            self.command(command, 'printf "FORBIDDEN\\n" >> "$HALO_TEST_LOG"; exit 77')

    def executable(self, path, body):
        path.write_text("#!/bin/sh\n" + body + "\n", encoding="utf-8")
        path.chmod(0o755)

    def command(self, name, body):
        self.executable(self.bin / name, body)

    def java(self, major, prefix=""):
        body = f"printf '%s\\n' 'openjdk version \"{major}.0.8\"' >&2"
        if prefix:
            body = f"printf '%s\\n' '{prefix}' >&2\n" + body
        self.executable(self.jdk / "bin/java", body)

    def javac(self, major):
        self.executable(self.jdk / "bin/javac", f"echo 'javac {major}.0.8'")

    def run_command(self, args):
        result = subprocess.run(args, env=self.env, cwd=ROOT, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=12, check=False)
        if self.log.exists():
            self.assertNotIn("FORBIDDEN", self.log.read_text())
        return result

    def helper(self, function, *args):
        return self.run_command([
            "bash", "-c", 'source "$1"; shift; "$@"', "halo-fixture",
            str(HELPER), function, *args,
        ])

    def preflight(self, *args):
        return self.run_command(["bash", str(ROOT / "scripts/preflight.sh"), *args])

    def setup_config(self):
        return self.run_command(["bash", str(ROOT / "scripts/setup-trixie-desktop.sh")])

    def test_arm64_build_and_read_only_session_probes(self):
        result = self.preflight("--build")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("kernel=aarch64 userspace=arm64", result.stdout)
        self.assertIn("Compiler: javac 21", result.stdout)
        self.assertIn("user lingering enabled", result.stdout)
        self.assertEqual(self.log.read_text().splitlines(), [
            "systemctl --user is-system-running",
            "loginctl show-user 1000 --property=Linger --value",
            "wpctl status",
        ])

    def test_64_bit_kernel_does_not_hide_32_bit_userspace(self):
        self.command("dpkg", "echo armhf")
        result = self.preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unsupported kernel/userspace combination", result.stdout)

    def test_x86_linux_is_supported(self):
        self.command("uname", 'case "$1" in -m) echo x86_64;; *) echo "Linux fixture";; esac')
        self.command("dpkg", "echo amd64")
        self.assertEqual(self.preflight("--build").returncode, 0)

    def test_runtime_jre_works_but_source_build_needs_compiler(self):
        (self.jdk / "bin/javac").unlink()
        self.assertEqual(self.helper("halo_check_java", "runtime").returncode, 0)
        build = self.helper("halo_check_java", "build")
        self.assertNotEqual(build.returncode, 0)
        self.assertIn("a JRE is insufficient", build.stdout)

    def test_java_home_takes_precedence_over_path(self):
        self.java(17)
        self.command("java", 'echo \'openjdk version "21.0.8"\'')
        result = self.helper("halo_check_java", "runtime")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Java 21 or newer is required", result.stdout)

    def test_invalid_java_home_fails_without_path_fallback(self):
        (self.jdk / "bin/java").unlink()
        result = self.helper("halo_check_java", "build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Check JAVA_HOME", result.stdout)

    def test_newer_java_is_not_claimed_to_be_a_supported_build_jdk(self):
        self.java(25)
        self.javac(25)
        self.assertEqual(self.helper("halo_check_java", "runtime").returncode, 0)
        result = self.helper("halo_check_java", "build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source builds use the JDK 21 baseline", result.stdout)

    def test_mismatched_compiler_is_rejected(self):
        self.javac(17)
        result = self.helper("halo_check_java", "build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("java and javac must both be JDK 21", result.stdout)

    def test_jvm_option_banner_is_not_logged_or_mistaken_for_version(self):
        self.java(21, "Picked up JAVA_TOOL_OPTIONS: -Dprivate.token=fixture-secret")
        result = self.preflight("--build")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("fixture-secret", result.stdout)
        self.assertNotIn("JAVA_TOOL_OPTIONS", result.stdout)

    def test_missing_user_session_and_audio_server_are_actionable_warnings(self):
        self.command("systemctl", "echo offline; exit 1")
        self.command("loginctl", "echo no")
        self.command("wpctl", "exit 1")
        self.env.pop("XDG_RUNTIME_DIR")
        result = self.preflight()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("user manager unavailable", result.stdout)
        self.assertIn("boot/login persistence is not verified", result.stdout)
        self.assertIn("audio server/session is unreachable", result.stdout)
        self.assertIn("XDG_RUNTIME_DIR is absent or unusable", result.stdout)

    def test_os_release_is_read_as_data_not_executed(self):
        marker = self.base / "must-not-exist"
        release = self.base / "os-release"
        release.write_text(f'PRETTY_NAME="$(touch {marker})"\nVERSION_CODENAME=trixie\n')
        result = self.helper("halo_os_release_value", "PRETTY_NAME", str(release))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("$(touch", result.stdout)
        self.assertFalse(marker.exists())
        codename = self.helper("halo_os_release_value", "VERSION_CODENAME", str(release))
        self.assertEqual(codename.stdout.strip(), "trixie")

    def test_setup_uses_explicit_private_config_path_and_preserves_edits(self):
        path = Path(self.env["HALO_CONFIG_FILE"])
        result = self.setup_config()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o700)
        path.write_text("node:\n  display_name: Custom fixture\n")
        second = self.setup_config()
        self.assertEqual(second.returncode, 0, second.stdout)
        self.assertIn("Existing config preserved", second.stdout)
        self.assertEqual(path.read_text(), "node:\n  display_name: Custom fixture\n")

    def test_setup_does_not_follow_broken_config_symlink(self):
        path = Path(self.env["HALO_CONFIG_FILE"])
        path.parent.mkdir()
        target = self.base / "must-not-create"
        path.symlink_to(target)
        result = self.setup_config()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("non-symlink config", result.stdout)
        self.assertFalse(target.exists())
        self.assertTrue(path.is_symlink())

    def test_setup_does_not_create_config_when_build_jdk_is_missing(self):
        (self.jdk / "bin/java").unlink()
        result = self.setup_config()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(Path(self.env["HALO_CONFIG_FILE"]).exists())

    def test_setup_refuses_root_account_before_writing(self):
        self.command("id", "echo 0")
        result = self.setup_config()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("without sudo", result.stdout)
        self.assertFalse(Path(self.env["HALO_CONFIG_FILE"]).exists())

    def test_setup_rejects_relative_config_path(self):
        self.env["HALO_CONFIG_FILE"] = "relative/config.yaml"
        result = self.setup_config()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("absolute", result.stdout)

    def test_preflight_rejects_unknown_arguments(self):
        self.assertEqual(self.preflight("--install").returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
