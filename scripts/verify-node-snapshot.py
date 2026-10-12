#!/usr/bin/env python3
"""Read-only verification of a HALO-LINUX-022 snapshot directory.

This tool never restores, overwrites, unlocks, or exports user data.
It verifies the manifest, SHA-256, SQLite quick_check, and POSIX privacy bits.
"""
import argparse
from contextlib import closing
import hashlib
import json
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
    actual = set()
    for entry in entries:
        filename = entry.get("filename", "")
        if filename not in expected or filename in actual:
            fail("Unknown or repeated snapshot database")
        actual.add(filename)
        path = folder / filename
        check_private(path, directory=False)
        if path.stat().st_size != entry.get("bytes"):
            fail(f"Unexpected size: {filename}")
        if sha256(path) != entry.get("sha256"):
            fail(f"Checksum mismatch: {filename}")
        # Open SQLite in read-only mode; this is a VACUUM snapshot (no WAL needed).
        uri = "file:" + quote(str(path), safe="/") + "?mode=ro"
        with closing(sqlite3.connect(uri, uri=True)) as conn:
            result = conn.execute("PRAGMA quick_check").fetchone()
            if result is None or result[0] != "ok":
                fail(f"SQLite integrity failed: {filename}")
        print(f"PASS {filename}: checksum + SQLite integrity")
    if actual != expected:
        fail("Snapshot missing one or more expected database files")
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
