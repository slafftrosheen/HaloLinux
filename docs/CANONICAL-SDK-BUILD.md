# HALO-LINUX-009 — canonical SDK source integration

**Goal:** Stop silently diverging Kotlin SDK contracts from Android Halo.

Canonical owner: https://github.com/slafftrosheen/Halo
Pinned upstream revision: `config/canonical-halo.sha`

## Source modes

**Standalone/default:** Linux's bootstrap SDK snapshots continue compiling so an
independent Pi clone is possible. They are technical debt, not the authoritative
home for SDK changes.

**Canonical source (opt-in):** pass `-PhaloCanonicalRoot=/absolute/path/Halo`.
The Gradle Kotlin source sets for common, cognition-language, host-sdk,
runtime-sdk, provider-sdk and events use **only canonical Halo sources**.
Gradle checks that the upstream repository matches the reviewed pinned SHA.
The source override does not make any changes to the Android repository.

Example using a separate detached worktree (do not reset an active Halo checkout):

```bash
git -C ../Halo worktree add --detach ../HaloLinux-canonical 82cc3532dbb2a2edb15144fffd1b6a26154a0e25
./scripts/canonical-sdk-preflight.sh ../HaloLinux-canonical
./gradlew -PhaloCanonicalRoot="$(realpath ../HaloLinux-canonical)" clean build
```

The additional `libraries/halo-cognition-language` module exists so latest
canonical runtime-sdk sources can resolve their cognition-language contracts.
Native Android sources are never imported.

## Safety and known limitations

This is an executable **opt-in integration path, not yet a green CI/build claim**.
Canonical SDK APIs have moved since the original Linux snapshot. Compile and
contract tests may identify adaptation work in Linux adapters or portable module
dependencies. Resolve mismatches at a named version boundary; don't alter
canonical contracts solely to silence Linux compilation.

No automatically tracking `main` branch. To advance the pin, explicitly review
upstream API changes, update `config/canonical-halo.sha`, run both build
variants and regression tests, and commit the reviewed pin change.

After the pinned canonical build is demonstrated on Linux AND Android, replace
vendored sources with reproducible published Maven/JVM artifacts (including SHA
provenance). Until then, do not delete the fallback.

The Linux host still does not execute the complete Android agent coordinator.
See `docs/COGNITIVE-INTEGRATION.md` for the staged portability boundary.
