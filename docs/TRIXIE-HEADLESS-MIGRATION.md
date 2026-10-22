# Transition Trixie Desktop to headless without losing Halo state

This is an operator procedure for an existing Trixie installation. Halo scripts
do not change boot targets, enable lingering or alter audio permissions automatically.
Use the same regular user account throughout.

## 1. Back up while the daemon is still running

The CLI sends backup and storage commands to the daemon; stopping it first would
make those commands fail. With the existing service or foreground daemon running:

```bash
~/.local/bin/halo-node storage check
install -d -m 0700 "$HOME/halo-backups"
~/.local/bin/halo-node node backup "$HOME/halo-backups/before-headless"
python3 scripts/verify-node-snapshot.py "$HOME/halo-backups/before-headless"
```

Choose a new destination name if it already exists. A node snapshot includes the
main database and, when linked, the canonical agent database, with a checksum
manifest. It includes private identity material and is **not encrypted**.
Preserve the YAML configuration and any separate environment/credential files
privately as well; they are not included in this database snapshot.

After verification, stop the service (`systemctl --user stop halo-node`) or stop
the foreground daemon with Ctrl-C. Confirm no second daemon is running before
moving or restoring data.

## 2. Keep storage and environment stable

Preserve the existing account/UID, configuration, XDG data paths and any NVMe mount
backing `storage.data_dir`. Verify persistent mounts and permissions. Do not
regenerate the identity or copy a database over a live process.

A lingering service does not inherit the graphical terminal's environment.
Configure required `HALO_CONFIG_FILE`, XDG path overrides, `JAVA_HOME` and provider
credentials explicitly for the user unit; see [the environment file procedure](TRIXIE-DESKTOP.md#use-the-same-account-for-the-user-service).
Do not store credentials in a world-readable unit or repository.

## 3. Verify user-service persistence before changing boot mode

```bash
sudo loginctl enable-linger "$USER"
systemctl --user enable --now halo-node
loginctl show-user "$USER" --property=Linger --value
systemctl --user status halo-node --no-pager
~/.local/bin/halo-node status
```

With [lingering](https://manpages.debian.org/trixie/systemd/loginctl.1.en.html), the
user manager starts at boot and remains after logout. Test an SSH logout/reconnect
and reboot with the same account. Confirm `status`, `storage check` and intended
provider access after restart. `enable` alone does not prove boot success.

Only after that, use `sudo raspi-config` to select console boot. Keep desktop
packages initially so rollback stays simple. Do not enable console auto-login as a
substitute for a correctly configured lingering service.

## 4. Recheck headless audio separately

If audio is needed, test the actual PipeWire/WirePlumber user services, runtime
directory, device permissions and capture/playback route after logout and reboot.
An audio device visible during a graphical session can become inaccessible to an
unattended service. See [Audio diagnostics](AUDIO.md).

Do not automatically add all users to `audio`, disable login session policy or
replace desktop ALSA configuration. Diagnose the concrete device/permission failure
before changing it. Current HaloLinux audio support is diagnostics only.

## Rollback

Re-select Desktop boot with `raspi-config`. Keep the same account, node identity,
configuration and data directory. Restore a verified snapshot only if needed,
with the Halo service stopped and original privacy permissions preserved.
Undo lingering or service enablement only if returning to interactive-only use.

Headless boot does not create a LAN listener, Android transport, microphone stream
or speech engine. Use the sync protocol documentation for the current Android
interoperability boundary; service persistence is a separate feature.
