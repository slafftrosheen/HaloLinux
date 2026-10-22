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
            con.executescript("""
                CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL,description TEXT);
                INSERT INTO schema_version VALUES(1,'2026-10-12T00:00:00Z','Core persistence and FTS5');
                INSERT INTO schema_version VALUES(2,'2026-10-12T00:00:00Z','Node identity and sync peers');
                INSERT INTO schema_version VALUES(3,'2026-10-12T00:00:00Z','Conservative text-turn checkpoint ledger');
                CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT,created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,provider_id TEXT,model_id TEXT,metadata TEXT);
                CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,role TEXT NOT NULL,
                    content TEXT NOT NULL,provider_id TEXT,model_id TEXT,tokens_used INTEGER,latency_ms INTEGER,
                    created_at TEXT NOT NULL,metadata TEXT);
                CREATE TABLE memories(id TEXT PRIMARY KEY,content TEXT NOT NULL,source TEXT,scope TEXT,
                    confidence REAL,expires_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,metadata TEXT);
                CREATE VIRTUAL TABLE memories_fts USING fts5(content,source,scope,content='memories',content_rowid='rowid');
                CREATE TABLE node_identity(key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at TEXT NOT NULL);
                CREATE TABLE sync_peers(id TEXT PRIMARY KEY,name TEXT,public_key TEXT NOT NULL,trust_state TEXT NOT NULL,
                    capabilities TEXT,last_seen TEXT,created_at TEXT NOT NULL,metadata TEXT);
                CREATE TABLE turn_checkpoints(interaction_id TEXT PRIMARY KEY,state TEXT NOT NULL,
                    created_at TEXT NOT NULL,updated_at TEXT NOT NULL,conversation_id TEXT,failure_type TEXT);
            """)
            con.executemany("INSERT INTO node_identity VALUES(?,?,'2026-10-12T00:00:00Z')", [
                ("node_id", "fixture"),
                ("public_key", "fixture-public"),
                ("private_key", "fixture-private"),
            ])
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

    def update_manifest(self, db: Path) -> None:
        manifest = db.parent / "manifest.json"
        data = json.loads(manifest.read_text())
        record = next(item for item in data["files"] if item["filename"] == db.name)
        record["bytes"] = db.stat().st_size
        record["sha256"] = hashlib.sha256(db.read_bytes()).hexdigest()
        manifest.write_text(json.dumps(data))

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

    def test_manifest_identity_swap_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            self.make_snapshot(folder)
            manifest = folder / "manifest.json"
            data = json.loads(manifest.read_text())
            data["node_id"] = "different-node"
            manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                module.verify(folder)

    def test_missing_private_node_identity_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            main = self.make_snapshot(folder)
            with sqlite3.connect(main) as db:
                db.execute("DELETE FROM node_identity WHERE key='private_key'")
            manifest = folder / "manifest.json"
            data = json.loads(manifest.read_text())
            data["files"][0]["bytes"] = main.stat().st_size
            data["files"][0]["sha256"] = hashlib.sha256(main.read_bytes()).hexdigest()
            manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                module.verify(folder)

    def test_unexpected_extra_file_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            self.make_snapshot(folder)
            (folder / "unexpected.txt").write_text("not in manifest")
            with self.assertRaises(ValueError):
                module.verify(folder)

    def test_public_file_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            db.chmod(0o644)
            with self.assertRaises(ValueError):
                module.verify(folder)

    def test_legacy_master_version_three_rejected_without_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with sqlite3.connect(db) as conn:
                conn.execute("DROP TABLE turn_checkpoints")
                conn.execute("CREATE TABLE agent_runs(run_id TEXT PRIMARY KEY,status TEXT)")
                conn.execute("CREATE TABLE agent_run_events(event_id TEXT PRIMARY KEY,run_id TEXT)")
                conn.execute("INSERT INTO agent_runs VALUES('preserve-run','INTERRUPTED')")
                conn.execute("INSERT INTO agent_run_events VALUES('preserve-event','preserve-run')")
                conn.execute("UPDATE schema_version SET description='Add agent runs and event journal for crash recovery' WHERE version=3")
            self.update_manifest(db)
            original = {path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in folder.iterdir()}
            with self.assertRaisesRegex(ValueError, "legacy master"):
                module.verify(folder)
            self.assertEqual(original, {
                path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in folder.iterdir()
            })

    def test_future_main_schema_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with sqlite3.connect(db) as conn:
                conn.execute("INSERT INTO schema_version VALUES(4,'2026-10-23T00:00:00Z','future')")
            self.update_manifest(db)
            with self.assertRaisesRegex(ValueError, "main database schema"):
                module.verify(folder)

    def test_missing_main_schema_history_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with sqlite3.connect(db) as conn:
                conn.execute("DROP TABLE schema_version")
            self.update_manifest(db)
            with self.assertRaisesRegex(ValueError, "schema_version"):
                module.verify(folder)

    def test_missing_main_table_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with sqlite3.connect(db) as conn:
                conn.execute("DROP TABLE messages")
            self.update_manifest(db)
            with self.assertRaisesRegex(ValueError, "messages"):
                module.verify(folder)

    def test_malformed_main_table_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with sqlite3.connect(db) as conn:
                conn.execute("DROP TABLE turn_checkpoints")
                conn.execute("CREATE TABLE turn_checkpoints(interaction_id TEXT PRIMARY KEY)")
            self.update_manifest(db)
            with self.assertRaisesRegex(ValueError, "turn_checkpoints"):
                module.verify(folder)

    def test_valid_snapshot_verification_is_read_only(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            self.make_snapshot(folder)
            original = {path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in folder.iterdir()}
            self.assertEqual(module.verify(folder), 0)
            self.assertEqual(original, {
                path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in folder.iterdir()
            })

    def test_wal_mode_file_rejected_without_creating_sidecars(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            with sqlite3.connect(db) as conn:
                conn.execute("PRAGMA journal_mode=WAL")
            self.assertEqual(db.read_bytes()[18:20], b"\x02\x02")
            self.update_manifest(db)
            original = {
                path.name: (path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_mode)
                for path in folder.iterdir()
            }
            with self.assertRaises(ValueError) as failure:
                module.verify(folder)
            self.assertEqual(original, {
                path.name: (path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_mode)
                for path in folder.iterdir()
            })
            self.assertIn("standalone", str(failure.exception))

    def test_vacuum_snapshot_of_active_wal_source_remains_supported(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "set"
            db = self.make_snapshot(folder)
            source = Path(temp) / "source.sqlite"
            db.rename(source)
            with sqlite3.connect(source) as conn:
                conn.execute("PRAGMA journal_mode=WAL")
                conn.execute("PRAGMA wal_autocheckpoint=0")
                conn.execute("UPDATE node_identity SET updated_at='2026-10-23T00:00:00Z'")
                conn.commit()
                self.assertTrue(Path(str(source) + "-wal").is_file())
                conn.execute("VACUUM INTO ?", (str(db),))
                self.assertEqual(source.read_bytes()[18:20], b"\x02\x02")
                self.assertEqual(db.read_bytes()[18:20], b"\x01\x01")
                db.chmod(0o600)
                self.update_manifest(db)
                self.assertEqual(module.verify(folder), 0)
                self.assertEqual({path.name for path in folder.iterdir()}, {"halo-main.sqlite", "manifest.json"})


if __name__ == "__main__":
    unittest.main()
