#!/usr/bin/env bash
# Isolated, model-free daemon acceptance. Never touches an installed user's Halo data.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./gradlew :host:linux:shadowJar --no-daemon
JAR="$(find host/linux/build/libs -maxdepth 1 -name '*.jar' ! -name '*plain*' ! -name '*sources*' -print -quit)"
test -n "$JAR" || { echo "Missing executable JAR" >&2; exit 1; }
JAR="$(realpath "$JAR")"
WORK="$(mktemp -d)"
mkdir -p "$WORK"/{run,config,data,cache,home}
chmod 700 "$WORK"/{run,config,data,cache,home}
export XDG_RUNTIME_DIR="$WORK/run"
export XDG_CONFIG_HOME="$WORK/config"
export XDG_DATA_HOME="$WORK/data"
export XDG_CACHE_HOME="$WORK/cache"
unset HALO_CONFIG_FILE HALO_INFERENCE_API_KEY
PID=""
clean() {
  if [[ -n "$PID" ]]; then kill -TERM "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap clean EXIT
halo() { java -Duser.home="$WORK/home" -jar "$JAR" "$@"; }
start() {
  halo start >"$WORK/daemon.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 60); do
    if ! kill -0 "$PID" 2>/dev/null; then cat "$WORK/daemon.log" >&2; exit 1; fi
    if halo status >"$WORK/status.json" 2>/dev/null; then return 0; fi
    sleep 0.2
  done
  echo "FAIL: daemon not responsive" >&2; cat "$WORK/daemon.log" >&2; exit 1
}
stop() {
  kill -TERM "$PID"
  wait "$PID" 2>/dev/null || true
  PID=""
}
echo "Starting isolated daemon"
start
NODE_BEFORE="$(sed -n 's/.*"node_id": *"\([^"]*\)".*/\1/p' "$WORK/status.json")"
test -n "$NODE_BEFORE" || { echo "FAIL: no node identity" >&2; exit 1; }
halo memory add "halo-smoke-unique-5821" >"$WORK/insert.json"
halo memory search "halo-smoke-unique-5821" >"$WORK/search.json"
grep -q halo-smoke-unique-5821 "$WORK/search.json"
echo "Restarting daemon to verify durable storage"
stop
start
NODE_AFTER="$(sed -n 's/.*"node_id": *"\([^"]*\)".*/\1/p' "$WORK/status.json")"
test "$NODE_BEFORE" = "$NODE_AFTER" || { echo "FAIL: node identity changed" >&2; exit 1; }
halo memory search "halo-smoke-unique-5821" >"$WORK/after.json"
grep -q halo-smoke-unique-5821 "$WORK/after.json"
echo "PASS: daemon request, local search, persistent identity and restart"
echo "NOT VERIFIED: real inference, ARM64, systemd user service, audio hardware, peer sync"
