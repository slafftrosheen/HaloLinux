#!/usr/bin/env bash
set -euo pipefail
if command -v halo-node >/dev/null 2>&1; then
    exec halo-node doctor
fi
echo "HaloLinux is not installed; showing public environment diagnostics only."
uname -srm
java -version 2>&1 | head -3 || true
test -d /proc/asound && echo "ALSA subsystem detected" || echo "ALSA subsystem unavailable"
command -v pw-cli >/dev/null 2>&1 && echo "PipeWire CLI available" || echo "PipeWire CLI unavailable"
echo "No configuration contents or secrets displayed."
