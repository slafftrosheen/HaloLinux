# Standalone Linux Brain API (HALO-LINUX-053)

The user requested continued sync work through an API, with Halo integration later.
Build on the existing schema-1 import store and leave both the Android repository
and active JVM runtime untouched. Source imports remain the explicit offline CLI.

## Approach and boundaries

Use Python 3.11+ stdlib and the existing verified store-opening/DTO helpers.
Expose versioned JSON queries through HTTP/1.1 over a private filesystem Unix
socket, not TCP. Same-UID peer credentials and private directory/socket permissions
are the local boundary; this is not pairing, LAN authorization or a cloud service.
A native JVM adapter and authenticated remote transport are deferred, not simulated.

Separate explicit raw record inspection from conservative memory retrieval.
Raw list/read needs an operator startup flag plus `intent: inspect` per request.
The default memory search only returns explicitly confirmed ACTIVE memories in the
exact requested scope. No automatic GLOBAL inclusion, confidence fabrication,
legacy-memory promotion, settings activation, tool execution or provider calls.
Retained conflicts and historical non-ACTIVE statuses must not resurrect memories.
Unknown/unvalidated evidence, inactive/missing sources and expired memories fail
closed. This filter is narrower than Android's complete runtime policy, not a fork
of its planner or a claim to cryptographically trust an imported record.

Every request uses a fresh read-only transaction with a bounded scan/byte/time/SQL
budget. Cursors bind query filters and the store identity/generation. Store replacement
requires an API restart. Importers can operate between requests; hot journals require
explicit offline recovery. No schema migrations, indexes or database writes.

## Contract

POST /v1/status; POST /v1/records/list; POST /v1/records/read;
POST /v1/memory/search. Requests and errors are JSON; personal selectors stay out
of URLs. Search is literal Unicode casefolded substring matching, not embeddings.
Fixed error codes, no request/access logging, no CORS or redirects, one bounded
request per connection. Ship an OpenAPI contract and a Unix-socket Python client.

## Evidence required

Actual SQLite imports and read-only checks, status/scope/expiry/evidence/conflict
filter tests, generation/cursor guards, budget failures, native Unix socket and
HTTP framing tests, wrong-UID refusal, malformed requests, CLI shutdown/restart,
client integration and file-permission checks. Never claim Pi/JVM/Android app
verification from Python fixture or x86-64 results.
