# Native Linux PCM playback

Continue Android/Linux functionality alignment with an explicit audio output adapter.
The reviewed Linux base is 0aabae928c12cb2bc39e26fabc914a5eebedc3d1.
Android 64202f92e325d6a7f090762c1c71d5b8c1a92d74 adds app/Canvas changes but no
shared SDK changes relative to the existing d1c87d5 pin. Keep the pin unchanged.

## Approach

Use the existing canonical Pcm16Wav utility unchanged, and JDK ProcessBuilder to
feed bounded PCM to explicit distro ALSA/PipeWire players. This avoids a second
speech engine, new JNI dependency, or modified SDK. Direct JNI/Java Sound would
need additional driver qualification; a Python-only sidecar would not advance the
native Linux host. This is the implementation choice for this continuation, not
a previously specified user requirement.

## Boundary

- PCM16 little-endian, interleaved mono/stereo, 8–96 kHz, at most 8 MiB and 120 s.
- Explicit backend and device; no automatic fallback, network listener, recording,
  model invocation, system mixer changes or auto-start. Route fixed per player.
- One synchronous playback per instance. stop/close/interrupt/deadline must terminate
  the exact owned child and release blocked input writes. No shell invocation.
- Successful return means the backend accepted the bytes and exited 0; it does not
  prove audible output, word position or physical latency. No fabricated ranges.
- Copy incoming audio before dispatch. No plaintext audio files/cache written.
- Fixed public error codes; suppress child output; do not pass provider credentials.
- Opt-in `halo-node audio play` command runs without starting the daemon/database.
  Read bounded raw PCM from a regular local file, reject symlinks and special files.
- Physical ARM64/Pi/HiFiBerry/PipeWire-session acceptance remains separate.

## Tests

Compile the complete new adapter and CLI plus the unchanged Pcm16Wav source with
JDK/Kotlin. Real subprocess fixtures verify exact bytes/argv, malformed input,
nonzero exit, full stderr, blocked writes, timeouts, stop/close races, interruption,
reuse, caller-buffer mutation, source-file protection and CLI errors.
The full application/Gradle gate is distinct from these focused executable tests.
