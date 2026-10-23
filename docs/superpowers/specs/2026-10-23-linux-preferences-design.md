# HALO-LINUX-056: Canonical settings storage on Linux

## Intent
Supply the canonical `com.halo.sdk.runtime.Storage` service to Linux runtime engines, backed by a private on-disk store. Canonical code is never copied and edited in place. Settings must survive restart and be atomically committed for `putBatch` even with concurrent Linux processes. No changes to existing `halo.db`, backup schema, Android, or source pin.

## Approach
Use a small versioned, checksummed binary store in the already-private Linux XDG configuration directory. Take a stable sidecar file lock for every read/modify/write across processes, and serialize same-process callers on a directory-keyed JVM mutex. Serialize to an owner-only temporary file, force bytes to disk, atomically rename, then force the directory. Reject malformed/tampered files rather than silently treating them as empty. Supply null string, integer, boolean, remove, clear and atomic batch semantics.

## Rules
Fail closed on symlink components/files, multi-link files, wrong owner/mode, corruption and too-large input. Preserve existing data until successful rename. Avoid secrets in error messages and do not log values. No migration from old Linux storage or Android profile stores is implied. Do not create an alternative planner or authorization engine. Runtime composition must register exactly one canonical `Storage` service after the existing SQLite guard succeeds. Do not read or write the preference store from a model-provided command.

## Deliberate limits
Synchronous disk calls (not for UI threads); 1 MiB total store, 2048 records, 128 UTF-8 bytes per key, 65536 bytes per string. Default valid target is existing private config directory; 0600 files and 0700 directory. This is not encryption at rest. File atomics do not prove Pi power-loss durability.
