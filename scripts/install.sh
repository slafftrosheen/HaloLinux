#!/usr/bin/env bash
# HaloLinux installation script for Linux systems

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Default installation paths
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"
BIN_DIR="$INSTALL_PREFIX/bin"
LIB_DIR="$INSTALL_PREFIX/lib/halo-linux"
SERVICE_DIR="/etc/systemd/user"
USER_SERVICE_DIR="$HOME/.config/systemd/user"

echo "=== HaloLinux Installation ==="
echo "Project root: $PROJECT_ROOT"
echo "Install prefix: $INSTALL_PREFIX"

# Check if running as root for system-wide install
if [[ "$INSTALL_PREFIX" != "/usr/local" ]] || [[ $EUID -ne 0 ]]; then
    echo "Installing for current user..."
    BIN_DIR="$HOME/.local/bin"
    LIB_DIR="$HOME/.local/lib/halo-linux"
    SERVICE_DIR="$USER_SERVICE_DIR"
    mkdir -p "$BIN_DIR" "$LIB_DIR" "$SERVICE_DIR"
else
    echo "Installing system-wide (requires root)..."
    mkdir -p "$BIN_DIR" "$LIB_DIR" "$SERVICE_DIR"
fi

# Build the project first
echo "Building project..."
cd "$PROJECT_ROOT"
./gradlew :host:linux:shadowJar --no-daemon

# Find the built JAR
JAR_PATH=$(find "$PROJECT_ROOT/host/linux/build/libs" -name "*.jar" | grep -v sources | grep -v javadoc | head -1)
if [[ -z "$JAR_PATH" ]]; then
    echo "ERROR: Built JAR not found"
    exit 1
fi

echo "Found JAR: $JAR_PATH"

# Copy JAR and dependencies
echo "Installing runtime..."
cp "$JAR_PATH" "$LIB_DIR/halo-node.jar"

# Create launcher script
cat > "$BIN_DIR/halo-node" << 'EOF'
#!/usr/bin/env bash
# Halo Linux Node launcher

HALO_NODE_HOME="${HALO_NODE_HOME:-$HOME/.local/share/halo-linux}"
HALO_NODE_CONFIG="${HALO_NODE_CONFIG:-$HOME/.config/halo-linux}"
HALO_NODE_CACHE="${HALO_NODE_CACHE:-$HOME/.cache/halo-linux}"

export HALO_NODE_HOME HALO_NODE_CONFIG HALO_NODE_CACHE

# JVM options
JVM_OPTS=(
    "-Xms256m"
    "-Xmx2g"
    "-XX:+UseG1GC"
    "-XX:MaxGCPauseMillis=100"
    "-Dfile.encoding=UTF-8"
    "-Djava.awt.headless=true"
    "-Dhalo.node.home=$HALO_NODE_HOME"
    "-Dhalo.node.config=$HALO_NODE_CONFIG"
    "-Dhalo.node.cache=$HALO_NODE_CACHE"
)

# Module path for JavaFX if needed
MODULE_PATH=""

# Find Java
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="java"
fi

# Find the JAR
JAR_PATH="${HALO_NODE_JAR:-/usr/local/lib/halo-linux/halo-node.jar}"
if [[ ! -f "$JAR_PATH" ]]; then
    # Try user install location
    JAR_PATH="$HOME/.local/lib/halo-linux/halo-node.jar"
fi

if [[ ! -f "$JAR_PATH" ]]; then
    echo "ERROR: halo-node.jar not found at $JAR_PATH" >&2
    echo "Set HALO_NODE_JAR environment variable or install HaloLinux" >&2
    exit 1
fi

exec "$JAVA_CMD" "${JVM_OPTS[@]}" -jar "$JAR_PATH" "$@"
EOF

chmod +x "$BIN_DIR/halo-node"

# Install systemd user service
echo "Installing systemd service..."
cp "$PROJECT_ROOT/services/systemd/halo-node.service" "$SERVICE_DIR/"

# Reload systemd
if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload 2>/dev/null || true
fi

echo ""
echo "=== Installation Complete ==="
echo ""
echo "Binary installed to: $BIN_DIR/halo-node"
echo "Runtime installed to: $LIB_DIR/halo-node.jar"
echo "Systemd service installed to: $SERVICE_DIR/halo-node.service"
echo ""
echo "To start the service:"
echo "  systemctl --user start halo-node"
echo "  systemctl --user enable halo-node"
echo ""
echo "To run directly:"
echo "  halo-node --version"
echo "  halo-node doctor"
echo "  halo-node start"
echo ""
echo "Note: Ensure Java 21+ is installed and on PATH"