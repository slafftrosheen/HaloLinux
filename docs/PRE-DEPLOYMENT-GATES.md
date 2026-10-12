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
