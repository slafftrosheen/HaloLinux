# Shared runtime contracts — source ownership

**State:** copied Halo portable SDK files remain in HaloLinux. The architecture is **not**
single-source-of-truth yet. The original 85% portable estimate is not a measured build result.

## Canonical owner
Canonical implementation: https://github.com/slafftrosheen/Halo

The following duplicated JVM sources must converge:
- `sdk/runtime-sdk`
- `sdk/provider-sdk`
- `sdk/host-sdk`
- `libraries/common`
- `runtime/events`

Use `python3 scripts/upstream-drift.py --halo-repo ../Halo` to report exact diffs.
The script is read-only and works offline with two local checkouts.

## Migration plan
1. Compile and test both host builds independently before changing ownership.
2. Identify a minimal, acyclic portable module dependency graph in the **canonical** Halo project.
   Keep Android Gradle Plugin, Room, AndroidX and native models out of portable contracts.
3. Publish versioned, signed/tagged JVM artifacts from the canonical project using local scripts
   (no GitHub Actions). Include source revision and API compatibility tests.
4. Consume pinned Maven artifacts (or a verified local composite build of portable-only modules)
   in HaloLinux. Remove duplicated sources only after both builds pass.
5. Define cross-host wire protocol versions independently from Kotlin ABI.
6. Add an automated contract compatibility test between Android and Linux artifacts.

## Explicit non-goals
- Do not reference a mutable `main` dependency for reproducible Pi builds.
- Do not copy Android application composition roots into Linux.
- Do not rewrite durable agent execution, tool authorization or model routing as Linux-only logic.
- Do not replace real protocol interoperability testing with Kotlin compile success.

## Deployment note
Independent Pi alpha testing is possible with the temporary pinned source copy after
software gates pass. Multi-host sync and migration-safe updates are **not** ready yet.
