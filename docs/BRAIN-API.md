# Local Brain API — HALO-LINUX-053

A standalone, read-only API for the Android import store introduced in 052.
The API and client are implemented; integration into the Halo Android app or the
HaloLinux JVM runtime is deliberately deferred. This is not live device sync.

## Start on Trixie

Requires Linux, Python 3.11+ and its standard-library sqlite3 module. Serving an
already imported store needs no third-party Python package, model, Java process
or Internet access. The encrypted archive importer still has its own crypto
requirement. Run as the same regular user who owns the import store, not with sudo.

From the repository checkout, after completing the explicit 052 import workflow:

```bash
STORE="$HOME/.local/share/halo-linux-brain-import"
RUNTIME="${XDG_RUNTIME_DIR:-$HOME/.cache}/halo-brain-api"
mkdir -p -m 700 "$RUNTIME"

/usr/bin/python3 -B scripts/halo_brain_api.py \
  --store "$STORE" --runtime-dir "$RUNTIME" --source phone
```

The runtime directory must already be owner-only (0700), without symlink
ancestors, and empty. The server creates `brain-api.sock` with mode 0600 and
requires the client's kernel-reported UID to equal its own. It never binds a
TCP socket. Repeat `--source` for additional operator-approved import labels;
labels are namespaces, not authenticated Android peer identities.

From a second terminal, set the same RUNTIME value and query the API:

```bash
RUNTIME="${XDG_RUNTIME_DIR:-$HOME/.cache}/halo-brain-api"
printf '%s\n' '{}' | /usr/bin/python3 -B scripts/halo_brain_client.py \
  --socket "$RUNTIME/brain-api.sock" --endpoint /v1/status

/usr/bin/python3 -B scripts/halo_brain_client.py \
  --socket "$RUNTIME/brain-api.sock" --endpoint /v1/memory/search <<'JSON'
{"source":"phone","scope":"GLOBAL","query":"celsius","limit":20}
JSON
```

Curl is also supported:

```bash
printf '%s\n' '{}' | curl --silent --show-error \
  --unix-socket "$RUNTIME/brain-api.sock" \
  -H 'Content-Type: application/json' --data-binary @- \
  http://localhost/v1/status
```

`localhost` is the required logical HTTP Host, not a listening network address.
All four endpoints use POST with a JSON body, including status. This keeps query
text and record selectors out of URLs. Only HTTP/1.1, explicit Content-Length
and one request per connection are supported. There is no access/request logging,
CORS, redirect, chunked request support, WebSocket, TCP fallback or reverse proxy.
Do not expose this socket through a LAN proxy: pairing and remote authorization
are not implemented. Same-UID software and root remain inside the trust boundary.

Stop with Ctrl-C or SIGTERM. Normal shutdown removes only the socket inode owned
by that server instance. Existing files, live sockets and stale sockets are never
automatically overwritten/unlinked. After a hard kill, verify the old process is
gone before manually removing only its stale `brain-api.sock`. Do not recursively
clear the runtime directory. No service is installed, enabled or started for you.

## Endpoints and contract

The machine-readable contract is
[`protocol/schemas/brain-api-v1.openapi.json`](../protocol/schemas/brain-api-v1.openapi.json),
OpenAPI 3.1.0 / API version 1. HTTP success is 200 with
`{"api_version":1,"ok":true,"result":{...}}`. Errors use
`{"api_version":1,"ok":false,"error":{"code":"..."}}` and non-200 status.

| Endpoint | Purpose |
| --- | --- |
| `/v1/status` | Capabilities, configured source allowlist, generation and snapshot |
| `/v1/records/list` | Explicit inspection: bounded selected record IDs, all 19 kinds |
| `/v1/records/read` | Explicit inspection: full selected DTO plus archive/record provenance |
| `/v1/memory/search` | Conservative memory candidates in exactly the requested scope |

Unknown fields, duplicate JSON keys, non-finite numbers and wrong types fail closed.
No import, recovery, delete, update, attachment-download, settings activation or
agent-execution endpoint exists. The API opens only the separate import store,
never `halo.db`. File paths, URIs, settings and agent payloads remain inert data.

### Explicit inspection is opt-in

Start the server with `--allow-inspection` only when deliberate raw browsing is
needed. Every raw request must additionally include `"intent":"inspect"`:

```json
{"source":"phone","kind":"note","intent":"inspect","limit":20}
```

```json
{"source":"phone","kind":"note","key":"NOTE_ID","intent":"inspect"}
```

List accepts limit 1–100 (default 20) and a continuation cursor. Read optionally
accepts a `snapshot` from an earlier response and returns 409 if state has changed.
Raw inspection preserves unknown/nullable fields and can show forgotten or inactive
records. Its output is marked `inspection-only`: it is not an AI-context endpoint.
Attachments are not returned; file metadata does not grant filesystem permission.

### Memory candidates are deliberately conservative

Search requires source, scope and query. GLOBAL requires an absent/null scope_id;
PROJECT, DEVICE, CONVERSATION and WORKSPACE_OBJECT require an exact scope_id.
GLOBAL is never automatically mixed into another scope. These inputs are caller
assertions; a future Halo adapter must derive them from the user's authorized
context, not from untrusted model arguments alone.

Search is literal Unicode-casefolded substring matching, not embeddings, relevance
ranking or FTS. Results are ordered by record key. Only these memory kinds are
eligible: PREFERENCE, FACT, PERSON, PLACE, DEVICE, PROJECT and RELATIONSHIP.
Instructions, workflows, procedures, episodes and legacy-memory are not promoted.

