# HaloLinux

Linux host and personal compute node for the [Halo Cognitive Runtime](https://github.com/slafftrosheen/Halo).

**HALO-LINUX-053:** a standalone, read-only Unix-socket JSON API now serves the
Android import store. It provides opt-in raw inspection, conservative scoped memory
search, provenance, stable pagination, an OpenAPI contract and a Python client.
**Halo app/JVM integration is deferred; there is no TCP listener or live peer sync.**
See [API usage and boundaries](docs/BRAIN-API.md) and
[executed checks](docs/HALO-LINUX-053-VERIFICATION.md).

**HALO-LINUX-050:** standalone and optional canonical-cognition JVM builds,
unit tests, executable JAR packaging and daemon backup/restart smoke tests have
passed on x86-64 Linux. Raspberry Pi 5/ARM64 hardware,
audio, NVMe, boot persistence and sustained operation still need device acceptance.
See [current verification and limits](docs/IMPLEMENTATION-STATUS.md).

HaloLinux provides local notes/search, durable conversations, an explicit
OpenAI-compatible inference adapter, a private Unix-socket daemon, consistent
SQLite backups and ALSA/PipeWire diagnostics. It needs no Halo-operated server.
Local memory operations work without a model or network.

**HALO-LINUX-052:** Android schema-2 archives can now be imported transactionally
into a separate private SQLite store, with preview tokens, conflict retention,
attachment preservation and explicit crash recovery. **This does not activate
imported data in the JVM runtime or implement live peer sync.** See the
[import guide](docs/ANDROID-ARCHIVE-IMPORT.md) and
[this batch's executed tests and limits](docs/HALO-LINUX-052-VERIFICATION.md).

## Android alignment

The portable production sources now match Android Halo revision
`5a4047fec6dc9c561969c446641b22373d5157e2`: **183 files in seven reviewed modules**.
A checked-in SHA-256 inventory and explicit updater prevent silent SDK divergence.
The standalone build includes these sources; it does not need an Android checkout.

```bash
python3 -B scripts/sync-canonical-sources.py
```

This is implemented **source synchronization**. Live Android/Linux personal-data
sync, pairing and encrypted peer transport are not implemented. Android's
`.halobrain` archives now have offline inspection and lossless import-store support.
Import compatibility is reviewed separately at Android `023ef76013b599ee7ca3513014af184a5242e2b5`;
the runtime source pin above is unchanged. A Linux SQLite node snapshot cannot
currently be imported into Android.

- [Source ownership and reviewed updates](docs/SHARED-SOURCE-STRATEGY.md)
- [Canonical source build](docs/CANONICAL-SDK-BUILD.md)
- [Data synchronization boundary and proposal](docs/SYNC-PROTOCOL.md)
- [Android archive inspection](docs/ANDROID-ARCHIVE-INTEROP.md)
- [Android archive import and conflicts](docs/ANDROID-ARCHIVE-IMPORT.md)
- [Local Brain API and client](docs/BRAIN-API.md)

## Raspberry Pi OS Trixie quick start

Reference target: **Raspberry Pi 5, 8 GB, Raspberry Pi OS Trixie 64-bit**,
JDK 21. Start with Desktop; later headless operation should keep the same account,
configuration, storage and identity. A 64-bit userspace is required, not just a
64-bit kernel. x86-64 Linux is the other host target.

Install the prerequisites from [Pi 5 setup](docs/PI5-SETUP.md), then run as the
regular account that will own the node:

```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./scripts/preflight.sh --build
./scripts/verify-offline-source.sh
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node doctor
~/.local/bin/halo-node start
```

The configuration helper creates a private memory-only template and preserves an
existing configuration. The installer builds a fresh executable JAR and replaces
the installed artifact atomically. **It does not start, restart or enable the
service.** Do not run these setup/install scripts with `sudo`.

From a second terminal:

```bash
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "My offline test note"
~/.local/bin/halo-node memory search "offline"
~/.local/bin/halo-node storage check
```

To use inference, configure both an actual endpoint and an installed model.
For endpoints requiring a key, provide `HALO_INFERENCE_API_KEY` in the protected
process/service environment. Do not put credentials in YAML or Git. Provider
selection is explicit; there is no automatic remote fallback.

```bash
~/.local/bin/halo-node provider check
~/.local/bin/halo-node ask "Explain HaloLinux in one sentence"
```

`halo-node ask -` reads bounded text from stdin when a prompt should stay out of
process arguments.

## Service and updates

Stop the foreground daemon with Ctrl-C before starting the user service:

```bash
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
journalctl --user -u halo-node -f
```

Enable boot/logout persistence only after verifying the service, as described in
[Desktop-to-headless migration](docs/TRIXIE-HEADLESS-MIGRATION.md).
Terminal environment variables are not automatically inherited by systemd.

Use `main` for development and updates. Review local changes, pull with
`git pull --ff-only origin main`, verify/build, install, then explicitly restart
the service when ready. The [Trixie desktop guide](docs/TRIXIE-DESKTOP.md) covers
the full sequence. Halo scripts do not perform OS upgrades or change boot overlays.

## Data protection

From a running node, create a consistent private snapshot set:

```bash
mkdir -p -m 700 "$HOME/halo-backups"
~/.local/bin/halo-node node backup "$HOME/halo-backups/snapshot-001"
python3 -B scripts/verify-node-snapshot.py "$HOME/halo-backups/snapshot-001"
```

Snapshots contain personal data and private node identity; they are not encrypted.
The node refuses to overwrite an existing snapshot destination. A full node
snapshot includes the optional canonical agent database when that variant is active.

Storage opening now checks the actual schema before initialization. Incompatible
legacy `master` databases are rejected without automatic conversion. Preserve the
complete old data directory if this happens; a reviewed migration is required.
Do not point both old and new nodes at the same live database.

## Runtime boundary and remaining work

The standalone host uses canonical SDK contracts, content-free runtime stage
events and conservative text-turn checkpoints. Unsupported commands and model
tool requests are rejected; a restart does not automatically repeat inference.

An optional build compiles the real pinned Android `runtime/cognition` sources
and adds the canonical coordinator/recovery planner with a transactional Linux
`AgentRunStore`. It has its own build and acceptance gate. Tool execution,
automatic recovery replay and LAN control remain disabled.
See [cognitive integration](docs/COGNITIVE-INTEGRATION.md) and
[canonical agent-store limits](docs/CANONICAL-AGENT-STORE.md).

Audio currently means diagnostics. Capture/playback, wake/VAD, STT/TTS and hardware
routing still need Linux adapters and physical qualification. JVM build success
does not establish ARM64 JNI, HiFiBerry or real-provider behavior on your Pi.

## Source layout and documentation

| Path | Purpose |
| --- | --- |
| `host/linux` | Kotlin CLI, private Unix socket and Linux composition |
| `adapters/inference` | Explicit SDK-backed inference provider |
| `adapters/storage` | SQLite/FTS5, conversations, checkpoints and node identity |
| `adapters/agent-store` | Optional canonical agent journal |
| `adapters/audio` | ALSA/PipeWire diagnostics |
| `sdk/*`, reviewed `libraries/*` and runtime modules | Pinned canonical production snapshot |
| `protocol/schemas` | Local-control contracts and separately labeled peer-sync drafts |

- [Getting started](docs/GETTING-STARTED.md)
- [Pi 5 setup](docs/PI5-SETUP.md)
- [Audio](docs/AUDIO.md)
- [Security](docs/SECURITY.md)
- [Pre-deployment gates](docs/PRE-DEPLOYMENT-GATES.md)
- [Roadmap](docs/ROADMAP.md)

## License

The licensing and provenance of imported Halo source require review.
No blanket new license is asserted for that material.
