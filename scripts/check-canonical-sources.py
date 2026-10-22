#!/usr/bin/env python3
"""Offline regression tests for reviewed, SHA-pinned canonical source updates."""
from pathlib import Path
import hashlib
import json
import subprocess
import tempfile
import unittest

import canonical_sources as sources


class CanonicalSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.upstream = self.base / "android"
        self.local = self.base / "linux"
        for repo in (self.upstream, self.local):
            repo.mkdir()
            self.git(repo, "init", "-q")
            self.git(repo, "config", "user.email", "fixture@example.invalid")
            self.git(repo, "config", "user.name", "Fixture")
        (self.upstream / "settings.gradle.kts").write_text("// fixture\n")
        self.relative = "sdk/runtime-sdk/src/main/java/com/halo/Example.kt"
        self.write_upstream(self.relative, b"package com.halo\nclass Example\n")
        self.revision = self.commit_upstream()
        (self.local / "config").mkdir()
        (self.local / "config/canonical-modules.txt").write_text("sdk/runtime-sdk\n")
        (self.local / "config/canonical-halo.sha").write_text(self.revision + "\n")
        self.git(self.local, "add", ".")
        self.git(self.local, "commit", "-qm", "fixture")

    @staticmethod
    def git(repo, *args):
        return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()

    def write_upstream(self, path, content):
        target = self.upstream / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

    def commit_upstream(self):
        self.git(self.upstream, "add", "-A")
        self.git(self.upstream, "commit", "-qm", "source update")
        return self.git(self.upstream, "rev-parse", "HEAD")

    def apply(self, revision=None):
        return sources.synchronize(self.local, self.upstream, revision or self.revision)

    def test_apply_is_verbatim_and_idempotent(self):
        self.apply()
        self.assertEqual((self.local / self.relative).read_bytes(), (self.upstream / self.relative).read_bytes())
        manifest = self.local / "config/canonical-sources.json"
        before = manifest.read_bytes()
        mtime = (self.local / self.relative).stat().st_mtime_ns
        self.assertEqual(sources.verify(self.local, self.upstream), [])
        self.apply()
        self.assertEqual(manifest.read_bytes(), before)
        self.assertEqual((self.local / self.relative).stat().st_mtime_ns, mtime)

    def test_check_detects_local_changes_without_writing(self):
        self.apply()
        (self.local / self.relative).write_text("changed\n")
        before = (self.local / "config/canonical-sources.json").read_bytes()
        self.assertTrue(any("changed" in message for message in sources.verify(self.local)))
        self.assertEqual((self.local / "config/canonical-sources.json").read_bytes(), before)
        with self.assertRaisesRegex(ValueError, "Local source drift"):
            self.apply()

    def test_removes_only_old_manifest_files_deleted_upstream(self):
        self.apply()
        replacement = self.relative.replace("Example.kt", "Replacement.kt")
        (self.upstream / self.relative).unlink()
        self.write_upstream(replacement, b"package com.halo\nclass Replacement\n")
        revision = self.commit_upstream()
        untouched = self.local / "sdk/runtime-sdk/build.gradle.kts"
        untouched.write_text("// Linux build file\n")
        self.apply(revision)
        self.assertFalse((self.local / self.relative).exists())
        self.assertTrue((self.local / replacement).is_file())
        self.assertEqual(untouched.read_text(), "// Linux build file\n")
        self.assertEqual(sources.verify(self.local, self.upstream), [])

    def test_rejects_dirty_upstream_before_mutation(self):
        (self.upstream / self.relative).write_text("dirty\n")
        with self.assertRaisesRegex(ValueError, "checkout differs|Dirty canonical"):
            self.apply()
        self.assertFalse((self.local / self.relative).exists())

    def test_rejects_untracked_even_if_ignored_upstream_source(self):
        self.git(self.upstream, "config", "core.excludesfile", str(self.base / "ignore"))
        (self.base / "ignore").write_text("Ignored.kt\n")
        self.write_upstream(self.relative.replace("Example.kt", "Ignored.kt"), b"// ignored\n")
        with self.assertRaisesRegex(ValueError, "checkout differs"):
            self.apply()

    def test_rejects_wrong_revision(self):
        with self.assertRaisesRegex(ValueError, "revision"):
            self.apply("0" * 40)

    def test_external_check_rejects_tracked_bytes_hidden_from_git_status(self):
        self.apply()
        self.git(self.upstream, "update-index", "--assume-unchanged", self.relative)
        (self.upstream / self.relative).write_text("// hidden tracked edit\n")
        self.assertEqual(self.git(self.upstream, "status", "--porcelain"), "")
        with self.assertRaisesRegex(ValueError, "committed bytes"):
            sources.verify(self.local, self.upstream)

    def test_cognition_check_rejects_ignored_extra_source(self):
        cognitive = "runtime/cognition/src/main/java/com/halo/Coordinator.kt"
        self.write_upstream(cognitive, b"package com.halo\nclass Coordinator\n")
        self.revision = self.commit_upstream()
        self.apply()
        self.assertEqual(sources.verify(self.local, self.upstream, include_cognition=True), [])
        ignored = self.base / "ignored-cognition"
        ignored.write_text("Injected.kt\n")
        self.git(self.upstream, "config", "core.excludesfile", str(ignored))
        self.write_upstream(cognitive.replace("Coordinator.kt", "Injected.kt"), b"// hidden extra source\n")
        self.assertEqual(self.git(self.upstream, "status", "--porcelain"), "")
        with self.assertRaisesRegex(ValueError, "inventory"):
            sources.verify(self.local, self.upstream, include_cognition=True)

    def test_cognition_check_requires_external_checkout(self):
        self.apply()
        with self.assertRaisesRegex(ValueError, "checkout"):
            sources.verify(self.local, include_cognition=True)

    def test_rejects_symlinked_destination_without_touching_target(self):
        outside = self.base / "outside"
        outside.mkdir()
        (self.local / "sdk").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            self.apply()
        self.assertEqual(list(outside.iterdir()), [])

    def test_rejects_symlinked_upstream_source(self):
        target = self.upstream / self.relative
        target.unlink()
        target.symlink_to(self.upstream / "settings.gradle.kts")
        revision = self.commit_upstream()
        with self.assertRaisesRegex(ValueError, "Symlink|regular source"):
            self.apply(revision)

    def test_rejects_traversal_in_manifest(self):
        self.apply()
        manifest = self.local / "config/canonical-sources.json"
        data = json.loads(manifest.read_text())
        data["files"][0]["path"] = "sdk/runtime-sdk/src/main/java/../../escape.kt"
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            sources.verify(self.local)

    def test_rejects_additional_local_sources(self):
        self.apply()
        extra = self.local / self.relative.replace("Example.kt", "PrivateChange.kt")
        extra.write_text("// keep me\n")
        with self.assertRaisesRegex(ValueError, "Local source drift"):
            self.apply()
        self.assertEqual(extra.read_text(), "// keep me\n")

    def test_rejects_bootstrap_local_changes(self):
        target = self.local / self.relative
        target.parent.mkdir(parents=True)
        target.write_text("// local work\n")
        with self.assertRaisesRegex(ValueError, "Uncommitted bootstrap"):
            self.apply()
        self.assertEqual(target.read_text(), "// local work\n")

    def test_checks_pin_matches_manifest(self):
        self.apply()
        (self.local / "config/canonical-halo.sha").write_text("0" * 40 + "\n")
        with self.assertRaisesRegex(ValueError, "pin"):
            sources.verify(self.local)

    def test_rejects_ignored_bootstrap_file_collision(self):
        ignored = self.base / "ignored-local"
        ignored.write_text("Example.kt\n")
        self.git(self.local, "config", "core.excludesfile", str(ignored))
        target = self.local / self.relative
        target.parent.mkdir(parents=True)
        target.write_text("// ignored local work\n")
        with self.assertRaisesRegex(ValueError, "Uncommitted bootstrap"):
            self.apply()
        self.assertEqual(target.read_text(), "// ignored local work\n")

    def test_drift_ignores_tests_and_build_files(self):
        self.apply()
        test_path = self.upstream / "sdk/runtime-sdk/src/test/java/AndroidOnlyTest.kt"
        test_path.parent.mkdir(parents=True)
        test_path.write_text("// tests are not production sources\n")
        (self.upstream / "sdk/runtime-sdk/build.gradle.kts").write_text("// Android build\n")
        self.assertEqual(sources.verify(self.local, self.upstream), [])

    def test_manifest_hashes_are_real_sha256(self):
        self.apply()
        manifest = json.loads((self.local / "config/canonical-sources.json").read_text())
        self.assertEqual(manifest["files"], [{"path": self.relative, "sha256": hashlib.sha256((self.upstream / self.relative).read_bytes()).hexdigest()}])


if __name__ == "__main__":
    unittest.main()
