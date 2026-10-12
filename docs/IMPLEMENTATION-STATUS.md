# HALO-LINUX-002 implementation status

**Source commits on main; no JVM build or real Pi validation is claimed by this document.**

## Implemented in source (requires execution verification)
- Gradle module graph and wrapper rationalization.
- SQLite migration transactions, memory FTS5 and persistence operations.
- SDK-backed OpenAI-compatible model calls in a Unix-socket daemon.
- Explicit memory add/search commands with no inference dependency.
- Local daemon command/status protocol.
- Configuration loading and masked display.
- Ed25519 key generation for local node identity; stored in private XDG data directory.
- Minimal unprivileged user systemd service and user-local installer.
- Revised provider fixture tests and SQLite round-trip tests.

## Not yet verified
- Gradle clean build (this environment cannot download Gradle/Maven dependencies).
- Real model endpoint requests, SQLite tests, daemon health and systemd lifecycle.
- ARM64, Raspberry Pi hardware, HiFiBerry audio and NVMe deployment.

## Remaining before 002 is done
- Execute Gradle build/test and fix compile or API version mismatches.
- Add daemon integration tests including socket access and cancellation.
- Converge copied SDK modules to a single source of truth with Android Halo.
- Audit unsafe provider behaviors and more precise resource health.
- Add explicit database backup/corruption/recovery tests.
- Confirm packaging and private-key permissions on ARM64.
- Implement full agentic cognition only by adopting canonical Halo cognition.
- Correct repository default branch from master to main (GitHub repository admin access required).
