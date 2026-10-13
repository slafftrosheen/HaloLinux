#!/usr/bin/env bash
# Offline source checks; no models, network access, or root permissions.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Checking shell syntax"
for script in scripts/*.sh; do bash -n "$script"; done
echo "Checking Python syntax"
python3 -m compileall -q scripts
echo "Checking project dependencies"
python3 scripts/check-module-graph.py
echo "Checking snapshot verifier fixtures"
python3 scripts/check-snapshot-verifier.py
echo "Checking canonical agent SQLite DDL invariants"
python3 scripts/check-agent-journal-schema.py
echo "PASS: offline source gates (not a JVM or ARM64 build)"
