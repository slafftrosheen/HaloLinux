# HaloLinux implementation status — HALO-LINUX-007

## Implemented in source, not build-verified
- Gradle module-direction fix (`runtime-sdk` depends on portable `host-sdk`, not vice versa).
- Real limited runtime composition: shared event bus, resource lease manager, lifecycle.
- Commands/queries intentionally reject unsupported execution.
- Unix-domain socket control v1, bounds and single-instance protection.
- Explicit memory search/delete, conversation recording, local SQLite integrity diagnostics.
- SQLite snapshot backup with no-overwrite policy and private identity warning.
- Read-only Linux memory/thermal reporting and test fixtures.
- Trixie Desktop onboarding helper and documented later headless transition.
- Expanded smoke tests, config and storage tests.

## Not verified by execution in this development environment
- Gradle compile/test and shadow JAR launch (dependencies unavailable).
- Live model inference, Pi 5 ARM64 native SQLite, SQLite VACUUM INTO acceptance.
- systemd user-service behavior, thermals, NVMe mount and actual HiFiBerry I/O.
- All-day stability and error-recovery testing.

## Remaining architectural work
- Shared SDKs are still copied. See `docs/SHARED-SOURCE-STRATEGY.md`.
- Conversation and provider interaction remain direct SDK-backed operations, **not**
  the full canonical Halo CognitiveSession/agent execution pipeline.
- Streaming audio/STT/TTS, autonomous actions and peer sync are not implemented.
- `main` has the new code, but GitHub default branch remains `master` until
  manually changed in repository settings.

## Next pass before physical deployment
1. Execute an actual Linux JDK 21 build and fix any compile/runtime defects.
2. Run `scripts/smoke-test.sh`; no source review can replace this.
3. Confirm ARM64 native SQLite on a Pi or ARM64 test machine.
4. Install on Trixie Desktop, collect logs and fix observed hardware integration.

## Static repository audit

The current 13-module build-file dependency graph was checked through the GitHub
source API: no missing project references and no cycles. An offline equivalent
is provided by `python3 scripts/check-module-graph.py`.

This is *not* JVM compilation evidence and cannot establish ARM64 JNI support.
