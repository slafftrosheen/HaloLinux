# Android source alignment and Trixie baseline

## Goal

Make HaloLinux reproducibly consume current Android Halo portable contracts and
produce a working Linux host build targeting Raspberry Pi OS Trixie 64-bit on
Pi 5. Android remains the source of truth. This pass changes HaloLinux only.

## Evidence and boundaries

- Integration starts from Linux `main` at `cf084290b6493014698279da799b75aac111b635`.
- Default `master` has four bootstrap repairs but lacks 93 main commits. Its
  alternate agent contracts must not replace the canonical implementation.
- Android source is reviewed at `5a4047fec6dc9c561969c446641b22373d5157e2`.
- Portable source snapshots must be byte-for-byte copies, tied to a full SHA
  and per-file checksums. Linux adapter and build changes stay outside them.
- The existing canonical cognition option stays explicit. No second planner,
  automatic action replay, provider fallback, or LAN listener is introduced.
- This is source synchronization. Android `.halobrain` transfer and live peer
  synchronization require separate host adapters and remain unimplemented.

## Design

One module allowlist controls snapshot validation and canonical source switching:
`libraries/common`, `libraries/halo-cognition-language`, `sdk/host-sdk`,
`sdk/runtime-sdk`, `sdk/provider-sdk`, `runtime/events`, `runtime/provider-core`.
The default build contains all portable production sources for these modules.
A deterministic manifest binds their paths and SHA-256 hashes to the reviewed
Android commit. Checking requires no network; importing requires an explicit
revision and clean matching upstream checkout. Added, changed, and removed
production files are accounted for. Android tests and Gradle files are not copied.

The opt-in canonical build replaces exactly those sources plus canonical
`runtime/cognition`; it rejects a dirty or mismatched source checkout. Missing
provider validation and cognition-language dependencies are repaired without
forking their APIs. JVM 21 and the existing Gradle 8.14.3 wrapper remain the
baseline. Linux tests, packaging and smoke commands establish actual readiness.

Trixie setup checks distro, userspace architecture, selected Java/compiler,
user-service availability and headless persistence. Audio remains diagnostics
only. Setup never silently edits boot overlays, changes audio routing, enables
lingering, upgrades the OS, or exposes remote management.

The two Linux branches reused SQLite schema version 3 for different schemas.
Reject incompatible legacy databases before persistent changes with actionable
backup guidance; never reinterpret legacy agent records as canonical journals.

## Acceptance

- Offline snapshot check detects missing, changed and untracked source files.
- Explicit source import is repeatable and refuses unsafe or dirty sources.
- Standalone build, unit tests and local daemon smoke flow execute successfully.
- Canonical SDK/cognition build is executed against the exact Android pin;
  any remaining incompatibility is reported precisely.
- Trixie script fixture checks cover wrong Java/architecture and unavailable
  user services; documentation matches implemented configuration and commands.
- Legacy SQLite rejection preserves existing bytes and personal records.
- Physical Pi ARM64 JNI, HiFiBerry I/O, reboot persistence and sustained local
  inference remain separate hardware acceptance gates unless actually exercised.
