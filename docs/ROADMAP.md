# HaloLinux Roadmap

## Vision

HaloLinux: A distributed personal computing ecosystem where every device is a peer - Raspberry Pi nodes, Android wearables, Windows/Mac desktops, robots - all synchronizing securely without central authority.

---

## Milestone 0: Bootstrap (Batch 001) ✅

- [x] Repository creation and Gradle project structure
- [x] Portability audit of upstream Halo modules
- [x] Core SDK modules copied (runtime-sdk, provider-sdk, libraries/common)
- [x] Linux host application skeleton (`host:linux`)
- [x] OpenAI-compatible inference adapter (`adapters:inference`)
- [x] Fixture provider for testing
- [x] Audio diagnostics (`adapters:audio`)
- [x] SQLite + FTS5 storage (`adapters:storage`)
- [x] Protocol schemas (Node Identity, Memory, Conversation, Sync Manifest)
- [x] systemd service unit
- [x] Build/test/install/doctor scripts
- [x] Documentation (Architecture, Portability Audit, Getting Started, Pi 5, Inference, Audio, Sync, Security)

**Target:** Working CLI, build passes, basic daemon structure

---

## Milestone 1: Working Host (Batch 002) ✅ COMPLETED

### Core Runtime
- [x] Complete `HaloNodeApplication` Engine implementation
- [x] RuntimeContext with real EventBus/CommandBus/QueryBus
- [x] ServiceRegistry with dependency injection
- [x] Lifecycle: initialize → start → pause/resume → stop → dispose
- [x] Signal handling (SIGINT, SIGTERM via CLI)
- [x] Structured logging (SLF4J/Logback to stdout/journald)

### Configuration
- [x] YAML config loading via preferences storage
- [x] Config validation with clear errors
- [x] Environment variable overrides (XDG directories)
- [x] `halo-node config show` with secret masking

### Health Reporting (Multi-Dimensional)
- [x] Process health (JVM version, OS, architecture)
- [x] Runtime health (engine state)
- [x] Provider health (configured, connected, model ready)
- [x] Audio health (devices enumerated, ALSA status)
- [x] Storage health (DB accessible, migrations current)
- [x] AgentRunStore health (crash recovery, non-terminal runs)
- [x] Overall: capability-specific statuses, not a single fake green light

### Daemon Operation
- [x] Foreground mode for systemd
- [x] Graceful shutdown on SIGTERM (coroutine cancellation)
- [x] Resource cleanup on exit (DB connections, coroutines)

### Testing
- [x] Unit tests for FixtureInferenceProvider
- [x] Unit tests for LinuxStorage (conversations, memories, node identity)
- [x] Unit tests for SqliteAgentRunStore (create, transition, CAS, deduplication, crash recovery)
- [x] Fixture provider tests
- [x] Storage migration tests (clean install, reopening, failed migration rollback)

---

## Milestone 2: First Useful Runtime (Batch 003 - HALO-LINUX-003) ✅ COMPLETED

### Contract & Source Integrity (P0)
- [x] Fixed all compilation errors by source inspection
- [x] Added missing types to provider SDK: `ProviderProtocol`, `Credential`, `CredentialProvider`
- [x] Added missing audio types to runtime SDK: `AudioContext`, `AudioDevice`, `AudioDeviceType`, `AudioSampleFormat`
- [x] Created `StructuredStorage` interface distinct from simple `Storage` key-value
- [x] Removed Android dependencies from Linux modules
- [x] Fixed `LinuxOpenAiCompatibleProvider` to delegate to `ConfiguredOpenAiCompatibleProvider`
- [x] Fixed `FixtureInferenceProvider` stream events to match SDK (`Completed` not `Done`)
- [x] Fixed `LinuxAudioDiagnostics` to report unknown capabilities as unknown (empty lists)
- [x] Fixed `LinuxStorage` migration system (no regex SQL splitter)

### SQLite Integrity (P0)
- [x] Replaced regex-based migration splitting with explicit statement lists
- [x] Migration rollback is structurally correct (transactional, preserves version on failure)
- [x] Durable data survives reopening
- [x] FTS5 triggers verified for insert/update/delete
- [x] Added indexes and foreign keys with `PRAGMA foreign_keys=ON`, WAL mode, busy timeout
- [x] Added AgentRunStore tables (agent_runs, agent_run_events) in migration 3
- [x] Tests for: clean install, reopening, failed migration rollback, migration replay, FTS updates/deletes, conversation ordering, preference batch atomicity

### Canonical AgentRunStore (P1)
- [x] Ported `AgentRun`, `AgentRunStore`, `AgentStep` contracts from Halo Android (`runtime/cognition/agent/`)
- [x] Added `AgentRunStatus` enum with explicit legal transitions
- [x] Added append-only event journal with monotonic sequence numbers
- [x] Crash recovery: `RUNNING`/`WAITING_FOR_TOOL` → `INTERRUPTED` on startup
- [x] Atomic CAS transitions via `revision` field
- [x] Deduplication for tool dispatches and input resolutions
- [x] `SqliteAgentRunStore` implementation in `adapters:storage`
- [x] Tests: create/get, state transitions, CAS, deduplication, non-terminal runs, crash recovery reconciliation

