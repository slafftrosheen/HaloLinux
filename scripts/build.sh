#!/usr/bin/env bash
# HaloLinux build script

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

echo "=== HaloLinux Build ==="
echo "Project root: $PROJECT_ROOT"

# Check Java version
if command -v java >/dev/null 2>&1; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
    echo "Java version: $JAVA_VERSION"
else
    echo "ERROR: Java not found. Please install Java 21+"
    exit 1
fi

# Run Gradle build
echo "Running Gradle build..."
./gradlew build --no-daemon

echo "=== Build completed successfully ==="