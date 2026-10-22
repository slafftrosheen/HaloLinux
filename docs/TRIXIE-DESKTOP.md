# HaloLinux on Raspberry Pi OS Trixie with Desktop

Target: **Raspberry Pi 5, 8 GB, Raspberry Pi OS Trixie 64-bit**, JDK 21.
Pi architecture is ARM64 (`aarch64` kernel / `arm64` userspace); x86-64 Linux is a
separate supported host target. JVM bytecode is portable, but SQLite JNI and any
local inference binaries must support the actual userspace architecture.

Start with the package/build instructions in [Pi 5 setup](PI5-SETUP.md). This guide
covers interactive use, controlled updates and the service boundary. Hardware
acceptance must still be performed on the actual Pi.

## Interactive development

After a successful build and smoke test:

```bash
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node --version
~/.local/bin/halo-node config show
~/.local/bin/halo-node doctor
~/.local/bin/halo-node start
```

The foreground daemon stays in that terminal. From a second terminal:

```bash
~/.local/bin/halo-node status
~/.local/bin/halo-node memory add "Test from Pi desktop"
~/.local/bin/halo-node memory search "desktop"
~/.local/bin/halo-node storage check
```

The configuration template starts in memory-only mode. Omit both
`inference.endpoint` and `inference.model` for offline operation; set both for an
explicitly chosen provider and installed model. A half-configured provider fails
validation. Do not leave placeholder model identifiers enabled.

For sensitive prompts, stdin avoids process-argument exposure:

```bash
~/.local/bin/halo-node ask - < /path/to/private-question.txt
```

This does not encrypt local data or change the privacy of the selected provider.

## Use the same account for the user service

Stop the foreground daemon with Ctrl-C before starting the service:

```bash
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
journalctl --user -u halo-node -f
```

The service uses the same `~/.local/bin/halo-node` launcher and user data paths.
Custom `HALO_CONFIG_FILE`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `JAVA_HOME` or provider
credentials exported in a terminal are **not automatically inherited** by the
systemd user manager. Inspect `halo-node config show` in the intended environment
and explicitly configure any required service environment.

Use a user-owned file with mode `0600` for sensitive environment values and refer
to it through `systemctl --user edit halo-node`, for example:

```ini
[Service]
EnvironmentFile=/absolute/private/path/halo-node.env
```

An environment file uses `NAME=value` lines, not shell commands or `export`.
Keep secrets out of the unit, terminal history, diagnostics and repository. Use
`systemctl --user daemon-reload` and an explicit service restart after changes.
See [systemd execution environment](https://manpages.debian.org/trixie/systemd/systemd.exec.5.en.html).

`storage.data_dir` must be an absolute path or begin with `~/`; relative data paths
are rejected so changing working directory cannot silently select a different database.

## Audio and resource checks

Read [AUDIO.md](AUDIO.md) before changing routes or overlays. The current host
reports ALSA cards and PipeWire connectivity; it does not implement live microphone,
STT, TTS or audio routing. Desktop audio working in another application does not
prove Halo has a speech pipeline.

The desktop, Java host and local inference process share the Pi's memory budget.
Choose a small explicit model for initial transport verification and measure the
actual device; do not infer performance from parameter count alone.

```bash
free -h
top
vcgencmd measure_temp
vcgencmd get_throttled
```

`vcgencmd` is Pi-specific; its absence on x86 Linux is expected. Do not assume an
NPU/GPU delegate is present without a supported backend and measured execution.

## Controlled source updates

Keep the repo on `main`; do not use a stale default branch checkout. Inspect local
changes before pulling and use a fast-forward update:

```bash
git status --short
git pull --ff-only origin main
./scripts/preflight.sh --build
./scripts/verify-offline-source.sh
python3 -B scripts/check-trixie-scripts.py
./gradlew build --no-daemon
./scripts/smoke-test.sh
./scripts/install.sh
```

The installer builds before replacement and atomically renames the fresh JAR and
launcher. It does not truncate the JAR used by a running process and does not
restart the service. When ready to adopt the installed code:

```bash
systemctl --user restart halo-node
systemctl --user status halo-node --no-pager
~/.local/bin/halo-node storage check
```

Retain the previous verified artifact and a consistent private backup before a
release change. Never restore a database over a running daemon.

OS maintenance remains an explicit operator task through APT. Halo scripts do not
add repositories, run unattended upgrades, change kernel/firmware or enable update
timers. Canonical Android contract updates remain pinned and reviewed separately
from package updates; see the repository's canonical-source documentation.

## Verification and headless transition

`preflight.sh --build` checks the JDK 21 source-build baseline. Without `--build`,
it checks runtime prerequisites and allows a JRE. Both modes are read-only and
report OS/userspace, user-manager/linger state and audio server availability.
Passing preflight is not a build, physical audio test or boot persistence test.

The default user service avoids optional mount namespace restrictions. The example
drop-in at `services/systemd/halo-node-hardening.conf.example` is available only
after verifying those restrictions on the installed kernel.

For unattended use, follow [Desktop-to-headless migration](TRIXIE-HEADLESS-MIGRATION.md)
and [pre-deployment gates](PRE-DEPLOYMENT-GATES.md). A desktop reinstall is not
required to preserve Halo's configuration and identity.
