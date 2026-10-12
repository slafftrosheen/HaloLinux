# HALO-LINUX-019 — Canonical AgentRunStore / Recovery (Experimental)

## Scope

HALO-LINUX-019 introduces **actual implementations of canonical**
`com.halo.runtime.cognition.agent.AgentRunStore` and the real
`DefaultAgentRecoveryPlanner`, compiled only against the SHA-pinned Halo
source. Linux does not maintain a second copy of these interfaces.

- `adapters/agent-store` is an opt-in Kotlin/JVM module.
- `SqliteAgentRunStore` stores agent runs and ordered journal steps in
  `canonical-agent-runs.db` using SQLite WAL, foreign keys, and transactions.
- `CanonicalAgentJson` serializes supported canonical types with explicit v1
  schema and strict rejection of unknown versions and variants.
- `CanonicalRecoveryInspector` asks the actual Halo planner to classify
  nonterminal runs. **Classification never executes a recovery plan.**
- `host/linux/src/canonical/java` adds an optional daemon recovery bridge.
  `halo-node status` reports content-free plan counts.
  `halo-node agent recovery` prints only this recovery-status section. Normal builds do not
  create or initialize an agent-run database.

## Supported contract operations

| Operation | Behavior |
|---|---|
| `create` / `get` / `observeRuns` | Typed rows and ordered step reconstruction |
| `appendStep` | Unique step ID and journal identity, one SQL transaction |
| `update` | CAS against prior state/timestamp; immutable ancestry; no step rewriting |
| `children` / `createChildIfAllowed` | Atomic parent/depth/limit checks |
| `nonTerminalRuns` | Loads canonical typed state and journal |
| `claimResume` | Conditional one-winner state change |
| `claimRecovery` | Conditional state change **and** recovery claim journal atomically |
| `resolveInputIfPending` | Journaled first-writer-wins approval/clarification resolution |

The SQLite uniqueness constraints enforce response/turn identity, recovery
fingerprints, input request identity, and input resolution identity. Claims
are never split into multiple committed transactions.

## Deliberately restricted codec

v1 supports `RunPrepared`, `ProviderTurnStarted`,
`ProviderTurnCompleted` **including proposed tool-call evidence**, `ResponseProduced`,
`ResponseCommitted`, `RecoveryClaimed`, `InputRequested`, and
`InputResolved`. The only supported clarifications are non-sensitive text;
approval requests may be stored, but approval **does not enable a tool
executor**. Unknown / newer `AgentStep` variants, incompatible schema,
audio/origin metadata, and tool-producing provider responses reject explicitly.

This means `SqliteAgentRunStore` is **not yet a full production implementation
for every canonical agent step**. Do not mount a production Android agent journal
in this adapter or assume round-trip compatibility for unknown variants.

## Startup behavior

If the opt-in Gradle flag was used to build the executable, the daemon:

1. Acquires the single-instance lock.
2. Initializes the existing local Halo SQLite database.
3. Opens its separate versioned canonical agent database.
4. Classifies nonterminal runs with the real canonical recovery planner.
5. Starts normal Linux text-only runtime operation.
6. Exposes metadata-only plan counts under `canonical_agent_recovery`.

Every recovery entry reports `automaticReplayPermitted=false`.
No `claimResume`, `claimRecovery`, provider retry or tool dispatch is
called during startup or status reporting.

This **is not yet** canonical `AgentRunCoordinator` or
`CognitiveSession` execution. The existing text-only Linux turn ledger
continues to serve user `ask` requests independently.

## Build

Create an isolated detached checkout at the pinned commit in
`config/canonical-halo.sha` as explained in
[CANONICAL-SDK-BUILD.md](CANONICAL-SDK-BUILD.md).

```bash
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

The command compiles real cognition and runs adapter/host tests with
`-PhaloCanonicalCognition=true`. The standalone Raspberry Pi build does
not need or import canonical cognition sources by default.

No Gradle/JVM verification has yet been executed in the environment that
authored HALO-LINUX-019.

## Backup, secrecy, and limitations

- Agent run requests and some journal step payloads contain **private text**.
- The agent SQLite database is owner-private; the containing directory is 0700.
- `halo-node storage backup` currently backs up the main Halo database **only**.
  It **does not** include `canonical-agent-runs.db`.
- Back up both databases consistently while the service is stopped, or use a
  separate correctly implemented SQLite online backup for the agent database.
  Do not copy a live SQLite main file without its WAL.
- Do not sync or upload agent runs by default.
- The observation flow is in-process, not cross-process DB change notifications.
- Error handling for unknown step kinds intentionally fails instead of dropping
  important replay evidence.

## Remaining gates

1. Execute pinned canonical Gradle build and fix actual compiler/API errors.
2. Add a versioned *complete* step codec and durable action-truth encoding.
3. Verify full canonical journal migration/roundtrip with Android fixtures.
4. Wire an authorized canonical coordinator and run-store adapter only after
   resource leases, user approval, and replay-safety tests pass.
5. Add a complete, consistent backup/restore procedure for both SQLite DBs.
6. Test ARM64/Trixie before enabling the experimental integration on the Pi 5.

## Inspect after opt-in deployment

The metadata-only operator command is:

```bash
halo-node agent recovery
```

Standalone default builds return `{"state":"NOT_LINKED",...}`.
Experimental pinned cognition builds return `CLASSIFICATION_ONLY` and
grouped plan counts (not run prompts or provider payloads). This command
does not resume work.

A host-only regression test for this behavior is under
`host/linux/src/canonicalTest` and runs with the opt-in verification script.

## HALO-LINUX-020C — canonical coordinator at startup

The experimental host now constructs the real
`DefaultAgentRunCoordinator(store, scope)` and calls
`prepareOutstandingRunsForRecovery()` **once**, after acquiring the local
daemon instance lock. That canonical operation can update durable run states:
e.g. nonresumable/unknown external work becomes `INTERRUPTED`, pending
approval retains a waiting state, and resumable work remains `SUSPENDED`.
It does NOT execute actions, call the provider, or retry generation.

`halo-node agent recovery` returns both remaining nonterminal planner counts
and the *startup classification categories*. If the journal contains an
unsupported version, daemon initialization fails closed. It does not discard
or silently convert the unsupported evidence.

Automatic resume and manual resume remain unavailable on this Linux host.

## HALO-LINUX-020E — consistent agent DB backup

Opt-in agent builds expose `halo-node agent backup /absolute/private/folder/agent.db`.
The parent folder must exist and be owner-only (0700); the snapshot is created
0600 using `VACUUM INTO`, verified using `PRAGMA quick_check`, and existing
files are never overwritten. The standalone default build rejects this command
because no canonical agent journal is linked. This is a separate backup from
`halo-node storage backup`.

For a **coordinated full-node backup**, stop the daemon before restoring,
and take snapshots of both the main Halo storage and agent run journal.
These are separate databases; this command does not provide a cross-database
atomic snapshot.

## HALO-LINUX-020F — strict pending human input

`resolveInputIfPending` now requires the run to be in the correct waiting
state (approval versus clarification), verifies allowed resolution types, and
enforces original text-field min/max length and single-line requirements.
Expiry fails closed unless the resolution records an explicit Expired result.
The first durable resolution still wins; approvals never dispatch a tool.

New regression fixtures verify proposed provider tool calls survive journaling
without granting execution.
