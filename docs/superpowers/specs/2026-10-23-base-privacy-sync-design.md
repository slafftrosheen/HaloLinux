# Android base and Linux dispatch parity — HALO-LINUX-054

Continue source/functionality synchronization without modifying Android, exposing a
LAN API, or wiring the separately developed Brain API into Halo. Review Android
`d1c87d5895e9c68af62a652b6dbec0d3f626f0ae` against the Linux source pin.

Use exact canonical production source copies and retain the existing seven-module
allowlist. Prove source-tree identity, not only that a revision label changed.
Host functionality uses the canonical RemoteDispatchPolicy/guarded OkHttp client;
it must not introduce a second routing or authorization engine.

The Linux process currently supports only text inference and explicit provider
checks. Off-device endpoints require explicit `inference.allow_remote: true`;
missing permission means private/local processing. An endpoint setting or stored
credential alone is not disclosure consent. Loopback HTTP remains supported.
Other HTTP is rejected; remote HTTPS still needs consent. Loopback uses literal
addresses and no system proxy. Redirects cannot delegate the configured authority.
Unsupported operations/categories/provider identities fail closed. Shutdown
revokes all outstanding leases before stopping HTTP resources. No hot profile
switch UI, paid-media permission, activity ledger or richer remote context is added.

Alternative: copy SDK files without wiring privacy. Rejected because shared types
alone would not give the host the corresponding behavior. Alternative: port Android
mode-profile storage/UI wholesale. Deferred because this host has a pinned single
provider and no Android preferences/UI owner.

Validation: exact upstream blob/tree identities, focused executable JVM policy and
endpoint tests, configuration/wiring regression tests where dependencies permit,
and honest separation from a full Gradle build/hardware acceptance. The execution
workspace has no direct GitHub DNS and is a reconstructed partial checkout.
