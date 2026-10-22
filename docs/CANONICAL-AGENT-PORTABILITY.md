# Canonical agent runtime portability

The optional HaloLinux composition compiles the real Android Halo
`runtime/cognition` production module directly from a separate reviewed checkout.
It uses the same coordinator, journal/store contracts and recovery planner,
rather than maintaining a Linux copy of that runtime.

Current pin: `5a4047fec6dc9c561969c446641b22373d5157e2` in
`config/canonical-halo.sha`. The ordinary standalone Pi build includes the
reviewed portable SDK snapshot and does not require an external Halo checkout.

## Build and verification gate

Prepare a detached checkout at the pin as described in
[Canonical SDK Build](CANONICAL-SDK-BUILD.md), then run:

```bash
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

The gate verifies source provenance and the canonical agent contract, then runs
the cognition, Linux agent-store and optional host test tasks and builds the
executable JAR. The underlying Gradle selection is:

```bash
./gradlew -PhaloCanonicalRoot=/absolute/path/HaloLinux-canonical \
  -PhaloCanonicalCognition=true \
  :runtime:cognition:test :adapters:agent-store:test \
  :host:linux:test :host:linux:shadowJar --no-daemon
```

Both properties are required to enable canonical cognition. Linux build files
and tests remain local; canonical production sources come from the exact
reviewed checkout. Changing the pin or allowing dirty upstream source bytes
would invalidate the provenance gate.

HALO-LINUX-050 has compiled the real pinned cognition module and the Linux
agent-store implementation on the JVM and executed their focused tests
successfully. The complete optional host/package gate and current totals are
recorded in [implementation status](IMPLEMENTATION-STATUS.md). Keep module
compilation, focused test results, the complete gate and physical Pi acceptance
as separate evidence.

## Integrated components

| Component | Current role |
| --- | --- |
| Canonical `runtime/cognition` | Owns agent contracts, `DefaultAgentRunCoordinator` and recovery planning |
| Linux `SqliteAgentRunStore` | Implements durable canonical storage and atomic claims with a strict versioned codec |
| `CanonicalHostRecoverySupport` | Composes coordinator/store, prepares startup classification and journals host text turns |
| `CanonicalRecoveryInspector` | Reports planner categories without executing plans |
| Main Linux storage | Owns the atomic conversation/text-checkpoint commit |
| Linux snapshot set | Coordinates main/agent SQLite backups behind the daemon mutation gate |

A default build uses `DisabledRecoverySupport`, reports `NOT_LINKED` and does
not initialize the separate canonical agent database. The optional build reports
`CLASSIFICATION_ONLY` for recovery policy; this label does not mean the
coordinator is absent. It is used for new text-run journaling as well.

## Startup and text-run behavior

After the daemon takes its single-instance lock, the optional composition opens
the canonical store and calls
`DefaultAgentRunCoordinator.prepareOutstandingRunsForRecovery()` once.
The canonical operation classifies nonterminal runs and may change their durable
states. It never dispatches a provider retry or tool action during startup.

Fresh text requests call the real coordinator's `start()`, record the provider
turn and response evidence, and complete only after the main conversation is
durable. A mismatch between the two database commits is exposed as
`NEEDS_RECONCILIATION`; the node does not automatically repeat inference.
See [Cognitive Integration](COGNITIVE-INTEGRATION.md).

Recovery inspection remains metadata-only:

```bash
halo-node agent recovery
```

The output includes startup classification categories and remaining nonterminal
plan counts, without prompts or provider payloads. A plan recommending a retry
does not grant execution authority. Automatic and manual resume are unavailable
on this Linux host.

## Contract and persistence checks

`scripts/check-canonical-contract.py` validates the pinned source identity,
required store methods, coordinator recovery entry point and symmetric
encode/decode coverage for supported `AgentStep` variants. It names unsupported
variants instead of treating them as safely discardable.

The Linux store already implements create/read/update, append deduplication,
child admission, resume/recovery claims and first-writer input resolution.
Its tests exercise persistence, conflict/claim behavior and conservative recovery
against the real canonical types. These are implemented features, not future
replacement interfaces.

The structural source check cannot prove Kotlin compatibility or lossless
serialization by itself. JVM compilation and semantic storage tests remain
required. Unsupported schemas, variants and host metadata continue to fail
explicitly. See [Canonical Agent Store](CANONICAL-AGENT-STORE.md).

## What the gate establishes and what remains

A passing complete gate demonstrates that the selected canonical JVM sources,
Linux storage adapter and optional host integration compile together and pass
their current automated tests. It does not establish:

- Serialization of every canonical journal variant or compatibility with an
  arbitrary Android database.
- A complete multi-turn `CognitiveSession`, context reconstruction or compaction.
- User-facing tool dispatch, authorization, action reconciliation or replay.
- A cross-database atomic commit or a complete restore/migration workflow.
- Real-model inference, Linux speech, ARM64 JNI or Pi hardware stability.
- Android personal-data synchronization or secure peer transport.

Next work is to close those specific gaps with canonical contracts and measured
host behavior. Add complex receipts and authority envelopes only with lossless
fixtures. Enable actions only after host capability, resource, approval and
replay-safety gates exist. Do not introduce a second Linux planner to bypass
unsupported canonical evidence.

Source snapshots remain a temporary distribution mechanism. Successful optional
compilation does not by itself justify deleting them: demonstrate Android and
standalone Linux compatibility before replacing them with pinned shared JVM
artifacts.
