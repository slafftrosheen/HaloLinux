# Shared runtime contracts — source ownership

Canonical implementation: <https://github.com/slafftrosheen/Halo>.

HaloLinux carries a reproducible snapshot of reviewed portable production sources.
Android remains their owner. Linux build files, OS adapters, CLI and storage are
maintained here. This shares runtime contracts; it does not implement peer data
synchronization or make Linux capable of Android-only host actions.

## Reviewed source boundary

`config/canonical-modules.txt` is the allowlist. Each line names a module whose
`src/main/java` production sources are managed verbatim:

| Module | Responsibility |
| --- | --- |
| `libraries/common` | Shared identifiers and versions |
| `libraries/halo-cognition-language` | Intent, interaction and cognitive language types |
| `sdk/host-sdk` | Portable host contracts |
| `sdk/runtime-sdk` | Runtime, resource, workload, speech and embodiment contracts |
| `sdk/provider-sdk` | Provider, model, workload, embedding and speech interfaces |
| `runtime/events` | Shared event bus implementation |
| `runtime/provider-core` | Canonical AI response validation |

The reviewed revision lives in `config/canonical-halo.sha`.
`config/canonical-sources.json` records that revision, the module list and each
source file's SHA-256. It has no timestamp, so repeated synchronization produces
the same manifest. Source additions and deletions are included in the inventory.

HALO-LINUX-050 pins Android commit
`5a4047fec6dc9c561969c446641b22373d5157e2`: 183 source files across these seven
modules. Gradle verifies the manifest before Kotlin compilation and restricts
managed modules to the reviewed production roots.

`runtime/cognition` remains an explicit optional source integration. The Linux
runtime observer/kernel assembly is not copied by this workflow. Expanding the
allowlist requires a portability and dependency review; the updater does not
discover or copy arbitrary Android modules.

## Checks and updates

Check the checked-in snapshot without another checkout or network access:

```bash
python3 -B scripts/sync-canonical-sources.py
```

Check provenance against a clean canonical checkout at the pinned revision:

```bash
python3 -B scripts/sync-canonical-sources.py --halo-repo ../HaloLinux-canonical
```

Review a candidate Android revision using a separate clean checkout:

```bash
python3 -B scripts/upstream-drift.py --halo-repo ../HaloLinux-candidate
```

The report compares production sources only. Android tests and build files stay
outside this ownership boundary. `--strict` fails on differing sources, local
snapshot damage, or a candidate revision different from the pin.

After reviewing the candidate, update with an explicit full commit SHA:

```bash
python3 -B scripts/sync-canonical-sources.py \
  --halo-repo ../HaloLinux-candidate \
  --revision 5a4047fec6dc9c561969c446641b22373d5157e2 \
  --apply
```

The command validates the upstream HEAD, Git tree, index and actual production
bytes before touching Linux files. It rejects dirty or incomplete upstream source
trees, Android API imports, unsafe paths and symlinks. Existing Linux source edits
or unexpected extra source files must be preserved and reviewed first. Removed
upstream files are deleted only when they belonged to the previous manifest.
Linux build files and tests are untouched. The command does not fetch, change
branches, edit Android, run builds or create commits.

The initial migration from the old unmanifested bootstrap additionally requires
clean tracked local sources. Subsequent updates require an intact prior manifest.
Writes use per-file atomic replacement, with provenance written after sources;
an interrupted update fails integrity checks and must be recovered from the
reviewed Git worktree before retrying.

Run the offline updater regression tests and then both standalone and canonical
Gradle builds. Resolve API mismatches in Linux adapters or in a separately reviewed
canonical change. Do not patch the mirrored SDK to hide a build incompatibility.

## Eventual artifact ownership

1. Demonstrate compatible standalone and canonical builds on Linux, then verify
   the canonical project on Android.
2. Publish versioned JVM artifacts from the portable canonical modules using
   local release scripts, with the source revision and compatibility evidence.
3. Consume pinned artifacts in HaloLinux and remove the source snapshot only
   after the replacement passes both host builds.
4. Version device wire protocols independently from Kotlin source/ABI versions.

No mutable `main` dependency or GitHub Actions is introduced. Matching source
hashes proves the reviewed snapshot, not compilation, ARM64 native support,
hardware acceptance or Android/Linux wire interoperability.
