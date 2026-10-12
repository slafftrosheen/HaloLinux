#!/usr/bin/env bash
# Opt-in integration proof for the *actual* Halo agent model and recovery planner.
# Requires a separate, pinned canonical Halo worktree.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HALO_ROOT="${1:-$ROOT/../HaloLinux-canonical}"
cd "$ROOT"
./scripts/canonical-sdk-preflight.sh "$HALO_ROOT"
echo "Compiling canonical portable SDK and 200+ cognition source files (opt-in)"
./gradlew -PhaloCanonicalRoot="$(realpath "$HALO_ROOT")" \
  -PhaloCanonicalCognition=true :runtime:cognition:compileKotlin \
  :runtime:cognition:test --no-daemon
echo "PASS: pinned canonical cognition contract tests completed"
echo "NOTE: agent execution remains disabled until a real host AgentRunStore and authorization bridge exist."
