# HaloLinux on Raspberry Pi OS Trixie (64-bit) with Desktop

Target: **Raspberry Pi 5, 8 GB, Raspberry Pi OS Trixie 64-bit with Desktop**.

> Raspberry Pi 5 64-bit OS is **ARM64/AArch64** (`uname -m`: `aarch64`). 
> It is not x86-64 ("x64" on Intel/AMD PCs). The JVM bytecode is portable,
> but SQLite JNI and local model binaries require ARM64 support.

This document is a development-first setup guide. Hardware has **not yet been tested**.

## Stage 1 — interactive desktop development

Open the desktop Terminal and check:

```bash
uname -m
cat /etc/os-release
java -version
free -h
lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINTS
```

Install Java 21/JDK and Git through the distribution's package manager if missing;
do not add external repositories merely for newer JDK builds.

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk git
```

Clone from **main**, not GitHub's current default branch `master`:

```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./scripts/preflight.sh
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
```

The build is a **gate**. Stop and save the first complete compiler error if it fails;
do not install an unverified JAR.

After a green build and smoke test:

```bash
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node --version
~/.local/bin/halo-node doctor
```

Edit `~/.config/halo-linux/config.yaml` in your preferred GUI editor.
The example configuration now starts in **memory-only mode**. An inference
endpoint and a real installed model must be explicitly enabled before `ask`
will work. Storage, offline search, diagnostics and backups remain available
without an inference provider.

### Running during GUI development

Use a Terminal tab for the daemon:

```bash
~/.local/bin/halo-node start
```

From a second Terminal tab:

```bash
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "Test from Pi desktop"
~/.local/bin/halo-node memory search "desktop"
~/.local/bin/halo-node storage check
```

After validating the daemon, use a **user systemd service**:

```bash
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
journalctl --user -u halo-node -f
```

Do not launch a second manual daemon while the service is running.

## Audio testing during desktop development

The desktop user session is convenient because PipeWire/WirePlumber may already
provide working devices/routes. Record the actual hardware/driver identity first:

```bash
cat /proc/asound/cards
aplay -l
arecord -l
wpctl status
```

The HiFiBerry DAC+ ADC Pro model/overlay must be verified from your actual board.
Do not assume card 0 or a fixed device number.
Audio enumeration does **not** mean the Halo STT/audio pipeline is implemented.

## Model and memory expectations on Pi 5

Use an explicit, small local model first (e.g. 0.5–1.5B quantized) to establish
working transport and memory/power use. A GUI, Java daemon, database, audio and
large inference engine all consume the same 8 GB RAM. Monitor:

```bash
free -h
top
vcgencmd measure_temp
vcgencmd get_throttled
```

Never claim an NPU/GPU delegate is present without a supported backend and measured test.

## Stage 2 — convert this exact installation to headless

See [Desktop-to-headless migration](TRIXIE-HEADLESS-MIGRATION.md).
The principle is to change boot/session management, not the Halo storage schema.

## First acceptance commands after obtaining the Pi

Run in the local desktop Terminal (no service is installed automatically
by diagnostics):

```bash
python3 scripts/check-module-graph.py
python3 scripts/check-snapshot-verifier.py
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
./scripts/preflight.sh
```

Only if all mandatory checks pass, proceed to the user-service installation.
Check `docs/PRE-DEPLOYMENT-GATES.md` for the acceptance order.

Optional canonical agent mode is a separate experiment requiring a second,
pinned canonical Halo checkout and `verify-canonical-cognition.sh`.
Do not conflate that with the standalone Pi acceptance build.

## HALO-LINUX-026 — safe user-service updates

The installer now builds the JAR *before* updating the installed binary,
then stages and atomically renames the JAR and launcher. It never overwrites
the JAR inode used by an already-running process. It does **not** stop,
restart, enable or otherwise alter your running Halo service.

The default systemd **user** unit avoids features that depend on unprivileged
mount namespaces; those hardening directives are available as an optional
drop-in at `services/systemd/halo-node-hardening.conf.example`. Enable only
after confirming they are supported on your Trixie kernel.

To adopt new code after installation, use an explicit controlled restart:

```bash
systemctl --user stop halo-node
./scripts/install.sh
systemctl --user daemon-reload
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
```

Keep the same account, XDG paths and node identity. Do not run a manual
`halo-node start` concurrently with the service.

## Offline check and private text requests

Before attempting network-dependent Gradle tasks, use:

```bash
./scripts/verify-offline-source.sh
```

After configuration and daemon startup, to keep a sensitive prompt out of
the process command-line arguments, use stdin:

```bash
halo-node ask - < /path/to/private-question.txt
```

The input is bounded and rejects oversized prompts. Keep the source text file
private. Avoid assuming that shell-history and process-argument privacy alone
provide encryption or protect the SQLite conversation database.

## HALO-LINUX-036 — deterministic paths and provider setup

`storage.data_dir` must be an absolute path or begin with `~/`. Relative
paths are rejected rather than changing location between the Desktop terminal,
systemd user service, and later headless operation. The configured path is
normalized once, without changing the existing default XDG behavior.

An `inference.endpoint` without an `inference.model` (or the reverse) now
fails at configuration validation. Omit both for offline/memory-only startup;
provide both when an actual provider and installed model are ready.
