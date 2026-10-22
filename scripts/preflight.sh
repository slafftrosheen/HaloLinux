#!/usr/bin/env bash
# Read-only Pi/Linux deployment preflight; does not install packages or change OS settings.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/lib/host-platform.sh"
MODE=runtime
case "${1:-}" in
  '') ;;
  --build) MODE=build ;;
  --help|-h)
    echo "Usage: $0 [--build]"
    echo "Read-only Linux deployment checks. --build additionally requires the JDK 21 compiler."
    exit 0 ;;
  *) echo "Usage: $0 [--build]" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { echo "Too many arguments" >&2; exit 2; }
FAILURES=0
echo "HaloLinux deployment preflight ($MODE) — no system changes"
OS_NAME="$(halo_os_release_value PRETTY_NAME /etc/os-release)"
OS_CODENAME="$(halo_os_release_value VERSION_CODENAME /etc/os-release)"
echo "OS: ${OS_NAME:-unknown} (codename: ${OS_CODENAME:-unknown})"
if [[ "$OS_CODENAME" == trixie ]]; then
  echo "OS baseline: Debian/Raspberry Pi OS Trixie"
else
  echo "NOTE: Trixie is the Pi reference OS; other Linux distributions need their own acceptance."
fi
echo "Kernel: $(uname -sr)"
ARCH="$(uname -m)"
USERSPACE_ARCH=unknown
if command -v dpkg >/dev/null 2>&1; then
  USERSPACE_ARCH="$(dpkg --print-architecture 2>/dev/null || true)"
  USERSPACE_ARCH="${USERSPACE_ARCH:-unknown}"
fi
echo "Architecture: kernel=$ARCH userspace=$USERSPACE_ARCH"
if ! halo_check_architecture "$ARCH" "$USERSPACE_ARCH"; then FAILURES=$((FAILURES + 1)); fi
if ! halo_check_java "$MODE"; then FAILURES=$((FAILURES + 1)); fi
if command -v systemctl >/dev/null 2>&1; then
  USER_MANAGER="$(systemctl --user is-system-running 2>/dev/null || true)"
  case "$USER_MANAGER" in
    running) echo "systemd user: running" ;;
    degraded) echo "WARN: systemd user is degraded; inspect: systemctl --user --failed" ;;
    *) echo "WARN: systemd user manager unavailable (${USER_MANAGER:-unknown}); use a normal login session, without sudo. Manual CLI startup remains available." ;;
  esac
else
  echo "WARN: systemd unavailable; use manual CLI startup."
fi
if command -v loginctl >/dev/null 2>&1; then
  LINGER="$(loginctl show-user "$(id -u)" --property=Linger --value 2>/dev/null || true)"
  if [[ "$LINGER" == yes ]]; then
    echo "Headless persistence: user lingering enabled"
  else
    echo "NOTE: user lingering is ${LINGER:-unavailable}; boot/login persistence is not verified."
    echo 'For an unattended node, after verifying the service: sudo loginctl enable-linger "$USER"'
  fi
fi
if [[ -n "${XDG_RUNTIME_DIR:-}" && -d "$XDG_RUNTIME_DIR" && -O "$XDG_RUNTIME_DIR" && -w "$XDG_RUNTIME_DIR" ]]; then
  echo "User runtime directory: present, owned by this user and writable"
else
  echo "WARN: XDG_RUNTIME_DIR is absent or unusable; inspect the user session before testing PipeWire/systemd."
fi
echo "Memory:"
free -h 2>/dev/null | head -n 2 || true
echo "Storage volumes:"
df -h "$HOME" 2>/dev/null || true
echo "NVMe:"
lsblk -o NAME,TYPE,SIZE,MOUNTPOINTS 2>/dev/null | grep -E '(NAME|nvme)' || true
echo "Audio subsystem:"
if test -r /proc/asound/cards; then cat /proc/asound/cards; else echo "ALSA cards unavailable"; fi
if command -v aplay >/dev/null 2>&1; then aplay -l 2>&1 || true; fi
if command -v arecord >/dev/null 2>&1; then arecord -l 2>&1 || true; fi
if command -v wpctl >/dev/null 2>&1; then
  # Bound a disconnected session-manager probe; do not mistake an installed binary for a running server.
  if command -v timeout >/dev/null 2>&1; then
    if AUDIO_STATUS="$(timeout 4s wpctl status 2>/dev/null)"; then
      echo "PipeWire/WirePlumber: reachable in this user session"
      printf '%s\n' "$AUDIO_STATUS" | sed -n '1,30p'
    else
      echo "WARN: wpctl is installed but the audio server/session is unreachable."
      echo "Inspect: systemctl --user status pipewire.service wireplumber.service"
    fi
  else
    echo "NOTE: wpctl probe skipped because timeout is unavailable; run wpctl status manually."
  fi
else
  echo "NOTE: wpctl not installed; optional PipeWire audio setup is documented in docs/AUDIO.md."
fi
echo "Network listener: not enabled by HaloLinux"
echo "HiFiBerry overlay, capture/playback, audio I/O and physical NVMe: not validated by this script."
echo "Preflight complete: $FAILURES prerequisite failure(s). This is not a runtime or hardware acceptance test."
(( FAILURES == 0 ))
