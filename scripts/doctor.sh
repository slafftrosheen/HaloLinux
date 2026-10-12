#!/usr/bin/env bash
# HaloLinux diagnostic script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== HaloLinux Diagnostics ==="
echo ""

# System information
echo "--- System Information ---"
echo "OS: $(uname -s)"
echo "Kernel: $(uname -r)"
echo "Architecture: $(uname -m)"
echo "Hostname: $(hostname)"
echo ""

# CPU information
echo "--- CPU Information ---"
if [[ -f /proc/cpuinfo ]]; then
    grep -m1 "model name" /proc/cpuinfo | cut -d: -f2 | xargs
    echo "Cores: $(nproc)"
fi
echo ""

# Memory information
echo "--- Memory Information ---"
if [[ -f /proc/meminfo ]]; then
    TOTAL_MEM=$(grep MemTotal /proc/meminfo | awk '{print $2}')
    AVAIL_MEM=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
    echo "Total: $((TOTAL_MEM / 1024)) MB"
    echo "Available: $((AVAIL_MEM / 1024)) MB"
fi
echo ""

# Disk information
echo "--- Disk Information ---"
df -h "$HOME" | tail -1 | awk '{print "Home: " $4 " free of " $2 " (" $5 " used)"}'
df -h / | tail -1 | awk '{print "Root: " $4 " free of " $2 " (" $5 " used)"}'
echo ""

# Java version
echo "--- Java Runtime ---"
if command -v java >/dev/null 2>&1; then
    java -version 2>&1 | head -3
else
    echo "Java: NOT FOUND"
fi
echo ""

# Check for HaloLinux installation
echo "--- HaloLinux Installation ---"
if command -v halo-node >/dev/null 2>&1; then
    echo "halo-node: $(which halo-node)"
    halo-node --version 2>&1 || true
else
    echo "halo-node: NOT INSTALLED"
fi

JAR_PATHS=(
    "/usr/local/lib/halo-linux/halo-node.jar"
    "$HOME/.local/lib/halo-linux/halo-node.jar"
)
for jar in "${JAR_PATHS[@]}"; do
    if [[ -f "$jar" ]]; then
        echo "Runtime JAR: $jar ($(stat -c%s "$jar" 2>/dev/null | numfmt --to=iec 2>/dev/null || stat -c%s "$jar") bytes)"
    fi
done
echo ""

# Audio system
echo "--- Audio System ---"
if [[ -f /proc/asound/cards ]]; then
    echo "ALSA: Available"
    cat /proc/asound/cards
else
    echo "ALSA: Not available"
fi

if command -v pw-cli >/dev/null 2>&1; then
    echo "PipeWire: Available (pw-cli found)"
    pw-cli info 0 2>/dev/null | head -5 || echo "  PipeWire daemon not running"
else
    echo "PipeWire: Not available (pw-cli not found)"
fi
echo ""

# Network
echo "--- Network ---"
ip route get 1.1.1.1 2>/dev/null | head -1 | awk '{print "Default route: via " $3 " dev " $5}' || echo "No default route"
echo "Interfaces:"
ip -brief addr show | grep -v "lo " | awk '{print "  " $1 ": " $3}'
echo ""

# HaloLinux data directories
echo "--- HaloLinux Data Directories ---"
DIRS=(
    "$HOME/.local/share/halo-linux"
    "$HOME/.config/halo-linux"
    "$HOME/.cache/halo-linux"
)
for dir in "${DIRS[@]}"; do
    if [[ -d "$dir" ]]; then
        SIZE=$(du -sh "$dir" 2>/dev/null | cut -f1)
        echo "$dir: EXISTS ($SIZE)"
        ls -la "$dir" | tail -5 | sed 's/^/  /'
    else
        echo "$dir: NOT FOUND"
    fi
done
echo ""

# Systemd service status
echo "--- Systemd Service ---"
if command -v systemctl >/dev/null 2>&1; then
    systemctl --user status halo-node --no-pager 2>/dev/null | head -20 || echo "Service not installed or not running"
else
    echo "systemctl: Not available"
fi
echo ""

# Configuration file
echo "--- Configuration ---"
CONFIG_FILES=(
    "$HOME/.config/halo-linux/config.yaml"
    "$HOME/.config/halo-linux/halo-node.yaml"
    "/etc/halo-linux/config.yaml"
)
for cfg in "${CONFIG_FILES[@]}"; do
    if [[ -f "$cfg" ]]; then
        echo "Config: $cfg"
        head -30 "$cfg" | sed 's/^/  /'
    fi
done
if ! ls "${CONFIG_FILES[@]}" >/dev/null 2>&1; then
    echo "No configuration file found"
fi
echo ""

echo "=== Diagnostics Complete ==="