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

## HALO-LINUX-021–023 source batches — pre-test checkpoint

- **021A/B:** Experimental text turns now go through the actual pinned
  `DefaultAgentRunCoordinator`, recording `RunPrepared`, provider turn
  evidence, `ResponseProduced` and eventual `ResponseCommitted`.
  The main database remains responsible for the atomic local conversation
  commit. A secondary journal mismatch is surfaced as
  `NEEDS_RECONCILIATION` without triggering automatic inference retry.
- **022A/B:** Host mutation gate serializes write-producing commands while
  allowing status/health reads; `halo-node node backup /absolute/private/folder`
  creates two SQLite snapshots where applicable, a SHA-256 manifest, and an
  owner-only backup directory. Failure rolls back the newly created set.
- **023A/B/C:** Read-only Python snapshot verification, corruption/privacy
  fixtures, default daemon smoke integration and experimental
  canonical-success/failure text-turn test sources.

**Still unverified:** JVM clean build, canonical adapter/host test execution,
full-node snapshot execution on an ARM64 host, Pi 5/Trixie audio/NVMe,
24-hour stability, and encryption/restore operations. Do not interpret
GitHub commits as passing integration tests. The canonical adapter remains
experimental; no user action/tool execution or automatic recovery enabled.

Before Pi deployment, collect compiler output and actual test logs. Do not
enable the optional canonical cognition integration by default until its
Gradle API/ABI compatibility is demonstrated against the pinned Halo worktree.

## HALO-LINUX-027–030: build-readiness corrections

Changes committed to `main`:
- **027A:** Declared recovery adapter state before capturing it in the cognition
  callback, correcting Kotlin's local variable declaration order at daemon startup.
- **027B/C:** Enforced a bounded, valid JSON reply on the Unix socket, with a
  regression fixture for oversized responses.
- **028A/B:** Completion events and secondary journal finalization no longer
  falsify a committed conversation when a client or observer fails.
- **029:** Corrected invalid Kotlin newline/carriage-return character literal
  escaping in the optional canonical store and matching text-input fixture.
- **030A/B:** Enforced an encoded JSON reply-size budget before committing
  model output as delivered. Large reply test ensures no conversation commit.

### Available verification evidence

- JDK 21 and a local Kotlin CLI are present in the development environment.
- An isolated Kotlin character-literal syntax smoke check passed.
- An isolated SQLite check confirmed `VACUUM INTO` supports a pre-created empty
  destination file on the available SQLite installation.
- Network DNS access to GitHub and Gradle dependency resolution are unavailable
  in the local execution container. Consequently, **no repo Gradle build, unit
  suite, shadow JAR, ARM64 native SQLite or Pi 5 execution has passed yet**.
- GitHub commits are verified independently; this is not build verification.

### Mandatory next execution gate

On a connected Linux host with Java 21:

```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
python3 scripts/check-module-graph.py
python3 scripts/check-snapshot-verifier.py
./gradlew clean build --no-daemon --stacktrace
./scripts/smoke-test.sh
```

After the standalone build is green, use a detached pinned Halo worktree
to run `./scripts/verify-canonical-cognition.sh /path/to/Halo-worktree`.
Do **not** deploy the experimental agent variant, enable tool execution,
or assume inference readiness based only on committed source.

## HALO-LINUX-031–034 — source hardening and offline test preparation

- **031:** Inference output exceeding the local JSON delivery envelope now fails
  with typed `AiErrorType.INVALID_RESPONSE` and a sanitized operator message.
  No false durable conversation commit.
- **032:** An explicit provider health probe that cannot acquire the inference
  lease does not mark the still-running provider as DEGRADED. Generation and
  probing remain serialized with separate admission time limits.
- **033:** Added `./scripts/verify-offline-source.sh`, a single entry point
  for Bash/Python syntax, static module graph and synthetic backup verifier
  regressions without requiring Java, a model, or network access.
- **034:** `halo-node ask -` now reads a bounded question from standard input,
  avoiding sensitive question content in shell command arguments. Added stdin
  boundary and normal-argument tests.

**Source committed, execution not yet verified.** The host environment has JDK
21 but cannot resolve GitHub/Gradle dependency hosts, so a full repository
build or JUnit run cannot be claimed. This is still an experimental alpha host.

On first connected x86-64 or Pi 5/Trixie Linux test, run:

```bash
./scripts/verify-offline-source.sh
./gradlew clean build --no-daemon --stacktrace
./scripts/smoke-test.sh
```

Run the separate pinned canonical cognition build only after the normal
standalone build passes, with actual compiler output retained.

## HALO-LINUX-036–038 follow-on stabilization

- 036: reject partially configured inference endpoint/model pairs and relative
  data paths, preventing a Desktop-to-systemd storage-location split.
- 037: missing configuration still starts model-free, but an existing symlink,
  directory, or unexpected filesystem object is rejected.
- 038: provider generation retains its one-at-a-time inference permit until
  SDK response validation, terminal status recording, and latency bookkeeping
  complete. Admission failure does not mark another active request degraded.

These source changes remain pending Gradle/JUnit execution.

## HALO-LINUX-039–040 — safe memory search

- Memory FTS excludes entries whose `expires_at` has passed, but does not
  delete them. A regression test includes both active and expired entries.
- IPC memory search returns up to 20 bounded note excerpts. Every record
  reports whether its content was truncated; the result includes returned,
  excerpted and size-omitted counts. The complete original memory stays in
  SQLite, and the returned JSON is sized before sending.
- No model automatically receives personal memory from this operation.
- Kotlin/JUnit tests remain source-only pending an executable JVM build.

## HALO-LINUX-041–044 safety pass

- **041:** Private SQLite, XDG data/cache directories, and managed child
  paths are rejected if relative or already symlinked. Validation occurs
  before creating files or changing directory permissions.
- **042:** Client requests are size-checked before transmission. Incoming
  UTF-8 frames use strict decoding; malformed bytes are rejected rather
  than silently replaced.
- **043:** Local socket shutdown closes accepted and queued sockets, interrupts
  client workers and waits a bounded 10 seconds for worker termination.
- **044:** Write-producing operations have a 5-second admission bound. If
  another write or long model generation owns the mutation gate, callers
  receive `NODE_BUSY`, and their queued write does not run later.
  Once admitted, a mutation is **not** cancelled by the admission timeout.

Source and tests committed; JVM and ARM64 execution are still pending.
