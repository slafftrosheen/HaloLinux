# HALO-LINUX-008/010 — staged cognitive integration

Canonical source: https://github.com/slafftrosheen/Halo
Canonical Halo revision used for source audit: `82cc3532dbb2a2edb15144fffd1b6a26154a0e25`.

## Implemented in Linux source

The first Linux cognitive slice now runs:

```
Unix socket ask
  -> Halo LinuxCognitiveTurn
  -> content-free ACCEPTED / REASONING events on Halo EventBus
  -> pinned LinuxProviderLifecycle
  -> Halo SDK AiRequest (ToolUseMode.NONE) / AiContext.EMPTY
  -> Halo SDK provider transport
  -> response validation (reject tool calls, reject empty text)
  -> SQLite transactional user+assistant conversation
  -> content-free COMMITTED or FAILED event
  -> response to invoking CLI
```

The provider is constructed once per daemon and reports CONFIGURED, CHECKING,
AVAILABLE, INFERENCING, DEGRADED or DISABLED. An explicit `provider check`
tests connectivity. Status is content-free and does not launch model requests.
A provider error is never silently retried on another model or sent to the cloud.

Local memory commands remain typed operations; there is no hidden model
authorization to execute shell/device tools or import personal notes.

## Not yet implemented

- Canonical `runtime/cognition` `AgentRunCoordinator` and durable recovery:
  needs matching `libraries/halo-cognition-language`, persistent run store,
  tool authorization and replay-safety contracts, not just an extra provider call.
- Multi-turn conversation resume/context compaction.
- Runtime tool execution, action confirmation, timers, OS command adapters.
- Advanced reasoning pipeline, model delegation and sub-agent recovery.
- Android ↔ Linux peer synchronization.
- User-visible streaming and STT/TTS.

Do not label the current text slice as complete Ask → Reason → Act → Respond.
It intentionally does **not** perform any actions. The next integration should
pin a shared canonical agent-run contract and explicit storage adapters before
tool execution is enabled.

All new code remains source-level pending real Gradle/Linux execution testing.

## HALO-LINUX-011 — crash-safe text-turn checkpoints

SQLite migration v3 adds a content-free `turn_checkpoints` table. A turn
transitions ACCEPTED → REASONING → COMMITTED or FAILED. Startup reconciles any
nonterminal checkpoints into INTERRUPTED. **No request is rerun automatically.**

The ledger stores identifiers, state, timestamps, an optional conversation ID
and a sanitized failure category. It deliberately does not store prompts,
responses, tokens or authorization data. This is a conservative preparation for
the canonical AgentRunCoordinator, **not** a replacement for durable agent
planning/recovery. HALO-LINUX-013 makes conversation and checkpoint completion a single SQLite
transaction. If message insertion fails, neither a completed journal record nor
a partial conversation is committed. The node still never automatically replays
an interrupted turn.

## HALO-LINUX-013 — atomic completion

`commitTextTurn(interactionId, conversation)` conditionally advances the
REASONING checkpoint to COMMITTED and inserts user/assistant messages in the
**same SQLite transaction**. A duplicate commit attempt does not rewrite the
original answer. Tests cover a real SQLite primary-key violation and rollback.

## HALO-LINUX-014 — canonical cognitive event compatibility

The Linux turn now emits the existing portable runtime-sdk events
`CognitiveRequestStarted`, `CognitiveReasoningStarted`, and
`InferenceObserved` along with the minimal Linux turn stages. These canonical
events contain stable IDs, provider/model metadata and outcomes but no prompt
or response payloads. A fixture regression test asserts both order and lack
of private request text in emitted events. Linux continues to forbid tools and
unauthorized replay.
