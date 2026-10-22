#!/usr/bin/env bash
# Shared, read-only platform checks. Source this file; do not execute OS changes.

halo_os_release_value() {
  local key="$1" file="$2" line value
  [[ -r "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == "$key="* ]] || continue
    value="${line#*=}"
    if [[ ${#value} -ge 2 && ( "$value" == \"*\" || "$value" == \'*\' ) ]]; then
      value="${value:1:${#value}-2}"
    fi
    printf '%s\n' "$value"
    return 0
  done < "$file"
}

halo_check_architecture() {
  local kernel_arch="$1" userspace_arch="$2"
  case "$kernel_arch:$userspace_arch" in
    aarch64:arm64|x86_64:amd64) return 0 ;;
    aarch64:unknown|x86_64:unknown)
      echo "WARN: userspace architecture unavailable; confirm a 64-bit installation." >&2
      return 0 ;;
    *)
      echo "FAIL: unsupported kernel/userspace combination: $kernel_arch / $userspace_arch." >&2
      echo "Use 64-bit ARM64 on Pi 5 or x86-64 on Linux PCs; an aarch64 kernel alone is insufficient." >&2
      return 1 ;;
  esac
}

halo_check_java() {
  local mode="$1" java_bin javac_bin version major compiler
  # Match Gradle's JAVA_HOME precedence instead of inspecting a different JVM on PATH.
  if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$JAVA_HOME/bin/java"
    javac_bin="$JAVA_HOME/bin/javac"
  else
    java_bin="$(command -v java || true)"
    javac_bin="$(command -v javac || true)"
  fi
  if [[ -z "$java_bin" || ! -x "$java_bin" ]]; then
    echo "FAIL: Java unavailable. Install openjdk-21-jdk-headless for builds or openjdk-21-jre-headless for runtime." >&2
    echo "Check JAVA_HOME if it is set; it takes precedence over PATH." >&2
    return 1
  fi
  if ! version="$("$java_bin" -version 2>&1)"; then
    echo "FAIL: selected Java could not run. Check JAVA_HOME and the installed JDK." >&2
    return 1
  fi
  # The JVM may print JAVA_TOOL_OPTIONS before its version. Never echo those
  # options: they can contain private paths or credentials supplied by a user.
  version="$(printf '%s\n' "$version" | sed -nE '/^(openjdk|java) version "[0-9]+/ {p;q;}')"
  major="$(printf '%s\n' "$version" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
  echo "Java: $version"
  if ! [[ "$major" =~ ^[0-9]+$ ]] || (( major < 21 )); then
    echo "FAIL: Java 21 or newer is required to run the node." >&2
    return 1
  fi
  if [[ "$mode" == build ]]; then
    if (( major != 21 )); then
      echo "FAIL: source builds use the JDK 21 baseline; select JDK 21 with JAVA_HOME." >&2
      echo "A newer runtime is not proof that the pinned Gradle/plugins support that JDK." >&2
      return 1
    fi
    if [[ -z "$javac_bin" || ! -x "$javac_bin" ]] || ! compiler="$("$javac_bin" -version 2>&1)"; then
      echo "FAIL: source builds require javac from openjdk-21-jdk-headless; a JRE is insufficient." >&2
      return 1
    fi
    compiler="$(printf '%s\n' "$compiler" | sed -nE '/^javac [0-9]+/ {p;q;}')"
    if ! [[ "$compiler" =~ ^javac[[:space:]]21([.]|[[:space:]]|$) ]]; then
      echo "FAIL: java and javac must both be JDK 21 for source builds. Select a single JDK with JAVA_HOME." >&2
      return 1
    fi
    echo "Compiler: $compiler"
  elif (( major != 21 )); then
    echo "WARN: Java $major detected; Java 21 is the reference runtime and source-build baseline."
  fi
}
