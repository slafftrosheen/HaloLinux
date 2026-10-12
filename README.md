# HaloLinux

Linux host and personal compute node for the [Halo Cognitive Runtime](https://github.com/slafftrosheen/Halo).

**Status: early implementation, source committed; build and hardware verification pending.**
This is not yet a production-ready Linux service. See [implementation status](docs/IMPLEMENTATION-STATUS.md).

## Goals

- Headless Linux host for ARM64 and x86-64
- Local-first personal notes and search
- Optional local/remote inference through explicit provider configuration
- Audio diagnostics followed by actual capture/playback adapters
- Future encrypted account-free peer synchronization

## Source layout

- `host/linux` — Kotlin CLI and Unix-domain socket daemon
- `adapters/inference` — SDK-backed OpenAI-compatible provider adapter
- `adapters/storage` — SQLite, FTS5 and local node metadata
- `adapters/audio` — conservative ALSA/PipeWire diagnostics
- `sdk/*` — **temporary copied** Halo contracts, not yet shared from canonical repo
- `runtime/*` — subset and placeholders; **not** the full cognitive runtime
- `protocol/schemas` — proposed, not yet interoperable sync schemas

## Raspberry Pi 5 / Trixie Desktop first

Initial supported reference setup is a **Pi 5 (8 GB), Raspberry Pi OS
Trixie 64-bit ARM64 with Desktop**. A later headless move should reuse the
same user account, configuration, XDG data and node identity.

- [Trixie Desktop first-run guide](docs/TRIXIE-DESKTOP.md)
- [Desktop-to-headless migration](docs/TRIXIE-HEADLESS-MIGRATION.md)
- [Local control protocol](docs/LOCAL-CONTROL-V1.md)
- [Pre-deployment gates](docs/PRE-DEPLOYMENT-GATES.md)

Run the offline Gradle module graph audit before attempting network builds:

```bash
python3 scripts/check-module-graph.py
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
```

The source has not been JVM-build-verified or ARM64-hardware-verified. Please do
not treat a static dependency check as an executable acceptance test.

Once the daemon is running, backups and non-sensitive diagnostics are available:

```bash
halo-node storage check
halo-node storage backup "$HOME/halo-backup-001.db"
```

Backups contain the private node identity and should be encrypted when stored
off-device or shared. An existing backup file is never overwritten.

## Build (requires JDK 21 and network for dependencies)

```bash
./gradlew clean build
./gradlew :host:linux:shadowJar
./scripts/install.sh
```

The build has **not** been verified in the execution environment that produced HALO-LINUX-002.

## First configuration

```bash
mkdir -p ~/.config/halo-linux
cp config/halo-node.example.yaml ~/.config/halo-linux/config.yaml
# Edit the model identifier to match a model installed at your endpoint.
```

For endpoints requiring an API key, set `HALO_INFERENCE_API_KEY` securely in the process environment.
Do **not** put the key in YAML or check it into Git.

The application never selects a remote provider automatically.

## Start

```bash
~/.local/bin/halo-node doctor
~/.local/bin/halo-node start
```

In a second terminal:

```bash
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "My personal offline test note"
~/.local/bin/halo-node memory search "offline"
~/.local/bin/halo-node ask "What is Halo?"
```

Inference requires an explicitly configured endpoint and model. Memory operations do not.

```bash
systemctl --user start halo-node
systemctl --user status halo-node
systemctl --user stop halo-node
```

For an always-on headless Pi, user lingering may need explicit configuration.

## Safety and limitations

Only the current local user can access the Unix socket through its protected directory.
The service does not expose a LAN API. Private node identity is stored in the local XDG data directory.
Peer authentication, encryption and synchronization are **not implemented**.

The committed source is not proof of a working Raspberry Pi deployment. Hardware, storage corruption recovery, executable packaging, SQLite round-trip tests and daemon lifecycle still require actual testing.

See [portability audit](docs/PORTABILITY-AUDIT.md), [security](docs/SECURITY.md), [Pi setup](docs/PI5-SETUP.md) and [implementation status](docs/IMPLEMENTATION-STATUS.md).

## License

The licensing and provenance of copied Halo SDK material require review.
No blanket new license is asserted for that imported source.

## Cognition and providers — experimental

```bash
halo-node status
halo-node provider check
halo-node ask "Explain HaloLinux in one sentence"
```

The provider is process-scoped. There is no automatic fallback to a remote
service, and no tool/shell execution. Text turns use Halo SDK contracts,
record content-free stage events, and keep conservative interrupted-turn
checkpoints so a crash does not automatically re-run inference.

For optional source-of-truth SDK integration against the pinned Android Halo
revision, see [Canonical SDK build](docs/CANONICAL-SDK-BUILD.md). This is a
portability development path, **not yet a verified shared binary publication**.

See [cognitive integration](docs/COGNITIVE-INTEGRATION.md) for what's wired
and what still requires the canonical agent/recovery modules.

## Canonical agent runtime portability (opt-in)

The full Android Halo `runtime/cognition` code is not copied into this repo.
Instead, a conditional Gradle module can compile the real pinned upstream
agent coordinator and recovery planner directly from a local checkout:

```bash
./scripts/canonical-sdk-preflight.sh ../HaloLinux-canonical
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

See [canonical agent portability](docs/CANONICAL-AGENT-PORTABILITY.md)
and [current implementation status](docs/IMPLEMENTATION-STATUS.md).
This is **experimental compilation**, not a production-ready agent executor.

## Experimental canonical agent recovery

The optional HALO-LINUX-019 adapter implements the actual canonical Halo
`AgentRunStore` with transactional SQLite claims and a restricted, versioned
journal codec. It can classify interrupted canonical runs on daemon startup
using the real Halo recovery planner. **No automatic replay or tools are enabled.**

The standalone Pi build is unchanged. To build and test the opt-in agent mode,
use `scripts/verify-canonical-cognition.sh` with the pinned canonical Halo
worktree. See [agent-store limits](docs/CANONICAL-AGENT-STORE.md), including
the separate database backup requirement.

## HALO-LINUX-021–023 — canonical text turns and private snapshots

Experimental pinned canonical builds now journal text requests through the
real `DefaultAgentRunCoordinator`; standalone builds keep the model-free
local storage path. Provider/tool execution is never authorized by a journal.

For a private snapshot set from a running node:

```bash
mkdir -p -m 700 "$HOME/halo-backups"
halo-node node backup "$HOME/halo-backups/snapshot-001"
python3 scripts/verify-node-snapshot.py "$HOME/halo-backups/snapshot-001"
```

Verification is read-only and checks file hashes, database integrity and file
permissions. **Snapshots are not encrypted.** Always safeguard private node
identity and notes. Do not try to copy live SQLite files or restore over a
running service.

## Low-dependency source verification

Run `./scripts/verify-offline-source.sh` for Bash/Python syntax, the static
module graph and backup verifier fixtures. It needs no Gradle or AI model,
but is not an executable Kotlin build.

For private inference questions, `halo-node ask -` accepts bounded text on
stdin rather than putting the prompt directly in the process argument list.
