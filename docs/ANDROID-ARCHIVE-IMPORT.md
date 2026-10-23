# Android archive import: HALO-LINUX-052

This implements **persisted offline Android-to-Linux import**, not live device
synchronization. All record fields and original archive entry bytes are retained
in a separate private SQLite store. It does not write `halo.db`, feed native
retrieval, activate imported settings, schedule reminders, reconnect devices or
resume agent runs. Do not invoke it as a `halo-node` command.

The JVM storage model does not yet represent Android's memory status, scope ID,
evidence, confirmation and revision semantics. Writing only content/confidence into
its simpler memories table would be a lossy and potentially unsafe translation.
The import store provides a field-preserving boundary for the next native adapter.

## Trixie setup and first import

Use the repository checkout as your normal user. This tool requires Linux `/proc`,
Python 3.11+ and its sqlite3 module. Trixie's distribution Python 3.13 was tested;
use `/usr/bin/python3` rather than a different virtual environment. For encrypted
archives install the distribution crypto package, not packages into system Python
with pip. Plain archives need only the standard library.

```bash
sudo apt update
sudo apt install python3 python3-cryptography

# Run from the HaloLinux checkout.
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_halo_brain_import.py'

# The parent must exist. init creates only its final dedicated directory.
mkdir -p "$HOME/.local/share"
STORE="$HOME/.local/share/halo-linux-brain-import"
/usr/bin/python3 -B scripts/halo_brain_import.py init --store "$STORE"

/usr/bin/python3 -B scripts/halo_brain_import.py plan /path/to/export.halobrain \
  --store "$STORE" --source phone
```

`plan` emits JSON counts, warnings and a `plan_token`, without record text or IDs.
It does not create or modify the target database. Review the result, then copy its
64-character token into the explicit apply command:

```bash
/usr/bin/python3 -B scripts/halo_brain_import.py apply /path/to/export.halobrain \
  --store "$STORE" --source phone \
  --plan-token PASTE_THE_REVIEWED_TOKEN --accept-plaintext

/usr/bin/python3 -B scripts/halo_brain_import.py status --store "$STORE"
```

**The store contains decrypted personal data in plaintext.** Directory mode 0700
and file mode 0600 restrict local access; they are not encryption at rest. The
`--accept-plaintext` flag is required even for an encrypted input. Use an encrypted
filesystem when necessary. Never place this store or a real archive in the repo.

The `--source` label is chosen by you, e.g. `phone` or `tablet`. Use the same label
for subsequent archives from that source. It is a namespace, **not a verified
peer identity**, and is not inferred from an archive filename or account.

Encrypted files prompt through the terminal without echo. For automation use
`--password-stdin` with one UTF-8 line supplied by a secure input source; passwords
are not accepted through arguments or environment variables. Do not use shell
tracing, `echo PASSWORD`, or a literal password in a command. CRLF/newline is
removed from stdin; leading/trailing password spaces are otherwise preserved.
Plan and apply are separate invocations and each authenticates its input.

## Conflict, replay and deletion rules

The default `--policy fail` produces a preview but refuses apply if an existing
record has different content. This is a content comparison, not an assumption that
newer timestamps are authoritative. Numeric types, omitted fields and explicit nulls
remain distinct. JSON key order and insignificant whitespace do not cause conflicts;
original raw record bytes are nevertheless retained in archive history.

Choose either policy explicitly and **run a fresh preview with that policy**:

```bash
/usr/bin/python3 -B scripts/halo_brain_import.py plan /path/to/next.halobrain \
  --store "$STORE" --source phone --policy keep-local

# Use the token from that preview, with the same source and policy.
/usr/bin/python3 -B scripts/halo_brain_import.py apply /path/to/next.halobrain \
  --store "$STORE" --source phone --policy keep-local \
  --plan-token PASTE_THE_REVIEWED_TOKEN --accept-plaintext
```

`keep-local` preserves the current selection for changed records; `use-incoming`
selects the incoming version. Both save the complete incoming archive and record
versions, retain previous history and create genuinely new records. Policies apply
to the whole import; per-record conflict resolution is not yet implemented.

An already imported content snapshot is a no-op. Replaying an older snapshot does
not undo later selections, even with `use-incoming`. Changing the policy on an
already imported archive does not reapply it. Selecting historical versions is a
future explicit operation; currently make a new export when importing further
changes. Reusing an archive ID with different content is rejected.

**Absence never means deletion.** A missing record, a BRAIN_ONLY file omission, or
missing optional agent history does not remove previous records. Android lifecycle,
revocation and memory status fields are saved unchanged, not translated into native
runtime permissions. `unresolved_references` counts incoming links missing from both
the incoming records and the selected source's current store; these links are retained
for review. It is not a claim that the selected merged graph is fully consistent.

The token binds the store identity and generation, local source label, conflict
policy and verified archive content. Apply recomputes it under `BEGIN IMMEDIATE`
before any row mutation. A changed store, content, source or policy requires a new
preview. The token is a concurrency/consent guard, not a cryptographic signature
from a trusted peer. No network authentication is implemented.

## Explicit local inspection

