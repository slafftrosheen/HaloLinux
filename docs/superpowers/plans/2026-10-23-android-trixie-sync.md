# Android and Trixie Sync Implementation Plan

**Goal:** Reproducible Android portable-source alignment and an executable Trixie
Linux host baseline.

**Architecture:** Keep Android canonical, copy only an explicit portable module
set, validate provenance offline, and adapt Linux host/build code at its boundary.

**Tech stack:** Kotlin/JVM 21, Gradle 8.14.3, SQLite JDBC, Bash and Python 3.

**Spec:** `docs/superpowers/specs/2026-10-23-android-trixie-sync-design.md`.

## Global constraints

- Android SHA: `5a4047fec6dc9c561969c446641b22373d5157e2`.
- No Android modifications, GitHub Actions, new planner, implicit remote fallback,
  LAN management, automatic action replay or OS configuration mutations.
- Canonical files remain byte-identical to upstream; fixes belong in Linux code.
- Runtime target is Java 21; physical Pi validation is distinct from x86 JVM tests.

## Review focus

- A dirty upstream checkout must not masquerade as its committed SHA.
- A removed upstream source must not leave stale compiled local classes.
- Legacy master version-3 SQLite must not bypass main's migration assumptions.
- Canonical and standalone build modes must select the same portable contracts.
- Trixie Lite without login/audio services must get accurate diagnostics.

## Task 1: Shared source provenance

Files: `config/canonical-*`, `scripts/sync-canonical-sources.py`,
`scripts/upstream-drift.py`, `scripts/canonical-sdk-preflight.sh`, source roots
from the module allowlist, source-sync fixtures, shared-source documentation.

- [x] Add offline local verification and explicit reviewed-SHA import.
- [x] Exercise drift, deletion, dirty-source and repeatability fixtures.
- [x] Import the seven portable source modules verbatim and advance the pin.
- [x] Validate exact source parity against the Android checkout.

## Task 2: JVM integration

Files: root/module Gradle files, Linux host and adapter code only as needed,
wrapper/script modes, `scripts/verify-offline-source.sh`, verification docs.

- [x] Set JVM 21 toolchains consistently and cover provider-core source switching.
- [x] Execute standalone build; fix concrete compiler/runtime failures.
- [x] Execute canonical cognition and store/host tests against the reviewed SHA.
- [x] Run the packaged daemon smoke flow, without real models or network peers.

## Task 3: Existing data compatibility

Files: `LinuxStorage.kt`, `LinuxStorageTest.kt`, snapshot verification if needed.

- [x] Reproduce the master/main SQLite v3 lineage collision.
- [x] Reject incompatible lineage before changing the database.
- [x] Verify actionable rejection and preserved records; keep supported migration.

## Task 4: Trixie operation

Files: preflight/setup/install scripts, systemd unit, Pi/audio/headless docs,
isolated shell fixtures.

- [x] Detect real userspace architecture and the selected Java 21 JDK.
- [x] Explain optional audio and user manager/linger state accurately.
- [x] Preserve private, unprivileged, atomic installation semantics.
- [x] Verify scripts using fake commands and temporary fixture directories.

## Integration and publication

- [x] Review the complete diff independently and resolve concrete defects.
- [x] Record commands, test totals and hardware limitations in status docs.
- [x] Preserve reviewed master ancestry and useful executable-bit repair.
- [x] Prepare the reviewed tree for normal fast-forward publication to `main` and
  default `master`, preserving both histories. Remote publication is the final
  delivery step and its resulting commit is reported to the user.

## Execution notes

The user's request authorizes implementation. Work is split between source sync,
Trixie integration and legacy-storage compatibility, with the parent integrating
and running JVM builds. The fresh clone is isolated from the user's local work.
No automatic branch-setting change or unattended update job is part of this pass.


## Execution result

- Offline gate passed: source-sync regressions 18, Trixie script fixtures 17,
  snapshot verifier fixtures 15, module graph and actual SQLite DDL invariants.
- Clean standalone build and executable packaging passed with 83 JUnit tests
  (3 inference, 31 storage, 49 host), no failures/errors/skips.
- Complete optional canonical gate passed with 70 JUnit tests (2 cognition
  recovery contracts, 16 agent store, 52 host), no failures/errors/skips.
- Both exact executable artifacts passed isolated daemon smoke: initial state,
  no provider fallback, memory/FTS, integrity, no-overwrite backups, snapshot
  verification, confirmed JVM/socket shutdown and identity/data after restart.
  The canonical artifact also verified the separate agent snapshot.
- Independent review closed source-root/inventory bypasses, readonly-WAL
  mutation, orphan sidecars and temporary-capacity diagnostics. Actual execution
  exposed and resolved the JAR collision, missed JUnit methods, recovery-fixture
  initialization order and smoke-test Bash-wrapper PID ownership.
- No Pi hardware, ARM64 JNI, real model, audio I/O, systemd reboot, live Android
  data synchronization or automatic migration result is claimed.

The publication uses the mature main tree and an explicit reconciliation merge
for legacy master. Both remote refs must be checked before and after publishing;
no force push or branch-default metadata change is authorized by this plan.
