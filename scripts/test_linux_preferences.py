#!/usr/bin/env python3
"""Executable focused Trixie acceptance for the canonical Storage host adapter."""
from pathlib import Path
import os
import shutil
import pwd
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SOURCES=[ROOT/"sdk/runtime-sdk/src/main/java/com/halo/sdk/runtime/Storage.kt", ROOT/"adapters/storage/src/main/java/com/halo/adapters/storage/LinuxPreferenceStorage.kt", ROOT/"scripts/tests/LinuxPreferencesHarness.kt"]

class PreferencesTest(unittest.TestCase):
    def test_kotlin_contract_and_subprocesses(self):
        for file in SOURCES:
            self.assertTrue(file.is_file(), "Missing implementation dependency")
        with tempfile.TemporaryDirectory(prefix="halo-pref-build-") as temp:
            os.chmod(temp,0o755)
            jar=Path(temp)/"prefs.jar"
            compile=subprocess.run(["kotlinc",*[str(f) for f in SOURCES],"-jvm-target","17","-include-runtime","-d",str(jar)],capture_output=True,text=True,timeout=90)
            self.assertEqual(compile.returncode,0,compile.stderr[:3000])
            run=subprocess.run(["java",f"-Dharness.jar={jar}","-jar",str(jar)],capture_output=True,text=True,timeout=150)
            self.assertEqual(run.returncode,0,(run.stdout+run.stderr)[-3000:])
            self.assertIn("TOTAL ",run.stdout)
            self.assertIn("TOTAL 68 PASS, 0 FAIL",run.stdout)
            if os.geteuid()==0 and shutil.which("runuser"):
                # Discover a real unprivileged account instead of assuming pi/oai.
                account=next((u.pw_name for u in pwd.getpwall()
                    if 1000 <= u.pw_uid < 65534 and u.pw_name != "nobody"
                    and os.path.isdir(u.pw_dir)),None)
                if account is not None:
                    os.chmod(jar,0o644)
                    normal=subprocess.run(["runuser","-u",account,"--","java",
                        f"-Dharness.jar={jar}","-jar",str(jar)],capture_output=True,text=True,timeout=150)
                    self.assertEqual(normal.returncode,0,(normal.stdout+normal.stderr)[-3000:])
                    self.assertIn("TOTAL 68 PASS, 0 FAIL",normal.stdout)
            print(run.stdout[-1800:])

class RuntimeCompositionTest(unittest.TestCase):
    def test_canonical_storage_is_registered_after_xdg_initialization(self):
        bridge=(ROOT/"host/linux/src/main/java/com/halo/host/linux/LinuxRuntimeBridge.kt").read_text()
        self.assertIn("import com.halo.sdk.runtime.Storage",bridge)
        self.assertIn("import com.halo.adapters.storage.LinuxPreferenceStorage",bridge)
        self.assertIn("storage.getConfigDirectory()",bridge)
        self.assertIn("services.register(ServiceKey(Storage::class), preferences)",bridge)
        self.assertLess(bridge.index("services.register(ServiceKey(Storage::class), preferences)"),bridge.index("engine.initialize(EngineContext"))
        self.assertNotIn("Runtime.getRuntime().exec",bridge)
        adapter=(ROOT/"adapters/storage/src/main/java/com/halo/adapters/storage/LinuxPreferenceStorage.kt").read_text()
        self.assertIn(" : Storage {",adapter)
        self.assertNotIn("sqlite",adapter.lower())

if __name__=="__main__": unittest.main()
