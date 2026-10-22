# Canonical AgentRunStore and recovery — experimental

HaloLinux's optional `adapters/agent-store` implements the real
`com.halo.runtime.cognition.agent.AgentRunStore` contract from the SHA-pinned
Android Halo checkout. The canonical coordinator and recovery planner are also
used directly. Linux owns persistence and OS integration, not a second agent
execution model.

The active revision is recorded in `config/canonical-halo.sha`; HALO-LINUX-050
aligns it to `5a4047fec6dc9c561969c446641b22373d5157e2`.
Canonical cognition and the Linux agent store have compiled and passed their
focused JVM tests. See [implementation status](IMPLEMENTATION-STATUS.md) for the
complete optional host/package gate, current test totals and physical acceptance.

## Storage and contract operations

`SqliteAgentRunStore` uses a separate `canonical-agent-runs.db` database with
SQLite WAL, foreign keys, ordered steps and explicit transactions. Normal
standalone builds do not initialize this database.

| Operation | Implemented behavior |
| --- | --- |
| `create`, `get`, `observeRuns` | Typed rows and ordered step reconstruction |
| `appendStep` | Unique step and journal identities, persisted transactionally |
| `update` | Compare-and-set against prior state/timestamp; immutable ancestry; no step rewriting |
| `children`, `createChildIfAllowed` | Atomic parent, depth and child-limit checks |
| `nonTerminalRuns` | Reconstruct canonical state and typed journal |
| `claimResume` | One-winner conditional state transition |
| `claimRecovery` | Conditional state transition and durable recovery claim in one transaction |
| `resolveInputIfPending` | First valid durable approval/clarification resolution wins |

SQLite uniqueness constraints protect response/provider-turn identities, recovery
fingerprints, pending-input identities and global tool-execution idempotency keys.
An already-recorded claim cannot be interpreted as permission to dispatch again.

Input resolution validates the run's waiting state, requested resolution type,
text-field bounds and single-line requirements. An expired request fails closed
unless the explicit resolution represents expiry. Persisting an approval does
not start a tool executor.

The observation flow is in-process; it is not a cross-process database change
notification mechanism.

## Versioned evidence codec

`CanonicalAgentJson` uses an explicit v1 schema and fixed type handling.
It does not deserialize arbitrary JVM classes. Supported evidence includes:

| Evidence group | Scope |
| --- | --- |
| Run/context | Preparation, plans, context use and continuity |
| Provider/response | Provider-turn start/completion, proposed tool calls, produced/committed responses and continuations |
| Recovery/input | Recovery claims, clarification/approval requests and resolutions |
| Tool history | Requests, prepared invocation, dispatch/claim, completion/failure/outcome and typed rejection |
| Resource/guard | Resource blocking and run guard decisions |
| Conditions | External-condition registration, satisfaction, expiry and cancellation |

Typed `ActionTruth` is preserved. A proposed, dispatched or uncertain action
must not be silently converted into confirmed success. These historical
tool-evidence records are supported for storage and conservative classification;
the Linux text provider path still rejects tool-producing responses.

Complex semantic-app receipts, UI transaction receipts, interaction handoffs,
delegation evidence and direct-command authority envelopes remain unsupported.
Certain origin/audio metadata is also rejected. Newer schema versions and
unreviewed variants fail explicitly rather than losing replay-relevant evidence.

Do not open an Android production journal as this database or assume every
canonical variant can round-trip. Unknown unversioned agent stores are rejected;
there is no implicit migration to v1.

## Startup and fresh text requests

The optional host:

1. Acquires the daemon's single-instance lock before persistent initialization.
2. Opens main storage and the separate canonical store.
3. Constructs `DefaultAgentRunCoordinator(store, scope)`.
4. Calls `prepareOutstandingRunsForRecovery()` once.
5. Starts normal Linux text-only operation and exposes metadata-only recovery
   counts.

Canonical startup classification can change durable run states. Depending on the
evidence, it may retain a waiting state, suspend recoverable work or terminalize
unrecoverable work. It does not retry inference, dispatch a tool or claim a
resume/recovery operation.

The same coordinator is wired into fresh text turns. It creates the canonical
run and journals provider/response evidence; response commitment and completion
follow the main SQLite conversation commit. Thus the store/coordinator are
already used, while full multi-turn `CognitiveSession` and action execution
remain separate integration work.

There is no atomic transaction across the two databases. If canonical
finalization fails after the main conversation commits, the answer remains
durable and the local reply exposes `NEEDS_RECONCILIATION`. Neither startup nor
the failure path automatically regenerates or redelivers it.

## Operator inspection

```bash
halo-node agent recovery
```

Standalone builds report `NOT_LINKED`. Optional builds report
`CLASSIFICATION_ONLY`, startup categories and remaining planner counts, with
`automatic_replay=false` and `tool_execution=false`.
No prompts, tool arguments or model text are exposed by this status report.

`CanonicalRecoveryInspector` uses the real planner but executes none of its
recommendations. Automatic and manual resume are unavailable on this host.
An unsupported journal prevents successful initialization instead of being
silently discarded.

## Backups and privacy

Agent requests and some step payloads contain private text. The containing
directory is owner-private (0700), and snapshots are created with mode 0600.
Filesystem permissions are not encryption.

| Command | Captured data |
| --- | --- |
| `halo-node storage backup /absolute/private/main.db` | Main Halo database only |
| `halo-node agent backup /absolute/private/agent.db` | Optional canonical journal only; rejected when not linked |
| `halo-node node backup /absolute/private/new-folder` | Main database plus optional agent database and SHA-256 manifest |

Individual database snapshots use SQLite `VACUUM INTO`, integrity checks and a
no-overwrite policy. The destination parent must already exist and be private.

The full-node command holds the daemon mutation gate while taking both snapshots.
Its consistency guarantee covers daemon-initiated writes; it is not an atomic
SQLite transaction spanning both databases and cannot protect against unsupported
external writers.

```bash
mkdir -p -m 700 "$HOME/halo-backups"
halo-node node backup "$HOME/halo-backups/snapshot-001"
python3 -B scripts/verify-node-snapshot.py "$HOME/halo-backups/snapshot-001"
```

These backups are not encrypted and are not Android `.halobrain` archives.
Stop the daemon before any restore; copying a live SQLite main file without its
WAL is not a substitute for a consistent snapshot. A complete reviewed restore/
migration workflow and cross-host archive interoperability remain future work.

## Build and remaining gates

Use a separate pinned checkout prepared according to
[Canonical SDK Build](CANONICAL-SDK-BUILD.md):

```bash
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

This verifies provenance and structural contracts, then compiles/tests the real
cognition module, Linux store and optional host and packages the executable.
It does not enable user actions or automatic replay.

The remaining work is specific:

- Lossless fixtures and codecs for the currently unsupported evidence variants.
- Reviewed migration/restore and main/canonical mismatch reconciliation.
- Full session context and explicit user-controlled recovery admission.
- Host authorization, resource leasing and truthful action receipts before tools.
- ARM64/Trixie, real-provider, power-loss and sustained-operation acceptance.
- Android archive interchange and a separately reviewed peer transport.

Durable evidence is necessary for safe recovery. It never provides sufficient
authority on its own to perform a new external action.
