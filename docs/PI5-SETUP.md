# Raspberry Pi 5 — HaloLinux reference host

**Reference target:** Raspberry Pi 5 (8 GB) with optional NVMe HAT and optional HiFiBerry DAC+ ADC Pro.
No physical Pi, NVMe HAT, or HiFiBerry validation is claimed.

## 1. Inspect, don't modify
Use an ARM64 Raspberry Pi OS or Debian-based image supporting OpenJDK 21.
Run `uname -m` (expected `aarch64`), `lsblk`, `free -h`, and `./scripts/preflight.sh`.
Verify the board, OS, PCIe interface, NVMe detection and actual audio card model.

## 2. Build on Pi or transfer verified artifact
```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
```
Build initially requires network for JDK/Gradle/Maven dependencies. Artifact must be created from
the same version/commit tested on another environment if cross-built.

## 3. NVMe data layout
Do not automatically repartition, format or mount NVMe.
Use `lsblk -f`, `findmnt`, and `df -h` to verify the already configured drive.
For large model/blob storage use an explicit data directory on a filesystem with sufficient
free space and sensible backup plan; SQLite metadata and cryptographic identity must be backed
up together. If changing storage path, copy while service stopped and verify permissions.

## 4. Audio is an independent acceptance
The name and overlay must be confirmed from actual HiFiBerry board markings, Pi OS version
and HAT documentation. Do not guess device indexes. Inspect `/proc/asound/cards`,
`aplay -l`, `arecord -l` and `wpctl status` when installed.
Listing cards is **not** proof of input or output functionality. Record/playback tests come later.

## 5. Install user service
```bash
./scripts/install.sh
systemctl --user daemon-reload
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
journalctl --user -u halo-node -n 80 --no-pager
```
Choose a real inference model/endpoint in `~/.config/halo-linux/config.yaml` before using `ask`.
A model is optional for storage/search and required for model-generated responses.
No LAN HTTP interface is exposed by default.

## 6. Before leaving unattended
Verify clean service shutdown, restart after power loss, database reopen, filesystem free space,
thermals, memory, and no unexpected external inference requests. The device can be a useful
offline note/search node without inference.

See [pre-deployment gates](PRE-DEPLOYMENT-GATES.md). Full device sync remains planned.
