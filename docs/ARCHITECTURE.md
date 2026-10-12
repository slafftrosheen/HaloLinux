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
| `sdk/runtime-sdk` | Engine, EventBus, ServiceRegistry, Resource contracts | `libraries:common` |
| `sdk/provider-sdk` | AiInferenceProvider, AiProviderRouter, OpenAI transport | `sdk:runtime-sdk` |
| `sdk/host-sdk` | Host capability interfaces | `sdk:runtime-sdk` |
| `runtime:kernel` | Engine graph, lifecycle, resource lease manager | `sdk:runtime-sdk` |
| `runtime:cognition` | Agent runs, journaling, recovery, tool execution | `runtime:kernel` |
| `runtime:events` | EventBus, event envelopes | `sdk:runtime-sdk` |
| `runtime:lifecycle` | Engine state machine | `sdk:runtime-sdk` |
| `runtime:observer` | Observability, metrics, health | `sdk:runtime-sdk` |
| `runtime:provider-core` | Provider registry, routing | `sdk:provider-sdk` |

### Linux Host Modules (Platform-Specific)

| Module | Purpose | Implements |
|--------|---------|------------|
| `host:linux` | Main application, CLI, daemon | `Engine`, `RuntimeContext` |
| `adapters:inference` | OpenAI-compatible, fixture providers | `AiInferenceProvider` |
| `adapters:audio` | ALSA/PipeWire device enumeration | `AudioDiagnostics` |
| `adapters:storage` | SQLite + FTS5, XDG directories | `Storage` |
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
  Storage.storeConversation()
         │
         ▼
  Return to User
```