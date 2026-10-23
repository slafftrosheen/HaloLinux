# Native PCM playback — HALO-LINUX-055

Linux now has an opt-in Kotlin/JVM audio output adapter and foreground operator
command. This is a playback primitive, not a second speech engine. Android, the
Brain API, inference, persisted data and the existing source pin are unchanged.

## Before use

Run the full repository build and smoke gates before installing this revision:

```bash
python3 -B scripts/sync-canonical-sources.py
./scripts/preflight.sh --build
./gradlew clean build --no-daemon
./scripts/smoke-test.sh
```

The focused test gate needs a Kotlin CLI compiler and JDK on PATH, but no card,
audio service, model, third-party JVM library or Internet connection:

```bash
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_linux_pcm_playback.py'
```

This compiles the complete new adapter/CLI and unchanged canonical Pcm16Wav, then
runs real subprocess fixtures. The application entrypoint check is static, not a
packaged-daemon test. See [executed evidence](HALO-LINUX-055-VERIFICATION.md).

Install the backend selected for your own setup: `alsa-utils` supplies aplay;
`pipewire-bin` supplies pw-cat and needs a working user audio session. No package,
service, boot overlay or mixer setting is installed/changed by this feature.
Use the normal account that owns the audio session, not sudo.

## Explicit playback command

Input must be **raw signed 16-bit little-endian interleaved PCM**, mono or stereo,
8,000–96,000 Hz. Maximum is 8 MiB and 120 seconds, whichever is smaller. All five
options are mandatory; there is no inferred backend, device, rate or channel count.
The input must be an absolute regular-file path without symlinks. Empty, oversized,
misaligned and recognizable WAV input is rejected. Other wrong encodings cannot
be reliably recognized from raw bytes: confirm the format before playing.

Inspect `aplay -L` or `wpctl status` first. Select a safe output and low volume.
These example selectors must be replaced with an observed device name:

```bash
halo-node audio --help
halo-node audio play --backend alsa \
  --device 'plughw:CARD=YOUR_CARD_ID,DEV=0' \
  --rate 24000 --channels 1 --pcm /absolute/path/speech.pcm

halo-node audio play --backend pipewire \
  --device YOUR_PIPEWIRE_NODE_NAME \
  --rate 24000 --channels 1 --pcm /absolute/path/speech.pcm
```

`default` for ALSA or `auto` for PipeWire explicitly delegates initial device
selection to the user's audio configuration. Prefer a named target when output
privacy matters. PipeWire target `0` (unlinked) is rejected. Only conservative
ASCII device identifiers are accepted; this is not an arbitrary ALSA expression
or PipeWire property interface.

The command runs in the foreground without opening node configuration, databases
or the daemon socket. `audio.enabled` remains diagnostic-only and is not a mute
switch for this explicit command. Nothing plays at daemon start or during doctor.

Success prints a small JSON result with state, input-frame count, rate and channels.
**COMPLETED means the input write completed and the backend exited 0.** It does not
prove a speaker was audible, a user heard the result, a word boundary was reached,
or a particular hardware latency. Frames are input frames, not measured playback
progress. Failure/timeout/cancellation must never be shown as successful speech.

Exit status: 0 completed, 1 backend/write failure, 2 rejected input/arguments,
124 playback deadline, 130 API interruption. OS-delivered SIGINT/SIGTERM follows
normal JVM termination status; the shutdown hook still stops the owned player.
No raw backend stderr, input filename, audio samples or private exception text is
printed by the adapter. The caller can still disclose content through its own logs.

## Lifecycle and integration seam

The public native seam is:

```kotlin
val route = LinuxAudioRoute(LinuxAudioBackend.ALSA, "plughw:CARD=YOUR_CARD_ID,DEV=0")
LinuxPcmPlayback(route).use { output ->
    val result = output.play(pcm16Bytes, sampleRateHz = 24000, channels = 1)
    // Observe result.state; a successful process exit is not audible timing evidence.
}
```

`play` is blocking and must run off a UI/event thread. Another thread can call
`stop()` for explicit cancellation. `close()` closes admission, stops the active
child and waits boundedly for cleanup. There is one active playback per instance;
additional calls receive BUSY rather than queueing. No automatic barge-in detection
exists yet. A future coroutine/speech owner must connect cancellation to stop or
thread interruption, acquire the canonical audio resource lease, and preserve
provider/route identity. Merely calling this blocking method in a coroutine does
not automatically make it cancellable.

Each request has a private audio copy. ALSA receives a WAV produced by the
unchanged canonical Pcm16Wav utility; PipeWire receives only the PCM payload.
A 25 ms watchdog observes owner-thread interruption and a deadline of input
sample duration plus 5 seconds. It signals only the exact owned process handle,
including when the backend stops consuming stdin. The caller reaps the child
and closes pipes before releasing the playback slot. A stop racing process launch
may briefly create the child, but no samples are dispatched after that stop is
observed. Stop is immediate termination, not a click-free fade-out.

This is a host-side work bound, not a hard real-time guarantee. OS scheduling,
unalterable kernel waits, external audio buffering and hardware remain outside it.
Cleanup failure closes the instance rather than admitting overlapping work.
SIGKILL cannot execute JVM cleanup; a backend may survive until EOF or its own
termination. Do not present hard-kill behavior as the tested SIGTERM behavior.

There is no seamless multi-segment stream, pause/resume, volume API, sample-clock
feedback, word-range callbacks, resampler, float conversion or model synthesis.
Larger/unsupported provider output must be converted or segmented by a reviewed
speech owner; it is never silently truncated by this adapter.

## Routing and privacy boundary

Only `/usr/bin/aplay` and `/usr/bin/pw-cat` are launched, with an argv list and no
shell. ALSA uses nonblocking device opening and aborts on fatal stream errors.
The Trixie PipeWire command uses `-` as raw stdin, not newer `--raw` flags, and
sets `node.dont-fallback`, `node.dont-reconnect` and `node.dont-move` true with
`node.linger` false. These properties request strict WirePlumber routing; other
session managers or operator rules need separate acceptance. The adapter never
retries with another backend or device.

Child stdout/stderr are discarded. A small allowlist of user/audio-session
environment values is passed; provider API keys, proxy variables and arbitrary
debug/preload variables are not inherited. Locale is C and executable paths are
fixed. The user's ALSA/PipeWire configuration, installed binaries, same-UID software
and root remain trusted. This is not a sandbox or an OS network policy; audio
plugins and session configuration can have their own behavior. Do not adversarially
replace input paths while they are being read.

No temporary audio file is created by the adapter. This does not guarantee secure
memory erasure, prevent OS swap, or control storage performed by audio plugins.

## Physical acceptance still required

On the actual Pi, verify named ALSA and PipeWire targets separately, the selected
rate/channels, missing/busy devices, unplug/reconnect behavior, Ctrl-C/SIGTERM and
repeated playback. Listen for wrong routing, clipping, pops, underruns and latency.
Recheck after logout/reboot for headless operation. Do not copy Android's local
TTS/STT success into Linux provider status; those adapters remain unimplemented.

References: [Trixie aplay](https://manpages.debian.org/trixie/alsa-utils/aplay.1.en.html),
[Trixie pw-cat](https://manpages.debian.org/trixie/pipewire-bin/pw-cat.1.en.html),
[WirePlumber linking policy](https://pipewire.pages.freedesktop.org/wireplumber/policies/linking.html),
and [JDK21 Process](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html).
