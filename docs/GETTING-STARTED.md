# HaloLinux — getting started

**Status: source-implemented, build and execution verification pending.**

## Requirements

- Linux aarch64 or x86-64 (ARM64 not yet tested)
- JDK 21
- Available network for first dependency resolution
- Optional OpenAI-compatible inference endpoint (Ollama / llama.cpp)

## Installation

```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./gradlew clean build
./scripts/install.sh
```

Do not report the installation successful until the Gradle task, JAR launch and daemon health are verified.

## Config

```bash
mkdir -p ~/.config/halo-linux
cp config/halo-node.example.yaml ~/.config/halo-linux/config.yaml
```

Choose your actual endpoint and installed model in the YAML.

If credentials are required, provide `HALO_INFERENCE_API_KEY` in the service's protected environment rather than a readable configuration file.

## First run

Terminal A:

```bash
~/.local/bin/halo-node start
```

Terminal B:

```bash
~/.local/bin/halo-node doctor
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "HaloLinux offline storage works"
~/.local/bin/halo-node memory search "offline"
~/.local/bin/halo-node ask "Explain the node architecture"
```

Memory search works without an inference provider. An ask needs an actual provider; simulated fixture answers are not used in production.

## systemd

```bash
systemctl --user daemon-reload
systemctl --user start halo-node
systemctl --user status halo-node
journalctl --user -u halo-node -f
```

For an always-on server across logout, check whether lingering is appropriate and enable it explicitly if needed.

See [current implementation status](IMPLEMENTATION-STATUS.md) for outstanding test gates.
