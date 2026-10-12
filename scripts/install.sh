#!/usr/bin/env bash
# Unprivileged install/update. Build before replacement; never touch user data.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$HOME/.local/bin"
LIB_DIR="$HOME/.local/lib/halo-linux"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
cd "$ROOT"
./gradlew :host:linux:shadowJar --no-daemon

JAR="$(find host/linux/build/libs -maxdepth 1 -name '*.jar' ! -name '*plain*' ! -name '*sources*' -print -quit)"
test -n "$JAR" && test -s "$JAR" || {
  echo "FAIL: executable Halo node JAR not found" >&2
  exit 1
}
mkdir -p "$BIN_DIR" "$LIB_DIR" "$SERVICE_DIR"
chmod 700 "$LIB_DIR"

# Atomic replacement means an already-running JVM can finish reading its old
# inode; no JAR is ever truncated beneath a live daemon.
STAGING="$(mktemp "$LIB_DIR/.halo-node.XXXXXXXX.jar")"
cleanup() { rm -f "$STAGING"; }
trap cleanup EXIT
install -m 0600 "$JAR" "$STAGING"
mv -f "$STAGING" "$LIB_DIR/halo-node.jar"

LAUNCHER="$(mktemp "$BIN_DIR/.halo-node.XXXXXXXX")"
trap 'rm -f "$STAGING" "$LAUNCHER"' EXIT
cat > "$LAUNCHER" <<'LAUNCHER_EOF'
#!/usr/bin/env bash
set -euo pipefail
umask 077
JAR="$HOME/.local/lib/halo-linux/halo-node.jar"
exec java -Djava.awt.headless=true -jar "$JAR" "$@"
LAUNCHER_EOF
chmod 0755 "$LAUNCHER"
mv -f "$LAUNCHER" "$BIN_DIR/halo-node"
install -m 0644 services/systemd/halo-node.service "$SERVICE_DIR/halo-node.service"
if command -v systemctl >/dev/null 2>&1; then
  if ! systemctl --user daemon-reload; then
    echo "NOTE: user systemd unavailable; manual CLI startup is still supported." >&2
  fi
fi

echo "Installed: $BIN_DIR/halo-node"
echo "Next: $BIN_DIR/halo-node doctor"
echo "Start manually: $BIN_DIR/halo-node start"
echo "Or use: systemctl --user start halo-node"
echo "The installer does NOT start, restart or enable services automatically."
