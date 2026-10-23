#!/usr/bin/env python3
"""Read-only Android Halo schema-2 archive inspection. Never extracts or imports.

See docs/ANDROID-ARCHIVE-INTEROP.md for the compatibility pin, limits and threat
boundary. Encrypted archives require the distro's python3-cryptography package.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
from dataclasses import dataclass
import getpass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import struct
import sys
import tempfile
from typing import BinaryIO
import warnings
import zipfile
import zlib

MAGIC = b"HALOBRAIN"
CHUNK = 64 * 1024
SECTION_PATHS = {
    "workspace": ("workspace/objects.jsonl",),
    "notes": ("notes/metadata.jsonl",),
    "conversations": ("conversations/conversations.jsonl", "conversations/turns.jsonl"),
    "memory": ("memory/memories.jsonl", "memory/legacy.jsonl", "memory/revisions.jsonl"),
    "working-set": ("working-set/entries.jsonl",),
    "relations": ("relations/relations.jsonl",),
    "settings": ("settings/user-settings.json",),
    "reminders": ("reminders/reminders.jsonl",),
    "devices": ("devices/configuration.jsonl",),
    "files": ("files/index.jsonl",),
    "agent": ("agent/runs.jsonl", "agent/steps.jsonl"),
}
SUPPLEMENTAL = frozenset(("workspace/provenance.jsonl", "memory/episodes.jsonl",
    "memory/suggestion-decisions.jsonl", "memory/reflection-statuses.jsonl"))
RECORD_PATHS = frozenset(p for paths in SECTION_PATHS.values() for p in paths if p.endswith(".jsonl")) | SUPPLEMENTAL
METADATA = {"manifest.json": 1024 * 1024, "checksums.sha256": 16 * 1024 * 1024,
    "archive.complete": 4096, "settings/user-settings.json": 1024 * 1024}
FIXED_PATHS = RECORD_PATHS | set(METADATA) | {"README.md", "memory/memories.md"}
PROFILES = frozenset(("BRAIN_ONLY", "BRAIN_WITH_FILES", "FULL_USER_BACKUP"))


@dataclass(frozen=True)
class Limits:
    max_archive_bytes: int = 2 * 1024**3
    max_entries: int = 50_000
    max_entry_bytes: int = 512 * 1024**2
    max_expanded_bytes: int = 4 * 1024**3
    max_central_bytes: int = 16 * 1024**2
    max_record_bytes: int = 1024**2
    max_compression_ratio: int = 200
    max_json_depth: int = 64


class ArchiveError(Exception):
    """Messages are fixed, public diagnostics; never include input data."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ArchiveError(message)


def exact(stream: BinaryIO, count: int) -> bytes:
    data = stream.read(count)
    require(len(data) == count, "Truncated archive.")
    return data


def path_allowed(name: str) -> None:
    require(0 < len(name) <= 240 and all(re.fullmatch(r"[A-Za-z0-9_.-]+", p) and p not in (".", "..") for p in name.split("/")), "Unsafe archive path.")
    parts = name.split("/")
    markdown = len(parts) == 2 and parts[0] in ("notes", "conversations") and parts[1].endswith(".md")
    attachment = len(parts) == 4 and parts[:2] == ["files", "content"]
    require(name in FIXED_PATHS or markdown or attachment, "Unsupported archive entry.")


def _snapshot(path: Path, output: BinaryIO, limits: Limits) -> tuple[int, str]:
    # O_NONBLOCK prevents a malicious FIFO from blocking before fstat rejects it.
    descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        before = os.fstat(source.fileno())
        require(stat.S_ISREG(before.st_mode), "Input must be a regular file, not a symlink or device.")
        require(0 < before.st_size <= limits.max_archive_bytes, "Archive size limit exceeded or empty input.")
        digest, count = hashlib.sha256(), 0
        while block := source.read(CHUNK):
            count += len(block)
            require(count <= limits.max_archive_bytes, "Archive size limit exceeded.")
            digest.update(block)
            output.write(block)
        after = os.fstat(source.fileno())
        require(count == before.st_size and (before.st_size, before.st_mtime_ns, before.st_ctime_ns) == (after.st_size, after.st_mtime_ns, after.st_ctime_ns), "Source changed during inspection.")
    output.flush()
    output.seek(0)
    return count, digest.hexdigest()


