# HaloLinux implementation status — HALO-LINUX-050

**Updated:** 2026-10-23 Europe/Riga (execution logs dated 2026-10-22 UTC).

The Linux JVM implementation now builds and passes executable tests on x86-64.
Android portable sources are pinned and reproducible. Raspberry Pi 5 hardware
acceptance and Android user-data synchronization remain separate unfinished work.
Historical source-only claims are preserved in the
[001–049 record](history/IMPLEMENTATION-STATUS-001-049.md); they are not the current
verification status.

## Source and build baseline

| Item | Reviewed baseline |
| --- | --- |
| Linux integration base | `cf084290b6493014698279da799b75aac111b635` on `main` |
| Legacy branch reviewed | `f975c97dd6c5bdc22fa15d4f580f43d6bfea2b66` on `master` |
| Canonical Android Halo | `5a4047fec6dc9c561969c446641b22373d5157e2` |
| Portable source snapshot | 183 files in seven modules, each SHA-256 recorded |
| JVM / compiler | JDK 21; Kotlin 2.2.10 targeting JVM 21 |
| Build wrapper | Gradle 8.14.3 with distribution checksum |
| Native database binding exercised | sqlite-jdbc 3.46.1.0 on Linux x86-64 |
| Reference device | Pi 5, Raspberry Pi OS Trixie 64-bit ARM64 |

The two Linux branch histories are retained by a reviewed reconciliation merge;
see [branch and database reconciliation](BRANCH-RECONCILIATION.md). Android source
files and repository history were not modified by this pass.

## Executed verification

The development environment is x86-64 Linux with Temurin 21.0.13+9. This is not
a Raspberry Pi or a physical Trixie installation. Builds used a temporary Gradle
cache; first resolution downloaded the wrapper and dependencies. Later identical
tasks could use that cache. Counts below are JUnit XML results, including explicit
zero failure/error/skip checks, rather than counts of source annotations.

| Gate | Command / scope | Result |
| --- | --- | --- |
| Offline source gate | `./scripts/verify-offline-source.sh` | Passed |
| Source-update regressions | `python3 -B scripts/check-canonical-sources.py` | 18 passed |
| Trixie shell fixtures | `python3 -B scripts/check-trixie-scripts.py` | 17 passed |
| Snapshot verifier fixtures | `python3 -B scripts/check-snapshot-verifier.py` | 15 passed |
| Gradle module graph | 16 declared modules, 44 project references | No missing projects or cycles |
| Agent journal DDL | Actual Python SQLite constraints, rollback and snapshot checks | Passed |
| Standalone JVM / packaging | `./gradlew clean build :host:linux:shadowJar --no-daemon` | Passed; 83 tests, no failures/errors/skips |
| Complete canonical cognition gate | `./scripts/verify-canonical-cognition.sh ../HaloAndroid` | Passed; 70 tests, no failures/errors/skips; executable JAR built |
| Standalone packaged-daemon smoke | `./scripts/smoke-test.sh --jar <standalone.jar>` | Passed; real JVM stop/restart, identity and data retained |
| Canonical packaged-daemon smoke | `./scripts/smoke-test.sh --jar <canonical.jar>` | Passed; main + agent snapshot validation and real JVM restart |

Standalone JUnit results: **3 inference + 31 storage + 49 host = 83 tests**.
Canonical gate results: **2 recovery-contract + 16 agent-store + 52 host = 70
tests**. The optional host reuses standalone tests, so its result must not be
added to the standalone count as though every test were unique.

Both runnable JAR manifests were checked for the exact Android pin. The default
artifact reports `Halo-Source-Mode: snapshot` and
`Halo-Canonical-Cognition: false`; the opt-in artifact reports `canonical` and
`true`. This proves packaged provenance, not hardware readiness.

Both smoke runs used the exact built artifacts, JDK 21 and isolated temporary
XDG directories, with Unix sockets permitted by the execution environment. They
verified healthy status, disabled-provider behavior, memory search, integrity,
backup no-overwrite, complete snapshot validation, confirmed process/socket
shutdown, and identity/data persistence in a new JVM. No installed user data or
system service was touched. The canonical run verified both database snapshots.

## Changes established by this pass

### Reproducible Android alignment

