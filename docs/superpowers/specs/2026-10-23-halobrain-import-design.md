# HALO-LINUX-052: lossless, transactional Android import

Continue the approved archive-interoperability work. Android is canonical. Pin the
reviewed DTOs at 023ef76013b599ee7ca3513014af184a5242e2b5; the models, exporter and
crypto still match HALO-LINUX-051. The runtime SDK source pin is not changed.

## Architecture decision

The current JVM memories table has no columns for Android status, scopeId, evidence,
revision, origin or confirmation. Direct projection would lose semantics or expose
revoked/private facts to retrieval. Use a distinct private SQLite import store,
not halo.db and not an alternate reasoning engine. Store all original entry bytes
and raw record versions; select current versions with explicit conflict policies.
No settings, reminders, devices or executable agent state become live runtime state.
This is persisted offline import, not JVM retrieval integration or peer transport.

## Contract

- Reuse the existing bounded archive verifier and crypto. Authenticate and validate
  a private snapshot; consume the SAME snapshot, never reopen the user source.
- Validate reviewed DTO field types/identities; preserve nullable, omitted, defaulted,
  opaque JSON strings and unknown fields without normalization of saved bytes.
- Stream attachments into SQLite BLOBs. Do not follow any stored URI or path.
- Store namespace is an explicit user source label, NOT an authenticated peer ID.
- init is explicit. Preview is read-only. Apply requires the preview token and
  plaintext-at-rest consent. Token binds store identity/generation, source, policy
  and verified archive content; recompute under BEGIN IMMEDIATE before mutations.
- Default policy fails on changed records. keep-local and use-incoming are explicit;
  both retain incoming archive versions and never erase previous history.
- Repeat archives are no-ops, even after later imports. A reused archiveId with
  different content fails. Absent records are NOT interpreted as deletions.
- Unresolved links are counted and retained, not silently discarded; native runtime
  activation is unsupported. Validate attachment hashes/sizes when metadata supplies them.
- Store directory is user-owned 0700; DB is regular, unlinked elsewhere, 0600.
  Use directory handles, no symlink traversal, process locking, SQLite transactions,
  a distinct application/schema ID and explicit recovery for interrupted journals.
- Fixed errors and content-free plan/status output. Explicit read may return content
  as escaped JSON. No password argument/environment, networking, CI or Android edits.
- Total records are capped at 100,000 per import in addition to archive limits.

## Verification boundary

Use real Python 3.13/SQLite on Debian Trixie x86-64, synthetic DTO-complete archives,
plain/encrypted inputs, rollback injection, process death, replay and concurrency
checks. No claim to real Android app export, ARM64 hardware or full JVM testing.

Operational qualification: success/errors/ordinary cancellation clean staging; hard
process death can retain private staging directories. Explicit list/read are local
inspection operations and may expose IDs/content; plan/status remain content-free.
