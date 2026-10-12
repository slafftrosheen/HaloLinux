#!/usr/bin/env bash
# Isolated, hardware-free daemon acceptance. Requires JDK 21, Python 3, and Gradle.
# Uses private temporary XDG paths; never reads or modifies installed Halo data.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./gradlew :host:linux:shadowJar --no-daemon
JAR="$(find host/linux/build/libs -maxdepth 1 -name '*.jar' ! -name '*plain*' ! -name '*sources*' -print -quit)"
test -n "$JAR" || { echo "FAIL: executable JAR not found" >&2; exit 1; }
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
  if [[ -n "$PID" ]]; then
    kill -TERM "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap clean EXIT
halo() { java -Duser.home="$WORK/home" -jar "$JAR" "$@"; }
start() {
  halo start >"$WORK/daemon.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 80); do
    if ! kill -0 "$PID" 2>/dev/null; then
      cat "$WORK/daemon.log" >&2; echo "FAIL: daemon exited" >&2; exit 1
    fi
    if halo status >"$WORK/status.json" 2>/dev/null; then return; fi
    sleep 0.2
  done
  cat "$WORK/daemon.log" >&2
  echo "FAIL: daemon status did not become reachable" >&2
  exit 1
}
stop() {
  kill -TERM "$PID"
  wait "$PID" 2>/dev/null || true
  PID=""
}
extract_node() {
  python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["control_protocol_version"]==1; assert x["runtime_health"]=="Healthy"; print(x["node_id"])' <"$WORK/status.json"
}
echo "Running isolated HaloLinux daemon smoke test"
start
python3 - "$WORK/status.json" <<'PY'
import json,sys
s = json.load(open(sys.argv[1]))
assert s["provider"]["phase"] == "DISABLED", s["provider"]
assert s["provider"]["attempts"] == 0
assert s["control_protocol_version"] == 1
PY
halo provider check >"$WORK/no-provider-check.json"
python3 - "$WORK/no-provider-check.json" <<'PY'
import json,sys
probe=json.load(open(sys.argv[1]))
assert probe["connection_verified"] is False
assert probe["phase"] == "DISABLED"
PY
if halo ask "This must not silently use a cloud provider" >"$WORK/unconfigured-ask.log" 2>&1; then
  echo "FAIL: an unconfigured model produced a successful answer" >&2; exit 1
fi
NODE_BEFORE="$(extract_node)"
test -n "$NODE_BEFORE" || { echo "FAIL: node identity empty" >&2; exit 1; }
halo memory add "halo-smoke-unique-5821" >"$WORK/insert.json"
halo memory search "halo-smoke-unique-5821" >"$WORK/search.json"
grep -q halo-smoke-unique-5821 "$WORK/search.json"
halo storage check >"$WORK/health.json"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["integrity"]=="ok"; assert x["memory_count"]==1' "$WORK/health.json"
halo storage backup "$WORK/backup.db" >"$WORK/backup.json"
test -s "$WORK/backup.db"
python3 - "$WORK/backup.db" <<'PY'
import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as db:
    assert db.execute("PRAGMA quick_check").fetchone()[0] == "ok"
    assert db.execute("SELECT COUNT(*) FROM memories").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM node_identity WHERE key='private_key'").fetchone()[0] == 1
PY
if halo storage backup "$WORK/backup.db" >/dev/null 2>&1; then
  echo "FAIL: backup overwrote existing file" >&2; exit 1
fi
mkdir -m 700 "$WORK/snapshots"
halo node backup "$WORK/snapshots/node-001" >"$WORK/node-backup.json"
python3 "$ROOT/scripts/verify-node-snapshot.py" "$WORK/snapshots/node-001"
echo "Restarting to verify durability"
stop
start
NODE_AFTER="$(extract_node)"
test "$NODE_BEFORE" = "$NODE_AFTER" || { echo "FAIL: node identity changed" >&2; exit 1; }
halo memory search "halo-smoke-unique-5821" >"$WORK/restarted.json"
grep -q halo-smoke-unique-5821 "$WORK/restarted.json"
stop
echo "PASS: IPC v1, runtime status, search, integrity, backup, no-overwrite, identity, restart"
echo "NOT VERIFIED: live inference, Pi hardware, STT/TTS, systemd startup, cross-device sync"
