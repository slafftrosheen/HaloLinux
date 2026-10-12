# HaloLinux Roadmap

## Vision

HaloLinux: A distributed personal computing ecosystem where every device is a peer - Raspberry Pi nodes, Android wearables, Windows/Mac desktops, robots - all synchronizing securely without central authority.

---

## Milestone 0: Bootstrap (Current - Batch 001) ✅

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

## Milestone 1: Working Host (Batch 002) 🎯 Next

### Core Runtime
- [ ] Complete `HaloNodeApplication` Engine implementation
- [ ] RuntimeContext with real EventBus/CommandBus/QueryBus
- [ ] ServiceRegistry with dependency injection
- [ ] Lifecycle: initialize → start → pause/resume → stop → dispose
- [ ] Signal handling (SIGINT, SIGTERM, SIGHUP)
- [ ] Structured logging (JSON to stdout/journald)

### Configuration
- [ ] YAML config loading (SnakeYAML/Kotlinx Serialization)
- [ ] Config validation with clear errors
- [ ] Environment variable overrides
- [ ] `halo-node config show` with secret masking

### Management API
- [ ] Unix domain socket server (abstract namespace `@halo-node-mgmt`)
- [ ] Authentication (token-based, generated on startup)
- [ ] JSON-RPC 2.0 or simple request/response protocol
- [ ] Endpoints: status, health, inference, config, shutdown
- [ ] CLI client for daemon communication

### Health Reporting (Multi-Dimensional)
- [ ] Process health (JVM metrics, GC, memory)
- [ ] Runtime health (engine state, event bus)
- [ ] Provider health (configured, connected, model ready)
- [ ] Audio health (devices enumerated, default set)
- [ ] Storage health (DB accessible, migrations current)
- [ ] Sync health (peers connected, last sync)
- [ ] Overall: Healthy/Degraded/Failed (not single boolean)

### Daemon Operation
- [ ] Foreground mode for systemd (`Type=notify`)
- [ ] PID file management
- [ ] Graceful shutdown on SIGTERM (30s timeout)
- [ ] Resource cleanup on exit

### Testing
- [ ] Unit tests for config, lifecycle, health
- [ ] Integration test: start → health → stop
- [ ] Fixture provider tests
- [ ] Storage migration tests

---

## Milestone 2: First Useful Runtime (Batch 003)

### Real Inference
- [ ] `LinuxOpenAiCompatibleProvider` integration with host
- [ ] Provider selection and routing
- [ ] Streaming response handling
- [ ] Tool call support (when provider supports it)
- [ ] Model listing and selection
- [ ] Connection pooling and timeouts
- [ ] Retry with backoff for transient failures

### Conversation Persistence
- [ ] `LinuxStorage.storeConversation()` / `loadConversation()`
- [ ] Conversation list with pagination
- [ ] Message ordering and deduplication
- [ ] Provider/model metadata per message
- [ ] Token usage tracking

### Local Search (No LLM Required)
- [ ] FTS5 memory search (already in schema)
- [ ] Conversation full-text search
- [ ] CLI: `halo-node search "query"`
- [ ] Search API endpoint

### Degraded Operation
- [ ] Work without inference provider (local search only)
- [ ] Work without audio devices
- [ ] Work without network
- [ ] Clear error messages for missing capabilities

### Integration Tests
- [ ] End-to-end: ask → inference → store → search
- [ ] Restart survival: conversations persist
- [ ] Provider failure handling
- [ ] Concurrent request handling

---

## Milestone 3: Portability & Sync Foundation (Batch 004)

### Linux Capability Abstraction
- [ ] CPU/RAM/Disk/Network info collection
- [ ] Hardware capability registry
- [ ] Thermal/power monitoring (where available)
- [ ] Capability advertisement for sync

### Audio Diagnostics Enhancement
- [ ] PipeWire device details (ports, profiles)
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