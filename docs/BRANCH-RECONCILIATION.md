# Main and legacy master reconciliation

HALO-LINUX-050 uses the mature `main` implementation as its integration base.
The reviewed starting heads were:

| Branch | Commit | Unique commits since the common ancestor |
| --- | --- | --- |
| `main` | `cf084290b6493014698279da799b75aac111b635` | 93 |
| legacy `master` | `f975c97dd6c5bdc22fa15d4f580f43d6bfea2b66` | 4 |

The common ancestor is `4b480255132cbbaca09d490bf2ec40d36935507d`.
The legacy branch was still the GitHub default, so an ordinary clone could miss
the later daemon, storage, recovery and privacy work on `main`.

## Resolution

The integration retains `main`'s host and adapters and imports the exact reviewed
Android contracts. The useful executable-wrapper repair from `master` is retained.
The other bootstrap repairs are superseded by the compiled canonical source
snapshot and the later Linux fixes. In particular, the legacy locally invented
agent/store contracts are not merged over Android's canonical contracts.

An explicit reconciliation merge records the legacy head as a parent while
retaining this reviewed implementation tree. Both branch refs can then advance
to that merge by fast-forward; no history rewriting or force push is required.
`main` remains the branch named in installation and development commands.
Repository default-branch settings are not changed by the source update.

## Existing legacy databases

Both old branches called their latest main database schema **version 3**, but
they meant different structures:

| Lineage | Version 3 tables |
| --- | --- |
| main | `turn_checkpoints` |
| legacy master | `agent_runs`, `agent_run_events` |

HaloLinux now checks the migration history and required columns before any
database mutation, directory creation or permission changes. An incompatible
legacy database is rejected with recovery instructions. There is no automatic
conversion between these schemas.

Before changing an installation that used legacy master:

1. Stop the old daemon and confirm it has exited.
2. Back up the complete private data directory, including any `-wal`, `-shm`
   or rollback-journal files. Preserve the original until recovery is verified.
3. Use a reviewed migration from a copy, or configure a new empty data directory.
   Do not delete tables or change the recorded schema version to bypass the guard.

An empty data directory creates a new node identity; it does not transfer old
notes, conversations or pairing metadata. These files contain private identity
material and personal content and belong on trusted encrypted backup storage.

## Why inspection uses a temporary copy

Even a read-only SQLite connection may create WAL/SHM sidecars beside a WAL-mode
database. The startup check therefore copies the database and any existing WAL
into a temporary directory with mode `0700`, using files with mode `0600`, and
inspects that copy. The original files are never opened by SQLite during this
check. File identity, length and modification time are checked for concurrent
changes; symbolic-link sidecars and rollback journals are rejected.

This costs one sequential read and temporary disk space equal to the database
plus WAL at each startup. Copying uses a bounded 64 KiB buffer. Keep sufficient
space in the JVM temporary directory, especially for large stores on a Pi.
For a disk-backed temporary directory on NVMe, create a private directory and
set the JVM's `-Djava.io.tmpdir=/absolute/path` option, for example through
`JAVA_TOOL_OPTIONS` in a user service override. Do not put credentials in JVM
options. If a rollback journal exists, recover it with the originating build
before migrating. The guard does not attempt crash repair or a cross-process
live copy.

The separate optional `canonical-agent-runs.db` remains the canonical agent
store. A full `halo-node node backup` snapshot includes it when that integration
is linked; `storage backup` alone backs up only the main database.
