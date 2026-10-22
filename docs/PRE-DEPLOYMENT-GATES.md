# HaloLinux pre-deployment acceptance

The reference target is **Raspberry Pi 5, Raspberry Pi OS Trixie 64-bit
ARM64**, initially with Desktop. Development builds on x86-64 and fixture tests
are useful evidence, but do not establish acceptance on the Pi. See the dated
execution record in [implementation status](IMPLEMENTATION-STATUS.md).

## A. Source and JVM build

Use JDK 21 and Python 3. The first Gradle run needs dependency downloads:

```bash
./scripts/verify-offline-source.sh
./scripts/preflight.sh --build
./gradlew clean build :host:linux:shadowJar --no-daemon
```

The build verifies the pinned Android source manifest before Kotlin compilation.
Keep failing logs, fix failures and rerun the affected gate. Successful source
integrity checks alone are not evidence that Kotlin code runs.

## B. Isolated packaged-daemon smoke

```bash
./scripts/smoke-test.sh
```

This launches the real executable JAR with temporary private XDG directories.
It checks Unix control protocol v1, healthy runtime status, disabled-provider
behavior, SQLite FTS5, integrity, private backups, no-overwrite protection and
identity/data persistence across restart. It requires no model and does not
read installed node data. Run it on the Pi to exercise ARM64 SQLite JNI.

To verify a specific already-built standalone or canonical artifact without
rebuilding it, use `./scripts/smoke-test.sh --jar /absolute/path/to/halo-node.jar`.
The harness tracks the JVM directly and requires its process and socket to exit
before restart. On failure it reports the private temporary fixture path and
retains diagnostic logs; successful fixtures are removed.

## C. Trixie installation and user service

Run as the normal account that will own the node:

```bash
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node --version
~/.local/bin/halo-node doctor
```

The setup preserves existing configuration and starts with inference disabled.
The installer builds a fresh executable JAR and replaces it atomically. It
does not start, restart or enable the service. Test a manual daemon first, stop
it, then test `systemctl --user start halo-node`. Never run both at once.

Check existing data lineage before an upgrade from legacy `master`; incompatible
version-3 databases are refused. See [branch reconciliation](BRANCH-RECONCILIATION.md).

## D. Real inference, when needed

A memory-only node is supported without a model. If inference is required,
configure the actual provider and installed model, execute a real call, and
record provider/model identity, latency, RAM and temperature. Fixture-provider
JUnit results do not establish model quality or Pi performance. Remote
providers always require explicit configuration; there is no silent fallback.

## E. Headless operation and reboot

After the manual and user-service tests pass, follow
[desktop-to-headless migration](TRIXIE-HEADLESS-MIGRATION.md). Verify storage,
configuration, secret injection and node identity before and after reboot.
Enabling a user unit and enabling lingering serve different purposes; both
need an explicit operator decision for unattended boot.

## F. Physical hardware

On the actual Pi, verify power supply, throttling and sustained temperatures,
NVMe mount/disk pressure, and native SQLite loading. Check the actual HiFiBerry
board and overlay before capture/playback. ALSA enumeration and a reachable
PipeWire session do not prove audio streaming. Halo's audio adapter currently
provides diagnostics; STT/TTS and end-to-end capture/playback remain unfinished.

## G. Private backup and restore rehearsal

With a running node:

```bash
mkdir -p -m 700 "$HOME/halo-backups"
halo-node node backup "$HOME/halo-backups/snapshot-001"
python3 -B scripts/verify-node-snapshot.py "$HOME/halo-backups/snapshot-001"
```

The daemon holds its write gate while creating private main-storage and, when
linked, canonical-agent snapshots plus a SHA-256 manifest. Verification checks
file permissions, hashes, identity consistency, SQLite integrity and supported
schemas. Snapshots contain private identity keys and personal content and are
**not encrypted**. Keep them on trusted encrypted storage.

This is not a backup of model binaries, a cross-process transaction, proof of
authenticity, or a completed restore rehearsal. Rehearse recovery into an
isolated data directory before relying on the installation. Do not restore over
a running daemon or copy live SQLite files independently.

## H. Optional canonical cognition integration

Use a clean separate Halo checkout at `config/canonical-halo.sha`:

```bash
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

This verifies exact upstream source bytes, compiles Android's real portable
cognition module, and runs the Linux agent-store, recovery-contract and optional
host tests. Canonical cognition is opt-in; the default Pi installation uses the
standalone host. A passing developer build does not enable tools, autonomous
actions or automatic replay and does not establish Pi acceptance.

Source synchronization with Android is implemented. Android/Linux peer
pairing, archive import and live user-data synchronization remain separate
unfinished work; see [sync protocol proposal](SYNC-PROTOCOL.md).
