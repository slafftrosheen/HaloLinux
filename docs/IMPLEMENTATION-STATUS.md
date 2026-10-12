# HaloLinux implementation status

Updated through HALO-LINUX-004/005 source commits.

## Source implemented, NOT JVM-executed in this work environment
- Kotlin/JVM Linux CLI, unprivileged user systemd service, local Unix socket daemon.
- Bounded local IPC worker pool, 180s deadlines, single-instance file lock and permission checks.
- Explicit configured inference through the existing Halo provider SDK transport.
- SQLite conversation storage, local FTS5 memory add/search/delete and migrations.
- Persistent UUID and Ed25519 keypair in private local storage.
- Diagnostics without printing configuration files.
- Isolated smoke testing script and read-only Pi 5 hardware preflight script.
- Test sources for IPC, inference fixtures and SQLite durability.

## Build & deployment verification (PENDING)
- JVM/Gradle `clean build` and full test suite: not executed successfully.
- Isolated `scripts/smoke-test.sh`: not executed.
- Live inference request: not executed.
- systemd user service lifecycle: not executed.
- Linux ARM64 and Raspberry Pi 5: not executed.
- HiFiBerry audio + NVMe: not executed.
- Resource usage and 24h stability: not measured.

## Architecture limitations
- Real cognition/agent runtime not yet wired through Linux host; initial CLI uses provider SDK directly.
- Core SDK modules still copied, not consumed as pinned shared artifacts.
- Full encrypted peer pairing and sync absent; JSON schema sketches alone do not provide sync.
- No Linux STT/TTS or validated capture/playback stream yet.
- No external management listener; Unix socket is local-user only.
- `main` has recent development commits but GitHub repository default still `master`.
  Switch default in repository settings after review.

## Blocking steps before unattended deployment
1. Run `./gradlew clean build` and fix any errors using a network-enabled Linux environment.
2. Run the isolated smoke test and all JVM tests. No skipped/ignored failures.
3. Verify authenticated/configured provider with a real model or deploy memory-only intentionally.
4. Run systemd start/stop and process restart checks.
5. Confirm service filesystem permissions, disk space, backups and Pi power/thermal state.

See `docs/PRE-DEPLOYMENT-GATES.md` and `docs/SHARED-SOURCE-STRATEGY.md`.
