# Cognitive integration — HALO-LINUX-050

Canonical runtime owner: <https://github.com/slafftrosheen/Halo>.
Reviewed revision: `5a4047fec6dc9c561969c446641b22373d5157e2`, recorded in
`config/canonical-halo.sha`.

HaloLinux supports two explicit compositions. Both accept text requests through
the local Unix socket, use one configured provider/model, preserve local
conversations and reject tool execution.

| Composition | Runtime ownership and persistence |
| --- | --- |
| Standalone/default | Canonical SDK sources, shared event bus and Linux text-turn orchestration; main SQLite conversations and content-free turn checkpoints |
| Optional canonical cognition | The same host text path plus the real upstream `DefaultAgentRunCoordinator`, recovery planner and Linux `SqliteAgentRunStore` in a separate database |

The standalone JVM build and host tests have executed on x86-64 Linux.
Compilation of the pinned canonical cognition and Linux agent-store modules and
their focused tests has also succeeded. Consult
[implementation status](IMPLEMENTATION-STATUS.md) for the final complete
canonical host/package gate and hardware evidence; a module-level result is not
the complete deployment gate.

## Text request flow

`LinuxCognitiveTurn` implements the bounded host turn. Its
`LinuxRecoverySupport` hook is inactive in the standalone build and backed by
`CanonicalHostRecoverySupport` in the optional build.

| Stage | Behavior |
| --- | --- |
| Admission | Validate the bounded request and acquire the host mutation gate |
| Local checkpoint | Persist ACCEPTED, then REASONING, with an interaction ID |
| Optional canonical start | Create a real canonical run, record preparation and provider-turn evidence before inference |
| Inference | Call the process-pinned provider with `ToolUseMode.NONE` and `AiContext.EMPTY` |
| Validation | Reject tool calls, blank responses and responses exceeding the encoded local delivery budget |
| Local commit | Insert the conversation and advance its checkpoint in one SQLite transaction |
| Optional canonical completion | Record response commitment and complete the canonical run after the main database commit |
| Return | Send the answer and metadata to the invoking local client |

The event bus carries `CognitiveRequestStarted`,
`CognitiveReasoningStarted`, `InferenceObserved` and Linux turn-stage events
without prompt/response content. This does not mean all persistence is
content-free: conversations and the optional canonical run journal contain
private user/model text.

A committed response means the host durably recorded it. It does not prove that
a disconnected CLI displayed the answer or that a user read it.

## Provider lifecycle and bounded work

The provider is constructed once per daemon with an explicit endpoint/model.
It reports DISABLED, CONFIGURED, CHECKING, AVAILABLE, INFERENCING, DEGRADED or
STOPPED. `provider check` is an explicit connectivity probe; status snapshots
do not launch model requests.

Only one generation owns the provider permit. Admission waits are bounded
separately from generation time, and a busy caller does not start a late queued
model request or mark the active model degraded. The permit remains held through
response validation and terminal status updates. Shutdown cancels/closes the
underlying transport and retains terminal lifecycle state.

An unavailable, failed, timed-out or busy provider never triggers an automatic
retry on another model or remote service. Requests receive `AiContext.EMPTY`;
the host does not silently retrieve personal memory into a model prompt.

## Main-database durability

The main SQLite `turn_checkpoints` table stores interaction IDs, stage,
timestamps, an optional conversation ID and a sanitized failure category.
It does not store request text, responses or authorization evidence.

`commitTextTurn(interactionId, conversation)` conditionally advances a
REASONING checkpoint to COMMITTED and inserts the user/assistant conversation
in the same transaction. A failed insert rolls both changes back; a duplicate
commit does not replace the original answer.

After the daemon acquires its single-instance lock, unfinished standalone
checkpoints are classified as INTERRUPTED. This does not rerun inference.
The lightweight checkpoint ledger remains distinct from the canonical
agent journal.

## Optional canonical journaling and startup recovery

The optional host instantiates the actual canonical
`DefaultAgentRunCoordinator(store, scope)`. Startup calls
`prepareOutstandingRunsForRecovery()` once, after acquiring the daemon lock.

That operation classifies existing evidence and may normalize durable run states
to SUSPENDED, waiting states or terminal states. It is not purely a read-only
database operation. Subsequent `agent recovery` inspection is read-only and
reports content-free counts and startup categories.

A fresh text request is registered through `coordinator.start()`. The journal
contains `RunPrepared` and `ProviderTurnStarted` before inference,
`ProviderTurnCompleted` and `ResponseProduced` after a valid response,
and `ResponseCommitted` after main-database persistence. The coordinator then
completes the run.

The main and canonical stores are separate SQLite databases. There is no atomic
transaction spanning both. If the main conversation commits but canonical
finalization fails, the answer remains durable and is returned with
`canonical_journal_state=NEEDS_RECONCILIATION`. The canonical run ID identifies
the outstanding evidence. The node does not regenerate or redeliver the answer
automatically.

A recovery planner's recommendation is not permission to run it. Neither startup
nor status invokes `claimResume`, `claimRecovery`, a provider retry or a tool
executor. Manual resume is not exposed by this host either. Unknown journal
versions/variants fail explicitly instead of dropping evidence.

See [canonical agent store](CANONICAL-AGENT-STORE.md) for codec, claims and
backup details, and [the portability gate](CANONICAL-AGENT-PORTABILITY.md)
for the optional build.

## Local query and action boundary

`LinuxRuntimeBridge` implements two typed read-only queries:
`SearchLocalMemory` and `CountInterruptedTextTurns`. Unknown query types and
unsupported commands fail explicitly. Explicit CLI memory operations do not
grant a model permission to read arbitrary notes or invoke OS/device actions.

Canonical action and authorization types compile in the optional module, but
that does not install a Linux executor. User-facing tool execution requires
separately wired host capabilities, resource admission, authorization, action
receipts and replay-safety evidence.

## Remaining integration work

- Full multi-turn `CognitiveSession` context assembly, resume and compaction.
- Additional lossless canonical journal variants and cross-host migration fixtures.
- A reviewed reconciliation workflow for main/canonical delivery mismatches.
- Authorized host tool execution and explicit user-controlled recovery admission.
- Linux capture/playback, streaming delivery, wake/VAD, STT and TTS.
- Android `.halobrain` interchange and subsequently reviewed live peer sync.
- Pi 5/ARM64, real-provider, power-loss and sustained-operation acceptance.

Keep runtime planning and authorization canonical. The implemented source-sync
gate prevents SDK drift; it does not implement Android personal-data transfer
or qualify Android-specific host functionality on Linux.
