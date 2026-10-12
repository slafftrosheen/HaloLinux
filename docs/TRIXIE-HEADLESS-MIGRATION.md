# Transition Trixie Desktop to headless without losing Halo state

This is a future operator procedure, **not** a script that changes system boot
settings or services automatically.

## Before making changes

1. Stop interactive runs and the Halo user service: `systemctl --user stop halo-node`.
2. Run `halo-node storage check` while the daemon is running **before** stopping it;
   make a consistent `storage backup /absolute/path/backup.db` into a private directory.
   Stop it only after the backup is confirmed.
3. Preserve `~/.config/halo-linux/config.yaml`, XDG data directory and any NVMe mount
   backing `storage.data_dir`. These contain conversations, memories and **private keys**.
4. Validate filesystem mount persistence, permissions, and backup restore procedure.
5. Note your user UID, `XDG_RUNTIME_DIR`, environment secrets, audio defaults and local hostname.

## Headless session

Use `raspi-config` or distribution-supported configuration to select CLI boot.
Do **not** uninstall the desktop or wipe the user account as an initial migration.

The systemd user service uses the same `~/.local/bin/halo-node` executable and XDG
data paths. An always-on headless user service may require:

```bash
sudo loginctl enable-linger "$USER"
systemctl --user enable --now halo-node
```

Enable lingering only after validating the service and understanding the change.
Secrets supplied in a graphical shell's environment will **not** automatically carry
over into a lingering systemd user service. Use a protected, user-owned environment
file or appropriate secret manager. Never put secrets in a world-readable systemd unit.

If audio is required headlessly, validate the actual PipeWire/WirePlumber user services,
device permissions and ALSA route after logout. An audio device visible from the GUI
may not be available to an unattended service.

## Rollback

Re-select Desktop boot. Do not reinstall HaloLinux or regenerate the node identity.
Use the backed-up SQLite snapshot only when a restore is necessary. Restore with the
user service stopped, retaining original filesystem permissions and identity files.
Do not copy a SQLite database over a live daemon process.

## Current limits

Peer sync, microphone streaming and voice inference are not implemented yet.
Do not assume enabling headless boot provides these capabilities automatically.
