# HaloBrain transactional import implementation plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task.

**Goal:** Persist Android archives losslessly with preview, conflict handling and crash-safe writes.
**Architecture:** Reuse HALO-LINUX-051 authentication/ZIP validation. Stage bounded
records and entry payloads in a private temporary SQLite DB. Explicitly import into
a distinct private store, leaving halo.db and the JVM runtime untouched.
**Tech Stack:** distro Python 3.13, sqlite3, optional python3-cryptography on Trixie.
**Spec:** docs/superpowers/specs/2026-10-23-halobrain-import-design.md

## Global constraints
No Android edits, network transport, GitHub Actions or native runtime activation.
Preserve complete raw DTOs and original entry bytes. Do not infer deletions.
100,000 records/import; all HALO-LINUX-051 archive bounds still apply.

## Review focus
- Preview/apply source swapping and destination changes must invalidate consent.
- Repeated old snapshots must not undo newer selections.
- Conflicts, nullable confidence, evidence, scope and unknown fields must survive.
- Failure/process death must not leave partial records or attachment blobs.
- Imported settings, device state, reminders and agent history must remain inert.

## Tasks
- [x] Write CLI/import tests and demonstrate missing implementation failure.
- [x] Add DTO validation and verified staging in scripts/halo_brain_records.py.
- [x] Add private SQLite store, plan/apply/read/recover in scripts/halo_brain_import.py.
- [x] Extend malformed data, conflict, security, rollback and process-death tests.
- [x] Run all available verification, update compatibility pins and operator docs.
Publication gate: publish with expected-SHA guards and verify both remote branches;
the delivery message records that outcome.

## Execution notes
A fresh isolated partial checkout is used because direct git/network access fails.
The baseline archive verifier was reconstructed from fetched source and matched its
published Git blob 842c258ca350073853205010f8440380b36a57d7. Only new or explicitly
reviewed files will be published through the live GitHub connector. No independent
reviewer tool is available; author review and execution evidence are reported as such.

Rulings: dedicated import store, not direct JVM projection; incomplete relationships
are preserved and counted. Recovery checks the SQLite application/schema header
before opening SQLite. SIGKILL may retain private staging; no unsafe automatic
recursive cleanup is added. 58 focused tests pass; full JVM/ARM64 testing is not claimed.
