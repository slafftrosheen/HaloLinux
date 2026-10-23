# HALO-LINUX-053 verification record

Base: `361de8111c238b8d2daba9a91dcb70f4bb5c0459`. Date: 2026-10-23.
Scope: standalone local Brain API, read-only reader, Python client and contract.
The user explicitly deferred Halo application integration.

## Executed

Debian GNU/Linux 13 Trixie, x86-64, distribution Python 3.13.5 and SQLite 3.46.1.
The API has no third-party production dependency.

```bash
/usr/bin/python3 -B -W error::ResourceWarning -m unittest discover \
  -s scripts -p 'test_halo_brain_*.py'
```

New suites: 64 reader tests, 38 server/transport tests, 13 client tests and four
contract tests: **119 unique tests passed**, zero failures, errors or skips, both
as root and as the normal user (UID 1000). The final normal-user run completed in
5.484 seconds. Repeated executions are not additional unique tests.

The initial 50 reader tests and 38 server tests failed before their implementations
existed. Further red/green regressions covered evidence-array wrapper injection,
maximum-length Unicode cursor round trips, malformed SDK error objects, boolean/
string timeout handling and overflowing-exponent responses. The HTTP client was
also corrected to close HTTPConnection explicitly rather than assuming it was a
context manager. Test-owned SQLite connections now close deterministically.

Tests exercise real 051 archive verification and 052 stage/plan/apply operations,
all 19 raw record kinds, nullable/unknown-field retention, exact source/scope bounds,
confirmation/status/expiry/evidence rules, retained conflicts, historical forgetting,
source/parent lifecycle, generation-bound cursors, store replacement, history depth,
byte/SQL/scan budgets, corruption/provenance mismatches and database non-mutation.

Transport checks use real AF_UNIX sockets and HTTP requests: UID denial, private
permissions, malformed/duplicate headers and JSON, timeouts, response privacy,
raw-inspection opt-ins, existing/replaced socket preservation, and SIGTERM/restart
of the actual CLI subprocess. The SDK is exercised against the real server and
against malformed-response fixtures; there is no TCP fallback.

A separate actual cross-UID experiment ran the server as UID 0 and connected as
UID 65534. Test-only directory/socket permissions were temporarily widened so the
client could reach the credential check; the kernel-derived identity was rejected
with HTTP 403 `peer_not_allowed`. Permissions were restored and fixtures removed.
This supplements, rather than replaces, the unit-test credential injection.

A normal-user (UID 1000) smoke run imported 5,000 synthetic memory records and
queried the real API using curl and the supplied SDK. A rare match on the final
record was found after 25 pages. The database SHA-256 remained unchanged; normal
shutdown removed the socket. The one-run search elapsed time was 0.463 seconds and
whole-process peak RSS was 32,944 KiB, including fixture import. These x86-64 numbers
are not a Pi prediction, an API-only heap measurement or a sustained-load test.

The OpenAPI artifact's 16 component schemas were checked with JSON Schema draft
2020-12. All four request examples and their actual successful reader responses,
plus an error response, validated against their declared schemas. Stdlib contract
tests additionally check endpoint/enumeration/transport-limit agreement and local
reference resolution. A whole-document OpenAPI meta-validator was not available;
that broader validation is not claimed.

## Baseline and review

The isolated execution checkout was reconstructed through GitHub connector reads
because direct GitHub DNS/clone access failed. Its three production dependencies
matched these published Git blob hashes exactly before execution:

- `scripts/halo_brain_archive.py`: `842c258ca350073853205010f8440380b36a57d7`
- `scripts/halo_brain_records.py`: `a4778ad461a82f9c1f5df1dbf78b17757423cf02`
- `scripts/halo_brain_import.py`: `6b697a0d34ed1692caeccbb0fb4caf911715f662`

Those files are unchanged. The README baseline was likewise hash-checked before
editing. Source/cursor/error/provenance/permission/cleanup paths received author
review. No independent reviewer or subagent was available; none is claimed.

## Not executed / not implemented

No full Gradle build, original 051/052 test suites, canonical cognition build,
packaged halo-node daemon test, physical Pi ARM64 run, real Android app export,
audio/model/NVMe/power-loss qualification or load/fuzz campaign was run in this
batch. New API tests use the original importer but do not replace its entire suite.

No Android edits, native runtime wiring, cloud/provider calls, network pairing,
LAN listener, schema/index migrations, automatic recovery, attachment export,
mutation endpoints, GitHub Actions or automatic service installation. The filter
is deliberately narrower than Android policy; raw imported text and confirmation
flags remain untrusted. Same-UID software/root and client-side disclosure remain
outside the API's authorization guarantees.

Publication uses the existing GitHub tree and expected-head guarded branch updates;
final commit/branch confirmation is recorded in the delivery message. Local test
fixtures and benchmark databases are not published.
