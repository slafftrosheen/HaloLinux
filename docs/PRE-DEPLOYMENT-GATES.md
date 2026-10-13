# HaloLinux pre-deployment acceptance

Target for the initial deployment: **Raspberry Pi 5 8 GB, Raspberry Pi OS
Trixie 64-bit ARM64 with Desktop**, not x86-64.

## Gate A — build (BLOCKING)
From a Pi desktop Terminal (or independent Linux developer machine):

```bash
./gradlew --version
./gradlew clean build --no-daemon
./gradlew test --no-daemon
./gradlew :host:linux:shadowJar --no-daemon
```

No skipped compilation failures. Save the complete console log on failure.

## Gate B — isolated smoke (BLOCKING)
```bash
./scripts/smoke-test.sh
```

It exercises the real daemon, versioned local Unix IPC, persistence, SQLite FTS5,
integrity checks, sensitive database snapshot, identity across restart and cleanup.
This script does not touch installed user data or require a model.

## Gate C — desktop installation (BLOCKING)
```bash
./scripts/preflight.sh
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node --version
~/.local/bin/halo-node doctor
```

Edit the configuration in the GUI, start the daemon manually, then validate it
as a user-level systemd service. Do not run both manual and managed daemon at once.

## Gate D — inference (OPTIONAL for memory-only node)
Run one real configured model call; log **latency, RAM and thermal state**, and
record provider/model identity. Do not substitute fixture output for proof of inference.
A model-free storage node is a legitimate deployment mode.

## Gate E — headless transition (DEFERRED)
Do not change boot targets or enable lingering during the initial desktop test.
Confirm user-service lifetimes, XDG storage and secret injection before going headless.
See `docs/TRIXIE-HEADLESS-MIGRATION.md`.

## Gate F — hardware (UNVERIFIED)
Check Pi voltage/power supply, thermal behaviour, NVMe mount and disk pressure.
Verify actual HiFiBerry card name, I2S overlays, capture route and playback; enumeration
alone does not prove streaming audio.

## Gate G — privacy and provenance (BLOCKING before public alpha)
Review SQLite DB and WAL/SHM permissions, backups and their sensitive private
node identity. Check SDK copy licensing/provenance and move to versioned shared
contracts. Peer pairing/sync cannot be advertised as implemented yet.

All gates are **pending actual execution** unless logs from the target device are supplied.

## Optional gate H — HALO-LINUX-019 canonical recovery (DO NOT ENABLE ON PI YET)

With a SHA-pinned separate Halo checkout, run:

```bash
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

Pass the actual canonical cognition, SQLite agent store and optional host tests
before building an experimental agent-enabled JAR. The standard Pi evaluation
build omits this experimental integration. No automatic agent actions are enabled.

**Backup warning:** the existing main storage backup command does **not** back up
the separate `canonical-agent-runs.db`. Both private databases need a
coordinated backup/recovery plan. See `docs/CANONICAL-AGENT-STORE.md`.

## Full-node private backup (HALO-LINUX-022)

After the daemon is running, create a private snapshot directory parent
(`mkdir -m 700 ~/halo-backups`), then use:

```bash
halo-node node backup "$HOME/halo-backups/snapshot-001"
```

The operation holds the daemon's write gate, so user text turns and memory
mutations cannot straddle the two SQLite online snapshots. It creates a
*new* 0700 directory, 0600 snapshots, and a SHA-256 manifest. The optional
canonical agent journal is included only when linked.

**This is not encrypted** and does not provide a cross-process database
transaction, a backup of installed model binaries, or a complete external
restore test. Store the snapshot on trusted encrypted storage. Never publish
the folder or its private node identity to GitHub.

## HALO-LINUX-025 — backup contents verification

`scripts/verify-node-snapshot.py` additionally checks that the manifest's
node ID matches the backed-up SQLite `node_identity` row, that the public and
private identity keys are present, and (when enabled) that the agent journal
uses the supported v1 schema and expected tables. Extra files in the
snapshot directory are rejected. These checks verify **internal consistency**,
not that the backup is authentic, encrypted, or safe to restore over live data.

## HALO-LINUX-046C — executable SQLite journal constraints

The offline gate now executes `scripts/check-agent-journal-schema.py` using
Python's actual SQLite engine. It extracts the CREATE TABLE/INDEX statements
from the experimental Kotlin store, checks uniqueness of execution keys and
recovery fingerprints, first-writer resolution IDs, foreign keys, transaction
rollback and a verified SQLite VACUUM snapshot. This does not compile the Kotlin
adapter, validate its codecs, or prove the bundled JDBC version behaves
identically on ARM64. It provides a fast local schema regression ahead of
Gradle and hardware acceptance.
