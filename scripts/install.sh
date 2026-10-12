#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$HOME/.local/bin"
LIB_DIR="$HOME/.local/lib/halo-linux"
SERVICE_DIR="$HOME/.config/systemd/user"
cd "$ROOT"
./gradlew :host:linux:shadowJar --no-daemon
mkdir -p "$BIN_DIR" "$LIB_DIR" "$SERVICE_DIR"
JAR="$(find host/linux/build/libs -maxdepth 1 -name '*.jar' ! -name '*plain*' ! -name '*sources*' -print -quit)"
test -n "$JAR" || { echo "Fat JAR not found" >&2; exit 1; }
install -m 0600 "$JAR" "$LIB_DIR/halo-node.jar"
cat > "$BIN_DIR/halo-node" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
JAR="$HOME/.local/lib/halo-linux/halo-node.jar"
exec java -Djava.awt.headless=true -jar "$JAR" "$@"
LAUNCHER
chmod 0755 "$BIN_DIR/halo-node"
install -m 0644 services/systemd/halo-node.service "$SERVICE_DIR/halo-node.service"
if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
fi
echo "Installed $BIN_DIR/halo-node"
echo "Start: systemctl --user start halo-node"
echo "Headless service after logout may require: loginctl enable-linger $USER"
