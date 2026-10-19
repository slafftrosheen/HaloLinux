# HaloLinux Architecture

## Design Principles

1. **Runtime-First**: Core runtime contracts are platform-neutral; host-specific adapters implement platform capabilities
2. **Local-First**: All functionality works offline; network is optional enhancement
3. **Security by Default**: No unauthenticated network exposure; least privilege; encrypted secrets
4. **Observability**: Structured logging, health states, diagnostics built-in
5. **Modularity**: Clean dependency boundaries; replaceable adapters

## Module Structure

### Core Runtime Modules (Platform-Neutral)

| Module | Purpose | Dependencies |
|--------|---------|--------------|
| `sdk/runtime-sdk` | Engine, EventBus, ServiceRegistry, Resource contracts, AgentRunStore, StructuredStorage, AudioTypes | `libraries:common` |
| `sdk/provider-sdk` | AiInferenceProvider, AiProviderRouter, OpenAI transport, CredentialProvider | `sdk:runtime-sdk` |
| `sdk/host-sdk` | Host capability interfaces (WearableHost, AudioSource) | `sdk:runtime-sdk` |
| `runtime:core` | Engine graph, lifecycle, resource lease manager | `sdk:runtime-sdk` |
| `runtime:events` | EventBus, event envelopes | `sdk:runtime-sdk` |
| `runtime:lifecycle` | Engine state machine | `sdk:runtime-sdk` |
| `runtime:observer` | Observability, metrics, health | `sdk:runtime-sdk` |
| `runtime:provider-core` | Provider registry, routing | `sdk:provider-sdk` |

### Linux Host Modules (Platform-Specific)

| Module | Purpose | Implements |
|--------|---------|------------|
| `host:linux` | Main application, CLI, daemon, composition root | `Engine`, `RuntimeContext` |
| `adapters:inference` | OpenAI-compatible (delegates to ConfiguredOpenAiCompatibleProvider), fixture providers | `AiInferenceProvider` |
| `adapters:audio` | ALSA device enumeration (no speculative pw-cli), reports unknown capabilities as unknown | `AudioDiagnostics` |
| `adapters:storage` | SQLite + FTS5, XDG directories, deterministic migrations, AgentRunStore | `StructuredStorage`, `Storage`, `AgentRunStore` |
| `adapters:bluetooth` | BlueZ/D-Bus (future) | `BluetoothAdapter` |

### Protocol & Sync

| Module | Purpose |
|--------|---------|
| `protocol:schemas` | JSON Schema definitions for sync contracts |

## Dependency Graph

```
libraries:common
    │
    ▼
sdk:runtime-sdk ◄── sdk:provider-sdk
    │                    │
    ▼                    ▼
runtime:*           adapters:inference
    │                    │
    ▼                    ▼
adapters:storage ◄────┘
    │
    ▼
host:linux (main application)
```

## Lifecycle

```
Created → Initializing → Running ↔ Paused → Stopped → Disposed
                │
                ▼
           EngineContext
                │
        ┌───────┴───────┐
        ▼               ▼
   ServiceRegistry  RuntimeContext
        │               │
        ▼               ▼
   EventBus        Scheduler
   CommandBus      Logger
   QueryBus        Clock
```

## Health States

| State | Meaning |
|-------|---------|
| `Healthy` | All systems operational |
| `Degraded` | Some non-critical components failed |
| `Recovering` | Startup or recovery in progress |
| `Failed` | Critical failure, manual intervention needed |
| `Offline` | Stopped or disposed |
| `Unknown` | Initial state |

## Node Health Dimensions (Not a Single Boolean)

- **Process**: JVM alive, no OOM/crash
- **Runtime**: Engine initialized, EventBus running
- **Provider**: Configured, credentials valid
- **Model**: Selected model available, reachable
- **Audio**: Input/output devices enumerated
- **Storage**: Database accessible, migrations current
- **AgentRunStore**: Crash recovery operational, non-terminal runs tracked
- **Sync**: Peer connections (when enabled)

## Security Boundaries

