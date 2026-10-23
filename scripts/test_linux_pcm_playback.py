#!/usr/bin/env python3
"""Compile and execute real native playback code without a sound device or Gradle.

The JVM harness uses real owned subprocesses, not mocked Process objects. A sound
card/PipeWire session and the packaged daemon still require separate acceptance.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'sdk/runtime-sdk/src/main/java/com/halo/sdk/runtime/multimodal/PcmAudio.kt',
    'adapters/audio/src/main/java/com/halo/adapters/audio/LinuxAudioRoute.kt',
    'adapters/audio/src/main/java/com/halo/adapters/audio/LinuxPcmPlayback.kt',
    'host/linux/src/main/java/com/halo/host/linux/LinuxAudioCli.kt',
    'scripts/tests/LinuxPcmPlaybackHarness.kt',
]


class NativePlaybackTests(unittest.TestCase):
    def test_native_playback_and_cli(self):
        for name in SOURCES:
            self.assertTrue((ROOT / name).is_file(), f'Missing implementation: {name}')
        compiler, java = shutil.which('kotlinc'), shutil.which('java')
        self.assertIsNotNone(compiler, 'A Kotlin command-line compiler is required')
        self.assertIsNotNone(java, 'JDK 21 is required')
        with tempfile.TemporaryDirectory(prefix='halo-audio-test-') as directory:
            jar = Path(directory) / 'tests.jar'
            compile_result = subprocess.run(
                [compiler, *map(lambda p: str(ROOT / p), SOURCES), '-jvm-target', '17',
                 '-include-runtime', '-d', str(jar)], capture_output=True, text=True, timeout=90)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            run = subprocess.run([java, '-Xmx128m', '-jar', str(jar)],
                                 capture_output=True, text=True, timeout=90)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn('ALL PLAYBACK CASES PASSED', run.stdout)
            print(run.stdout.strip())

    def test_entrypoint_routes_audio_without_starting_daemon(self):
        text = (ROOT / 'host/linux/src/main/java/com/halo/host/linux/HaloNodeApplication.kt').read_text()
        self.assertIn('args.firstOrNull() == "audio"', text)
        self.assertIn('LinuxAudioCli.run(args.drop(1))', text)
        self.assertIn('else HaloCli.run(args)', text)


if __name__ == '__main__':
    unittest.main(verbosity=2)
