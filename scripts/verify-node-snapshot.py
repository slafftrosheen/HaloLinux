#!/usr/bin/env python3
"""Read-only verification of a HALO-LINUX-022 snapshot directory.

This tool never restores, overwrites, unlocks, or exports user data.
It verifies the manifest, SHA-256, SQLite quick_check, and POSIX privacy bits.
"""
import argparse
from contextlib import closing
import hashlib
import json
import re
import os
from pathlib import Path
import sqlite3
import stat
import sys
from urllib.parse import quote


def fail(message: str) -> None:
    raise ValueError(message)


def check_private(path: Path, directory: bool) -> None:
    if path.is_symlink():
        fail(f"Symbolic link not permitted: {path.name}")
    if not (path.is_dir() if directory else path.is_file()):
        fail(f"Missing {'directory' if directory else 'file'}: {path.name}")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        fail(f"Insecure permissions: {path.name} (group/other access)")
    if not (mode & 0o400):
        fail(f"Unreadable snapshot component: {path.name}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def check_main_schema(conn: sqlite3.Connection) -> None:
    """Validate the main v3 lineage; master used the same number for other tables."""
    tables = {
        row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }

    def require_columns(table: str, expected: set[str]) -> None:
        if table not in tables:
            fail(f"Main database schema table is missing: {table}")
        # Names are fixed schema identifiers below, never values from the archive.
        columns = {row[1] for row in conn.execute(f"PRAGMA table_info('{table}')")}
        if not expected <= columns:
            fail(f"Main database schema columns are incompatible: {table}")

    require_columns("schema_version", {"version", "applied_at", "description"})
    versions = [row[0] for row in conn.execute("SELECT version FROM schema_version ORDER BY version")]
    if versions != [1, 2, 3]:
        fail("Unsupported main database schema or incomplete migration history; expected versions 1, 2, 3")
    if "turn_checkpoints" not in tables and tables & {"agent_runs", "agent_run_events"}:
        fail("Incompatible legacy master schema 3; preserve this backup for a reviewed migration")
    required = {
        "conversations": {"id", "title", "created_at", "updated_at", "provider_id", "model_id", "metadata"},
        "messages": {"id", "conversation_id", "role", "content", "provider_id", "model_id",
                     "tokens_used", "latency_ms", "created_at", "metadata"},
        "memories": {"id", "content", "source", "scope", "confidence", "expires_at",
                     "created_at", "updated_at", "metadata"},
        "memories_fts": {"content", "source", "scope"},
        "node_identity": {"key", "value", "updated_at"},
        "sync_peers": {"id", "name", "public_key", "trust_state", "capabilities", "last_seen",
                       "created_at", "metadata"},
        "turn_checkpoints": {"interaction_id", "state", "created_at", "updated_at",
                             "conversation_id", "failure_type"},
    }
    for table, columns in required.items():
        require_columns(table, columns)


def check_standalone_sqlite(path: Path) -> None:
    """A snapshot component must not depend on a WAL or rollback journal."""
    with path.open("rb") as handle:
        header = handle.read(100)
    if len(header) != 100 or header[:16] != b"SQLite format 3\x00":
        fail(f"Invalid SQLite snapshot header: {path.name}")
    # SQLite documents offsets 18/19 as read/write format: 1=rollback, 2=WAL.
    # https://www.sqlite.org/fileformat2.html#file_format_version_numbers
    # Halo creates snapshots with VACUUM INTO, whose output is standalone even
    # when the source is in WAL mode. A copied live/WAL database is not this format.
    if header[18:20] != b"\x01\x01":
        fail(f"Snapshot database is not a standalone VACUUM image: {path.name}; preserve it and create a complete node backup")


def verify(folder: Path) -> int:
    folder = folder.absolute()
    check_private(folder, directory=True)
    manifest = folder / "manifest.json"
    check_private(manifest, directory=False)
    document = json.loads(manifest.read_text(encoding="utf-8"))
    if document.get("format") != "halo-node-snapshot-v1":
        fail("Unknown Halo node snapshot format")
    if document.get("encrypted") is not False or document.get("sensitive") is not True:
        fail("Missing snapshot sensitivity/encryption metadata")
    entries = document.get("files")
    if not isinstance(entries, list):
        fail("Manifest has no file list")
    expected = {"halo-main.sqlite"}
    if document.get("includes_canonical_agent_store") is True:
        expected.add("halo-agent.sqlite")
    elif document.get("includes_canonical_agent_store") is not False:
        fail("Invalid agent-store inclusion state")
    # Reject journals/sidecars before SQLite can inspect or recover any database.
    if {entry.name for entry in folder.iterdir()} != expected | {"manifest.json"}:
        fail("Unexpected or missing files in the snapshot directory")
    actual = set()
    for entry in entries:
        filename = entry.get("filename", "")
        if filename not in expected or filename in actual:
            fail("Unknown or repeated snapshot database")
        actual.add(filename)
        path = folder / filename
        check_private(path, directory=False)
        if entry.get("role") != ("halo-main" if filename == "halo-main.sqlite" else "canonical-agent"):
            fail(f"Incorrect database role: {filename}")
        if not isinstance(entry.get("bytes"), int) or entry["bytes"] <= 0:
            fail(f"Invalid recorded size: {filename}")
        expected_hash = entry.get("sha256")
        if not isinstance(expected_hash, str) or re.fullmatch(r"[0-9a-f]{64}", expected_hash) is None:
            fail(f"Invalid SHA-256 metadata: {filename}")
        if path.stat().st_size != entry["bytes"]:
            fail(f"Unexpected size: {filename}")
        if sha256(path) != expected_hash:
            fail(f"Checksum mismatch: {filename}")
        check_standalone_sqlite(path)
        # These validated standalone snapshots contain all committed state and
        # have no sidecars. Immutable mode prevents SQLite from creating any.
        # Never use this approach for a live Halo database or one with a WAL.
        uri = "file:" + quote(str(path), safe="/") + "?mode=ro&immutable=1"
        with closing(sqlite3.connect(uri, uri=True)) as conn:
            result = conn.execute("PRAGMA quick_check").fetchone()
            if result is None or result[0] != "ok":
                fail(f"SQLite integrity failed: {filename}")
            if filename == "halo-main.sqlite":
                check_main_schema(conn)
                node = conn.execute(
                    "SELECT value FROM node_identity WHERE key='node_id'"
                ).fetchone()
                if not node or node[0] != document.get("node_id"):
                    fail("Node identity does not match the backup manifest")
                for key in ("public_key", "private_key"):
                    record = conn.execute(
                        "SELECT value FROM node_identity WHERE key=?", (key,)
                    ).fetchone()
                    if not record or not record[0]:
                        fail("A required node key is missing from the backup")
            else:
                version = conn.execute(
                    "SELECT version FROM agent_schema LIMIT 1"
                ).fetchone()
                if not version or version[0] != 1:
                    fail("Unknown canonical agent backup schema")
                for table in ("agent_runs", "agent_steps"):
                    exists = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                        (table,),
                    ).fetchone()
                    if not exists:
                        fail("Canonical agent journal table is missing")
        print(f"PASS {filename}: checksum + SQLite integrity + schema")
    if actual != expected:
        fail("Snapshot missing one or more expected database files")
    directory_entries = {entry.name for entry in folder.iterdir()}
    if directory_entries != expected | {"manifest.json"}:
        fail("Unexpected or missing files in the snapshot directory")
    # A manifest can be valid without the referenced node still being available.
    print("PASS: snapshot files are internally consistent")
    print("CAUTION: unencrypted private data; no restore or cross-device compatibility test was run")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot_dir", type=Path)
    args = parser.parse_args()
    try:
        return verify(args.snapshot_dir)
    except (ValueError, OSError, sqlite3.Error, json.JSONDecodeError) as error:
        # Never dump database bodies or keys into diagnostics.
        print(f"FAIL: snapshot verification: {type(error).__name__}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
