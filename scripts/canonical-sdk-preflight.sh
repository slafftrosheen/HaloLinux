#!/usr/bin/env bash
# Verifies a local Halo checkout before optional canonical source-compilation.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HALO_ROOT="${1:-$ROOT/../Halo}"
[[ -d "$HALO_ROOT" ]] || { echo "Canonical checkout missing: $HALO_ROOT" >&2; exit 2; }
EXPECTED="$(tr -d '[:space:]' <"$ROOT/config/canonical-halo.sha")"
ACTUAL="$(git -C "$HALO_ROOT" rev-parse HEAD)"
[[ "$EXPECTED" == "$ACTUAL" ]] || {
  echo "ERROR: expected canonical Halo SHA $EXPECTED, found $ACTUAL" >&2
  echo "Create a detached worktree at the pinned SHA; do not reset your active Android checkout." >&2
  exit 1
}
for module in libraries/common libraries/halo-cognition-language sdk/host-sdk sdk/runtime-sdk sdk/provider-sdk runtime/events; do
  [[ -d "$HALO_ROOT/$module/src/main/java" ]] || {
    echo "Missing canonical module source: $module" >&2
    exit 1
  }
done
echo "PASS: canonical Halo SDK source tree found at pinned SHA $EXPECTED"
echo "To attempt integration compilation:"
echo "./gradlew -PhaloCanonicalRoot=$(realpath "$HALO_ROOT") clean build"
echo "NOTE: compilation has not been executed by this preflight script."