def _decrypt(source: BinaryIO, output: BinaryIO, size: int, password: bytes | bytearray | None) -> None:
    require(password is not None and 0 < len(password) <= 4096, "Encrypted archive requires a password (hidden prompt or --password-stdin).")
    try:
        bytes(password).decode("utf-8", errors="strict")
    except UnicodeError:
        raise ArchiveError("Password must be valid UTF-8.") from None
    require(exact(source, 9) == MAGIC, "Invalid encrypted archive header.")
    version, iterations, salt_length = struct.unpack(">iii", exact(source, 12))
    require(version == 1, "Unsupported encrypted envelope version.")
    require(100_000 <= iterations <= 2_000_000, "Invalid archive KDF work factor.")
    require(16 <= salt_length <= 64, "Invalid archive salt length.")
    salt = exact(source, salt_length)
    iv_length = struct.unpack(">i", exact(source, 4))[0]
    require(12 <= iv_length <= 32, "Invalid archive nonce length.")
    iv = exact(source, iv_length)
    header_end = source.tell()
    require(size >= header_end + 16, "Truncated encrypted archive.")
    source.seek(0)
    aad = exact(source, header_end)
    source.seek(size - 16)
    tag = exact(source, 16)
    source.seek(header_end)
    try:
        from cryptography.exceptions import InvalidTag
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except ImportError:
        raise ArchiveError("Encrypted inspection needs python3-cryptography; install the distro package.") from None
    key = hashlib.pbkdf2_hmac("sha256", password, salt, iterations, dklen=32)
    decryptor = Cipher(algorithms.AES(key), modes.GCM(iv, tag)).decryptor()
    decryptor.authenticate_additional_data(aad)
    remaining = size - header_end - 16
    try:
        while remaining:
            chunk = exact(source, min(CHUNK, remaining))
            remaining -= len(chunk)
            output.write(decryptor.update(chunk))
        output.write(decryptor.finalize())
    except InvalidTag:
        raise ArchiveError("Wrong password or damaged encrypted archive.") from None
    finally:
        del key  # Python/OpenSSL copies cannot be guaranteed to be zeroized.
    # No ZIP parsing, reporting, or restoration before finalize authenticates all bytes.
    output.flush()
    output.seek(0)


def _directory_bounds(stream: BinaryIO, limits: Limits) -> tuple[int, int]:
    stream.seek(0, os.SEEK_END)
    size = stream.tell()
    require(size <= limits.max_archive_bytes, "Archive size limit exceeded.")
    stream.seek(max(0, size - 65_557))
    tail = stream.read(65_557)
    position = tail.rfind(b"PK\x05\x06")
    require(position >= 0 and position + 22 <= len(tail), "Missing ZIP central directory.")
    _, disk, start_disk, disk_count, count, central_size, central_offset, comment_size = struct.unpack_from("<4s4H2IH", tail, position)
    require(position + 22 + comment_size == len(tail), "Truncated ZIP or trailing data.")
    require(disk == start_disk == 0 and disk_count == count, "Multipart ZIP is not supported.")
    require(0 < count <= limits.max_entries and count != 0xffff, "ZIP entry limit exceeded or ZIP64 unsupported.")
    require(central_size <= limits.max_central_bytes and central_size != 0xffffffff, "ZIP central directory limit exceeded.")
    absolute_end = size - len(tail) + position
    require(central_offset + central_size == absolute_end and central_offset < absolute_end, "Invalid ZIP directory bounds or unsupported ZIP64.")
    return count, central_offset


