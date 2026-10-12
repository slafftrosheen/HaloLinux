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

## HALO-LINUX-008–012 changes (source integrated, build not yet executed)

- SDK-grounded text-only cognitive turn with content-free event bus signals.
- Process-scoped model provider, explicit `provider check`, no hidden fallback.
- Provider activity/failure diagnostics and bounded one-at-a-time generation.
- Optional SHA-pinned canonical Halo source mode for six portable source modules.
- SQLite v3 content-free turn checkpoint journal with conservative restart classification.
- Single-instance lock acquired *before* any database mutation or recovery.
- Safe, actionable local provider errors and model-free smoke assertions.
- JUnit coverage for fixture turns, provider failure/tool-call rejection and checkpoints.

**Do not claim full AgentRunCoordinator portability or shared artifact publication.**
`-PhaloCanonicalRoot` allows direct canonical-source compilation but has not
yet been tested with latest canonical SDK, and vendored SDK snapshots remain
the default standalone build.

Physical Pi 5/Trixie, JVM builds, JNI, memory pressure and actual provider
calls still require executed acceptance evidence.

## HALO-LINUX-013 through 017 (source committed)

- **013:** Atomic SQLite transaction for text turn completion, conditional journal
  advancement and duplicate-commit rejection. Dedicated rollback tests.
- **014:** Canonical Halo `CognitiveRequestStarted`,
  `CognitiveReasoningStarted`, and `InferenceObserved` metadata-only events.
- **015:** Experimental `-PhaloCanonicalCognition=true` conditional Gradle
  target compiles the *real* canonical `runtime/cognition` sources from the
  SHA-pinned upstream checkout, plus direct recovery-contract tests.
- **016:** Provider lifecycle terminality is preserved during late response
  completion and connection checks. The underlying HTTP dispatcher is cancelled
  and closed on shutdown.
- **017:** Typed read-only `QueryBus` for local notes and interrupted-turn
  counters; all other query/command types still reject execution.

### Truthful readiness

There is **no verified Kotlin/Gradle build log** for any of these batches.
The optional canonical agent target may discover additional dependency/API
incompatibilities at first build. No physical Pi, Docker ARM64, audio/HiFiBerry,
model endpoint, systemd, or 24-hour stability tests have run in this environment.
This means the repository is still **pre-deployment source**, not accepted software.

### First execution sequence, on a network-enabled Trixie Desktop Pi

```bash
git clone -b main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
python3 scripts/check-module-graph.py
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
./scripts/preflight.sh
```

The canonical cognition build is independent and opt-in:
`./scripts/verify-canonical-cognition.sh /path/to/pinned/Halo-worktree`.
Do not enable tools or auto-replay as a workaround for compile failures.

### Next software work

1. Executed compile fixes and source/ABI compatibility matrix for canonical SDK.
2. A real SQLite-backed implementation of canonical `AgentRunStore`, with
   versioned step encoding and CAS semantics; no generic JVM object serialization.
3. Demonstrate canonical `AgentRunCoordinator` startup classification and
   recovery *without* enabling action execution.
4. Implement host tool authorization and resource admission separately.

## HALO-LINUX-019 — source committed, optional agent store

- Real SHA-pinned canonical `AgentRunStore` interface implemented using SQLite
  in `adapters/agent-store` under experimental `haloCanonicalCognition` flag.
- Explicit v1 journal codec for supported text/recovery/input steps; unsupported
  tool evidence fails closed. Unknown schemas are not deserialized dynamically.
- Atomic resume/recovery claims, journal identity dedup, input first-writer
  resolution, and bounded child admission.
- Read-only startup/health inspector uses canonical recovery planner. No
  automatic replay or tool execution.
- Tests committed for restart, atomic CAS, duplicate journal IDs, pending input,
  child limits, unknown variants and classification-only behavior.
- Test execution and optional Gradle compilation **not verified**.
- `halo-node storage backup` excludes the optional separate agent database.
  Deployment must not treat it as a complete node backup.

## HALO-LINUX-020 — canonical journal and coordinator work

- **020A:** strict v1 codec now stores canonical provider tool-call proposals,
  invocation/claim evidence, resource blocking, tool outcomes, and typed
  `ActionTruth`, preserving uncertainty instead of silently treating claims
  as successful actions.
- **020B:** SQLite global deduplication of execution-claim idempotency keys and
  tests for `NEVER_REPLAY` unknown external results.
- **020C:** the opt-in daemon invokes Halo's actual
  `DefaultAgentRunCoordinator.prepareOutstandingRunsForRecovery()` once after
  acquiring the daemon lock. It classifies/normalizes run states; no provider,
  tool or automatic replay is invoked.
- **020D:** stored guard decisions and external-condition journal evidence.
- **020E:** owner-private, no-overwrite consistent SQLite agent journal backups
  via `halo-node agent backup /absolute/private/path.db`.
- **020F:** resolution of durable approval/clarification requests requires
  correct waiting state, matching resolution type, and length/line constraints.
- **020G:** regression tests for journal truth, response commit dedup, guard
  events and condition history.

**Important:** optional canonical Gradle compilation and all JUnit test
execution remain **unverified** in this environment. The default Pi build
continues to exclude canonical agent cognition. Host-specific semantic/UI
receipts, complex handoffs and some direct-authority records are still
unsupported by the strict journal codec and will fail closed. This is not
yet a complete portable coordinator for user-facing agent execution.
