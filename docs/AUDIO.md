# Linux audio diagnostics and Pi acceptance

## Current implementation

HaloLinux currently performs **audio diagnostics only**. The host does not open a
microphone, play audio, stream speech, select device routes, run VAD, or invoke
STT/TTS engines. Android speech-provider support does not make those Linux features
available automatically.

`LinuxAudioDiagnostics.enumerateDevices()` returns:

| Field | What is actually checked |
| --- | --- |
| `alsaAvailable` | Whether `/proc/asound` exists |
| `pipeWireAvailable` | Whether `pw-cli info 0` succeeds in this user session within the probe timeout |
| `cards` | Nonblank lines read from `/proc/asound/cards` |
| `errors` | A diagnostic when neither cards nor a reachable PipeWire server is found |

These checks do not establish device permissions, recording direction, sample
formats, rates, channel count or successful audio. No default input/output device
is inferred. An ALSA directory can exist with no usable card.

```bash
halo-node doctor
./scripts/preflight.sh
```

Preflight additionally uses `aplay -l`, `arecord -l` and a bounded `wpctl status`
probe when those commands exist. Installed PipeWire tools and a reachable audio
server are reported separately.

The configuration parser currently recognizes only:

```yaml
audio:
  enabled: false
```

This flag is exposed in configuration diagnostics; setting it to `true` does not
implement an audio pipeline. Device selectors, buffer sizes and sample-rate keys
are not supported configuration. Do not add them expecting an effect.

## Trixie packages and user sessions

[ALSA utilities](https://packages.debian.org/trixie/alsa-utils) provide card listing,
record/playback tools and mixer inspection:

```bash
sudo apt install alsa-utils
cat /proc/asound/cards
aplay -l
arecord -l
aplay -L
arecord -L
```

Desktop images commonly have an audio session already. Lite/headless setups must
be inspected rather than assumed to have one. If the operator chooses PipeWire:

```bash
sudo apt install pipewire-audio pipewire-bin wireplumber
systemctl --user status pipewire.socket pipewire.service wireplumber.service --no-pager
wpctl status
```

The [Trixie `pipewire-audio` metapackage](https://packages.debian.org/trixie/pipewire-audio)
includes the ALSA/PulseAudio compatibility layers, WirePlumber and Bluetooth
support. It is optional for Halo's text/storage/sync functions. Let APT resolve the
current library names; Trixie uses packages such as `libasound2t64`.

Run diagnostics as the account that owns the audio session, without `sudo`.
If tools exist but cannot connect, inspect the user manager and `XDG_RUNTIME_DIR`.
For a lingering service, recheck access after logout and reboot; a working GUI
session does not prove headless audio access.

Do not create `/etc/asound.conf` or `~/.asoundrc` as a default installation step.
[Raspberry Pi's audio guidance](https://www.raspberrypi.com/documentation/accessories/audio.html)
warns that direct ALSA configuration can interfere with desktop audio management.
Keep direct ALSA and PipeWire routing decisions explicit.

## HiFiBerry DAC+ ADC Pro on Pi 5

First confirm the product printed on the board. For **DAC+ ADC Pro** or **DAC2 ADC
Pro**, HiFiBerry documents this overlay:

```ini
dtoverlay=hifiberry-dacplusadcpro
```

On current Raspberry Pi OS the boot configuration is `/boot/firmware/config.txt`.
The `hifiberry-dacplus-pro` overlay is for a different playback board; do not
substitute it for an ADC Pro. The ADC Pro retained its separate overlay when Pi 5
driver support changed. Sources:
[HiFiBerry configuration](https://www.hifiberry.com/docs/software/configuring-linux-3-18-x/),
[Pi 5 driver changes](https://www.hifiberry.com/blog/changes-in-hifiberry-drivers/).

Any overlay edit/reboot is an operator hardware setup task. Halo does not change
boot files, disable HDMI audio, select card 0 or force an EEPROM workaround.
Leave other audio devices available unless a concrete routing requirement says
otherwise. After applying the confirmed overlay, inspect the real capture and
playback devices again.

## Manual physical acceptance

These commands record/play audio **only when you run them**. Replace selectors with
the observed symbolic card ID and PCM device from the lists above; numeric card
indexes can change. Confirm a supported format/rate/channel combination first.

For direct ALSA, the following is an example five-second capture with a selected
device and explicit conversion through `plughw`:

```bash
HALO_ALSA_CAPTURE='plughw:CARD=<observed-card-id>,DEV=<observed-capture-device>'
HALO_ALSA_PLAYBACK='plughw:CARD=<observed-card-id>,DEV=<observed-playback-device>'
arecord -D "$HALO_ALSA_CAPTURE" -f S16_LE -r 48000 -c 2 -d 5 /tmp/halo-audio-check.wav
aplay -D "$HALO_ALSA_PLAYBACK" /tmp/halo-audio-check.wav
```

For PipeWire, inspect `wpctl status`, use its chosen defaults or an explicit
observed target, record with `pw-record /tmp/halo-audio-check.wav`, stop with Ctrl-C,
then play with `pw-play /tmp/halo-audio-check.wav`. Do not use target `0` as a
generic microphone selector.

Listen to the result and check input gain, clipping, channel mapping and unwanted
feedback at a low playback volume. A successful file capture/playback is evidence
for that device/session only. Delete private test recordings when finished.

## Integration boundary

Future Linux capture, playback, VAD, STT and TTS adapters must use the canonical
Halo speech/resource contracts and explicit provider selection. They must not
silently substitute cloud providers or introduce a second speech orchestrator.
Implement and validate real adapters before adding settings that imply working
voice features. See [Pi acceptance](PI5-SETUP.md) and the canonical sync documents
for the current platform boundary.
