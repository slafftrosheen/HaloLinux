#!/usr/bin/env bash
# Read-only Pi/Linux deployment preflight; does not install packages or change OS settings.
set -euo pipefail
echo "HaloLinux deployment preflight — no system changes"
echo "Kernel: $(uname -sr)"
ARCH="$(uname -m)"
echo "Architecture: $ARCH"
case "$ARCH" in aarch64|x86_64) echo "Architecture: supported target (runtime unverified)";; *) echo "WARNING: non-reference architecture";; esac
if ! command -v java >/dev/null 2>&1; then
  echo "FAIL: Java 21 is required" >&2; exit 1
fi
JAVA_MAJOR="$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')"
echo "Java: $(java -version 2>&1 | head -n 1)"
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 21 )); then
  echo "FAIL: Java 21+ is required" >&2; exit 1
fi
if command -v systemctl >/dev/null 2>&1; then
  echo "systemd user: $(systemctl --user is-system-running 2>/dev/null || true)"
else echo "WARN: systemd unavailable"; fi
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
if command -v wpctl >/dev/null 2>&1; then wpctl status 2>/dev/null | head -n 30 || true; fi
echo "Network listener: not enabled by HaloLinux"
echo "HiFiBerry overlay, capture/playback, audio I/O and physical NVMe: not validated by this script."
echo "Preflight complete. This is not a successful runtime or hardware acceptance test."
