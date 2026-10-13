# HALO-LINUX-015 — canonical AgentRunCoordinator portability gate

This is an **optional integration compile target**, not a claim that Linux
has the full agent runtime operational.

The canonical `runtime/cognition` module includes Halo's real
`AgentRunCoordinator`, `AgentRunStore`, durable step journal, tool
authorization types, and deterministic recovery planner. It has a substantial
graph of 200+ Kotlin classes. Copying only its coordinator would create a
second, subtly incompatible runtime.

## Implementation

When both properties are supplied:

```bash
./gradlew -PhaloCanonicalRoot=/absolute/path/HaloLinux-canonical \
  -PhaloCanonicalCognition=true :runtime:cognition:test
```

The conditional Gradle module compiles `runtime/cognition/src/main/java`
**directly from the pinned canonical Halo checkout**, not from Linux copies.
The pin is in `config/canonical-halo.sha`. Tests live in HaloLinux and
exercise `AgentRunState.isTerminal` and the real
`DefaultAgentRecoveryPlanner` with missing journal evidence.

Use `./scripts/verify-canonical-cognition.sh /absolute/path/HaloLinux-canonical`
for the executable gate. The main Pi build stays independent of an external
Halo checkout and does NOT include this module by default.

## What successful compilation would establish

- Portable cognition and its language/SDK contracts can be compiled from a
  reviewed, immutable Android Halo revision using a JVM-only Linux build.
- The real recovery planner can classify unsupported, unjournaled work as
  ineligible for automatic recovery.

## HALO-LINUX-019 update

The opt-in [canonical agent store](CANONICAL-AGENT-STORE.md) now implements
the *real* `AgentRunStore` contract with a restricted, strict v1 SQLite journal
codec, atomic claims and read-only recovery classification. This is implemented
in source, not yet build-verified. It is not a complete tool-aware run store
or a running canonical coordinator.

## What it would NOT establish

- A complete, fully verified, tool-aware SQLite implementation of `AgentRunStore`.
- Serialization for every canonical journal variant or authorized tool replay.
- Reliable tool authorization or external action reconciliation.
- A wired `CognitiveSession`, multi-turn context, or autonomous tool executor.
- A passing default standalone/ARM64 build or Pi 5 hardware acceptance.

**Strict rule:** Do not change or delete copied SDKs merely because the
optional module compiles. First secure both Android and Linux build compatibility,
then publish pinned shared JVM artifacts and remove the duplicate source trees.

## Recommended next work

1. Run the optional pinned compile and collect missing dependency/API errors.
2. Make a typed SQLite AgentRunStore implementation against the genuine canonical
   interface; include versioned step encoding and unknown-schema rejection.
3. Demonstrate atomic `create`, `appendStep`, `claimResume`,
   `claimRecovery`, and `resolveInputIfPending` against restart.
4. Wire canonical `DefaultAgentRunCoordinator` without enabling any tool
   dispatch. Show `prepareOutstandingRunsForRecovery` classifying unfinished runs.
5. Only enable actions after resource leasing, user authorization and replay
   safety gates are tested against real host capabilities.

## HALO-LINUX-046B — pinned contract preflight

`python3 scripts/check-canonical-contract.py /path/to/pinned/Halo` checks
SHA identity, AgentRunStore method presence, canonical coordinator recovery
entry point, and symmetric encode/decode support for canonical AgentStep
variants. Unsupported canonical variants are reported explicitly. The
verification script now runs this before Gradle. This is a **static guard**,
not a replacement for Kotlin/JVM compilation or semantic round-trip tests.
