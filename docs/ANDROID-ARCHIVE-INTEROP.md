# Android archive interoperability: offline inspection

HALO-LINUX-051 adds a standalone, read-only verifier for Android `.halobrain`
archives. It is **not** a record importer, a daemon command, or live device sync.
Do not call it `halo-node brain inspect`: that JVM command is not implemented.

## Run on Raspberry Pi OS Trixie

From the HaloLinux repository, install the distribution package only when encrypted
archive support is required. Unencrypted inspection uses only the Python standard
library. Use the distribution Python rather than an unrelated virtual environment.

```bash
sudo apt update
sudo apt install python3 python3-cryptography

/usr/bin/python3 -B scripts/halo_brain_archive.py /path/to/export.halobrain
/usr/bin/python3 -B scripts/halo_brain_archive.py /path/to/export.halobrain --json
```

An encrypted archive prompts privately when a terminal is available. Automation
can supply one UTF-8 line through `--password-stdin`; EOF without a newline is
also supported. Never put a password into a command-line argument, shell history,
or environment variable. The tool has no password-argument or password-environment
option, and argument errors deliberately do not echo rejected values.

```bash
# Interactively populate a shell variable without echoing or writing its value
# into shell history. Disable shell tracing first.
set +x
IFS= read -r -s -p 'Archive password: ' HALO_ARCHIVE_PASSWORD; printf '\n'
printf '%s\n' "$HALO_ARCHIVE_PASSWORD" | \
  /usr/bin/python3 -B scripts/halo_brain_archive.py /path/to/export.halobrain \
  --password-stdin --json
unset HALO_ARCHIVE_PASSWORD
```

Exit status is `0` only after verification, `2` for invalid/unsupported input or an
I/O/dependency/password error, and `130` for cancellation. Success output contains
fixed-format inventory/counts and the source SHA-256, not note text, filenames,
archive identifiers, account data, passwords or device configuration.

The JSON output always includes `record_import: "unsupported"` and
`record_semantics: "not_validated"`. A successful inspection must never be used as
an authorization signal to restore settings, pair devices or execute agent runs.

## Compatibility reference and keeping it synchronized

The reviewed Android archive revision is `92370f6cd173affe1b9800fdc3b97a9e05559589` (schema 3). Schema 2 remains supported for older exports.
`config/halobrain-contract.json` records the exact Git blob IDs for
`BrainArchiveModels.kt`, `BrainArchiveCrypto.kt` and `ZipBrainExporter.kt`.

```bash
/usr/bin/python3 -B scripts/check_halobrain_contract.py ../Halo
```

This compares actual source bytes in an existing Android working tree, including
uncommitted edits. It does not fetch, change files, install dependencies or trust
only a Git revision label. A mismatch exits nonzero; review the Android format
change, extend fixtures and update the compatibility reference deliberately.

The archive reference is separate from `config/canonical-halo.sha`. This change
does **not** move the seven portable runtime modules to a new Android revision.
No automatic updater, GitHub Actions job or background service is added.

Supported today: schema 2 and schema 3, section version 1, all three profiles (`BRAIN_ONLY`,
`BRAIN_WITH_FILES`, `FULL_USER_BACKUP`), optional activity history and envelope
version 1. The envelope uses Android's big-endian header, PBKDF2-HMAC-SHA256,
AES-256-GCM, a 128-bit tag and the full header as associated authenticated data.
The exporter default is 600,000 iterations; readers accept 100,000–2,000,000.

## Validation and resource boundaries

The verifier authenticates the entire encrypted payload **before** interpreting
any ZIP or JSON bytes. It checks the manifest, state, completion proof, SHA-256
coverage of every content entry, section counts, and JSON/JSONL syntax. It rejects
ambiguous paths, links/special files, duplicates, unsupported entries, header/
directory inconsistencies, overlapping entries, gaps, truncation and trailing data.
ZIP64, split archives, schema 1 and future schema versions are intentionally unsupported.

