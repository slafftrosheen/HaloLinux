# Canonical Storage on Linux — HALO-LINUX-056

The Linux runtime now registers an implementation of Android Halo's unchanged
`com.halo.sdk.runtime.Storage` service. The implementation is a private, durable
key-value store. This is **not** the Android profile/permissions database, the
Halo Brain importer, a secrets manager, or cross-device synchronization.

## Placement and lifecycle

`LinuxRuntimeBridge.start()` constructs `LinuxPreferenceStorage` from the existing
`LinuxStorage.getConfigDirectory()` result, **after** SQLite schema and XDG private
path checks. The bridge registers it by canonical `ServiceKey(Storage::class)`
before starting engines. No new user-facing daemon command, automatic migration,
network listener or background polling service is introduced.

The data directory is the existing private XDG config folder, normally
`~/.config/halo-linux` under the account running `halo-node`:

- `preferences.v1`: checksummed version-1 preference snapshot (created on first
  effective write; contains **plaintext** user settings).
- `preferences.lock`: stable owner-only interprocess lock file, deliberately
  retained after use. Read operations can create this file.
- `.halo-prefs-*.pending`: temporary owner-only files while atomic replacement
  is in progress; not used for recovery or read-back.

An existing `config.yaml` and the `halo.db` node database are not touched by this
adapter. The existing Linux directory validation requires mode **0700**. Data,
lock and temporary files must have **0600**, belong to the current Unix user,
and have one hard link. Symlinks in the path and redirected data/lock files are
rejected; the adapter never changes permissions on existing unknown files.

## Data and transactional behavior

The adapter follows the canonical contract for string (including explicit null),
32-bit signed integer, boolean, remove and clear. A missing key returns the
caller's default; querying an existing key using a different type is an error,
not an implicit conversion. `putBatch(strings, booleans)` validates every key and
value before I/O, refuses a key supplied under both types, and replaces the
complete snapshot atomically or leaves the previous snapshot intact.

Every access takes the retained sidecar lock across JVMs and an in-process
per-directory mutex. Writes use a restricted 0600 temporary file, a checked
SHA-256 envelope, file `force(true)`, atomic rename and directory `force(true)`.
Existing data is never rewritten in place. Corruption, bad permissions,
unsupported schema, partial reads and hard links fail closed rather than
returning defaults or overwriting data. Neither user values nor filenames are
included in persistence errors or logs.

Bounds: **2048 keys**, **128 UTF-8 bytes per key**, **65536 UTF-8 bytes per
string**, and **1 MiB total snapshot**. Access is synchronous, so callers must
keep it off audio callbacks, the UI and other latency-critical threads. No
automatic pruning, database migrations or credential migration is performed.

The file checksum detects accidental corruption but is not cryptographic
proof of who produced the data. Files are not encrypted at rest, keys and values
may remain in process memory or swap, and temporary-file deletion is not secure
wiping. **Do not use this store as a provider API-key vault.** Keep credentials
in protected provider-specific storage/environment as already documented.

## Recovery and system operation

A kill between writing a temporary file and replacing `preferences.v1` retains
**the previous committed snapshot**; it can leave `.halo-prefs-*.pending` behind
with plaintext. The adapter ignores such unfinished files when reading. To clean
one up, stop every Halo process using this config directory, inspect the exact
filenames and ownership, back up important data, and delete *only* confirmed
abandoned Halo preference pending files. Do not delete `preferences.lock` during
operation or recursively delete the config directory. Never automatically recover
or overwrite a corrupt `preferences.v1`—preserve it and use a known-good copy.

Filesystem durability is limited to the Linux/JDK/fsync/rename semantics of the
chosen filesystem. A test process using `Runtime.halt` is not a power-cut
experiment. Validate on the Pi NVMe/filesystem before claiming power-loss safety.

## Verification

The focused gate compiles the canonical `Storage.kt` plus the full new adapter
and exercises the result with JDK 21 processes. A separate static check confirms
the bridge registration. It is not a complete Gradle/runtime test:

```bash
/usr/bin/python3 -B -W error::ResourceWarning -m unittest discover \
  -s scripts -p 'test_linux_preferences.py'
```

Before installation on Raspberry Pi OS Trixie, also run:

```bash
python3 -B scripts/sync-canonical-sources.py
./scripts/preflight.sh --build
./scripts/verify-offline-source.sh
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
```

Run the optional canonical-cognition gate independently when deploying that
variant. Verify prefs survive a real daemon restart, logout and reboot and do
not alter the existing node identity or backups. Those Pi/hardware/full-build
gates are **not** claimed by HALO-LINUX-056.