```
┌────────────────────────────────────────┐
│         User Session                   │
│  ┌──────────────────────────────────┐  │
│  │  halo-node (unprivileged)        │  │
│  │  ┌────────────────────────────┐  │  │
│  │  │ Management API (Unix sock) │  │  │
│  │  │ - Auth required            │  │  │
│  │  │ - Loopback only            │  │  │
│  │  └────────────────────────────┘  │  │
│  │  ┌────────────────────────────┐  │  │
│  │  │ Inference Adapter          │  │  │
│  │  │ - TLS to remote            │  │  │
│  │  │ - No auth for local        │  │  │
│  │  └────────────────────────────┘  │  │
│  │  ┌────────────────────────────┐  │  │
│  │  │ Storage (SQLite + files)   │  │  │
│  │  │ - File perms 0600          │  │  │
│  │  └────────────────────────────┘  │  │
│  └──────────────────────────────────┘  │
└────────────────────────────────────────┘
```

## Data Flow: Text Inference

```
User Request (CLI/API)
         │
         ▼
  HaloNodeApplication
         │
         ▼
  RuntimeContext.commandBus
         │
         ▼
  ProviderRouter.selectProvider()
         │
         ▼
  AiInferenceProvider.generate()
         │
         ▼
  OpenAiCompatibleTransport (HTTP/SSE)
         │
         ▼
  Remote/Local Inference Server
         │
         ▼
  AiResponse
         │
         ▼
  StructuredStorage.storeConversation()
  AgentRunStore.create() / update() / appendStep()
         │
         ▼
  Return to User
```

## Agent Run Lifecycle & Crash Recovery

### Run State Machine

```
CREATED → QUEUED → RUNNING ↔ WAITING_FOR_TOOL
                      ↓              ↓
                 WAITING_FOR_USER   │
                      ↓              │
                 COMPLETED/FAILED/CANCELLED
                      ↓
                 INTERRUPTED (on crash)
                      ↓
              QUEUED (for retry) / CANCELLED
```

### Crash Recovery Policy

1. **On startup**: Reconcile all `RUNNING` and `WAITING_FOR_TOOL` runs to `INTERRUPTED` via atomic CAS
2. **Resume**: Only from explicit safe checkpoints (`AgentRun.checkpointRef`)
3. **Tool side effects**: Never automatically replayed; require idempotency key + reconciliation or user confirmation
4. **Pure inference**: May be retried with bounded attempt counter if user-visible semantics permit
5. **WAITING_FOR_USER**: Remains waiting (not interrupted)

### Event Journal

- Append-only with monotonically increasing per-run sequence numbers
- Stable event IDs (UUID)
- Includes: event type, timestamp, schema version, payload reference, correlation ID
- No chain-of-thought or hidden reasoning persisted
- Credential-bearing data redacted

### Concurrency Control

- Optimistic locking via `revision` field (compare-and-swap)
- Journal append and run state update commit atomically
- Stale writers rejected (`ConcurrentModificationException`)
- Unique constraints: `(run_id, sequence)` and `event_id`

## SQLite Migration Strategy

- One SQL statement per migration step (no regex splitting)
- Trigger definitions as indivisible statements
- Schema version tracked in `schema_version` table
- Migrations run in single transaction; on failure rollback and preserve old version
- On restart, retry safely from last committed version
- `PRAGMA foreign_keys=ON`, WAL mode, busy timeout on each connection
- `INSERT OR REPLACE` avoided where it cascades deletes; explicit upserts used
- FTS5 triggers verified for insert/update/delete; malformed FTS syntax handled as expected error

## Provider Integration

- Reuses `OpenAiCompatibleTransport` for HTTP/SSE, cancellation, timeouts, model discovery, tool-call streaming
- Supports OpenAI-compatible endpoints including loopback, LAN, and remote
- `CredentialTransportPolicy` enforces HTTPS or explicitly trusted local transport
- Auth-free local endpoints (`ProviderAuthKind.NONE`) work for `testConnection`, `listModels`, `generate`, `stream` without synthetic credentials
- Never silently selects paid remote model when local-only policy requested
- `LinuxOpenAiCompatibleProvider` delegates to generic `ConfiguredOpenAiCompatibleProvider` to eliminate duplication