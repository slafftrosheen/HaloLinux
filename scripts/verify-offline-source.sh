#!/usr/bin/env bash
# Offline source checks; no models, network access, or root permissions.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Checking shell syntax"
for script in scripts/*.sh; do bash -n "$script"; done
echo "Checking Python syntax without generating __pycache__"
python3 -B - <<'PY'
import ast
from pathlib import Path
for path in sorted(Path("scripts").glob("*.py")):
    ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
print("PASS: Python syntax")
PY
echo "Checking project dependencies"
python3 -B scripts/check-module-graph.py
echo "Checking snapshot verifier fixtures"
python3 -B scripts/check-snapshot-verifier.py
echo "Checking canonical agent SQLite DDL invariants"
python3 -B scripts/check-agent-journal-schema.py
echo "PASS: offline source gates (not a JVM or ARM64 build)"
