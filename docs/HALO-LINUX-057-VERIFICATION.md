# HALO-LINUX-057 verification record

Date: 2026-10-23 (Europe/Riga). Linux parent:
`b9833fc331611c157470c2e610c2af7fc3e3f97e`.
Reviewed Android exporter:
`92370f6cd173affe1b9800fdc3b97a9e05559589`.

## Source alignment

Android's current archive model moved to `BrainArchiveSchema.CURRENT = 3` and
the exporter now declares `studio` and writes
`studio/projects.jsonl`/`studio/versions.jsonl` in every export profile.
The AES-GCM envelope remains version 1. Reviewed studio snapshots and
document/session constraints came from exact Android Git blobs recorded
in `config/halobrain-contract.json`.

The seven portable runtime modules remain pinned to
`d1c87d5895e9c68af62a652b6dbec0d3f626f0ae`. New Android Studio
features live under the Android app rather than those shared source roots,
so this batch does not advance `config/canonical-halo.sha`.

## Executed

Environment: Debian GNU/Linux 13 (Trixie), x86-64, distribution
Python **3.13.5**.

```bash
/usr/bin/python3 -B -W error::ResourceWarning -m unittest discover \
  -s /mnt/data/halolinux-057/scripts -p 'test_halo_studio_contract.py'
```

**37 tests passed, zero failures, errors or skips.** They execute the complete
new Studio contract module against synthetic, in-memory SQLite staged records.
The test fixture was corrected to close SQLite connections deterministically;
the final resource-warning-enabled run produced no ResourceWarnings. Executed
cases include valid project/document/session, revision/history bounds, source
typing, duplicate JSON keys/layers, unsupported formats/future schemas,
wrong revision/key, absent or mismatched workspace references, extra Studio
workspace, and absent parents. The complete tested source and test modules
were uploaded with exact matching local Git blob identities:

- `scripts/halo_brain_studio.py` —
  `bdd7dee2360388cab8654cc9e1a40e99639efad0`
- `scripts/test_halo_studio_contract.py` —
  `8a35cdc0e1968e8c31a12e7b4ccde75ea476992c`

The archive reader/record importer/API modifications received source review and
contain additional regression tests checked in for the complete repository.
Legacy schema-2 Brain API fixtures were explicitly kept version-2 rather than
silently declaring new Studio streams. The OpenAPI kind enums were updated to
match 21 recognized kinds.

## Not executed / unsupported

**The complete schema-2/3 ZIP/record/import/API Python suites were not run** in
this session: the container could not resolve github.com to retrieve a full
checkout, and the execution environment held only the isolated new Studio
validator fixture. New schema-3 integration tests therefore remain a
**pending executable gate**, not evidence of working full archive import.

No Android-app-generated schema-3 archive fixture, full Kotlin/Gradle build,
shadowJar/daemon smoke, HiFiBerry/audio test, real Raspberry Pi OS Trixie
ARM64 deployment, restart/power-loss exercise, live encrypted peer sync,
or native Studio renderer was executed here.

Import-store persistence remains unencrypted at rest; Studio images/URIs and
editing session strings are retained as untrusted inert data. Linux checks
Studio document structures conservatively but does not decode Android
InkCodec and cannot claim full native Android Studio semantic validation.
Successful structural validation never grants tool execution or provider
dispatch permission. The Brain API remains Unix-only, opt-in inspection,
with no new endpoint that exposes Studio as active AI context.

Before release on hardware run the complete Python test family, Android
exporter-to-Linux round-trips with schema-2/3 files, and the documented
Gradle/daemon/Pi acceptance gates. No GitHub Actions or OS changes were made.
