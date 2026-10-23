# HALO-LINUX-052 verification

Date: 2026-10-23. Base: `4a634f8221b416c36c448575b2d9ba2328f09d83`.
Reviewed Android archive/DTO source: `023ef76013b599ee7ca3513014af184a5242e2b5`.

## Executed evidence

Environment: Debian GNU/Linux 13 Trixie, x86-64, `/usr/bin/python3` 3.13.5,
SQLite 3.46.1, distribution cryptography 43.0.0. No Pi hardware was involved.

```bash
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_halo_brain_import.py'
```

**58 tests passed**, zero failures, errors or skips in the final focused run.
Initial 35 tests failed because the import implementation did not exist. Further
red/green tests exposed and fixed DEL characters being accepted in record IDs,
SQLite being opened before checking the recovery target's header, and staging DB
permissions inheriting 0644 instead of explicit 0600. A staged-record corruption
test additionally required rechecking record identities and content fingerprints
inside the write transaction before accepting each version. The first profile-loop test
also uncovered a fixture error: switching from BRAIN_ONLY to a file-bearing profile
changes file metadata and correctly creates a conflict. Profile acceptance tests now
use independent sources rather than weakening conflict detection.

Coverage includes all 19 record kinds and every supported archive record path;
nullable confidence, scope, evidence, revocation and unknown/default-omitted fields;
raw JSON and entry-byte retention; binary attachments; all three export profiles;
plain/encrypted input; Unicode passwords and non-echoing errors; read-only previews;
plaintext consent; store/source/policy/content/generation token binding; idempotence;
explicit keep-local/use-incoming conflicts; archive ID collision refusal; replay of
an older imported archive after a newer one; missing references and absent-record
non-deletion; source replacement after staging; malformed DTOs and file metadata;
limits; bounded ID listing and escaped explicit reads; file/directory permissions;
symlink/hard-link/unknown DB rejection; locking and normal staging cleanup.

Transaction tests inject an exception after all record writes and verify rollback.
A subprocess imports an 8 MiB synthetic attachment, writes records, then calls
`os._exit(91)` before commit. The next command refuses the hot journal; explicit
recovery restores the prior logical store state and removes the journal. This is
process-death testing, NOT a physical power-cut or filesystem durability experiment.
Two real competing CLI processes using one preview produce exactly one successful
commit. Native `halo.db` contents remain unchanged in the isolation test.

The published HALO-LINUX-051 verifier was reconstructed for local execution and
matched Git blob `842c258ca350073853205010f8440380b36a57d7` exactly. Its source is
unchanged in this batch. The README base also matched its fetched Git blob before
editing. New source/record compatibility pin values came from live GitHub reads.

## Synthetic larger-input smoke run

A separate fresh subprocess imported 10,019 records (including 5,001 workspace
objects and 5,001 notes) plus a 32 MiB random attachment. Input was 33,961,649 bytes;
expanded entry data was 35,167,195 bytes; the resulting DB was 38,866,944 bytes.
The observed stage/preview/apply time was 1.470 seconds and process maximum resident
set size was 87,600 KiB on this x86-64 container. These are one-run observations,
not a Pi performance prediction, a worst-case bound or a sustained-operation test.
Synthetic benchmark archives/database are not committed.

## Explicit limits of this verification

No full Gradle build, canonical cognition build, existing JVM suites, original
77-test archive suite, packaged daemon smoke test, ARM64 execution, actual Android
app export, HiFiBerry/audio/model test or native runtime ingestion was executed in
this batch. Existing verifier behavior is exercised through the focused import
suite, but that does not substitute for rerunning all prior suites.

No independent reviewer/subagent was available. Author review covered source trust,
DTO shapes, fixed SQL, authentication ordering, per-source keys, consent-token
binding, crash rollback, inert permissions/execution state and large-entry copying.
Raw DTO preservation is not full business-semantic validation or native runtime
parity. SIGKILL can leave private staging data; the operator guide states that limit.

The execution container could not clone repositories directly. The workspace is an
isolated partial checkout with verified baseline files. Publication uses the live
GitHub connector and expected-head guards; the delivery message records the final
remote commit/branch outcome. No Android files, runtime source pin or CI are changed.