def _layout(stream: BinaryIO, entries: list[zipfile.ZipInfo], central_offset: int, limits: Limits) -> int:
    names: set[str] = set()
    folded: set[str] = set()
    expected_offset, total = 0, 0
    ordered = sorted(entries, key=lambda entry: entry.header_offset)
    require(ordered[-1].filename == "archive.complete", "Archive completion marker must be last.")
    for entry in ordered:
        name = entry.filename
        path_allowed(name)
        require(entry.orig_filename == name and name not in names and name.lower() not in folded, "Duplicate or ambiguous archive path.")
        names.add(name); folded.add(name.lower())
        kind = stat.S_IFMT(entry.external_attr >> 16)
        require(not entry.is_dir() and kind in (0, stat.S_IFREG), "Archive links, directories and special files are not supported.")
        require(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) and not entry.flag_bits & ~0x0808, "Unsupported ZIP compression or encryption flags.")
        require(0 <= entry.file_size <= limits.max_entry_bytes and entry.compress_size >= 0, "ZIP entry size limit exceeded.")
        require(entry.file_size <= max(1, entry.compress_size) * limits.max_compression_ratio, "ZIP compression ratio limit exceeded.")
        total += entry.file_size
        require(total <= limits.max_expanded_bytes, "ZIP expanded size limit exceeded.")
        require(entry.header_offset == expected_offset, "ZIP contains gaps, overlapping entries or a prefix.")
        stream.seek(entry.header_offset)
        values = struct.unpack("<4s5H3I2H", exact(stream, 30))
        signature, _, flags, method, _, _, crc, compressed, expanded, name_size, extra_size = values
        require(signature == b"PK\x03\x04" and flags == entry.flag_bits and method == entry.compress_type, "ZIP local header mismatch.")
        raw_name = exact(stream, name_size)
        local_name = raw_name.decode("utf-8" if flags & 0x800 else "cp437", errors="strict")
        require(local_name == name, "ZIP local filename mismatch.")
        exact(stream, extra_size)
        expected_offset = stream.tell() + entry.compress_size
        require(expected_offset <= central_offset, "ZIP entry extends into central directory.")
        if flags & 8:
            require(crc in (0, entry.CRC) and compressed in (0, entry.compress_size) and expanded in (0, entry.file_size), "ZIP descriptor header mismatch.")
            stream.seek(expected_offset)
            first = exact(stream, 4)
            descriptor = exact(stream, 12) if first == b"PK\x07\x08" else first + exact(stream, 8)
            require(struct.unpack("<III", descriptor) == (entry.CRC, entry.compress_size, entry.file_size), "ZIP data descriptor mismatch.")
            expected_offset = stream.tell()
        else:
            require((crc, compressed, expanded) == (entry.CRC, entry.compress_size, entry.file_size), "ZIP local size or CRC mismatch.")
    require(expected_offset == central_offset, "Unexpected data before ZIP central directory.")
    return total


def _json_object(data: bytes, limits: Limits) -> dict:
    # Bound nesting before json.loads to avoid an adversarial recursive decoder.
    depth, in_string, escaped = 0, False, False
    for value in data:
        if in_string:
            if escaped: escaped = False
            elif value == 92: escaped = True
            elif value == 34: in_string = False
        elif value == 34: in_string = True
        elif value in (91, 123):
            depth += 1
            require(depth <= limits.max_json_depth, "JSON nesting limit exceeded.")
        elif value in (93, 125): depth -= 1

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON key.")
            result[key] = value
        return result

    def invalid_constant(_):
        raise ArchiveError("Non-finite JSON number.")

    value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=pairs, parse_constant=invalid_constant)
    require(isinstance(value, dict), "Expected a JSON object.")
    stack = [value]
    while stack:
        item = stack.pop()
        if isinstance(item, dict): stack.extend(item.values())
        elif isinstance(item, list): stack.extend(item)
        elif isinstance(item, float): require(math.isfinite(item), "Non-finite JSON number.")
    return value


def _manifest(data: bytes, encrypted: bool, limits: Limits) -> tuple[dict, dict[str, int]]:
    manifest = _json_object(data, limits)
    require(manifest.get("format") == "halo-brain", "Not a Halo Brain archive.")
    require(type(manifest.get("schemaVersion")) is int and manifest["schemaVersion"] == 2, "Only Halo Brain schema 2 is supported.")
    require(manifest.get("state") == "COMPLETE", "Archive is not complete.")
    require(manifest.get("profile") in PROFILES, "Unsupported archive profile.")
    require(type(manifest.get("encrypted")) is bool and manifest["encrypted"] == encrypted, "Encryption metadata does not match archive envelope.")
    archive_id = manifest.get("archiveId")
    require(isinstance(archive_id, str) and 0 < len(archive_id) <= 128 and all(ord(c) >= 32 for c in archive_id), "Invalid archive identifier.")
    for field in ("createdAtEpochMs", "appVersionCode", "databaseSchemaVersion"):
        number = manifest.get(field)
        require(type(number) is int and 0 <= number <= 2**63 - 1, "Invalid manifest numeric metadata.")
    require(isinstance(manifest.get("appVersionName"), str) and len(manifest["appVersionName"]) <= 256, "Invalid app version metadata.")
    sections = manifest.get("sections")
    require(isinstance(sections, list) and len(sections) <= len(SECTION_PATHS), "Invalid archive section list.")
    counts = {}
    for section in sections:
        require(isinstance(section, dict), "Invalid archive section.")
        name, count = section.get("name"), section.get("itemCount")
        require(isinstance(name, str) and name in SECTION_PATHS and name not in counts, "Unsupported or duplicate archive section.")
        version = section.get("version", 1)
        require(type(version) is int and version == 1, "Unsupported section version.")
        require(type(count) is int and 0 <= count <= 2**31 - 1, "Invalid section item count.")
        counts[name] = count
    require(set(SECTION_PATHS) - {"agent"} <= counts.keys(), "Required archive section is missing.")
    require(manifest["profile"] != "FULL_USER_BACKUP" or "agent" in counts, "Full backup requires the agent section.")
    return manifest, counts


