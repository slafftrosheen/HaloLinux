#!/usr/bin/env python3
"""Offline wire-format fixtures, not a claim to run the Android application."""
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts/halo_brain_archive.py"
CORE = {
    "workspace": ["workspace/objects.jsonl"],
    "notes": ["notes/metadata.jsonl"],
    "conversations": ["conversations/conversations.jsonl", "conversations/turns.jsonl"],
    "memory": ["memory/memories.jsonl", "memory/legacy.jsonl", "memory/revisions.jsonl"],
    "working-set": ["working-set/entries.jsonl"],
    "relations": ["relations/relations.jsonl"],
    "settings": ["settings/user-settings.json"],
    "reminders": ["reminders/reminders.jsonl"],
    "devices": ["devices/configuration.jsonl"],
    "files": ["files/index.jsonl"],
}
SECRET = "private-note-do-not-print"


def js(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def payload(profile="BRAIN_ONLY", encrypted=False, agent=False):
    entries = {p: b"" for paths in CORE.values() for p in paths}
    entries["workspace/objects.jsonl"] = js({"id": "n1", "type": "NOTE", "title": SECRET}) + b"\n"
    entries["notes/metadata.jsonl"] = js({"objectId": "n1", "body": SECRET, "revision": 1}) + b"\n"
    entries["settings/user-settings.json"] = b"{}"
    entries["workspace/provenance.jsonl"] = b""
    entries["memory/episodes.jsonl"] = b""
    entries["memory/suggestion-decisions.jsonl"] = b""
    entries["memory/reflection-statuses.jsonl"] = b""
    entries["README.md"] = b"# Halo Brain Export\n"
    entries["notes/note_n1.md"] = ("# Note\n" + SECRET).encode()
    entries["memory/memories.md"] = b"# Halo Memory\n"
    if profile != "BRAIN_ONLY":
        entries["files/content/f1/example.txt"] = b"fixture attachment"
        entries["files/index.jsonl"] = js({"objectId": "f1", "ownership": "HALO_OWNED", "ownedCopyPath": "archive:files/content/f1/example.txt"}) + b"\n"
    mapping = dict(CORE)
    if profile == "FULL_USER_BACKUP" or agent:
        mapping["agent"] = ["agent/runs.jsonl", "agent/steps.jsonl"]
        entries["agent/runs.jsonl"] = b'{"id":"run1"}\n'
        entries["agent/steps.jsonl"] = b""
    sections = [{"name": s, "version": 1, "itemCount": sum(1 if p.endswith(".json") else len(entries[p].splitlines()) for p in paths)} for s, paths in mapping.items()]
    manifest = {"format": "halo-brain", "schemaVersion": 2, "archiveId": "fixture-archive", "createdAtEpochMs": 1792720000000, "appVersionName": "fixture", "appVersionCode": 1, "databaseSchemaVersion": 1, "profile": profile, "encrypted": encrypted, "state": "COMPLETE", "sections": sections}
    return {"manifest.json": js(manifest), **entries}


def seal(entries):
    entries = dict(entries)
    checksums = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n" for name, data in entries.items()).encode()
    manifest = json.loads(entries["manifest.json"])
    entries["checksums.sha256"] = checksums
    entries["archive.complete"] = js({"archiveId": manifest["archiveId"], "manifestSha256": hashlib.sha256(entries["manifest.json"]).hexdigest(), "checksumListSha256": hashlib.sha256(checksums).hexdigest()})
    return entries


def zipped(entries, *, descriptor=False):
    class Stream(io.BytesIO):
        def seekable(self): return False
        def seek(self, *args): raise io.UnsupportedOperation()
    buffer = Stream() if descriptor else io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, value in entries.items() if isinstance(entries, dict) else entries:
                archive.writestr(name, value)
    return buffer.getvalue()


def encrypted(data, password=b"test-password", iterations=100000):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    salt, iv = b"a" * 16, b"b" * 12  # Test-only deterministic values, never production encryption.
    header = b"HALOBRAIN" + struct.pack(">iii", 1, iterations, len(salt)) + salt + struct.pack(">i", len(iv)) + iv
    key = hashlib.pbkdf2_hmac("sha256", password, salt, iterations, 32)
    return header + AESGCM(key).encrypt(iv, data, header)


def change_manifest(entries, key, value):
    m = json.loads(entries["manifest.json"])
    m[key] = value
    entries["manifest.json"] = js(m)
    return entries


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.path = self.dir / "phone.halobrain"

    def run_tool(self, data, *args, stdin=None):
        self.assertTrue(TOOL.exists(), "Archive inspection CLI has not been implemented")
        self.path.write_bytes(data)
        before = self.path.stat()
        proc = subprocess.run([sys.executable, "-B", str(TOOL), str(self.path), "--json", "--temp-dir", str(self.dir), *args], input=stdin, capture_output=True, timeout=15)
        self.assertEqual(self.path.read_bytes(), data)
        self.assertEqual(self.path.stat().st_mtime_ns, before.st_mtime_ns)
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), ["phone.halobrain"])
        self.assertNotIn(SECRET.encode(), proc.stdout + proc.stderr)
        self.assertNotIn(str(self.path).encode(), proc.stdout + proc.stderr)
        self.assertNotIn(b"Traceback", proc.stderr)
        return proc

    def good(self, entries=None, **kwargs):
        proc = self.run_tool(zipped(seal(entries or payload()), **kwargs))
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        result = json.loads(proc.stdout)
        self.assertEqual(result["integrity"], "verified")
        self.assertEqual(result["record_import"], "unsupported")
        return result

    def bad(self, data, *args, stdin=None):
        proc = self.run_tool(data, *args, stdin=stdin)
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(proc.stdout, b"")
        return proc

    def _schema3(self, projects=b'', versions=b'', declared_count=0):
        entries = payload()
        manifest = json.loads(entries['manifest.json'])
        manifest['schemaVersion'] = 3
        manifest['sections'].insert(1, {'name':'studio','version':1,'itemCount':declared_count})
        entries['manifest.json'] = js(manifest)
        entries['studio/projects.jsonl'] = projects
        entries['studio/versions.jsonl'] = versions
        return entries

    def test_schema3_empty_studio_all_profiles(self):
        for profile in ('BRAIN_ONLY','BRAIN_WITH_FILES','FULL_USER_BACKUP'):
            entries = self._schema3()
            if profile != 'BRAIN_ONLY':
                entries = payload(profile)
                manifest = json.loads(entries['manifest.json'])
                manifest['schemaVersion'] = 3
                manifest['sections'].insert(1, {'name':'studio','version':1,'itemCount':0})
                entries['manifest.json'] = js(manifest)
                entries['studio/projects.jsonl'] = b''
                entries['studio/versions.jsonl'] = b''
            with self.subTest(profile=profile):
                inventory = self.good(entries)
                self.assertEqual(inventory['schema_version'], 3)
                self.assertEqual(inventory['sections']['studio'], 0)

    def test_schema3_studio_section_count(self):
        row = b'{"objectId":"project","revision":1,"committed":"opaque","session":"opaque"}\n'
        inventory = self.good(self._schema3(projects=row, declared_count=1))
        self.assertEqual(inventory['record_counts']['studio/projects.jsonl'], 1)

    def test_schema3_missing_stream_rejected(self):
        entries = self._schema3()
        del entries['studio/versions.jsonl']
        self.bad(zipped(seal(entries)))

    def test_schema3_wrong_count_rejected(self):
        self.bad(zipped(seal(self._schema3(declared_count=1))))

    def test_schema2_studio_entries_rejected(self):
        entries = payload()
        entries['studio/projects.jsonl'] = b''
        entries['studio/versions.jsonl'] = b''
        self.bad(zipped(seal(entries)))

    def test_schema3_future_section_version_rejected(self):
        entries = self._schema3()
        manifest = json.loads(entries['manifest.json'])
        manifest['sections'][1]['version'] = 2
        entries['manifest.json'] = js(manifest)
        self.bad(zipped(seal(entries)))

    def test_brain_only(self): self.good()
    def test_with_files(self): self.assertEqual(self.good(payload("BRAIN_WITH_FILES"))["attachments"], 1)
    def test_full_backup(self): self.good(payload("FULL_USER_BACKUP"))
    def test_optional_agent_on_brain_only(self): self.good(payload(agent=True))
    def test_data_descriptors(self): self.good(descriptor=True)
    def test_unicode_record(self):
        entries = payload()
        entries["notes/metadata.jsonl"] = js({"objectId": "n1", "body": "Sveiki 🌍 Привет"}) + b"\n"
        self.good(entries)
    def test_encrypted(self):
        data = encrypted(zipped(seal(payload("FULL_USER_BACKUP", encrypted=True))))
        proc = self.run_tool(data, "--password-stdin", stdin=b"test-password\n")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        self.assertTrue(json.loads(proc.stdout)["encrypted"])
    def test_wrong_password(self):
        self.bad(encrypted(zipped(seal(payload(encrypted=True)))), "--password-stdin", stdin=b"WRONG\n")
    def test_gcm_tag_damage(self):
        data = bytearray(encrypted(zipped(seal(payload(encrypted=True)))))
        data[-1] ^= 1
        self.bad(bytes(data), "--password-stdin", stdin=b"test-password\n")
    def test_no_password_noninteractive(self): self.bad(encrypted(b"anything"), stdin=b"")
    def test_oversized_password(self): self.bad(encrypted(b"anything"), "--password-stdin", stdin=b"x" * 4097 + b"\n")
    def test_invalid_utf8_password(self): self.bad(encrypted(b"anything"), "--password-stdin", stdin=b"\xff\n")
    def test_encrypted_marker_mismatch(self): self.bad(encrypted(zipped(seal(payload()))), "--password-stdin", stdin=b"test-password\n")
    def test_plain_marker_mismatch(self): self.bad(zipped(seal(payload(encrypted=True))))
    def test_tampered_payload(self):
        entries = seal(payload()); entries["notes/note_n1.md"] += b"altered"
        self.bad(zipped(entries))
    def test_missing_checksum_coverage(self):
        entries = seal(payload()); entries["notes/unlisted.md"] = b"unlisted"
        self.bad(zipped(entries))
    def test_duplicate_zip_entries(self):
        entries = list(seal(payload()).items()); entries.append(entries[1])
        self.bad(zipped(entries))
    def test_missing_completion(self):
        entries = seal(payload()); del entries["archive.complete"]
        self.bad(zipped(entries))
    def test_wrong_completion_id(self):
        entries = seal(payload()); complete = json.loads(entries["archive.complete"]); complete["archiveId"] = "wrong"; entries["archive.complete"] = js(complete)
        self.bad(zipped(entries))
    def test_wrong_completion_digest(self):
        entries = seal(payload()); complete = json.loads(entries["archive.complete"]); complete["checksumListSha256"] = "0" * 64; entries["archive.complete"] = js(complete)
        self.bad(zipped(entries))
    def test_duplicate_json_keys(self):
        entries = payload(); entries["settings/user-settings.json"] = b'{"x":1,"x":2}'
        self.bad(zipped(seal(entries)))
    def test_deep_json(self):
        entries = payload(); entries["settings/user-settings.json"] = b'{"x":' + b"[" * 100 + b"0" + b"]" * 100 + b"}"
        self.bad(zipped(seal(entries)))
    def test_json_nonfinite(self):
        entries = payload(); entries["settings/user-settings.json"] = b'{"x":1e999}'
        self.bad(zipped(seal(entries)))
    def test_jsonl_non_object(self):
        entries = payload(); entries["notes/metadata.jsonl"] = b"[]\n"
        self.bad(zipped(seal(entries)))
    def test_jsonl_invalid_utf8(self):
        entries = payload(); entries["notes/metadata.jsonl"] = b'{"x":"\xff"}\n'
        self.bad(zipped(seal(entries)))
    def test_jsonl_count_mismatch(self):
        entries = payload(); entries["notes/metadata.jsonl"] += b'{"objectId":"n2"}\n'
        self.bad(zipped(seal(entries)))
    def test_missing_required_section_file(self):
        entries = payload(); del entries["notes/metadata.jsonl"]
        self.bad(zipped(seal(entries)))
    def test_full_missing_agent(self):
        entries = change_manifest(payload(), "profile", "FULL_USER_BACKUP")
        self.bad(zipped(seal(entries)))
    def test_brain_only_forbids_files(self):
        entries = payload(); entries["files/content/f1/x"] = b"x"
        self.bad(zipped(seal(entries)))
    def test_compression_bomb(self):
        entries = payload(); entries["notes/bomb.md"] = b"x" * 500000
        self.bad(zipped(seal(entries)))
    def test_symlink_zip_entry(self):
        entries = seal(payload()); buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for name, data in entries.items():
                info = zipfile.ZipInfo(name); info.create_system = 3
                info.external_attr = ((stat.S_IFLNK | 0o777) if name == "notes/note_n1.md" else (stat.S_IFREG | 0o600)) << 16
                archive.writestr(info, data)
        self.bad(buffer.getvalue())
    def test_source_symlink_refused(self):
        self.path.symlink_to("missing")
        proc = subprocess.run([sys.executable, "-B", str(TOOL), str(self.path)], capture_output=True, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
    def test_trailing_junk(self): self.bad(zipped(seal(payload())) + b"junk")
    def test_truncated_zip(self): self.bad(zipped(seal(payload()))[:-5])
    def test_prepended_zip(self): self.bad(b"junk" + zipped(seal(payload())))
    def test_zip_central_count_forgery(self):
        data = bytearray(zipped(seal(payload())))
        struct.pack_into("<HH", data, len(data) - 22 + 8, 1, 1)
        self.bad(bytes(data))
    def test_zip_directory_size_forgery(self):
        data = bytearray(zipped(seal(payload())))
        struct.pack_into("<I", data, len(data) - 22 + 12, 20 * 1024 * 1024)
        self.bad(bytes(data))
    def test_kdf_limit(self):
        data = b"HALOBRAIN" + struct.pack(">iii", 1, 2000001, 16) + b"a" * 16 + struct.pack(">i", 12) + b"b" * 28
        self.bad(data, "--password-stdin", stdin=b"test-password\n")
    def test_crypto_version(self):
        data = bytearray(encrypted(b"x")); struct.pack_into(">i", data, 9, 99)
        self.bad(bytes(data), "--password-stdin", stdin=b"test-password\n")
    def test_password_not_accepted_as_argument(self):
        proc = self.run_tool(zipped(seal(payload())), "--password", "DO-NOT-ECHO-ME")
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn(b"DO-NOT-ECHO-ME", proc.stdout + proc.stderr)
    def test_android_jvm_golden_vector(self):
        fixture = json.loads((ROOT / "tests/fixtures/halobrain/android-aes-gcm-v1.json").read_text())
        raw = base64.b64decode(fixture["archive_base64"], validate=True)
        proc = self.run_tool(raw, "--password-stdin", stdin=fixture["password"].encode() + b"\n")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        self.assertEqual(json.loads(proc.stdout)["profile"], "FULL_USER_BACKUP")
        self.assertNotIn(fixture["password"].encode(), proc.stdout + proc.stderr)

    def test_missing_crypto_is_explicit(self):
        self.path.write_bytes(encrypted(zipped(seal(payload(encrypted=True)))))
        proc = subprocess.run([sys.executable, "-B", "-S", str(TOOL), str(self.path), "--password-stdin"], input=b"test-password\n", capture_output=True, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn(b"python3-cryptography", proc.stderr)
        self.assertNotIn(b"Traceback", proc.stderr)

    def test_plain_archive_needs_no_site_packages(self):
        self.path.write_bytes(zipped(seal(payload())))
        proc = subprocess.run([sys.executable, "-B", "-S", str(TOOL), str(self.path)], capture_output=True, timeout=5)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())

    def test_source_fifo_is_not_opened_blocking(self):
        os.mkfifo(self.path)
        proc = subprocess.run([sys.executable, "-B", str(TOOL), str(self.path)], capture_output=True, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn(b"Traceback", proc.stderr)

    def test_empty_password(self):
        self.bad(encrypted(b"anything"), "--password-stdin", stdin=b"\n")

    def test_salt_length_limit(self):
        self.bad(b"HALOBRAIN" + struct.pack(">iii", 1, 100000, 65), "--password-stdin", stdin=b"test-password\n")

    def test_nonce_length_limit(self):
        self.bad(b"HALOBRAIN" + struct.pack(">iii", 1, 100000, 16) + b"a" * 16 + struct.pack(">i", 33), "--password-stdin", stdin=b"test-password\n")

    def test_empty_archive(self): self.bad(b"")

    def test_archive_contract_drift_detection(self):
        spec = importlib.util.spec_from_file_location("check_halobrain_contract", ROOT / "scripts/check_halobrain_contract.py")
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        path = self.dir / "test.kt"; path.write_bytes(b"hello")
        contract = {"sources": {"test.kt": hashlib.sha1(b"blob 5\0hello").hexdigest()}}
        self.assertEqual(module.check_contract(self.dir, contract), {"test.kt": "match"})
        path.write_bytes(b"edited")
        self.assertEqual(module.check_contract(self.dir, contract), {"test.kt": "changed"})
        path.unlink()
        self.assertEqual(module.check_contract(self.dir, contract), {"test.kt": "missing-or-unreadable"})
        path.symlink_to("missing")
        self.assertEqual(module.check_contract(self.dir, contract), {"test.kt": "unsupported-file"})

    def test_authentication_failure_never_parses_zip(self):
        spec = importlib.util.spec_from_file_location("halo_brain_archive", TOOL)
        module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module; spec.loader.exec_module(module)
        self.path.write_bytes(encrypted(zipped(seal(payload(encrypted=True)))))
        with patch.object(module, "_inspect_zip", side_effect=AssertionError("Unauthenticated plaintext parsed")) as parse:
            with self.assertRaises(module.ArchiveError):
                module.inspect_archive(self.path, b"wrong", temp_dir=self.dir)
            parse.assert_not_called()
        self.assertEqual([p.name for p in self.dir.iterdir()], ["phone.halobrain"])

    def test_duplicate_checksum(self):
        entries = seal(payload())
        entries["checksums.sha256"] += entries["checksums.sha256"].splitlines(keepends=True)[0]
        complete = json.loads(entries["archive.complete"])
        complete["checksumListSha256"] = hashlib.sha256(entries["checksums.sha256"]).hexdigest()
        entries["archive.complete"] = js(complete)
        self.bad(zipped(entries))

    def test_future_section_version(self):
        entries = payload(); manifest = json.loads(entries["manifest.json"])
        manifest["sections"][0]["version"] = 2
        entries["manifest.json"] = js(manifest)
        self.bad(zipped(seal(entries)))

    def test_duplicate_sections(self):
        entries = payload(); manifest = json.loads(entries["manifest.json"])
        manifest["sections"].append(manifest["sections"][0])
        entries["manifest.json"] = js(manifest)
        self.bad(zipped(seal(entries)))

    def test_password_spaces_preserved(self):
        raw = encrypted(zipped(seal(payload(encrypted=True))), password=b"  test password  ")
        proc = self.run_tool(raw, "--password-stdin", stdin=b"  test password  \r\n")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())

    def test_local_central_filename_mismatch(self):
        raw = bytearray(zipped(seal(payload())))
        raw[30] = ord("M")
        self.bad(bytes(raw))

    def test_truncated_authenticated_zip(self):
        raw = encrypted(zipped(seal(payload(encrypted=True)))[:-5])
        self.bad(raw, "--password-stdin", stdin=b"test-password\n")

    def test_binary_attachment(self):
        entries = payload("BRAIN_WITH_FILES")
        entries["files/content/f1/example.txt"] = bytes(range(256)) * 10
        self.good(entries)

    def test_limits_api(self):
        spec = importlib.util.spec_from_file_location("halo_brain_archive", TOOL)
        module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module; spec.loader.exec_module(module)
        self.path.write_bytes(zipped(seal(payload())))
        for limits in (module.Limits(max_archive_bytes=10), module.Limits(max_entries=2), module.Limits(max_entry_bytes=10), module.Limits(max_expanded_bytes=10), module.Limits(max_record_bytes=10)):
            with self.subTest(limits=limits), self.assertRaises(module.ArchiveError):
                module.inspect_archive(self.path, limits=limits, temp_dir=self.dir)


def add_bad_path(name, path):
    def test(self):
        entries = payload(); entries[path] = b"unsafe"
        self.bad(zipped(seal(entries)))
    setattr(ArchiveTests, "test_path_" + name, test)


for name, path in {"traversal": "../outside", "absolute": "/outside", "backslash": "notes\\x", "dot": "notes/./x.md", "empty": "notes//x.md", "control": "notes/\x1bx.md", "drive": "C:/x", "long": "notes/" + "x" * 241, "directory": "notes/", "unknown": "credentials.json"}.items():
    add_bad_path(name, path)


def add_bad_manifest(name, key, value):
    def test(self): self.bad(zipped(seal(change_manifest(payload(), key, value))))
    setattr(ArchiveTests, "test_manifest_" + name, test)


for name, key, value in [("future", "schemaVersion", 4), ("old", "schemaVersion", 1), ("bool_version", "schemaVersion", True), ("float_version", "schemaVersion", 2.0), ("wrong_format", "format", "other"), ("incomplete", "state", "WRITING"), ("unknown_profile", "profile", "OTHER"), ("string_encryption", "encrypted", "false"), ("bad_timestamp", "createdAtEpochMs", -1)]:
    add_bad_manifest(name, key, value)


if __name__ == "__main__":
    unittest.main(verbosity=2)
