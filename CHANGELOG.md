# Changelog

All notable changes to HaloLinux will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — HALO-LINUX-050 — 2026-10-23

### Android source alignment

- Pin canonical Halo to `5a4047fec6dc9c561969c446641b22373d5157e2` and import
  183 portable production files across seven allowlisted modules verbatim.
- Restore the missing cognition-language and provider-response-validator sources.
- Add a deterministic SHA-256 source manifest, read-only drift checks and an
  explicit reviewed-revision updater. Validate actual Git blobs, source inventory,
  dirty/index state and safe paths, including optional external cognition sources.
- Make Gradle verify provenance before Kotlin compilation and select only the
  reviewed source roots. Android remains canonical; no Android files are changed.

### Linux build and runtime verification

- Use JDK/JVM 21 consistently with the existing Gradle 8.14.3 wrapper and Kotlin
  2.2.10. Pin the wrapper distribution checksum and bound Gradle worker/heap use.
- Separate thin and executable JAR outputs to fix the full-build packaging
  collision; merge service descriptors and record source revision/mode in the JAR.
- Restore executable build entry points and correct coroutine test return types
  so JUnit discovers every intended test. Match recovery tests to startup lifecycle.
- Fix smoke-test JVM process ownership and verify actual shutdown/socket removal
  before restart. Support an exact prebuilt JAR and retain private failure logs.
- Record actual x86-64 build, test and daemon acceptance evidence in
  [implementation status](docs/IMPLEMENTATION-STATUS.md). Physical Pi acceptance
  remains separate.

### Existing data and backups

- Refuse incompatible legacy-master schema 3, incomplete migration histories,
  unsupported schemas, orphan sidecars and rollback-journal states before mutation.
- Inspect a private streamed DB/WAL copy so read-only SQLite cannot create
  sidecars beside the original. Preserve source bytes, timestamps and permissions
  on refusal; document temporary disk capacity and `java.io.tmpdir` selection.
- Validate snapshot schema lineage and standalone SQLite format before immutable
  inspection. Preserve malformed/WAL snapshots without creating sidecars.
- Retain both branch histories through an explicit reviewed reconciliation;
  document the legacy database boundary without inventing an automatic migration.

### Raspberry Pi OS Trixie

- Check real 64-bit userspace, JDK 21 selection and compiler availability,
  systemd user-manager/linger state and actual PipeWire session connectivity.
- Preserve private, unprivileged configuration and installation. Build a fresh
  JAR before atomic replacement; leave service start/restart/enable explicit.
- Update Pi 5, Desktop/Lite, headless, NVMe and exact-board audio instructions
  against official Trixie/Raspberry Pi documentation.
- Add isolated shell fixtures and extend the local offline verification gate.
- Clearly distinguish implemented Android source alignment from future
  `.halobrain` interchange and live peer-data synchronization.

No GitHub Actions, background updater, implicit remote provider, LAN listener,
automatic OS configuration change or tool replay is introduced.

## Bootstrap — 2026-10-12 (historical)

### Added
- Initial repository structure with Gradle Kotlin DSL
- Core SDK modules: `runtime-sdk`, `provider-sdk`, `host-sdk`, `libraries:common`
- Linux host application (`host:linux`) with basic Engine implementation
- OpenAI-compatible inference adapter (`adapters:inference`)
  - `LinuxOpenAiCompatibleProvider` for Ollama, llama.cpp, vLLM, OpenAI, Groq, etc.
  - `FixtureInferenceProvider` for deterministic testing
  - Provider presets for common local endpoints
- Audio diagnostics adapter (`adapters:audio`)
  - ALSA device enumeration via `/proc/asound`
  - PipeWire detection via `pw-cli`
  - Device capability reporting
- SQLite + FTS5 storage adapter (`adapters:storage`)
  - XDG Base Directory Specification compliance
  - Conversation persistence with messages
  - Memory entries with full-text search (FTS5)
  - Node identity persistence (Ed25519 keypair placeholder)
  - Schema migrations (v1: core tables, v2: node identity + sync peers)
- Protocol schemas (`protocol:schemas`)
  - Node Identity (JSON Schema)
  - Memory Entry (JSON Schema)
  - Conversation Record (JSON Schema)
  - Sync Manifest (JSON Schema)
- systemd service unit (`services/systemd/halo-node.service`)
  - Hardened security settings
  - User service (no root required)
  - Resource limits and logging
- Build and management scripts (`scripts/`)
  - `build.sh` - Full project build
  - `test.sh` - Test runner
  - `install.sh` - System/user installation with systemd
  - `doctor.sh` - Comprehensive diagnostics
- Documentation (`docs/`)
  - ARCHITECTURE.md - System design and data flows
  - PORTABILITY-AUDIT.md - Upstream Halo module analysis (commit efe9d44)
  - GETTING-STARTED.md - Quick start guide
  - PI5-SETUP.md - Raspberry Pi 5 installation (hardware unverified)
  - INFERENCE.md - Provider configuration guide
  - AUDIO.md - Audio architecture and diagnostics
  - SYNC-PROTOCOL.md - Peer-to-peer sync design
  - SECURITY.md - Threat model and security controls
  - ROADMAP.md - Development milestones

### Infrastructure
- Gradle 8.8 wrapper
- Kotlin 2.0.20 with Java 21 toolchain
- Shadow JAR plugin for fat JAR
- JUnit 5 + Mockito + MockWebServer for testing
- SLF4J + Logback for logging

### Compatibility
- **Upstream Halo Commit:** `efe9d44e6310d2573854667fbf01f489066f2f94` (2026-10-11)
- **Reusable Modules:** ~85% of core runtime (runtime-sdk, provider-sdk, kernel, cognition, engines)
- **Requires Adapters:** host-android, audio, storage, bluetooth, LiteRT, Sherpa STT
- **Not Portable:** Android app, Wear OS, Android Assistant, ElevenLabs (secure storage)

### Known Limitations
- Daemon management API not yet implemented (Unix socket placeholder)
- LiteRT Linux compatibility unverified
- HiFiBerry DAC+ ADC Pro on Pi 5 unverified
- SQLCipher not integrated (encryption pending)
- Pairing/sync transport not implemented (design only)
