#!/usr/bin/env bash
# HaloLinux test script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

echo "=== HaloLinux Tests ==="
echo "Project root: $PROJECT_ROOT"

# Run all unit tests
echo "Running unit tests..."
./gradlew test --no-daemon

echo "=== Tests completed ==="