# HaloLinux pre-deployment acceptance

Do not deploy as an unattended personal node until all **blocking** software checks pass.
This plan intentionally separates source review, test execution, Pi validation and sync readiness.

## Gate 1 — build (blocking)
On a network-enabled Linux x86-64 or ARM64 developer environment with JDK 21:

```bash
./gradlew --version
./gradlew clean build --no-daemon
./gradlew test --no-daemon
./gradlew :host:linux:shadowJar --no-daemon
```

Fix **all** build/compilation failures. Never ignore them or label skipped tests as passed.

## Gate 2 — isolated model-free smoke (blocking)
Run `./scripts/smoke-test.sh`. It must demonstrate real daemon startup, local IPC,
SQLite/FTS5 retrieval, stable identity across restart, clean SIGTERM and zero secrets in logs.
Inspect stderr/logs for unexpected crashes. Fix any failure before installing to real data paths.

## Gate 3 — inference (blocking if enabling AI)
Start an actual local Ollama/llama.cpp OpenAI-compatible service or use a deliberately configured HTTPS endpoint.
Select a real installed model in `~/.config/halo-linux/config.yaml` and execute:
```bash
halo-node ask "Reply with one short sentence explaining HaloLinux."
```
Capture provider name, model, elapsed time and success/failure. Missing model or endpoint must fail explicitly.
Never substitute the fixture provider silently.

## Gate 4 — systemd (blocking for 24/7 operation)
Install unprivileged user service and verify start, stop, restart and journal output.
Check that `status` fails when no daemon is running. Investigate logout/session behavior
before enabling lingering. Never use root as a workaround for filesystem permissions.

## Gate 5 — Pi 5 acceptance (hardware gated)
Run `./scripts/preflight.sh` and save results. Then test NVMe mount persistence and
free space. Check power-supply margin, thermal throttling and CPU/memory pressure.
Verify *actual* HiFiBerry model and Linux overlays before enabling the audio path.
Physical record/playback remains NOT VERIFIED until a known test audio file is captured
and reproduced with the intended card and playback route.

## Gate 6 — privacy and source ownership (blocking before alpha release)
Audit file permissions including SQLite WAL/SHM, local socket, node key material.
Review provider endpoint security. Confirm licensing and removal plan for copied Halo SDKs.
No accountless pairing or sync should be advertised as implemented until cryptographic
trust and cross-device compatibility tests exist.

## Never auto-run these actions
Do not format disks, repartition NVMe, alter boot overlays, change system services,
open firewall ports, or install packages automatically from diagnostics.

## Status
Documentation/check scripts are **not** proof that the gates were run.
Record execution output and SHA after the first real Linux build.
