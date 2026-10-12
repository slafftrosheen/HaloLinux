#!/usr/bin/env bash
# Non-destructive user setup for a Trixie Desktop evaluation node.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/halo-linux"
CONFIG_PATH="$CONFIG_DIR/config.yaml"
echo "Raspberry Pi OS Trixie (64-bit ARM64) desktop setup"
echo "Architecture: $(uname -m)"
if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "NOTE: this helper also runs on Linux PCs, but Pi 5 hardware expects aarch64."
fi
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | head -n 1
else
  echo "Java 21 missing. Install openjdk-21-jdk before building." >&2
fi
mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"
if [[ -e "$CONFIG_PATH" ]]; then
  echo "Existing config preserved: $CONFIG_PATH"
else
  umask 077
  install -m 0600 "$ROOT/config/halo-node.example.yaml" "$CONFIG_PATH"
  echo "Created template config: $CONFIG_PATH"
fi
echo "Edit the actual endpoint/model in $CONFIG_PATH."
echo "Build and smoke test first: ./gradlew clean build && ./scripts/smoke-test.sh"
echo "Then: ./scripts/install.sh"
echo "Start manually: ~/.local/bin/halo-node start"
echo "In another terminal: ~/.local/bin/halo-node status"
echo "When ready: systemctl --user start halo-node"
echo "No services, boot settings, audio overlays, or disks were modified."
