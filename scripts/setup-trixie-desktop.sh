#!/usr/bin/env bash
# Non-destructive user setup for a Trixie Desktop evaluation node.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/lib/host-platform.sh"
if [[ "$(id -u)" == 0 ]]; then
  echo "FAIL: run this user setup without sudo, as the account that will run HaloLinux." >&2
  exit 1
fi
CONFIG_PATH="${HALO_CONFIG_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/halo-linux/config.yaml}"
[[ "$CONFIG_PATH" == /* ]] || { echo "FAIL: choose an absolute HALO_CONFIG_FILE/XDG_CONFIG_HOME path." >&2; exit 1; }
CONFIG_DIR="$(dirname "$CONFIG_PATH")"
echo "Raspberry Pi OS Trixie (64-bit ARM64) desktop setup"
echo "Architecture: $(uname -m)"
if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "NOTE: this helper also runs on Linux PCs, but Pi 5 hardware expects aarch64."
fi
halo_check_java build
mkdir -p "$CONFIG_DIR"
if [[ -L "$CONFIG_PATH" || ( -e "$CONFIG_PATH" && ! -f "$CONFIG_PATH" ) ]]; then
  echo "FAIL: Halo requires a regular, non-symlink config file: $CONFIG_PATH" >&2
  exit 1
elif [[ -e "$CONFIG_PATH" ]]; then
  echo "Existing config preserved: $CONFIG_PATH"
else
  # Exclusive creation also preserves a config created by another process after the check.
  (set -o noclobber; cat "$ROOT/config/halo-node.example.yaml" > "$CONFIG_PATH")
  echo "Created template config: $CONFIG_PATH"
fi
echo "The template starts in offline memory-only mode. Enable an actual endpoint/model only when ready."
echo "Configuration: $CONFIG_PATH"
echo "Check build prerequisites: ./scripts/preflight.sh --build"
echo "Build and smoke test first: ./gradlew clean build && ./scripts/smoke-test.sh"
echo "Then: ./scripts/install.sh"
echo "Start manually: ~/.local/bin/halo-node start"
echo "In another terminal: ~/.local/bin/halo-node status"
echo "When ready: systemctl --user start halo-node"
echo "No services, boot settings, audio overlays, or disks were modified."
