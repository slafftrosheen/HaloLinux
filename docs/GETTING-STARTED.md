# HaloLinux — getting started

HALO-LINUX-050 has an executed standalone JVM build, executable JAR packaging and
host unit-test evidence on x86-64 Linux. Pi 5/ARM64 and physical peripherals remain
separate acceptance targets. See [implementation status](IMPLEMENTATION-STATUS.md)
for the current evidence and outstanding gates.

## Requirements

- Raspberry Pi OS Trixie 64-bit on Pi 5, or x86-64 Linux.
- JDK 21 for building/installing from source; a verified packaged JAR needs a JRE 21.
- Git, Python 3 and network access for the first Gradle dependency download.
- Optional explicitly configured OpenAI-compatible inference endpoint and model.

Use the pinned Gradle wrapper. Package commands and ARM64/userspace checks are in
[Pi 5 setup](PI5-SETUP.md). A working desktop audio session is not required for
model-free memory/search.

## Build and install

Run as the regular account that will own the node:

```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./scripts/preflight.sh --build
./scripts/verify-offline-source.sh
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node --version
```

The offline gate checks source provenance, scripts, dependency structure and
synthetic storage/host fixtures. Gradle and the smoke test are distinct execution
checks. Repeat them on the actual target before accepting its deployment.

The setup helper creates a private configuration template, preserves an existing
regular configuration file and works with Desktop or Lite. The installer builds
before atomically replacing the JAR/launcher. It does not alter user databases or
start, restart or enable the service. Do not run it with `sudo`.

## Start in offline memory mode

The default configuration omits both `inference.endpoint` and `inference.model`.
Use `halo-node config show` to confirm the resolved configuration:

```bash
~/.local/bin/halo-node config show
~/.local/bin/halo-node doctor
~/.local/bin/halo-node start
```

The foreground daemon stays in that terminal. In another terminal:

```bash
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "HaloLinux offline storage works"
~/.local/bin/halo-node memory search "offline"
~/.local/bin/halo-node storage check
```

A protected per-user socket and single-instance lock keep control local. No LAN
API, peer discovery or automatic model connection is enabled by this workflow.

The usual configuration path is
`${XDG_CONFIG_HOME:-$HOME/.config}/halo-linux/config.yaml`; `HALO_CONFIG_FILE`
overrides it. A custom `storage.data_dir` must be absolute or start with `~/`.
Use the same account and paths for interactive and service operation.

## Add an inference provider

Edit the configuration with an endpoint you control and a model actually installed
there. Set both values; a partially configured endpoint/model pair is rejected.
For an OpenAI-compatible local server, the shape is:

```yaml
inference:
  endpoint: "http://127.0.0.1:11434/v1"
  model: "your-installed-model-id"
```

The model above is a placeholder, not an installation or availability claim.
Restart the node explicitly after changing its configuration, then check:

```bash
~/.local/bin/halo-node provider check
~/.local/bin/halo-node ask "Explain the node architecture"
```

If a key is required, provide `HALO_INFERENCE_API_KEY` in the process's protected
environment. Keep credentials out of YAML, repository files and diagnostics.
There is no implicit remote fallback. Memory/search continues working when a
provider is absent or unavailable.

For private prompt input:

```bash
~/.local/bin/halo-node ask - < /path/to/private-question.txt
```

This keeps the prompt out of process arguments; it does not change the selected
provider's privacy or encrypt local storage.

## Run as a user service

Stop the foreground daemon with Ctrl-C first:

```bash
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
journalctl --user -u halo-node -f
```

Systemd does not automatically inherit the terminal's configuration overrides,
`JAVA_HOME` or credentials. Configure the service environment explicitly when
needed; the [Trixie desktop guide](TRIXIE-DESKTOP.md) gives the protected
`EnvironmentFile` workflow.

After the user service works, unattended boot/logout persistence can be enabled
explicitly:

```bash
sudo loginctl enable-linger "$USER"
systemctl --user enable halo-node
```

Verify a real logout and reboot. See [headless migration](TRIXIE-HEADLESS-MIGRATION.md)
for audio-session and user-manager limits.

## Backups and existing installations

With the daemon running:

```bash
mkdir -p -m 700 "$HOME/halo-backups"
~/.local/bin/halo-node node backup "$HOME/halo-backups/snapshot-001"
python3 -B scripts/verify-node-snapshot.py "$HOME/halo-backups/snapshot-001"
```

Use a new destination each time. Snapshots are consistent SQLite copies with a
manifest; they include personal data and private node identity and are not
encrypted. Do not copy/restore databases underneath a running daemon.

The current storage guard detects incompatible legacy `master` schemas before
initialization and refuses to convert them automatically. If startup rejects an
old database, stop the old node and preserve its complete data directory. Continue
only with a reviewed migration or a separately selected empty data directory;
keep the original available.

For ordinary source updates, follow the controlled fast-forward/build/install
sequence in [Trixie Desktop](TRIXIE-DESKTOP.md). Installing an update leaves a
running process on its old artifact until an explicit restart.

## Android alignment and optional cognition

The default build includes 183 portable source files from Android Halo revision
`5a4047fec6dc9c561969c446641b22373d5157e2`, across seven allowlisted modules.
Validate the snapshot offline:

```bash
python3 -B scripts/sync-canonical-sources.py
```

See [canonical source build](CANONICAL-SDK-BUILD.md) to build against a separate
pinned Android checkout or run the optional canonical cognition gate. Advancing
the source revision requires a reviewed explicit update, not changing the pin alone.

Android personal-data transfer is still future work. Linux snapshots are not
Android `.halobrain` archives, and enabling `sync` in a configuration cannot
create an unimplemented peer protocol. The current host also has audio diagnostics
only; microphone/STT/TTS and actual model performance require further integration.
