# Linux Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan with test gates and review.

**Goal:** Make the canonical Halo Storage interface durable on Linux without changing the existing node database.

**Architecture:** Host-owned versioned file snapshot with checksum, interprocess lock, atomic replacement and private XDG storage; injected through the existing LinuxRuntimeBridge service registry.

**Tech Stack:** Kotlin/JVM, JDK21 NIO, existing canonical Storage contract.

**Spec:** `docs/superpowers/specs/2026-10-23-linux-preferences-design.md`

## Global constraints

Android is source of truth, no edits to SDK snapshots, no GitHub Actions, no automatic network or OS configuration, no modification to native node database, preserve 0700/0600.

## Review focus

Uncommitted batch on failure; interprocess lost updates; malicious symlinks/hardlinks; corrupt/truncated file read; SIGKILL between fsync and atomic rename; control-plane call when config directory absent.

### Task 1 — Durable storage adapter
- [x] Write failing executable Kotlin tests for the canonical Storage interface.
- [x] Compile/run and observe missing adapter failure.
- [x] Implement `LinuxPreferenceStorage` with framed binary snapshot and checksums, file ownership checks, process lock and atomic commit.
- [x] Run Kotlin contract tests as root and normal user including concurrent child processes and crash injection.

### Task 2 — Runtime wiring
- [x] Inject/register one Storage adapter using actual initialized XDG config directory; avoid accidental `halo.db` schema changes.
- [x] Check component wiring and production dependency graph statically; run full Gradle gate if full repo/dependencies available.

### Task 3 — Delivery
- [x] Update operator documentation with data handling and recovery semantics.
- [x] Verify all changed blob hashes and scope, run tests again, and publish with guarded branch advancement.
