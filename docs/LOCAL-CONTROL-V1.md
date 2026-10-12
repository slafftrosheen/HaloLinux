# HaloLinux local control protocol v1

The control protocol is deliberately **local-only**, using the Unix socket at
`$XDG_RUNTIME_DIR/halo-linux/node.sock` (or an XDG-state fallback).

- One JSON object and trailing LF per request and per response.
- Every request must carry `"protocol_version":1` and a recognized `"op"`.
- Oversized frames and unknown versions fail closed.
- The same-user process boundary is enforced by a private parent directory,
  socket permissions and a daemon single-instance lock.
- This protocol is **not** the future Halo peer/sync transport. It must never be exposed
  unmodified to LAN or public network interfaces.

Example request:

```json
{"protocol_version":1,"op":"status"}
```

Status reports runtime, storage and provider readiness separately. `ask` requires
an explicit configured provider/model and persists a conversation. `memory.add`,
`memory.search`, and `memory.delete` are typed local storage operations.
`storage.backup` accepts an absolute path to a **nonexistent** backup file,
creates a 0600 SQLite snapshot, and responds with the file size and a warning
that the backup includes the sensitive node identity. Never share it publicly.

See `protocol/schemas/local-control-request-v1.json`. Unsupported operations
must never be implicitly mapped to shell execution.
