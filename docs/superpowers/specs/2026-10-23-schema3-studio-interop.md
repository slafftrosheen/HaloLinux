# HALO-LINUX-057: schema-3 Studio archive boundary

## Intent and constraints
Android revision `92370f6cd173affe1b9800fdc3b97a9e05559589` introduces
`.halobrain` schema 3 with `studio/projects.jsonl` and
`studio/versions.jsonl`. Keep the existing canonical JVM source pin,
Android ownership, privacy-first node and separate import-store schema intact.
Allow existing schema-2 backups without silent changes, and fail closed on
unsupported format or version.

## Approaches considered
- Silently discard Studio while importing other records: rejected because it
  makes a supposedly complete export incomplete.
- Accept the new streams into the inert, lossless import store with structural
  Studio validation: chosen for backwards compatibility, auditability and
  controlled eventual host adoption.
- Implement full native Studio execution and Android InkCodec: deferred until
  shared contracts, renderer/resource admission and OS-specific storage are
  reviewed. No untrusted Studio content is automatically activated.

## Design
Update the ZIP verifier to recognize schema 2/3 with mandatory version-specific
sections. Keep version-1 encryption, completion SHA-256 proof, source snapshot,
path/ratio/size limits and no extraction. Studio JSONL has its own conservative
32 MiB line bound; legacy JSONL remains 1 MiB. The typed DTO stager adds two
new raw kinds with version key `objectId:revision`; it retains all raw bytes
and uses the existing conflict/revision retention transaction. A read-only
`verify_stage` validates project/header ownership, revisions, document/session
structure and nested bounds before the destination import transaction.
Referenced images, paths, ink, model text, settings and action history stay inert.

Extend the opt-in Unix-socket Brain API's raw kind enum and OpenAPI schema,
without expanding memory-search eligibility, off-device authority or listener
scope. Update exact Android contract source hash inventory; do not change
`config/canonical-halo.sha`. Preserve legacy schema-2 fixture producers.

## Non-goals / residual risk
The structural mirror does not decode Android InkCodec, execute Studio, validate
every external reference or prove semantic parity with Android's renderer.
Real Android exporter fixtures, full historical Python suites, Gradle builds and
Pi ARM64 deployment must be tested separately before claiming full compatibility.
Do not hide these gaps or mark unexecuted gates as passed.
