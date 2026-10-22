#!/usr/bin/env bash
# Checks exact production sources and snapshot provenance before canonical compilation.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HALO_ROOT="${1:-$ROOT/../Halo}"
[[ -d "$HALO_ROOT" ]] || { echo "Canonical checkout missing: $HALO_ROOT" >&2; exit 2; }
python3 -B "$ROOT/scripts/sync-canonical-sources.py" --halo-repo "$HALO_ROOT"
echo "To attempt integration compilation:"
printf './gradlew -PhaloCanonicalRoot=%q clean build\n' "$(realpath "$HALO_ROOT")"
echo "NOTE: compilation has not been executed by this preflight script."