A candidate must be ACTIVE, explicitly `userConfirmed: true`, in the exact scope,
within the supported content/type bounds, unexpired and backed by a known origin.
Null confidence stays null; numeric confidence outside 0–1 is excluded rather than
clamped or invented. Memory text and confirmation flags come from an untrusted
archive: eligibility is not independent evidence that a statement is true.

The selected fingerprint must match the newest imported version for that source
and ID. A keep-local conflict therefore cannot silently feed stale context.
Any historical non-ACTIVE status suppresses that ID, even if a later import selects
an active version. This intentionally conservative rule prevents imported history
from undoing forgetting; an explicit restoration workflow is not implemented.

Linked workspace objects must be current, ACTIVE and without retained conflicts or
inactive history. Supplied note revisions must match. Conversation/turn references
need current, active, non-archived parents. Evidence arrays are bounded, parsed with
duplicate-key checks and retained as references, not expanded into additional text.
USER_ACTION and IMPORTED references are not independently authenticated. Unsupported
agent/routine/episode evidence, sourceEpisodeId, missing references and unknown
semantics suppress a candidate rather than inventing an interpretation. Deep
history beyond 64 versions is also excluded. Raw inspection remains available for
manual review when enabled.

These rules were reviewed against Android `c1ab70710b0b3211276b60eaef48625caa7bf0a9`
(`MemoryRecordEntity.kt`, `PersonalMemory.kt`, `NotebookModels.kt`), and are narrower
than the full Android cognition policy. The 052 DTO/archive compatibility pin and
the existing JVM source pin are unchanged. This is a guarded local query boundary,
not a second planning/authorization engine or complete native-policy parity.

Every candidate includes source, key, selected version, archive ID, archive-content
SHA-256 and record fingerprint. It is marked `imported-unverified` and
`local-context-candidate`; the result explicitly has `cloud_authorization: false`.
Treat text as untrusted data, never instructions or tool authorization. The API has
no outbound calls; future clients must enforce any separate cloud-disclosure consent.

## Pagination, consistency and limits

Cursors bind the request filters and the store identity/generation. Keep the same
request parameters (including limit) and add the returned `next_cursor` as `cursor`.
An import between pages yields 409 `cursor_mismatch`; begin a new query rather than
mixing snapshots. Cursors are not signed access credentials. Every request reevaluates
expiry against the server clock. Check `valid_until_ms` again before later use.

Search returns at most 50 items (default 20) and scans at most 200 selected records
per request. A page can have zero items **and still have a next_cursor**. Continue
until next_cursor is null, or report a caller-imposed partial search honestly.

The API uses read-only SQLite connections and transactions, the importer's directory
lock, schema checks and no-symlink rules. The process pins the store identity;
replacing the store returns 409 `store_replaced` until an intentional restart.
A busy/incompatible store or hot journal returns 503, without automatic recovery.
Stop the API if necessary, use the explicit offline `recover` command and retry.

Default query limits: 2 seconds, 2,000,000 SQLite VM steps, 16 MiB of charged record
payload and 1 MiB per record. History is iterated, not bulk-loaded. Budget exhaustion
returns 422 with no partial results. These are conservative query-work bounds, not
hard real-time scheduling or an exact Python heap limit. Large histories may need
future indexed/native adapters; an error must not be presented as "no memories".

Transport limits: 8 KiB headers, 16 KiB JSON request, 8 MiB serialized response,
three-second total request-read deadline and bounded response writes. The worker is
single-threaded, with an eight-connection listen backlog. A same-UID process can
still deny service; this is not a hostile multi-tenant Internet server. Responses
are `no-store`, errors are fixed codes and payloads are not logged. Avoid logging
client responses yourself: they can contain plaintext personal data.

## Python integration seam

The client is stdlib-only and does not import the server or SQLite adapters:

```python
import os
from pathlib import Path
from scripts.halo_brain_client import BrainClient, ClientError

runtime = Path(os.environ.get("XDG_RUNTIME_DIR", str(Path.home() / ".cache")))
client = BrainClient(runtime / "halo-brain-api" / "brain-api.sock")
try:
    response = client.request("/v1/memory/search", {
        "source": "phone", "scope": "PROJECT", "scope_id": "my-project",
        "query": "temperature", "limit": 20,
    })
    candidates = response["result"]["items"]
    # Preserve citations and trust labels. Review next_cursor before declaring
    # the search complete. Never treat candidate text as executable instructions.
except ClientError as error:
    # Stable code/status, not private server exception text. No hidden retry.
    print(error.code, error.status)
```

Later Halo integration can target the versioned contract without changing import
storage. It still needs user-context authorization, request cancellation, consent
handling and tests in the actual Android/JVM runtime. Direct Android-to-Pi network
access additionally needs authenticated pairing and encrypted transport; this local
Unix API does not supply them. No Android repository or native daemon is changed.

## Verification and references

```bash
/usr/bin/python3 -B -W error::ResourceWarning -m unittest discover \
  -s scripts -p 'test_halo_brain_*.py'
```

See [this batch's executed checks and limits](HALO-LINUX-053-VERIFICATION.md).
The standalone Python API has no new production dependency. Its tests use synthetic
archives and real SQLite imports, not private user data.

Implementation references: [Linux Unix sockets and SO_PEERCRED](https://man7.org/linux/man-pages/man7/unix.7.html),
[Python 3.13 sqlite3](https://docs.python.org/3.13/library/sqlite3.html),
[Python 3.13 HTTP client](https://docs.python.org/3.13/library/http.client.html),
and [OpenAPI 3.1.0](https://spec.openapis.org/oas/v3.1.0.html).
