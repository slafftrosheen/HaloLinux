# HaloLinux roadmap

## Direction

HaloLinux is the Linux host for the canonical Halo runtime: local-first personal
storage and intelligence, explicit optional providers, and future user-controlled
interoperability with Android. Raspberry Pi 5 with Raspberry Pi OS Trixie 64-bit
is the reference embedded target. No Halo-operated service or mandatory account
is needed for the local node.

This roadmap records the current HALO-LINUX-050 boundary and ordered next work.
Detailed test evidence belongs in [implementation status](IMPLEMENTATION-STATUS.md);
historical batches remain in the changelog.

## Current baseline — HALO-LINUX-050

| Area | Current state |
| --- | --- |
| Standalone build | JVM build, executable JAR packaging, 83 tests and daemon smoke passed on x86-64 Linux |
| Android source alignment | 183 verbatim production files, seven reviewed modules, pinned to `5a4047fec6dc9c561969c446641b22373d5157e2` |
| Repeatable updates | Source allowlist, SHA-256 manifest, offline verification and explicit reviewed update command |
| Local node | Kotlin CLI, private Unix socket, single-instance protection and bounded mutation admission |
| Storage | SQLite/FTS5 notes, conversations, conservative text-turn checkpoints and consistent snapshots |
| Existing data | Schema lineage guard rejects incompatible legacy `master` stores before initialization; no automatic conversion |
| Inference | Explicit process-scoped OpenAI-compatible provider, health checks and no automatic remote fallback |
| Canonical cognition | Real pinned coordinator/recovery contracts and Linux agent store; complete 70-test JVM gate and packaged daemon smoke passed; device acceptance pending |
| Trixie operations | JDK/userspace checks, Desktop/Lite setup and explicit user-service lifecycle; script fixtures executed |
| Audio | ALSA/PipeWire diagnostics only |
| Android personal-data sync | Not implemented; source alignment does not transfer user data |

The source snapshot repairs missing cognition-language and provider-validator
sources and includes current workload, provider, speech and embedding contracts.
Importing those contracts does not establish Linux execution support for every
workload or Android host capability.

## 1. Accept the Pi 5/Trixie host

The next deployment gate is executed evidence from the actual ARM64 device:

- Build with the pinned wrapper and JDK 21 on 64-bit Trixie userspace.
- Run the standalone tests and model-free daemon smoke path; verify native SQLite
  loads and databases/snapshots reopen correctly.
- Verify start, stop, restart, logout and reboot under the intended user service.
- Confirm NVMe mount/data paths, permissions, identity preservation and free space.
- Exercise provider loss, bounded concurrency, shutdown and memory pressure.
- Record thermals, resident memory and sustained behavior with the chosen model.

Collect observed results before claiming performance, uptime or ARM64 parity.
Source inspection and shell fixtures do not replace hardware acceptance.
See [pre-deployment gates](PRE-DEPLOYMENT-GATES.md).

## 2. Keep Android and Linux contracts aligned

Use the explicit [canonical source workflow](SHARED-SOURCE-STRATEGY.md) for each
reviewed Android revision:

- Review production source additions, removals and API changes.
- Update the source snapshot, revision pin and hash manifest together.
- Run standalone and canonical-source builds; run the optional cognition gate for
  variants that use it.
- Keep Linux adapters outside canonical source ownership.
- Move to revision-pinned published JVM artifacts after both hosts demonstrate
  compatibility.

Keep the optional canonical coordinator experimental until its complete gate and
host acceptance pass. Extend the existing Halo execution/authorization contracts
when adding actions; do not introduce a separate Linux planner or replay engine.

## 3. Establish portable Android data interchange

The first data bridge should implement Android's existing versioned
`.halobrain` archive contract through Linux host adapters.

- Read-only inspection and validation before any import.
- Exact manifest/completion/checksum and authenticated-encryption compatibility.
- Resource limits, unsafe-path rejection and transactional application.
- Preserved origins, identifiers, revisions and explicit conflict choices.
- Clear handling of unsupported records, missing attachments and host-local URIs.
- No transfer of credentials or assumptions that Android OS permissions are portable.
- Cross-host fixtures and round-trip acceptance before presenting the bridge to users.

Linux's current SQLite snapshot is a different format. A migration tool for
incompatible old Linux schemas is also separate work and must preserve the
original database until its conversion is verified.

## 4. Add reviewed live peer synchronization

Live sync follows data interchange and a concrete security review:

- Explicit pairing and user-approved data scopes.
- Authenticated encrypted transport, peer revocation and content-free diagnostics.
- Version negotiation, bounded transfers and interruption recovery.
- Conflict handling that understands concurrent edits and deletion history.
- Android and Linux implementation against one shared wire contract.
- Cross-device restart, conflict, corruption and revocation tests.

The existing peer JSON schemas are proposals. The transport, cryptography and
conflict rules require an agreed design; draft examples are not implementation
commitments or authorization to expose a network listener.
See [sync protocol boundary](SYNC-PROTOCOL.md).

## 5. Implement Linux audio and workload execution

Build actual Linux adapters behind the current SDK contracts:

- Capture/playback and route ownership for the selected ALSA/PipeWire setup.
- Wake/VAD/STT and speech interruption/stop behavior.
- TTS providers with measured ARM64 compatibility, voices, latency and memory use.
- Resource admission, residency and eviction coordinated with inference.
- Task-specific qualification for intent, chat, thinking, actions, embeddings
  and vision before advertising them as ready.
- Explicit provider and fallback policy; private content must not leave the node
  through an implicit routing decision.

HiFiBerry overlays, microphone paths and speaker playback require the exact
physical board. Android model availability is not proof that its artifact or
native backend runs on Raspberry Pi.

## Later extensions

Other hosts, additional peripherals, robot embodiments and optional peer compute
can build on these verified boundaries. Keep the current work focused on a useful,
recoverable local node, compatible Android contracts and demonstrated Pi behavior.

Changes follow [AGENTS.md](../AGENTS.md): canonical runtime ownership, local-first
operation, no surprise Android edits and no GitHub Actions.