def _inspect_zip(stream: BinaryIO, encrypted: bool, limits: Limits) -> dict:
    count, central_offset = _directory_bounds(stream, limits)
    with zipfile.ZipFile(stream) as archive:
        entries = archive.infolist()
        require(len(entries) == count, "ZIP entry count mismatch.")
        total = _layout(stream, entries, central_offset, limits)
        metadata, digests, records = {}, {}, {}
        expanded_total = 0
        for entry in entries:
            digest, length, stored = hashlib.sha256(), 0, bytearray()
            with archive.open(entry) as source:
                while True:
                    block = source.readline(limits.max_record_bytes + 1) if entry.filename in RECORD_PATHS else source.read(CHUNK)
                    if not block: break
                    length += len(block); expanded_total += len(block)
                    require(length <= min(entry.file_size, limits.max_entry_bytes) and expanded_total <= limits.max_expanded_bytes, "Expanded entry exceeds size limit.")
                    digest.update(block)
                    if entry.filename in RECORD_PATHS:
                        require(len(block) <= limits.max_record_bytes, "JSON record size limit exceeded.")
                        _json_object(block, limits)
                        records[entry.filename] = records.get(entry.filename, 0) + 1
                    elif entry.filename in METADATA:
                        require(length <= METADATA[entry.filename], "Archive metadata size limit exceeded.")
                        stored.extend(block)
            require(length == entry.file_size, "ZIP expanded length mismatch.")
            digests[entry.filename] = digest.hexdigest()
            if entry.filename in METADATA: metadata[entry.filename] = bytes(stored)
            if entry.filename in RECORD_PATHS: records.setdefault(entry.filename, 0)
        require(expanded_total == total and set(METADATA) <= metadata.keys(), "Required archive metadata is missing.")
        manifest, counts = _manifest(metadata["manifest.json"], encrypted, limits)
        _json_object(metadata["settings/user-settings.json"], limits)
        records["settings/user-settings.json"] = 1
        for section, expected in counts.items():
            paths = SECTION_PATHS[section]
            require(all(path in digests for path in paths), "Required section data is missing.")
            require(sum(records[path] for path in paths) == expected, "Section record count mismatch.")
        require("agent" in counts or not (set(SECTION_PATHS["agent"]) & digests.keys()), "Undeclared agent history.")
        attachments = sum(name.startswith("files/content/") for name in digests)
        require(manifest["profile"] != "BRAIN_ONLY" or attachments == 0, "Brain-only archive includes file payloads.")
        checksums = metadata["checksums.sha256"]
        require(checksums.endswith(b"\n"), "Invalid checksum list.")
        expected_hashes = {}
        for line in checksums.splitlines():
            match = re.fullmatch(rb"([0-9a-f]{64})  ([A-Za-z0-9_./-]+)", line)
            require(match is not None, "Invalid checksum record.")
            digest, name = match.group(1).decode("ascii"), match.group(2).decode("ascii")
            path_allowed(name)
            require(name not in expected_hashes and name not in ("checksums.sha256", "archive.complete"), "Duplicate or recursive checksum record.")
            expected_hashes[name] = digest
        actual = {name: digest for name, digest in digests.items() if name not in ("checksums.sha256", "archive.complete")}
        require(expected_hashes == actual, "Archive checksum mismatch or incomplete checksum coverage.")
        complete = _json_object(metadata["archive.complete"], limits)
        require(complete.get("archiveId") == manifest["archiveId"] and complete.get("manifestSha256") == digests["manifest.json"] and complete.get("checksumListSha256") == digests["checksums.sha256"], "Archive completion proof is invalid.")
        return {"integrity": "verified", "schema_version": 2, "profile": manifest["profile"], "encrypted": encrypted,
            "entries": count, "expanded_bytes": total, "attachments": attachments, "sections": counts,
            "record_counts": records, "record_import": "unsupported", "record_semantics": "not_validated"}