The allowlisted portable snapshot includes the missing cognition-language and
provider-validator implementations plus current workload, embedding and speech
contracts. The explicit updater validates the upstream Git tree and actual file
bytes, rejects dirty/hidden/extra sources and symlinks, handles additions/removals,
and updates the revision and deterministic manifest together.

Gradle runs the integrity gate before Kotlin compilation. Both build modes use
exact reviewed source roots; canonical mode verifies external Git blobs, including
optional cognition, so a matching HEAD alone cannot hide changed compiled code.
This is source synchronization, not an Android data transport. See
[shared source strategy](SHARED-SOURCE-STRATEGY.md).

### Build and test repairs

Thin and executable JARs previously wrote the same output path, which caused the
first real full build to fail. They now have distinct filenames, with merged
service descriptors and explicit source provenance in the runnable artifact.
JDK 21 toolchains, executable entry points and bounded Gradle memory/workers give
a consistent build baseline.

Coroutine test methods now return `Unit` explicitly where required by JUnit.
This recovered three previously undiscovered default host tests. The optional
recovery fixture now calls preparation before inspection, matching the daemon's
startup contract. No canonical source was patched to hide build failures.

The first real smoke run also exposed a Bash process-tracking defect: signaling
the background function could leave its JVM alive and let a restart probe reach
the previous daemon. The harness now owns the JVM PID directly, verifies process
exit and socket removal, retains private diagnostic logs on failure and supports
`--jar /absolute/path` to test an exact already-built artifact.

### Existing data preservation

Legacy master and main reused schema version 3 for different structures. Startup
now checks migration history and required columns before altering installation
paths or the database. Incompatible lineage, missing/malformed schema, rollback
journals, symlinked sidecars and orphan sidecars fail with recovery guidance.
The supported main version-2 upgrade still preserves stored content.

Inspection uses a private streamed DB/WAL copy because even read-only SQLite can
create sidecars. Tests cover closed WAL and schema/data committed only in WAL,
with original bytes, timestamps, permissions and file sets preserved. Temporary
space equal to DB plus WAL is required at startup; see the documented
`java.io.tmpdir` override in [branch reconciliation](BRANCH-RECONCILIATION.md).
Snapshot verification requires standalone VACUUM output and rejects WAL-format
inputs before opening them.

### Trixie operation

Preflight checks OS identity, kernel and userspace architecture, actual selected
Java/compiler, user service state, lingering and reachable PipeWire sessions.
Setup creates private offline configuration and preserves existing edits.
Installation builds a fresh JAR before atomic replacement and leaves service
start/restart/enable explicit. Documentation covers normal Trixie packages,
Desktop/Lite, NVMe, headless transition and exact-board audio checks.
The scripts do not upgrade the OS, edit overlays or change audio routing.

## Material limits and next acceptance

- **Pi / ARM64:** no physical Pi, ARM64 JNI run, native model benchmark, HiFiBerry
  stream, NVMe deployment, thermal test, reboot or sustained-operation test was
  performed. Run [pre-deployment gates](PRE-DEPLOYMENT-GATES.md) on the device.
- **Real providers:** JVM tests use fixtures; there is no verified live-model
  result. Explicit provider configuration remains required and no remote fallback
  is selected automatically.
- **Service installation:** shell fixtures and executable host tests do not prove
  a real Trixie user-service installation, login/linger behavior or audio session.
- **Android data:** pairing, live peer synchronization and Linux `.halobrain`
  import/export are not implemented. Linux snapshots are not Android archives.
- **Agent actions:** the optional real coordinator journals text runs and
  classifies startup recovery; tools and automatic/manual recovery execution remain
  disabled. Some complex canonical journal variants remain unsupported.
- **Backup/recovery:** snapshots are unencrypted and contain private identity and
  personal data. A complete external restore/migration rehearsal, legacy schema
  converter and atomic transaction across the two databases are not provided.
- **Artifact distribution:** source snapshots remain temporary; shared JVM artifact
  publication and licensing/provenance review are still future work.

No GitHub Actions, unattended update job, new Linux planner or LAN listener was
introduced. Continue Android alignment through reviewed pinned updates and keep
hardware evidence separate from source/build evidence.