### Real Host Lifecycle (P1)
- [x] Composition root for RuntimeContext, ServiceRegistry, EventBus, Storage, AgentRunStore, Providers
- [x] `start` initializes resources, reconciles interrupted runs, exposes truthful readiness
- [x] `stop` cancels jobs, awaits bounded graceful termination, flushes DB, closes resources
- [x] `doctor` checks Java/OS/arch, directories, DB migration, audio, provider reachability
- [x] `status` distinguishes stopped/running/degraded/unconfigured
- [x] `ask` actually invokes configured provider or fails clearly
- [x] No unauthenticated TCP management API; CLI remains useful
- [x] Systemd unit aligned with real daemon behavior (no `Type=notify` without sd_notify)

### Provider Integration & Safety (P1)
- [x] Reuses `OpenAiCompatibleTransport` for HTTP/SSE, cancellation, timeouts, model discovery, tool-call streaming
- [x] Supports OpenAI-compatible endpoints including loopback, LAN, remote
- [x] `CredentialTransportPolicy` enforced; no public HTTP credential transmission
- [x] Auth-free local endpoints (`ProviderAuthKind.NONE`) work for all operations
- [x] Never silently selects paid remote model when local-only policy requested
- [x] Fixture-based integration tests for model discovery, generation, token streams, cancellation, auth-free local, invalid credentials, interrupted runs

### Documentation (P2)
- [x] Updated `BUILD_FAILURE_REPORT.md`
- [x] Updated `docs/ARCHITECTURE.md`
- [x] Updated `docs/ROADMAP.md` (this file)
- [x] Updated `docs/GETTING-STARTED.md`
- [x] Added `docs/HALO-LINUX-003-IMPLEMENTATION-REPORT.md`

---

## Milestone 3: Portability & Sync Foundation (Batch 004) 🎯 Next

### Linux Capability Abstraction
- [ ] CPU/RAM/Disk/Network info collection
- [ ] Hardware capability registry
- [ ] Thermal/power monitoring (where available)
- [ ] Capability advertisement for sync

### Audio Diagnostics Enhancement
- [ ] PipeWire device details (ports, profiles) via proper JSON API
- [ ] Bluetooth audio device detection (BlueZ)
- [ ] Sample rate/format negotiation
- [ ] Latency measurement

### Node Identity
- [ ] Ed25519 keypair generation (libsodium or Bouncy Castle)
- [ ] Secure private key storage (encrypted, 0600)
- [ ] Identity persistence across restarts
- [ ] Display name management

### Protocol Schemas
- [ ] Kotlin data classes from JSON schemas
- [ ] Serialization/deserialization (Kotlinx Serialization)
- [ ] Schema version migration logic
- [ ] Validation on ingest

### Sync Architecture Document
- [ ] Complete SYNC-PROTOCOL.md (done)
- [ ] Threat model (done in SECURITY.md)
- [ ] Pairing flow implementation plan

### Pi 5 Documentation
- [ ] Complete PI5-SETUP.md (done)
- [ ] HiFiBerry overlay verification (hardware pending)
- [ ] Performance benchmarks

---

## Milestone 4: Sync Implementation (Batch 005+)

### Discovery
- [ ] mDNS announcement (`_halo._tcp.local`)
- [ ] mDNS browsing for peers
- [ ] TXT record parsing

### Pairing
- [ ] QR code generation (Android) / display (Linux)
- [ ] 6-digit pairing code fallback
- [ ] Fingerprint verification (SHA256 pubkey)
- [ ] Mutual confirmation
- [ ] Noise_XK handshake

### Transport
- [ ] TLS 1.3 or Noise protocol implementation
- [ ] Mutual authentication
- [ ] Session encryption
- [ ] Connection pooling per peer

### Sync Engine
- [ ] Manifest-based incremental sync
- [ ] Conflict resolution (LWW by revision)
- [ ] Tombstone handling
- [ ] Per-peer revision tracking
- [ ] Scope-based authorization

### Data Types
- [ ] Memory entry sync
- [ ] Conversation sync (opt-in)
- [ ] Notebook/file sync (future)
- [ ] Attachment sync (content-addressed)

---

## Milestone 5: Advanced Features (Batch 006+)

### Speech Pipeline
- [ ] STT adapter (Sherpa-ONNX)
- [ ] TTS adapter (Piper)
- [ ] Wake word detection (Porcupine)
- [ ] Voice activity detection
- [ ] Audio routing (capture → STT → inference → TTS → playback)

### Bluetooth/Peripherals
- [ ] BlueZ GATT client
- [ ] HeyCyan glasses driver
- [ ] Generic BLE device framework
- [ ] Wear OS companion (future)

### Distributed Inference
- [ ] Peer capability advertisement
- [ ] Request inference from peer
- [ ] Model offloading
- [ ] Load balancing

### Mobile/Desktop
- [ ] Windows host (MSIX/portable)
- [ ] macOS host (Homebrew/App bundle)
- [ ] Android app sync integration

---

## Long-term Vision (2027+)

- **Halo Mesh**: Automatic peer discovery, opportunistic sync
- **Collective Intelligence**: Shared memory graph across trusted peers
- **Edge Compute**: Raspberry Pi cluster for local LLM inference
- **Robotics Integration**: BuddyBot as mobile Halo node
- **Privacy-Preserving ML**: Federated learning across personal devices

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Cold start time | < 3 seconds |
| Memory footprint (idle) | < 200 MB |
| Inference latency (local 8B) | < 500ms TTFT |
| Sync latency (LAN) | < 100ms |
| Uptime (systemd) | 99.9% |
| Test coverage (core) | > 80% |
| ARM64/x86-64 parity | 100% |

---

## Contributing

See `AGENTS.md` for development workflow and `CONTRIBUTING.md` (future) for contribution guidelines.