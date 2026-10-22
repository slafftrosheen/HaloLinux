#!/usr/bin/env bash
# Isolated, hardware-free daemon acceptance. Builds by default; --jar tests an exact artifact.
# Uses private temporary XDG paths; never reads or modifies installed Halo data.
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/lib/host-platform.sh"
cd "$ROOT"
if [[ $# -eq 0 ]]; then
  halo_check_java build
  ./gradlew :host:linux:clean :host:linux:shadowJar --no-daemon
  shopt -s nullglob
  JARS=()
  for CANDIDATE in host/linux/build/libs/*.jar; do
    case "$CANDIDATE" in *-plain.jar|*-sources.jar|*-javadoc.jar) continue ;; esac
    JARS+=("$CANDIDATE")
  done
  [[ ${#JARS[@]} -eq 1 ]] || { echo "FAIL: expected exactly one fresh executable JAR" >&2; exit 1; }
  JAR="${JARS[0]}"
elif [[ $# -eq 2 && "$1" == --jar ]]; then
  JAR="$2"
else
  echo "Usage: $0 [--jar /absolute/path/to/verified-halo-node.jar]" >&2
  exit 2
fi
halo_check_java runtime
[[ -f "$JAR" && -r "$JAR" && -s "$JAR" ]] || { echo "FAIL: executable JAR not readable" >&2; exit 1; }
JAR="$(realpath -- "$JAR")"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
WORK="$(mktemp -d /tmp/halo-smoke.XXXXXXXX)"
mkdir -p "$WORK"/{run,config,data,cache,home}
chmod 700 "$WORK"/{run,config,data,cache,home}
export XDG_RUNTIME_DIR="$WORK/run"
export XDG_CONFIG_HOME="$WORK/config"
export XDG_DATA_HOME="$WORK/data"
export XDG_CACHE_HOME="$WORK/cache"
unset HALO_CONFIG_FILE HALO_INFERENCE_API_KEY
PID=""
GENERATION=0
DAEMON_EXIT=0
SOCKET="$WORK/run/halo-linux/node.sock"
HALO_JAVA=("$JAVA_BIN" "-Duser.home=$WORK/home" -jar "$JAR")
clean() {
  local result=$? step
  trap - EXIT INT TERM
  set +e
  if [[ -n "$PID" ]]; then
    kill -TERM "$PID" 2>/dev/null || true
    for step in {1..100}; do
      kill -0 "$PID" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$PID" 2>/dev/null; then
      echo "FAIL: cleanup required SIGKILL for daemon PID $PID" >&2
      kill -KILL "$PID" 2>/dev/null || true
      result=1
    fi
    wait "$PID" 2>/dev/null || true
  fi
  if (( result != 0 )); then
    echo "FAIL: smoke test exited with status $result. Private fixture logs retained at: $WORK" >&2
    for log in "$WORK"/daemon-*.log; do
      [[ -f "$log" ]] || continue
      echo "Daemon log: $log" >&2
      tail -n 80 "$log" >&2
    done
  else
    rm -rf "$WORK"
  fi
  exit "$result"
}
trap clean EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
halo() { "${HALO_JAVA[@]}" "$@"; }
reap() {
  DAEMON_EXIT=0
  wait "$PID" 2>/dev/null || DAEMON_EXIT=$?
  PID=""
}
start() {
  [[ ! -e "$SOCKET" && ! -L "$SOCKET" ]] || {
    echo "FAIL: control socket survived the previous stop; refusing to reuse a live/stale endpoint" >&2
    exit 1
  }
  GENERATION=$((GENERATION + 1))
  # Background the JVM itself. A background shell function can leave an orphan
  # JVM after its wrapper receives SIGTERM, letting restart probe the old server.
  "${HALO_JAVA[@]}" start >"$WORK/daemon-$GENERATION.log" 2>&1 &
  PID=$!
  for _ in {1..80}; do
    if ! kill -0 "$PID" 2>/dev/null; then
      reap
      echo "FAIL: daemon exited before readiness (exit $DAEMON_EXIT)" >&2
      exit 1
    fi
    if halo status >"$WORK/status.json" 2>"$WORK/status-error.log"; then
      if ! kill -0 "$PID" 2>/dev/null; then
        reap
        echo "FAIL: daemon died during readiness (exit $DAEMON_EXIT)" >&2
        exit 1
      fi
      echo "Daemon generation $GENERATION ready (PID $PID)"
      return
    fi
    sleep 0.2
  done
  echo "FAIL: daemon status did not become reachable" >&2
  exit 1
}
stop() {
  local step
  if ! kill -0 "$PID" 2>/dev/null; then
    reap
    echo "FAIL: daemon died before the requested stop (exit $DAEMON_EXIT)" >&2
    exit 1
  fi
  if ! kill -TERM "$PID"; then
    reap
    echo "FAIL: daemon vanished during stop (exit $DAEMON_EXIT)" >&2
    exit 1
  fi
  for step in {1..150}; do
    kill -0 "$PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$PID" 2>/dev/null; then
    echo "FAIL: daemon did not stop within 15 seconds" >&2
    exit 1
  fi
  reap
  if [[ "$DAEMON_EXIT" != 0 && "$DAEMON_EXIT" != 143 ]]; then
    echo "FAIL: unexpected daemon exit status after SIGTERM: $DAEMON_EXIT" >&2
    exit 1
  fi
  [[ ! -e "$SOCKET" && ! -L "$SOCKET" ]] || {
    echo "FAIL: daemon exited without removing its control socket" >&2
    exit 1
  }
}
extract_node() {
  python3 -c 'import json,sys; x=json.load(sys.stdin); assert x["control_protocol_version"]==1; assert x["runtime_health"]=="Healthy"; print(x["node_id"])' <"$WORK/status.json"
}
echo "Running isolated HaloLinux daemon smoke test"
echo "Artifact: $JAR"
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