Archive limits match Android's current conservative size limits: 2 GiB input,
50,000 entries, 512 MiB per expanded entry, 4 GiB total expanded data and 200:1
maximum per-entry compression ratio. Additional host protections cap the central
directory/checksum list at 16 MiB, manifest/settings at 1 MiB, completion metadata
at 4 KiB, JSONL records at 1 MiB, nesting at 64 and passwords at 4,096 UTF-8 bytes.
A legitimate unusually large record can therefore be refused; no truncation or
partial-success result is returned. These CLI limits are not silently relaxed.

Only files explicitly referenced by the supported exporter layout are accepted.
The `memory` manifest count deliberately covers memories, legacy memories and
revisions, **not** episodes/reflection/suggestion sidecars; those are reported
separately. The tool does not validate all record DTO fields, IDs, foreign keys,
file-index references, deletion semantics, scope, confidence or conflict policies.

A read-only source descriptor is copied into a private temporary snapshot to
avoid validating a changing path. The original must be a regular, non-symlink
file. Nothing is extracted. No SQLite database, Halo provider or network socket
is opened. Temporary files are removed on success, failure and cancellation.

Encrypted inspection can require approximately twice the input size in temporary
storage (encrypted snapshot plus decrypted ZIP). On a Pi with a small/tmpfs `/tmp`,
choose an existing private directory on the NVMe filesystem:

```bash
mkdir -p "$HOME/.cache/halo-archive-check"
chmod 700 "$HOME/.cache/halo-archive-check"
/usr/bin/python3 -B scripts/halo_brain_archive.py /path/to/export.halobrain \
  --temp-dir "$HOME/.cache/halo-archive-check"
```

Private temporary files are not secure erasure. Root, disk recovery and swap are
outside this boundary; use encrypted storage when required. Python/OpenSSL may
retain password/key copies, so guaranteed key-memory zeroization is not claimed.
Unencrypted SHA-256/completion checks detect inconsistency, not a malicious producer
who can recompute them. AES-GCM authenticates possession of the password, not a
paired Halo peer's identity or permission to import data.


## Studio section (schema 3)

Android schema 3 always includes `studio/projects.jsonl` and `studio/versions.jsonl`,
even when both are empty. Older schema 2 archives must not contain Studio entries.
The verifier checks both files, section counts, complete SHA-256 coverage and the
completion marker. Normal JSONL retains a 1 MiB record limit; Studio JSONL has a
separate conservative 32 MiB bound for encoded undo/redo histories. The importer
additionally validates basic Studio document/session shapes and version ownership,
but does **not** run Android InkCodec or render/edit Studio content. A valid
archive is not authorization to open referenced files or activate agent tools.
See [Studio archive import](ANDROID-STUDIO-ARCHIVE.md).

## Verification and reproducible cross-language fixture

```bash
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_halo_brain_archive.py'
```

The retained `tests/fixtures/halobrain/android-aes-gcm-v1.json` fixture contains a
synthetic, non-personal schema-2 ZIP encrypted by the **unmodified** Android
`BrainArchiveCrypto.kt` on JDK 21. Its public test password includes non-ASCII
characters and an emoji. It tests Kotlin/JCE-to-Python wire interoperability;
it is **not** an export captured from the running Android app.

To regenerate after reviewing the source pin, first run the contract check above.
Use a local Kotlin compiler and JDK 21. Create a synthetic plain ZIP using
`payload('FULL_USER_BACKUP', encrypted=True)`, `seal` and `zipped(...,
descriptor=True)` from the test helper, then compile/run:

```bash
kotlinc ../Halo/app/src/main/java/com/halo/app/backup/BrainArchiveCrypto.kt \
  tests/fixtures/halobrain/GenerateFixture.kt -include-runtime -d /tmp/halo-fixture.jar
java -jar /tmp/halo-fixture.jar /path/to/synthetic-plain.zip /path/to/test.halobrain
```

The harness password is public test data; never use it to protect personal backups.
The fixture JSON records source identity and plaintext SHA-256 for auditing.

## Next integration boundary

Actual Android-to-Linux import needs a field-preserving storage mapping and
transactional conflict/deletion rules, especially for memory scope/status/evidence,
nullable confidence, workspace relationships and activity history. Do not translate
these into a simpler Linux record by silently dropping fields. Live bidirectional
sync additionally needs explicit pairing, peer authentication, permissions,
replay protection and a durable incremental journal. Those features remain unimplemented.
