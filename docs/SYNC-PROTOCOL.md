# Halo Sync Protocol

## Overview

HaloLinux implements a local-first, peer-to-peer synchronization protocol for the Halo ecosystem. There is **no central server**, **no mandatory cloud service**, and **no account requirement**.

## Design Principles

1. **Local-First**: All data originates and resides on devices
2. **User-Controlled**: Explicit pairing, explicit sync scope
3. **Encrypted Transport**: All peer communication encrypted
4. **Versioned Contracts**: Schema evolution without breaking changes
5. **Conflict-Aware**: Deterministic conflict resolution
6. **Capability-Based**: Sync only what peers are authorized for

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Android    │     │   Linux     │     │  BuddyBot   │
│   Halo      │◀───▶│   Node      │◀───▶│             │
│             │     │             │     │             │
│  Memory     │     │  Memory     │     │  Memory     │
│  Notes      │     │  Notes      │     │  Notes      │
│  Files      │     │  Files      │     │  Files      │
│  Conv.      │     │  Conv.      │     │  Conv.      │
└─────────────┘     └─────────────┘     └─────────────┘
       │                  │                  │
       └──────────────────┼──────────────────┘
                          ▼
              ┌─────────────────────┐
              │   Sync Protocol     │
              │                     │
              │  Discovery (mDNS)   │
              │  Pairing (QR/Code)  │
              │  Transport (TLS)    │
              │  Sync (Manifest)    │
              └─────────────────────┘
```

## Node Identity

Each node has a persistent cryptographic identity:

```json
{
  "node_id": "uuid-v4",
  "display_name": "My Halo Node",
  "public_key": "base64-ed25519-pubkey",
  "protocol_version": 1,
  "software_version": "0.1.0",
  "capabilities": ["inference", "memory", "search", "audio"],
  "trust_state": "unpaired"
}
```

- **Ed25519 keypair** generated on first run
- **Private key** stored encrypted (file permissions 0600)
- **Node ID** = SHA256(public_key) truncated to UUID format
- **Display name** user-configurable

## Pairing Flow

```
1. Discovery
   │
   ├── mDNS: _halo._tcp.local
   │   TXT: node_id, display_name, protocol_version, capabilities
   │
   ▼
2. User Initiation
   │
   ├── Android: "Pair new device" → Scan QR / Enter code
   ├── Linux:   `halo-node pair --code XXXXXX` or QR display
   │
   ▼
3. Verification
   │
   ├── Both devices show: "Pair with [Device Name]?"
   ├── Show fingerprint: SHA256(pubkey) first 16 chars
   ├── User confirms on BOTH devices
   │
   ▼
4. Key Exchange
   │
   ├── ECDH (X25519) for session keys
   ├── Mutual authentication via Ed25519 signatures
   ├── Establish encrypted channel (Noise protocol or TLS)
   │
   ▼
5. Trust Established
   │
   ├── Store peer public key + trust_state="paired"
   ├── Exchange capability advertisements
   ├── Ready for sync
```

## Sync Data Model

### Memory Entry
```json
{
  "id": "uuid",
  "content": "User prefers dark mode",
  "source": "user",
  "scope": "personal",
  "confidence": 0.95,
  "origin_node_id": "uuid",
  "created_at": "2026-10-12T10:00:00Z",
  "updated_at": "2026-10-12T10:00:00Z",
  "revision": 1,
  "schema_version": 1,
  "attachments": [],
  "tombstone": false
}
```

### Conversation Record
```json
{
  "id": "uuid",
  "title": "Discussion about Halo",
  "origin_node_id": "uuid",
  "created_at": "2026-10-12T10:00:00Z",
  "updated_at": "2026-10-12T10:30:00Z",
  "messages": [...],
  "revision": 5,
  "schema_version": 1
}
```

### Sync Manifest
```json
{
  "sync_id": "uuid",
  "origin_node_id": "uuid",
  "target_node_id": "uuid",
  "timestamp": "2026-10-12T10:00:00Z",
  "entries": [
    {"type": "memory", "id": "uuid", "revision": 3, "operation": "update", "content_hash": "sha256:..."},
    {"type": "conversation", "id": "uuid", "revision": 5, "operation": "create", "content_hash": "sha256:..."}
  ],
  "schema_version": 1
}
```

## Conflict Resolution

**Policy: Last Writer Wins (by revision)** with content hash verification:

1. Compare revision numbers
2. Higher revision wins
3. If equal revision, compare content hashes
4. If content differs at same revision → flag for manual resolution
5. Tombstones (deletions) always win over creates/updates

## Transport Security

- **Noise_XK** or **TLS 1.3** with mutual authentication
- **Ed25519** for long-term identity
- **X25519** for ephemeral session keys
- **ChaCha20-Poly1305** or **AES-GCM** for encryption
- Perfect forward secrecy per session

## Sync Operations

### Pull Sync (Request from Peer)
```
1. Send SyncManifest with entries since last known revision
2. Peer responds with requested objects
3. Apply locally with conflict resolution
4. Acknowledge completion
```

### Push Sync (Send to Peer)
```
1. Send SyncManifest with local changes
2. Peer acknowledges receipt
3. Peer applies with conflict resolution
4. Peer sends updated manifest
```

### Incremental Sync
- Track `last_synced_revision` per peer per data type
- Only send changes since last sync
- Periodic full reconciliation (configurable)

## Capability Scopes

Sync is **scope-aware**:

| Scope | Sync Behavior |
|-------|---------------|
| `personal` | Only with explicitly paired trusted peers |
| `shared` | With peers granted "shared" access |
| `public` | Discoverable, no pairing required (future) |

**Sensitive data** (conversations, private memories) defaults to `personal` scope.

## Discovery

**mDNS** for local network:
```
Service: _halo._tcp.local
TXT Records:
  node_id=uuid
  name=My Halo Node
  proto=1
  caps=inference,memory,search
  port=8443
```

## Current Implementation Status

| Component | Status |
|-----------|--------|
| Node Identity | ✅ Implemented (LinuxStorage) |
| Protocol Schemas | ✅ Defined (JSON Schema) |
| Pairing | 🔄 Design complete, implementation pending |
| mDNS Discovery | 🔄 Planned |
| Encrypted Transport | 🔄 Planned (Noise/TLS) |
| Sync Engine | 🔄 Planned |
| Conflict Resolution | 🔄 Design complete |
| Android Integration | 🔄 Requires Halo app changes |

## Future Capabilities

| Capability | Description |
|------------|-------------|
| `discover` | Find peers on local network |
| `pair` | Establish trust relationship |
| `sync` | Synchronize selected data scopes |
| `search_remote` | Query peer's search index |
| `request_inference` | Offload inference to peer |
| `advertise_compute` | Offer local compute to peers |
| `revoke` | Remove trust, delete shared data |

## Security Considerations

1. **No automatic trust** - Every peer requires explicit user confirmation
2. **No sensitive data sync by default** - Conversations require explicit opt-in
3. **Revocable** - Pairing can be revoked at any time
4. **Auditable** - All sync operations logged
5. **Minimal metadata** - Sync manifests don't reveal content
6. **Forward secrecy** - Session keys ephemeral