# Raspberry Pi 5 — Trixie reference host

**Reference target:** Raspberry Pi 5 (8 GB), Raspberry Pi OS Trixie **64-bit**,
JDK 21, optional NVMe and optional HiFiBerry DAC+ ADC Pro. The same JVM host also
targets x86-64 Linux. Physical Pi, audio and NVMe acceptance remains a device test.

## 1. Check the installed OS and userspace

```bash
cat /etc/os-release
uname -m
dpkg --print-architecture
free -h
lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINTS
```

For the Pi reference target, expect `VERSION_CODENAME=trixie`, kernel architecture
`aarch64`, and Debian userspace architecture `arm64`. A 64-bit kernel can run a
32-bit userspace, so checking only `uname` is insufficient. Linux PCs normally
report `x86_64` / `amd64`.

Raspberry Pi OS Trixie is based on Debian 13. If changing from a previous major OS,
Raspberry Pi recommends a fresh image; back up the existing installation first.
Halo setup scripts do not upgrade the OS, change boot configuration or format disks.
[Official OS guidance](https://www.raspberrypi.com/documentation/computers/os.html).

## 2. Install source-build prerequisites

These are explicit operator commands, not actions performed by Halo's installer:

```bash
sudo apt update
sudo apt install --no-install-recommends \
  openjdk-21-jdk-headless git ca-certificates curl unzip python3 \
  alsa-utils dbus-user-session
java -version
javac -version
```

Trixie provides [OpenJDK 21 for arm64](https://packages.debian.org/trixie/openjdk-21-jdk-headless)
through its distribution repositories. Use **JDK 21 for source builds**. A device
receiving a verified executable JAR needs only `openjdk-21-jre-headless`.
The pinned Gradle wrapper is used; do not install the distro's `gradle` package or
replace the wrapper just because the OS changed. Newer Java releases have their
own [Gradle compatibility requirements](https://docs.gradle.org/current/userguide/compatibility.html).

If multiple JDKs are installed, select the JDK 21 directory with `JAVA_HOME` and
check that both its `bin/java` and `bin/javac` exist. Halo's prerequisite checks,
Gradle and the installed launcher all honor `JAVA_HOME` before `PATH`.

## 3. Build and install as the regular user

```bash
git clone --branch main https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux
./scripts/preflight.sh --build
python3 -B scripts/check-trixie-scripts.py
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
./scripts/setup-trixie-desktop.sh
./scripts/install.sh
~/.local/bin/halo-node doctor
```

The setup helper is usable on Desktop or Lite: it creates a private configuration
template, preserving an existing regular file. `HALO_CONFIG_FILE` overrides the
usual `${XDG_CONFIG_HOME:-$HOME/.config}/halo-linux/config.yaml` path.
The template starts with offline memory/search; inference requires an explicitly
configured endpoint **and** an actual installed model.

The installer requires a JDK, builds a fresh executable JAR, then replaces it
atomically. It does not start, restart or enable the service. Do not use `sudo`
for either Halo setup script. Build failures leave the installed JAR untouched.

## 4. Verify the daemon and boot persistence

```bash
systemctl --user start halo-node
systemctl --user status halo-node --no-pager
~/.local/bin/halo-node status
~/.local/bin/halo-node storage check
journalctl --user -u halo-node -n 80 --no-pager
```

For an unattended node, after this works:

```bash
sudo loginctl enable-linger "$USER"
systemctl --user enable halo-node
loginctl show-user "$USER" --property=Linger --value
```

[Lingering](https://manpages.debian.org/trixie/systemd/loginctl.1.en.html) starts the
user service manager at boot and keeps it available after logout. Verify an actual
logout/reboot, not just the enabled state. Do not run a manual daemon concurrently
with the service. A missing user bus needs a proper login session; running
`systemctl --user` through `sudo` is not a substitute.

## 5. NVMe, audio and physical acceptance

Use `findmnt`, `df -h` and `lsblk -f` to inspect an already-configured NVMe mount.
`storage.data_dir` must be absolute or begin with `~/`. Keep the same mount and
permissions across desktop, service and headless operation. Stop the service before
moving its data; preserve SQLite databases and node identity together.

Audio is an independent acceptance. Follow [Audio diagnostics and manual checks](AUDIO.md)
for PipeWire, ALSA and the exact HiFiBerry board. Enumeration is not capture/playback
validation. No microphone, STT or TTS runtime is enabled by setting `audio.enabled`.

Before leaving the node unattended, verify clean shutdown, restart after power loss,
database reopen, free space, thermals, memory pressure and intended provider routing.
Keep backups private; node snapshots contain personal data and private identity keys.

- [Desktop workflow and updates](TRIXIE-DESKTOP.md)
- [Desktop-to-headless procedure](TRIXIE-HEADLESS-MIGRATION.md)
- [Pre-deployment gates](PRE-DEPLOYMENT-GATES.md)
