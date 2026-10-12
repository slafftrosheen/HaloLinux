#!/usr/bin/env python3
"""Offline, model-free unit tests for snapshot manifest verification."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import hashlib
import os

SOURCE = Path(__file__).resolve().with_name("verify-node-snapshot.py")
spec = importlib.util.spec_from_file_location("halo_snapshot_verify", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SnapshotVerifyTest(unittest.TestCase):
    def make_snapshot(self, folder: Path) -> Path:
        folder.mkdir(mode=0o700)
        db = folder / "halo-main.sqlite"
        with sqlite3.connect(db) as con:
            con.execute("CREATE TABLE example(id TEXT)")
            con.execute("INSERT INTO example VALUES('private')")
        db.chmod(0o600)
        checksum = hashlib.sha256(db.read_bytes()).hexdigest()
        doc = {
            "format": "halo-node-snapshot-v1", "created_at": "2026-10-12T00:00:00Z",
            "node_id": "fixture", "sensitive": True, "encrypted": False,
            "includes_canonical_agent_store": False,
            "files": [{"role": "halo-main", "filename": db.name,
                       "bytes": db.stat().st_size, "sha256": checksum}]
        }
        manifest = folder / "manifest.json"
        manifest.write_text(json.dumps(doc), encoding="utf-8")
        manifest.chmod(0o600)
        return db

    def test_valid_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            self.make_snapshot(folder)
            self.assertEqual(module.verify(folder), 0)

    def test_tampering_is_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with db.open("ab") as handle:
                handle.write(b"tamper")
            with self.assertRaises(ValueError):
                module.verify(folder)

    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            self.make_snapshot(folder)
            alias = Path(temp) / "alias"
            alias.symlink_to(folder, target_is_directory=True)
            with self.assertRaises(ValueError):
                module.verify(alias)

    def test_public_file_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            db.chmod(0o644)
            with self.assertRaises(ValueError):
                module.verify(folder)


if __name__ == "__main__":
    unittest.main()
