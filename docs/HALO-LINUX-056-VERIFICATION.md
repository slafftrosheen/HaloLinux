# HALO-LINUX-056 verification — Linux canonical preferences

Date: 2026-10-23. Linux base:
`8f9afe6fab103853ca84ef9f7cea3d7d0447ba48`.

## Review of Android drift

At review, Android Halo `main` was
`64202f92e325d6a7f090762c1c71d5b8c1a92d74`. The two commits after the
pinned `d1c87d5895e9c68af62a652b6dbec0d3f626f0ae` changed only app/Canvas,
Android-specific tests/resources and documentation—not the seven allowlisted
portable source modules. The existing **186-file** manifest and Android pin are
therefore unchanged. The exact canonical `Storage.kt` local fixture Git blob
matched the published upstream SHA
`2f71afdd424143a4d46f6f963a633f14dd5b5062`.

### New archive-contract drift during this batch

A later live check found Android `main` at
`92370f6cd173affe1b9800fdc3b97a9e05559589`. Its additional commit changes
`BrainArchiveModels.kt`, `BrainSnapshot.kt`, `BrainImportRepository.kt` and
`ZipBrainExporter.kt`, but **no seven-module portable SDK sources**. Therefore
runtime source pinning remains correct; however the 051–053 Android archive
compatibility pin is now behind current Android exports and requires a separate
review/fixtures before accepting the newer format. This batch does not silently
advance that pin or claim importer parity with the newer Studio data.

## Executed

Environment: Debian GNU/Linux 13 Trixie x86-64, OpenJDK 21.0.12.1,
command-line Kotlin 1.9 compiling JVM target 17. The production Gradle JDK21
and Kotlin 2.2.10 configuration is unmodified. This is not Raspberry Pi hardware.

```bash
/usr/bin/python3 -B -W error::ResourceWarning -m unittest discover \
  -s scripts -p 'test_linux_preferences.py'
```

The Python gate compiles the complete new `LinuxPreferenceStorage` against the
unmodified canonical `Storage` interface. Its executable Kotlin harness runs
**68 named behaviors**; the normal-user UID 1000 rerun passes those same 68
behaviors and does not increase the unique-case count. A separate Python method
checks source-level `LinuxRuntimeBridge` composition. This static check is not a
full runtime start or a bytecode/Gradle test.

TDD: the first test failed on missing adapter code. The runtime-composition test
failed on missing canonical `Storage` service registration, then passed after
wiring. Tests cover explicit string nulls, Unicode, integer limits, type mismatch,
correct defaults, multi-type batch transaction and collision rejection, persistence
after a new JVM, size/record limits, private ownership/permissions, symlink/hardlink
rejection, full SHA-256 and strict binary decoder errors, type/version/truncation
fail-closed behavior, a crash before rename, and no mutation of existing config.

Two real JVM processes independently write 100 keys each to one directory. Two
threads/instances independently write 80 keys each; all 360 values survive
without lost updates. The crash test uses a child `Runtime.halt(91)` immediately
before atomic rename and verifies that old committed values remain. Neither test
models a physical power loss or guarantees durability on an actual Pi.

## Limits

No full Gradle build, complete SDK compilation, test suite, shadowJar smoke, real
Halo daemon restart with this change, native ARM64/Pi run, NVMe power-loss test,
new Android device instrumentation, full audio/inference execution or personal
Brain-API integration was performed here. Local repo/dependency downloads were
unavailable; the isolated workspace uses GitHub-verified baseline bytes and
publishes only reviewed changed files. No independent reviewer was available;
manual review covered thread/process locking, atomic replace, file modes,
input bounds, corruption handling and failure privacy.

The adapter is not an Android settings sync mechanism, credential vault or
permission grant. It registers a canonical service for future Linux runtime
consumers but does not make previously unavailable Android features functional.
Any deployment must pass its complete Gradle/host gates first, followed by
physical Raspberry Pi OS Trixie acceptance.
