# HaloLinux

Linux host and personal compute node for the Halo Cognitive Runtime. ARM64/x86-64, local-first intelligence, audio, memory and secure device synchronization.

## Overview

HaloLinux is an independent Linux host for the Halo cognitive runtime ecosystem. It provides:

- **Local-first AI inference** via OpenAI-compatible providers (Ollama, llama.cpp, vLLM, etc.)
- **Persistent memory and conversation storage** with full-text search
- **Audio diagnostics** for ALSA/PipeWire device enumeration
- **Node identity and sync foundation** for device-to-device synchronization
- **systemd integration** for reliable service management
- **Headless operation** - no desktop environment required

## Quick Start

```bash
# Clone and build
git clone https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./gradlew build

# Run directly
./gradlew :host:linux:run --args="--version"
./gradlew :host:linux:run --args="doctor"
./gradlew :host:linux:run --args="status"

# Or install and run as a service
./scripts/install.sh
systemctl --user start halo-node
systemctl --user enable halo-node
```

## Requirements

- **Java 21+** (OpenJDK or Oracle JDK)
- **Linux** (x86-64 or ARM64)
- **Gradle 8.8+** (wrapper included)
- **Optional**: Ollama, llama.cpp server, or other OpenAI-compatible inference endpoint
- **Optional**: ALSA/PipeWire for audio device support

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      HaloLinux Node                         │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   CLI       │  │  Daemon     │  │  Management API     │  │
│  │  Interface  │  │  (systemd)  │  │  (Unix Socket)      │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                    │             │
│         └────────────────┼────────────────────┘             │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Halo Runtime Core                       │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐  │   │
│  │  │ Lifecycle│ │ Events   │ │ Cognition│ │Provider│  │   │
│  │  │ Manager  │ │ Bus      │ │ Engine   │ │ Router │  │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                          │                                  │
│         ┌────────────────┼────────────────┐                │
│         ▼                ▼                ▼                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │  Inference  │  │   Audio     │  │  Storage    │        │
│  │  Adapters   │  │  Adapters   │  │  Adapters   │        │
│  │             │  │             │  │             │        │
│  │ • Ollama    │  │ • ALSA      │  │ • SQLite    │        │
│  │ • llama.cpp │  │ • PipeWire  │  │ • FTS5      │        │
│  │ • vLLM      │  │ • Diagnostics│ │ • Filesystem│        │
│  │ • OpenAI    │  │             │  │             │        │
│  └─────────────┘  └─────────────┘  └─────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) - System architecture and design decisions
- [Portability Audit](docs/PORTABILITY-AUDIT.md) - Analysis of reusable Halo modules
- [Getting Started](docs/GETTING-STARTED.md) - Detailed setup guide
- [Pi 5 Setup](docs/PI5-SETUP.md) - Raspberry Pi 5 specific installation
- [Inference](docs/INFERENCE.md) - Configuring inference providers
- [Audio](docs/AUDIO.md) - Audio device setup and diagnostics
- [Sync Protocol](docs/SYNC-PROTOCOL.md) - Device synchronization design
- [Security](docs/SECURITY.md) - Security model and threat analysis
- [Roadmap](docs/ROADMAP.md) - Future development plans

## Commands

```bash
halo-node --version          # Show version
halo-node doctor             # Run diagnostics
halo-node status             # Show node status
halo-node start              # Start daemon (foreground)
halo-node ask "query"        # Ask a question
halo-node config show        # Show configuration
```

## Configuration

Configuration file location (in order of precedence):
1. `$HALO_NODE_CONFIG/config.yaml`
2. `~/.config/halo-linux/config.yaml`
3. `/etc/halo-linux/config.yaml`

Example configuration:
```yaml
node:
  display_name: "My Halo Node"
  
inference:
  provider: "ollama-local"
  endpoint: "http://localhost:11434/v1"
  model: "llama3.1:8b"
  
storage:
  data_dir: "~/.local/share/halo-linux"
  
audio:
  enabled: true
  
sync:
  enabled: false
```

## License

Apache-2.0 (pending verification of upstream Halo licensing)