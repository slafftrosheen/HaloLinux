"""Shared offline implementation of Halo's reviewed portable-source snapshot.

Only the production source roots in config/canonical-modules.txt are managed.
This validates provenance and bytes; it is not a Kotlin compatibility checker.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile

REPOSITORY = "https://github.com/slafftrosheen/Halo"
MODULES = "config/canonical-modules.txt"
PIN = "config/canonical-halo.sha"
MANIFEST = "config/canonical-sources.json"


def git(root: Path, *arguments: str) -> bytes:
    return subprocess.check_output(
        ["git", "-C", str(root), *arguments], stderr=subprocess.PIPE,
    )


def safe_root(root: Path) -> Path:
    root = root.absolute()
    for part in (root, *root.parents):
        if part.is_symlink():
            raise ValueError(f"Symlink in source checkout path: {part}")
    if not root.is_dir():
        raise ValueError(f"Source checkout is missing: {root}")
    return root.resolve()


def safe_relative(path: str) -> str:
    if (not isinstance(path, str) or not path or
            not re.fullmatch(r"[A-Za-z0-9_./-]+", path) or
            PurePosixPath(path).is_absolute() or
            any(part in ("", ".", "..") for part in path.split("/"))):
        raise ValueError(f"Unsafe managed source path: {path!r}")
    return path


def checked_path(root: Path, relative: str) -> Path:
    path = root
    for part in safe_relative(relative).split("/"):
        path = path / part
        if path.is_symlink():
            raise ValueError(f"Symlink in managed source path: {path}")
    return path


def modules(root: Path) -> tuple[str, ...]:
    result = tuple(line.split("#", 1)[0].strip()
                   for line in checked_path(root, MODULES).read_text().splitlines()
                   if line.split("#", 1)[0].strip())
    if not result or len(result) != len(set(result)):
        raise ValueError("Canonical module allowlist must be nonempty and unique")
    for module in result:
        if not re.fullmatch(r"(?:sdk|libraries|runtime)/[a-z][a-z0-9-]*", module):
            raise ValueError(f"Unsafe canonical module: {module}")
    return result


def roots(allowed: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(f"{module}/src/main/java" for module in allowed)


def revision(root: Path) -> str:
    value = checked_path(root, PIN).read_text().strip()
    validate_revision(value)
    return value


def validate_revision(value: str) -> None:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Canonical revision must be a full lowercase 40-character SHA")


def source_files(root: Path, allowed: tuple[str, ...]) -> dict[str, bytes]:
    files = {}
    for relative_root in roots(allowed):
        directory = checked_path(root, relative_root)
        if not directory.exists():
            continue
        if not directory.is_dir():
            raise ValueError(f"Expected a source directory: {relative_root}")
        for parent, directories, names in os.walk(directory, followlinks=False):
            for name in directories + names:
                path = checked_path(root, (Path(parent) / name).relative_to(root).as_posix())
                if name in directories:
                    continue
                if not path.is_file() or path.suffix not in (".kt", ".java"):
                    raise ValueError(f"Not a regular source file: {path.relative_to(root)}")
                files[path.relative_to(root).as_posix()] = path.read_bytes()
    return files


def hashes(files: dict[str, bytes]) -> dict[str, str]:
    return {path: hashlib.sha256(data).hexdigest() for path, data in files.items()}


def git_blob_id(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def compare(actual: dict[str, str], expected: dict[str, str]) -> list[str]:
    differences = []
    for path in sorted(actual.keys() | expected.keys()):
        if path not in actual:
            differences.append(f"missing: {path}")
        elif path not in expected:
            differences.append(f"unexpected: {path}")
        elif actual[path] != expected[path]:
            differences.append(f"changed: {path}")
    return differences


def read_manifest(root: Path, allowed: tuple[str, ...]) -> dict:
    document = json.loads(checked_path(root, MANIFEST).read_text(encoding="utf-8"))
    if (not isinstance(document, dict) or document.get("schemaVersion") != 1 or
            document.get("repository") != REPOSITORY or
            document.get("modules") != list(allowed)):
        raise ValueError("Invalid canonical manifest or changed module allowlist; review ownership first")
    validate_revision(document.get("revision", ""))
    if document["revision"] != revision(root):
        raise ValueError("Canonical source manifest differs from the revision pin")
    records = document.get("files")
    if not isinstance(records, list) or not records:
        raise ValueError("Canonical manifest has no source files")
    found = set()
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("Invalid canonical manifest file record")
        path = safe_relative(record.get("path"))
        if (not any(path.startswith(directory + "/") for directory in roots(allowed)) or
                PurePosixPath(path).suffix not in (".kt", ".java")):
            raise ValueError(f"Unsafe path outside reviewed production source roots: {path}")
        digest = record.get("sha256", "")
        if path in found or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"Duplicate source path or invalid SHA-256: {path}")
        found.add(path)
    if any(not any(path.startswith(directory + "/") for path in found) for directory in roots(allowed)):
        raise ValueError("Manifest omits a reviewed source module")
    return document


def manifest_hashes(document: dict) -> dict[str, str]:
    return {item["path"]: item["sha256"] for item in document["files"]}


def upstream_snapshot(upstream: Path, sha: str, allowed: tuple[str, ...]) -> dict[str, bytes]:
    """Require checked-out production bytes and index to match the named Git commit."""
    upstream = safe_root(upstream)
    validate_revision(sha)
    top = Path(git(upstream, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    actual = git(upstream, "rev-parse", "HEAD").decode().strip()
    if top != upstream or actual != sha:
        raise ValueError(f"Canonical checkout revision is {actual}; expected {sha}")
    if not checked_path(upstream, "settings.gradle.kts").is_file():
        raise ValueError("Canonical checkout has no settings.gradle.kts")
    if git(upstream, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", *roots(allowed)):
        raise ValueError("Dirty canonical production sources or index; use a clean detached worktree")
    tree = git(upstream, "ls-tree", "-r", "-z", "--full-tree", sha, "--", *roots(allowed))
    committed = {}
    for entry in tree.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, kind, object_sha = metadata.decode().split(" ")
        path = safe_relative(raw_path.decode("utf-8"))
        if mode not in ("100644", "100755") or kind != "blob":
            raise ValueError(f"Not a regular source in canonical Git tree: {path}")
        committed[path] = object_sha
    actual_files = source_files(upstream, allowed)
    if set(actual_files) != set(committed):
        raise ValueError("Canonical checkout differs from committed production file inventory")
    for directory in roots(allowed):
        if not any(path.startswith(directory + "/") for path in committed):
            raise ValueError(f"Canonical revision has no reviewed sources: {directory}")
    for path, data in actual_files.items():
        # Git's SHA-1 object identity verifies checkout bytes against the pinned tree;
        # the checked-in snapshot manifest independently records SHA-256 for each file.
        object_id = git_blob_id(data)
        if object_id != committed[path]:
            raise ValueError(f"Canonical checkout differs from committed bytes: {path}")
        if re.search(r"(?m)^\s*import\s+(?:android|androidx)\.", data.decode("utf-8")):
            raise ValueError(f"Android API import requires a portability review: {path}")
    return actual_files


def verify(root: Path, upstream: Path | None = None, *, include_cognition: bool = False) -> list[str]:
    if include_cognition and upstream is None:
        raise ValueError("Canonical cognition verification requires an external checkout")
    root = safe_root(root)
    allowed = modules(root)
    document = read_manifest(root, allowed)
    expected = manifest_hashes(document)
    differences = compare(hashes(source_files(root, allowed)), expected)
    if upstream is not None:
        canonical = upstream_snapshot(upstream, document["revision"], allowed)
        differences += ["manifest/upstream " + line for line in compare(expected, hashes(canonical))]
        if include_cognition:
            # This module is compiled only from the external checkout; it is not
            # part of the vendored SDK snapshot. Verify every compiled byte too.
            upstream_snapshot(upstream, document["revision"], ("runtime/cognition",))
    return differences


def write_changed(root: Path, relative: str, data: bytes) -> None:
    destination = checked_path(root, relative)
    if destination.is_file() and destination.read_bytes() == data:
        return
    if destination.exists() and not destination.is_file():
        raise ValueError(f"Cannot replace non-file: {relative}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".halo-sync-", dir=destination.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
        os.chmod(name, 0o644)
        os.replace(name, destination)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def synchronize(root: Path, upstream: Path, sha: str) -> int:
    """Explicit update; validate both checkouts completely before writing anything."""
    root, upstream = safe_root(root), safe_root(upstream)
    if root == upstream or root in upstream.parents or upstream in root.parents:
        raise ValueError("Canonical and Linux checkouts must be separate directories")
    allowed = modules(root)
    incoming = upstream_snapshot(upstream, sha, allowed)
    actual = source_files(root, allowed)
    manifest_path = checked_path(root, MANIFEST)
    if manifest_path.exists():
        previous = read_manifest(root, allowed)
        drift = compare(hashes(actual), manifest_hashes(previous))
        if drift:
            raise ValueError("Local source drift; preserve/review edits before synchronization:\n" + "\n".join(drift))
        old_paths = set(manifest_hashes(previous))
    else:
        # The one-time migration may replace tracked bootstrap copies, but must
        # never silently destroy a developer's dirty or untracked source files.
        if git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", *roots(allowed)):
            raise ValueError("Uncommitted bootstrap source changes; preserve them before synchronization")
        tracked = {}
        for entry in git(root, "ls-files", "--stage", "-z", "--", *roots(allowed)).split(b"\0"):
            if entry:
                metadata, path = entry.split(b"\t", 1)
                mode, object_sha, stage = metadata.decode().split(" ")
                if mode not in ("100644", "100755") or stage != "0":
                    raise ValueError("Uncommitted bootstrap source state requires review")
                tracked[path.decode("utf-8")] = object_sha
        if any(tracked.get(path) != git_blob_id(data) for path, data in actual.items()):
            raise ValueError("Uncommitted bootstrap source bytes; preserve them before synchronization")
        if set(actual) - set(incoming):
            raise ValueError("Bootstrap contains local-only sources; review ownership before synchronization")
        old_paths = set(actual)
    for relative in (*incoming.keys(), *old_paths, PIN, MANIFEST):
        destination = checked_path(root, relative)
        if destination.exists() and not destination.is_file():
            raise ValueError(f"Cannot replace non-file: {relative}")
    for path, content in sorted(incoming.items()):
        write_changed(root, path, content)
    for path in sorted(old_paths - incoming.keys()):
        checked_path(root, path).unlink()
    document = {
        "schemaVersion": 1,
        "repository": REPOSITORY,
        "revision": sha,
        "modules": list(allowed),
        "files": [{"path": path, "sha256": digest} for path, digest in sorted(hashes(incoming).items())],
    }
    write_changed(root, PIN, (sha + "\n").encode())
    write_changed(root, MANIFEST, (json.dumps(document, indent=2) + "\n").encode())
    return len(incoming)
