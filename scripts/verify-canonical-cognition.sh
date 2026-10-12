#!/usr/bin/env bash
# Opt-in canonical AgentRunStore / recovery integration verification.
# Requires network-resolved JDK/Gradle dependencies and a separate pinned Halo checkout.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HALO_ROOT="${1:-$ROOT/../HaloLinux-canonical}"
cd "$ROOT"
./scripts/canonical-sdk-preflight.sh "$HALO_ROOT"
echo "Verifying canonical Halo cognition, Linux SQLite AgentRunStore, and opt-in host..."
./gradlew -PhaloCanonicalRoot="$(realpath "$HALO_ROOT")" \
  -PhaloCanonicalCognition=true \
  :runtime:cognition:compileKotlin \
  :runtime:cognition:test \
  :adapters:agent-store:clean \
  :adapters:agent-store:test \
  :host:linux:clean \
  :host:linux:test \
  :host:linux:shadowJar \
  --no-daemon
echo "PASS: pinned canonical cognition + agent store + host test tasks completed"
echo "NOTE: no agent actions or automatic recovery were executed by these tests."
