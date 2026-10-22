# Halo peer sync proposal — not implemented

## Overview

This document describes a proposed local-first peer protocol. HaloLinux does
**not** currently implement pairing, discovery, encrypted peer transport, or
cross-device synchronization. Its management endpoint is a private Unix socket.
The JSON schemas in `protocol/schemas` are drafts, not an Android interoperability
contract. There is no Halo-operated server or mandatory account in this design.

The implemented Android **source** synchronization is documented in
[Shared source strategy](SHARED-SOURCE-STRATEGY.md). It copies reviewed portable
contracts from an exact commit and is separate from transferring personal data.

## Android interoperability baseline (2026-10-23)

Reviewed Android revision: `5a4047fec6dc9c561969c446641b22373d5157e2`.
Android has a versioned `.halobrain` backup format, including section checksums,
completion evidence, and optional authenticated encryption. A Linux SQLite node
snapshot is **not** that format and cannot be imported into Android as one.

The first data-transfer integration should reuse Android's archive contract and
its preview/apply semantics through host adapters. It must preserve origin and
revision information, unknown records, import scope, and user conflict choices.
Credentials, OS permissions, and device-local content URIs must not be treated as
portable capabilities. Live sync requires a separately reviewed transport and
trust model; the following proposal is not authorization to expose a listener.

Canonical Android sources: `app/src/main/java/com/halo/app/backup/`:
`BrainArchiveModels.kt`, `BrainArchiveCrypto.kt`, `BrainSnapshot.kt`,
`ZipBrainExporter.kt`, and `BrainImportRepository.kt`.

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

Proposed peer identity (not the current Linux identity wire contract):

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
- **Private key** encryption and storage protection require a concrete design;
  current Linux node keys are local SQLite data protected by filesystem permissions,
  not encrypted at rest.
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

**Draft only:** revision comparisons below need an origin/version-vector design
before implementation. Independent per-device revision counters cannot establish
which concurrent edit is newer. Android archive import currently presents
explicit conflict choices; it must not be silently replaced by this draft.

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
| Pairing | Proposed; security/protocol review and implementation pending |
| mDNS Discovery | 🔄 Planned |
| Encrypted Transport | 🔄 Planned (Noise/TLS) |
| Sync Engine | 🔄 Planned |
| Conflict Resolution | Proposed; concurrent revisions and tombstones unresolved |
| Android portable source alignment | Implemented separately; SHA-pinned source snapshot |
| Android personal data transfer | Not implemented; `.halobrain` is the existing Android contract |

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
