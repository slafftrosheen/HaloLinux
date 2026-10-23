# HALO-LINUX-054 verification record

Date: 2026-10-23. Linux base: `ab0081f8f521cf0e4a2c4f6f54af894574063971`.
Reviewed Android revision: `d1c87d5895e9c68af62a652b6dbec0d3f626f0ae`.

## Source synchronization evidence

The seven retained `src/main/java` trees were compared through live GitHub Git-data
reads. Each resulting Linux source tree matches Android at the reviewed revision:

| Module | Matching Git source-tree SHA |
| --- | --- |
| libraries/common | 447fbd21783f4ab383b8de24bba0759ee2a7cf1a |
| libraries/halo-cognition-language | b1e8c31aaa8d70d514c9ed6deacc507bceb0847c |
| sdk/host-sdk | 42e6134037d9ab6024cf9c6d0503a52e9c086833 |
| sdk/runtime-sdk | 6148b7be078f29b2f7159a5e78908b061e3a6892 |
| sdk/provider-sdk | 72a29cc502f171fe86e9e26c59221b8ec8367bf4 |
| runtime/events | 3df4dfebdcd8a4438c8c757b046632bfe38f3353 |
| runtime/provider-core | e4f9d1279dc2983d4b666d6b29637e4131bc0aae |

This proves file names, contents and modes for those source trees, not runtime
behavior, build-script identity or Android feature parity. There are three new
and six modified canonical files; the inventory increases from 183 to 186.
The previous manifest was reconstructed and checked against Git blob
`eac0f2eb7b1d29b2181dd22b969fc1b22580b094` before updating the reviewed revision
and changed SHA-256 entries. Unchanged entries are retained. Storage.putBatch was
already aligned and is not a new Linux storage implementation in this batch.

## Executed tests

Environment: Debian GNU/Linux 13 Trixie, x86-64, OpenJDK 21.0.12.1, available Kotlin
CLI compiler with JVM target 17 for this isolated gate. The production Gradle/JDK21
configuration is unchanged. No Raspberry Pi hardware was involved.

```bash
/usr/bin/python3 -B -m unittest discover -s scripts -p test_linux_dispatch_policy.py
```

Final run: **two Python test methods passed**. The first compiles and executes
**70 named JVM cases** against the actual new Linux endpoint/settings/policy code
and unchanged declarations from Android RemoteDispatchPolicy.kt. The second checks
configuration/factory composition as source text; it is not an HTTP/YAML execution.
The 70 cases are not 70 additional JUnit tests, nor a whole-repository test count.

Coverage: missing/explicit/ill-typed consent, malformed endpoint/model settings,
HTTPS-only off-device endpoints, bracketed IPv6, literal loopback without DNS,
host substitution rejection, exact provider/category/operation matching, stale
revisions, lease completion, shutdown, callback failure, callback re-entry without
holding the policy lock, capacity reuse and concurrent admission/close. Tests were
written red before implementation; focused logs recorded missing implementation
and composition failures before the final green run.

The tested baseline NodeConfig and LinuxProviderLifecycle files matched blobs
`3e44c5e009c54b3ebc8a600f4c52b2e0432e914a` and
`ce94b822cf99c3216494096c11294f56285f849d` before modification. The README baseline
matched `0173d4996ec83c3d1f5f6f1b296ca28c8f73ed27`. New SDK files were compared
against Android blob identities, including exact trailing newlines. Author review
covered policy registration/revocation, endpoint classification, consent parsing,
factory cleanup order, unchanged-source ownership and documentation claims.

## Not executed / remaining acceptance

Direct repository/dependency downloads were unavailable in the execution container;
work used an isolated partial checkout reconstructed through the GitHub connector.
No full Gradle build, complete SDK compilation, real OkHttp dispatch, SnakeYAML
parsing execution, JUnit suite, packaged daemon smoke, optional canonical-cognition
build, physical ARM64 run, real-provider test, original archive/import/API suites,
HiFiBerry/audio/model test or power-loss qualification ran in this batch.

The focused harness extracts only the unchanged canonical declarations; storage/
provider coroutine extensions and HTTP classes are intentionally outside that gate.
The factory and duplicate-key composition checks are static. Do not report them as
end-to-end privacy verification. Run the full gates and HTTP acceptance described
in REMOTE-INFERENCE-POLICY.md before installing this revision on the Pi.

No independent reviewer/subagent was available. No Android edits, extra remote
listeners, automatic service changes, GitHub Actions, Brain API wiring, preference
backend migration, hot profile switching or additional authorization engine were
added. The previous archive/DTO compatibility pin is unchanged. Publication uses
expected-head guarded updates; the delivery message records the final commit and
branch outcome. Historical verification reports are not rewritten as current proof.
