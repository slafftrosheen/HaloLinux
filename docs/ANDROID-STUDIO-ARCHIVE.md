# HALO-LINUX-057 — Android Studio archive compatibility

Android Halo at `92370f6cd173affe1b9800fdc3b97a9e05559589` moved the
`.halobrain` format to **schema 3**. The existing version-1 PBKDF2/AES-GCM
envelope, checksums and `archive.complete` evidence are unchanged.

HaloLinux now recognizes **schema 2 and 3**. A schema-3 export includes two
new streams, even if empty:

| Stream | DTO | Linux retained kind |
| --- | --- | --- |
| `studio/projects.jsonl` | `StudioProjectSnapshot` (`objectId`, `revision`, `committed`, `session`) | `studio-project` |
| `studio/versions.jsonl` | `StudioVersionSnapshot` (`objectId`, `revision`, `document`, `savedAt`) | `studio-version` |

Studio projects use `objectId` as identity. Saved versions use
`objectId:revision`, because a project can contain multiple revisions.
The original record, including unknown fields and nested document/session
JSON strings, is stored byte-for-byte with its archive provenance.
**No duplicate version overwrites another, and missing records are not deletions.**

## Validation boundary

The existing ZIP verifier authenticates encrypted content *before* parsing it,
checks section counts, all checksums, completion markers and paths. Schema 2
archives cannot declare Studio or contain hidden Studio entries. Schema 3 must
declare the Studio section and include **both** streams.

Every imported Studio project must reference a workspace `ARTIFACT` with exact
`contentRef=studio:<objectId>` and a nonempty bounded title. Every saved version
must reference a present project and have a unique, positive revision not newer
than its parent. Limits match reviewed Android DTOs: project revision at most
9,007,199,254,740,991; document schema 1 with bounded canvas dimensions,
recipe, 32 layers, bounds, selections, source identities; session schema 1 with
up to 16 undo and 16 redo snapshots and a 4M-character history budget.

Normal JSONL records still have a **1 MiB** line bound. Studio JSONL has a
separate conservative **32 MiB** bound because an Android session may contain
nested undo/redo data. Other archive limits remain in force (including the
2 GiB input and 4 GiB expanded bounds). A valid extreme Studio archive may
exceed these host limits and be rejected rather than truncated.

The Linux validator mirrors the reviewed Studio document/session *structure*.
It **does not execute Android InkCodec or prove ink-stroke validity**, render
projects, resolve referenced source files, interpret tool instructions or
grant operating-system permissions. Imported Studio content is untrusted,
and a structurally valid record is not approval to act on it.

The private import store remains separate from the active JVM `halo.db`.
No native Studio editor or Android-to-Pi live sync has been added. Settings,
reminders, device configurations, images and agent runs remain inert.

## Inspect and import

Use the existing schema-2/3 archive tools from a clean HaloLinux checkout.
Run as the regular account that owns the private store:

```bash
# Inspect integrity without modifying the import store.
/usr/bin/python3 -B scripts/halo_brain_archive.py /absolute/path/export.halobrain --json

# Check archive DTO/code drift against a clean Android working tree.
/usr/bin/python3 -B scripts/check_halobrain_contract.py ../Halo

# Preview the full import and check source, counts and policy.
/usr/bin/python3 -B scripts/halo_brain_import.py plan \
  /absolute/path/export.halobrain \
  --store "$HOME/.local/share/halo-linux-brain-import" --source phone
```

Apply only with the resulting preview token and the existing explicit plaintext
storage approval. The schema-3 plan adds `archive_schema_version: 3` for clarity.
Previously imported schema-2 data and conflict histories are preserved. Never
re-import with automatic `use-incoming` for conflicting records without review.

Once imported, raw Studio records are available only through the existing
**opt-in inspection** endpoints (or explicit offline `read` command) using
`studio-project` / `studio-version` kinds. A saved version key is
`projectId:revision`. The API enforces its normal output size bound; oversized
record reads fail rather than truncating source bytes. Studio data is **not**
eligible for `/v1/memory/search` and cannot authorize model operations.

The Brain API wire version and import-store SQLite schema **remain version 1**.
Do not confuse them with the Android archive format's schema-3 revision.

## Verification and remaining gates

```bash
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_halo_studio_contract.py'
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_halo_brain_*.py'
./scripts/verify-offline-source.sh
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
```

This batch executed **37 standalone structural Studio tests** on Linux x86-64;
the earlier complete archive/import/API suites and packaged JVM build were not
available in the isolated environment. The new end-to-end Python integration
tests are checked in for the full checkout but were **not run in this batch**.
The suite uses synthetic schema-3 archives, not bytes from Android's running
exporter. Before claiming device compatibility, use a real Android schema-3
encrypted/unencrypted export, check import into a disposable private store,
verify conflicts/versions and check physical Pi Trixie/ARM64 behavior.

See [HALO-LINUX-057 verification](HALO-LINUX-057-VERIFICATION.md) for the executed
evidence and [Android archive inspection](ANDROID-ARCHIVE-INTEROP.md) for
password handling and temporary disk requirements.