def inspect_archive(path: Path | str, password: bytes | bytearray | None = None, *, limits: Limits = Limits(), temp_dir: Path | str | None = None) -> dict:
    """Inspect a private snapshot; never open a node database or extract a path."""
    require(all(type(value) is int and value > 0 for value in vars(limits).values()), "Inspection limits must be positive integers.")
    try:
        with ExitStack() as stack:
            snapshot = stack.enter_context(tempfile.TemporaryFile(mode="w+b", dir=temp_dir))
            size, source_hash = _snapshot(Path(path), snapshot, limits)
            encrypted = snapshot.read(len(MAGIC)) == MAGIC
            snapshot.seek(0)
            stream = snapshot
            if encrypted:
                stream = stack.enter_context(tempfile.TemporaryFile(mode="w+b", dir=temp_dir))
                _decrypt(snapshot, stream, size, password)
            result = _inspect_zip(stream, encrypted, limits)
            return {**result, "source_bytes": size, "source_sha256": source_hash}
    except ArchiveError:
        raise
    except (OSError, ValueError, TypeError, KeyError, UnicodeError, OverflowError, RecursionError, struct.error, zipfile.BadZipFile, zlib.error, NotImplementedError):
        raise ArchiveError("Archive is unreadable, malformed, or exceeds supported limits.") from None


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default includes rejected arguments, which could be a password.
        raise ArchiveError("Invalid arguments. Use --help; passwords are never accepted as arguments.")


def main(argv: list[str] | None = None) -> int:
    parser = _Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--json", action="store_true", help="Print a content-free JSON inventory")
    parser.add_argument("--password-stdin", action="store_true", help="Read one UTF-8 password line from stdin")
    parser.add_argument("--temp-dir", type=Path, help="Existing private staging filesystem (default: system temporary directory)")
    password = None
    try:
        args = parser.parse_args(argv)
        if args.password_stdin:
            password = bytearray(sys.stdin.buffer.readline(4099))
            if password.endswith(b"\n"): password.pop()
            if password.endswith(b"\r"): password.pop()
            require(0 < len(password) <= 4096, "Password must contain 1 to 4096 UTF-8 bytes.")
        try:
            result = inspect_archive(args.archive, password, temp_dir=args.temp_dir)
        except ArchiveError as error:
            if password is not None or not sys.stdin.isatty() or str(error) != "Encrypted archive requires a password (hidden prompt or --password-stdin).":
                raise
            # Prevent getpass falling back to an echoing stdin read.
            with open("/dev/tty", "r+") as tty:
                with warnings.catch_warnings():
                    warnings.simplefilter("error", getpass.GetPassWarning)
                    password = bytearray(getpass.getpass("Archive password: ", stream=tty).encode("utf-8"))
            result = inspect_archive(args.archive, password, temp_dir=args.temp_dir)
        if args.json:
            print(json.dumps(result, sort_keys=True, ensure_ascii=True))
        else:
            print(f"Archive integrity verified: {result['profile']}; schema 2; encrypted={result['encrypted']}.")
            print(f"Entries: {result['entries']}; expanded bytes: {result['expanded_bytes']}; attachments: {result['attachments']}.")
            print("Record import is not supported. Record semantics and peer trust have not been validated.")
        return 0
    except ArchiveError as error:
        print(f"halo-brain: {error}", file=sys.stderr)
        return 2
    except (OSError, UnicodeError, EOFError, getpass.GetPassWarning):
        print("halo-brain: Unable to read input or password securely.", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("halo-brain: Inspection cancelled.", file=sys.stderr)
        return 130
    finally:
        if password is not None: password[:] = b"\0" * len(password)


if __name__ == "__main__":
    raise SystemExit(main())
