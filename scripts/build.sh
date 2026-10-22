#!/usr/bin/env bash
# HaloLinux build script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

echo "=== HaloLinux Build ==="
echo "Project root: $PROJECT_ROOT"

source "$SCRIPT_DIR/lib/host-platform.sh"
halo_check_java build

# Run Gradle build
echo "Running Gradle build..."
./gradlew build --no-daemon

echo "=== Build completed successfully ==="
