# Canonical SDK source integration

**Goal:** Keep Linux's portable contracts aligned with an exact reviewed Android
Halo revision while preserving an independently buildable Raspberry Pi checkout.

Canonical owner: <https://github.com/slafftrosheen/Halo>.
Revision: `config/canonical-halo.sha`.
Reviewed modules: `config/canonical-modules.txt`.
Source inventory and SHA-256 digests: `config/canonical-sources.json`.

## Standalone build

The default build uses the complete, checked-in production snapshot of the seven
reviewed modules. This includes cognition-language and provider response
validation; a normal Pi clone does not require an Android checkout.

```bash
python3 -B scripts/sync-canonical-sources.py
python3 -B scripts/check-canonical-sources.py
./gradlew clean build --no-daemon
```

The Python checks require Git only for the regression fixtures or when comparing
with another checkout. The ordinary local manifest check requires Python 3 and
the repository files, without network, model or Java dependencies.

Every Kotlin compilation depends on the same integrity gate. Managed production
modules compile exactly their reviewed `src/main/java` roots in both build modes;
an extra `src/main/kotlin` directory cannot silently add unmanifested contracts.

## Canonical source build

`-PhaloCanonicalRoot=/absolute/path/to/Halo` substitutes the reviewed portable
production source roots from an exact pinned checkout. Linux's own Gradle
configuration, adapters and tests remain in use. The source mode must not mix
canonical and vendored main sources for the same module.

Create a separate detached worktree. Do not reset an active Android checkout:

```bash
HALO_REVISION="$(cat config/canonical-halo.sha)"
git -C ../Halo worktree add --detach ../HaloLinux-canonical "$HALO_REVISION"
./scripts/canonical-sdk-preflight.sh ../HaloLinux-canonical
./gradlew -PhaloCanonicalRoot="$(realpath ../HaloLinux-canonical)" \
  clean build --no-daemon
```

The preflight checks upstream HEAD, clean production source/index state, exact
Git blob contents and the Linux SHA-256 manifest. Checking HEAD alone would miss
uncommitted Android edits; a clean SHA label is not sufficient evidence. A sparse
checkout is accepted only when all allowlisted production sources are present.

Gradle also performs these checks itself when `haloCanonicalRoot` is set, so
calling Gradle directly does not bypass the preflight. The source inventory and
Git blob comparison detect ignored files and edits hidden by Git's
`assume-unchanged` flag.

`runtime/cognition` remains separately opt-in:

```bash
./scripts/verify-canonical-cognition.sh ../HaloLinux-canonical
```

That path also validates the canonical agent journal/store contract and compiles
the real canonical cognition implementation. It does not enable user tools,
automatic agent replay, LAN control or Android-specific host features.

In this mode the integrity gate also verifies every external `runtime/cognition`
production source against its pinned Git blob before compilation. The equivalent
read-only command is:

```bash
python3 -B scripts/sync-canonical-sources.py \
  --halo-repo ../HaloLinux-canonical --include-cognition
```

The executable JAR manifest records `Halo-Canonical-Revision`, `Halo-Source-Mode`
(`snapshot` or `canonical`) and `Halo-Canonical-Cognition`. This identifies the
source variant that was packaged; it is not a claim of hardware acceptance.

## Updating the pin

Use the explicit updater documented in [Shared Source Strategy](SHARED-SOURCE-STRATEGY.md).
Do not edit the pin alone: the source snapshot and manifest must move with it.
The updater is read-only unless `--apply`, `--halo-repo` and a full `--revision`
are supplied together. Review candidate changes first with `upstream-drift.py`.

After an update, run the offline integrity/regression checks, standalone build,
canonical source build, and the optional cognition gate if that variant is used.
Preserve actual compiler/test evidence. Source checks cannot establish Kotlin
compatibility, ARM64 SQLite loading or Raspberry Pi OS hardware behavior.

The source snapshot is a temporary distribution mechanism, not a second owner
of the Halo SDK. Published, revision-pinned JVM artifacts remain the eventual
replacement after both hosts demonstrate compatibility.
