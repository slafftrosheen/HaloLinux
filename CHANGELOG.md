# Changelog

All notable changes to HaloLinux will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 2026-10-12

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