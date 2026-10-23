# Schema-3 Studio Archive Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans or subagent-driven
> development; keep Android canonical and test before publication.

**Goal:** Import Android schema-3 Studio archive records losslessly and safely.

**Architecture:** Version-scoped ZIP validation, inert typed record staging,
bounded Studio structural validation, stable composite version keys and
unchanged transactional import-store policies.

**Tech stack:** Python 3.11+, stdlib ZIP/JSON/SQLite, optional system
cryptography for Android's existing AES-GCM envelope.

**Spec:** `docs/superpowers/specs/2026-10-23-schema3-studio-interop.md`

## Global constraints
No Android edits, no server/network or CI, no settings/agent activation,
preserve schema-2 compatibility and content provenance, maintain Trixie 64-bit
behavior. Keep archived Studio documents and image URIs inert.

## Tasks
- [x] Review Android schema-3 manifest/exporter, Studio DTOs, session and
  document bounds against exact reviewed source SHA.
- [x] Extend verified ZIP inventory to require/prohibit Studio streams by
  schema version and bound large Studio JSONL separately.
- [x] Add composite Studio version identities and fail-closed typed staging.
- [x] Implement project/document/session structural gate; write focused
  regression tests before accepting malformed/future versions.
- [x] Keep schema-2 Brain API fixture producer unchanged in behavior;
  extend opt-in raw kind enums and contract drift checks.
- [x] Add schema-3 import/ZIP integration tests and operator guidance.
  Tests are checked in; their full execution is a subsequent gate.
- [ ] Run the complete archive/import/API suites and full Gradle/daemon
  gates against a complete local repository, and test actual app-generated
  encrypted/unencrypted archives on Pi Trixie.
- [ ] Verify resulting commit/branch heads and publish this increment.

## Review focus
Unsupported schema 4, schema-2 hidden Studio payload, missing mandatory
schema-3 streams, duplicate composite versions, conflicting workspace
headers, malformed embedded schema and Unicode, TOCTOU replacement,
plaintext leaks and incorrect AI-context eligibility.
