# HaloLinux

Linux host and personal compute node for the [Halo Cognitive Runtime](https://github.com/slafftrosheen/Halo).

**Status: early implementation, source committed; build and hardware verification pending.**
This is not yet a production-ready Linux service. See [implementation status](docs/IMPLEMENTATION-STATUS.md).

## Goals

- Headless Linux host for ARM64 and x86-64
- Local-first personal notes and search
- Optional local/remote inference through explicit provider configuration
- Audio diagnostics followed by actual capture/playback adapters
- Future encrypted account-free peer synchronization

## Source layout

- `host/linux` — Kotlin CLI and Unix-domain socket daemon
- `adapters/inference` — SDK-backed OpenAI-compatible provider adapter
- `adapters/storage` — SQLite, FTS5 and local node metadata
- `adapters/audio` — conservative ALSA/PipeWire diagnostics
- `sdk/*` — **temporary copied** Halo contracts, not yet shared from canonical repo
- `runtime/*` — subset and placeholders; **not** the full cognitive runtime
- `protocol/schemas` — proposed, not yet interoperable sync schemas

## Build (requires JDK 21 and network for dependencies)

```bash
./gradlew clean build
./gradlew :host:linux:shadowJar
./scripts/install.sh
```

The build has **not** been verified in the execution environment that produced HALO-LINUX-002.

## First configuration

```bash
mkdir -p ~/.config/halo-linux
cp config/halo-node.example.yaml ~/.config/halo-linux/config.yaml
# Edit the model identifier to match a model installed at your endpoint.
```

For endpoints requiring an API key, set `HALO_INFERENCE_API_KEY` securely in the process environment.
Do **not** put the key in YAML or check it into Git.

The application never selects a remote provider automatically.

## Start

```bash
~/.local/bin/halo-node doctor
~/.local/bin/halo-node start
```

In a second terminal:

```bash
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "My personal offline test note"
~/.local/bin/halo-node memory search "offline"
~/.local/bin/halo-node ask "What is Halo?"
```

Inference requires an explicitly configured endpoint and model. Memory operations do not.

```bash
systemctl --user start halo-node
systemctl --user status halo-node
systemctl --user stop halo-node
```

For an always-on headless Pi, user lingering may need explicit configuration.

## Safety and limitations

Only the current local user can access the Unix socket through its protected directory.
The service does not expose a LAN API. Private node identity is stored in the local XDG data directory.
Peer authentication, encryption and synchronization are **not implemented**.

The committed source is not proof of a working Raspberry Pi deployment. Hardware, storage corruption recovery, executable packaging, SQLite round-trip tests and daemon lifecycle still require actual testing.

See [portability audit](docs/PORTABILITY-AUDIT.md), [security](docs/SECURITY.md), [Pi setup](docs/PI5-SETUP.md) and [implementation status](docs/IMPLEMENTATION-STATUS.md).

## License

The licensing and provenance of copied Halo SDK material require review.
No blanket new license is asserted for that imported source.