Plan/status output has no record content. These commands are explicit requests to
show IDs or personal content, so use a private terminal and do not log their output:

```bash
/usr/bin/python3 -B scripts/halo_brain_import.py list \
  --store "$STORE" --source phone --kind note --limit 50

/usr/bin/python3 -B scripts/halo_brain_import.py read \
  --store "$STORE" --source phone --kind note --key NOTE_ID_FROM_LIST
```

Listing is bounded to 200 keys and returns `next_offset` for the next page. Reads
return escaped JSON, not terminal control sequences. Kinds cover workspace, note,
conversation, turn, memory, legacy-memory, memory-revision, episode,
suggestion-decision, reflection-status, working-set, provenance, relation, file,
agent-run, agent-step, reminder, device, settings, studio-project and studio-version. The settings key is
`user-settings`; legacy-memory keys are the decimal Android legacy IDs.

Every original entry (including attachments, Markdown, manifest and checksums) is
stored as a BLOB. Attachments are copied in 64 KiB chunks. File-index hash and size
are checked when supplied. Android URIs, local paths, owned-copy pointers and agent
payloads remain inert strings; the importer never follows or executes them.
There is not yet an attachment-export CLI or an Android-compatible re-export.

## Failure, recovery and resource limits

A separate private staging area is verified before the target is opened. The same
private snapshot is used for verification and import; replacing the source pathname
between phases cannot substitute data. All target entries, record versions, current
selections, archive provenance and generation changes commit in one transaction.
Errors and ordinary cancellation roll back that transaction.

A process killed during apply may leave SQLite's rollback journal. Normal commands
refuse to open the store until you explicitly recover it:

```bash
/usr/bin/python3 -B scripts/halo_brain_import.py recover --store "$STORE"
/usr/bin/python3 -B scripts/halo_brain_import.py status --store "$STORE"
```

Recovery rolls back unfinished work and checks database/foreign-key integrity. It
never resumes an import. Preserve the directory if recovery fails; do not delete a
nonempty journal manually. Unknown database headers/schemas are refused. A dedicated
application ID, schema version and exact table definitions distinguish this store
from Halo's existing databases. Symbolic links, hard-linked DB files, permissive
permissions, unexpected files and WAL sidecars are rejected, not silently repaired.
Import commands serialize with a Linux directory lock; conflicting processes fail
rather than writing over each other.

All HALO-LINUX-051 archive limits still apply. The importer additionally caps a single
import at 100,000 records. Field types/identities are checked against the reviewed
Android DTOs; unknown fields, opaque JSON strings, nullable fields and omitted defaults
are preserved. This is not full semantic validation or authorization for runtime use.

Staging can require the encrypted snapshot, decrypted ZIP, expanded entry BLOBs and
record copies simultaneously. The target retains archive entries and record history,
so disk usage exceeds input ZIP size and grows across new snapshots. No pruning or
free-space reservation is implemented; a full filesystem must fail the transaction.
Use adequately sized private NVMe storage, not a small `/tmp` tmpfs:

```bash
mkdir -p "$HOME/.cache/halo-import-stage"
chmod 700 "$HOME/.cache/halo-import-stage"
/usr/bin/python3 -B scripts/halo_brain_import.py plan /path/to/export.halobrain \
  --store "$STORE" --source phone --temp-dir "$HOME/.cache/halo-import-stage"
```

Ordinary success/failure/cancellation removes staging. SIGKILL, sudden power loss or
`os._exit` can leave a **private 0700 `halo-import-*` staging directory** containing
plaintext staging data. Remove only a known abandoned directory after confirming
no importer uses it; no automatic recursive cleanup is attempted. Temporary deletion
is not secure erasure; swap, root access, disk recovery and guaranteed Python/OpenSSL
key-memory zeroization are outside this boundary. Exit 0 means command success
(a plan can still report `can_apply: false`); invalid input/operations exit 2,
ordinary interruption exits 130. Fixed error messages do not echo private values.

## Contract tracking and verification

Archive/DTO reference: Android `92370f6cd173affe1b9800fdc3b97a9e05559589` (schema 3; legacy schema 2 retained). Studio document shape is separately validated and remains inactive; see [Studio archive compatibility](ANDROID-STUDIO-ARCHIVE.md).
`config/halobrain-contract.json` now includes `BrainSnapshot.kt` as well as the
models, crypto and exporter. Their first three blob IDs are unchanged from 051.
Run the existing byte-based checker against a complete local Android checkout:

```bash
/usr/bin/python3 -B scripts/check_halobrain_contract.py ../Halo
```

This is separate from `config/canonical-halo.sha`; the JVM source pin is unchanged.
See [executed tests and remaining acceptance work](HALO-LINUX-052-VERIFICATION.md).
Actual Android app-export validation, a scope/status-aware native JVM adapter,
per-record conflict review, deletion/tombstone protocol, re-export, pairing and
bidirectional transport remain separate work. No Android repository edits or
GitHub Actions were added.

Implementation references: Python 3.13 sqlite3 documentation, SQLite transaction
control and the SQLite database file-format header (user_version at offset 60,
application_id at offset 68), and Debian Trixie's python3-cryptography package.
