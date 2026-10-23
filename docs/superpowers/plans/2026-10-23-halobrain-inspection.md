# HaloBrain Inspection Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Complete each test cycle before publishing.

**Goal:** Verify Android Halo archives offline on Trixie without modifying Linux data.

**Architecture:** A standalone repository tool streams a private snapshot through
bounded archive validation. AES-GCM decryption is authenticated before ZIP parsing.
Inventory output feeds future compatibility work; it is not an import plan.

**Tech Stack:** Python 3.11+, stdlib, optional distro python3-cryptography; JDK21/Kotlin for a cross-language fixture.

**Spec:** `docs/superpowers/specs/2026-10-23-halobrain-inspection-design.md`

## Global Constraints

No Android edits, CI, network listeners, database access, extraction, or record import.
Android schema 2 and encrypted envelope version 1 only. Keep the existing canonical
runtime source pin unchanged: the archive reference is a separate compatibility pin.

## Review Focus

- Truncated/authentication-failed archives must not expose plaintext or leave files.
- Forged ZIP counts/sizes/offsets must be refused before unbounded allocations.
- Passwords, filenames and JSON contents must not leak through output/error messages.
- JSON duplicate keys, numeric coercion and section count drift must fail closed.
- Normal Android exports with data descriptors, optional activity and empty records must work.

## Tasks

- [x] Write failing CLI and archive fixture tests in `scripts/test_halo_brain_archive.py`.
- [x] Implement `inspect_archive(path, password=None, limits=Limits(), temp_dir=None)`
      and CLI in `scripts/halo_brain_archive.py`; run fixtures to green.
- [x] Generate a JVM encryption fixture from the pinned Android crypto source;
      persist its provenance and verify interoperability with a non-ASCII password.
- [x] Add operator documentation with exact usage, limits and unsupported features.
- [x] Rerun tests, inspect changes and prepare atomic publication; remote outcome is recorded in the delivery message.

## Execution rulings

The live GitHub connector is available, but direct repository cloning/network access
from the execution container failed. Work is isolated to new files and verified
locally. No full Gradle/JVM suite or Raspberry Pi hardware validation is claimed.

A separate offline source-byte drift checker and compatibility manifest were added
to make future archive format changes explicit. The full runtime source pin is unchanged.
