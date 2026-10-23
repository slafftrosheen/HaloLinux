# Android Base and Linux Dispatch Sync Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this continuation.

**Goal:** Update the reviewed portable base and enforce explicit remote-processing
consent in the existing Linux provider factory.
**Architecture:** Unmodified canonical SDK files, a Linux policy implementation,
and a pure JVM endpoint classifier composed at the existing HTTP boundary.
**Tech Stack:** Kotlin/JVM, JDK21, existing OkHttp/coroutines, Python offline tooling.
**Spec:** `docs/superpowers/specs/2026-10-23-base-privacy-sync-design.md`

## Global constraints
No Android changes, GitHub Actions, network API, runtime Brain integration, hidden
fallback, secret logging, or OS upgrades. Keep all existing work/history.

## Review focus
- Consent and queued-request revisions cannot be inferred from endpoint/credential presence.
- Loopback names, IPv6, proxy use and redirects cannot leak a private request.
- Unsupported categories and provider identities fail before network access.
- Concurrent close/admission/finish must revoke once without callbacks under locks.
- Snapshot pin/manifest and the exact seven source trees must remain consistent.

## Tasks
- [x] Copy reviewed upstream SDK deltas and prove complete tree identity.
- [x] Write failing policy/endpoint tests; implement `LinuxInferenceEndpoint` and
      `LinuxRemoteDispatchPolicy` in `host/linux/src/main/java/com/halo/host/linux`.
- [x] Add strict opt-in parsing to NodeConfig, compose the canonical guard at the
      existing provider factory and preserve diagnostics/cleanup behavior.
- [x] Update source manifest/pin and operator documentation; run available gates,
      record unavailable build/hardware gates, review and prepare guarded publication.
      Final remote outcome is recorded in the delivery message.

## Execution ledger
Ruling: use an isolated partial checkout reconstructed from connector reads;
direct clone failed DNS resolution. Do not claim a full repository build.
Ruling: compile real policy/endpoint code against exact canonical contract
_declarations_ for the offline gate; full SDK/HTTP integration needs Gradle.
