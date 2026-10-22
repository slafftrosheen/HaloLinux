#!/usr/bin/env bash
# Unprivileged install/update. Build before replacement; never touch user data.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/lib/host-platform.sh"
if [[ "$(id -u)" == 0 ]]; then
  echo "FAIL: run the user installer without sudo, as the account that will run HaloLinux." >&2
  exit 1
fi
halo_check_java build
BIN_DIR="$HOME/.local/bin"
LIB_DIR="$HOME/.local/lib/halo-linux"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
cd "$ROOT"
./gradlew :host:linux:clean :host:linux:shadowJar --no-daemon

# The clean task prevents a previous version from winning an arbitrary directory-order search.
shopt -s nullglob
JARS=()
for CANDIDATE in host/linux/build/libs/*.jar; do
  case "$CANDIDATE" in *-plain.jar|*-sources.jar|*-javadoc.jar) continue ;; esac
  JARS+=("$CANDIDATE")
done
if [[ ${#JARS[@]} -ne 1 || ! -s "${JARS[0]:-}" ]]; then
  echo "FAIL: expected exactly one fresh executable Halo node JAR" >&2
  exit 1
fi
JAR="${JARS[0]}"
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
JAVA_BIN=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
  [[ -x "$JAVA_BIN" ]] || { echo "FAIL: JAVA_HOME does not contain an executable bin/java" >&2; exit 1; }
fi
exec "$JAVA_BIN" -Djava.awt.headless=true -jar "$JAR" "$@"
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
echo 'For unattended boot, after verifying the service: sudo loginctl enable-linger "$USER"'
echo "Then explicitly enable it: systemctl --user enable halo-node"
echo "The installer does NOT start, restart or enable services automatically."
