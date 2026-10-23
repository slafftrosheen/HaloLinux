# HALO-LINUX-055 verification record

Date: 2026-10-23. Linux base: `0aabae928c12cb2bc39e26fabc914a5eebedc3d1`.
Scope: native PCM16 playback, explicit CLI and Trixie-compatible backend arguments.

## Upstream and baseline

Live GitHub comparison from Android `d1c87d5895e9c68af62a652b6dbec0d3f626f0ae`
to `64202f92e325d6a7f090762c1c71d5b8c1a92d74` found two commits with app/Canvas
and test/documentation changes, not shared SDK changes. The portable source pin,
186-file inventory and archive compatibility pins are deliberately unchanged.
No Android files or canonical SDK copies are modified by this batch.

The isolated execution workspace reconstructed these baselines and verified their
exact Git blob hashes before changing/using them:

- PcmAudio.kt: `3d33a4836f4e2e64f3ae68df435084bb4e9824ea` (unchanged).
- HaloNodeApplication.kt: `f6155fb6c412d3e5357b65ed8762eef2c66f7058`.
- README.md: `469c1503d9c5332d6f3fc146a0d2f34e2e8ac4db`.
- docs/AUDIO.md: `c6897481ae5af0fc02800fe5b50975c367f653e4`.

Direct GitHub/Maven DNS access failed; repository reads and publication use the
connected GitHub tool. No incomplete local checkout is used as a full build.

## Executed

Debian GNU/Linux 13 Trixie x86-64, OpenJDK 21.0.12.1. The available Kotlin CLI
compiler compiled this focused gate at JVM target 17; the repository's Gradle
JDK21 configuration is not changed. No third-party JVM dependencies or SDK stubs
are used in the executable gate: it compiles the complete new route, player and
CLI implementations plus the exact canonical Pcm16Wav source.

```bash
/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_linux_pcm_playback.py'
```

Two Python methods pass. One compiles/runs **67 named JVM cases**; the other checks
application-entrypoint routing as source text. There are not 69 JUnit tests.
The same compiled executable harness also passed all 67 cases as normal user
UID 1000. Repeated runs do not increase the number of unique cases.

The first gate failed because playback implementation and entrypoint routing were
absent. A subsequent regression failed because a WAV file could be sent through
the raw-PCM CLI; the CLI now rejects recognizable WAV containers before launch.

Coverage includes:

- Exact ALSA WAV bytes and PipeWire raw PCM bytes, immutable dispatch copy, named
  device syntax, no shell arguments, known Trixie flags and requested routing properties.
- Empty/misaligned/unsupported/oversized PCM, exact 8 MiB and 120-second boundaries,
  invalid backend/device/format/options, duplicate options and source-file preservation.
- Missing executable, nonzero/early process exit, full stderr, blocked input writes,
  drain timeout, stop/close/interruption, preserved interrupt flag, launch cancellation
  races, concurrent admission refusal and successful reuse after stop/failure.
- Real owned subprocesses and real pipes, including a CLI subprocess terminated by
  SIGTERM. The CLI shutdown hook reaps its playback child. Repeated admission/close
  races leave no owned child running in these fixtures.
- Fixed error output, environment allowlisting, symlink ancestors/files, special
  files, missing files and oversized input rejection. Fixture audio/files are synthetic.

The fixture subprocess substitutes only the external audio executable; it is not
a fake Process implementation. Tests verify process lifecycle and wire bytes, not
ALSA/PipeWire driver behavior. The separate entrypoint test is static, not a full
HaloNodeApplication/daemon execution. No hidden production test fallback exists.

## Review and limits

Author review covered cancellation ordering, child ownership, blocked pipe cleanup,
route pinning, input bounds, error privacy and unchanged SDK ownership. No independent
reviewer/subagent was available. Documentation was checked against the Trixie aplay
and pw-cat manuals, WirePlumber's linking policy and JDK21 Process documentation.

No full Gradle build, full JVM/JUnit suite, canonical cognition build, packaged
halo-node smoke, original archive/import/API suites, real aplay/pw-cat run, physical
Pi ARM64/HiFiBerry sound, logout/reboot/NVMe behavior, audio latency or power-loss
qualification ran here. The container has no installed aplay/pw-cat or audio session.
Do not reuse the historical HALO-LINUX-050 build results as current acceptance.

No microphone capture, VAD/wake, STT/TTS provider, seamless speech streaming,
word timing, automatic barge-in detection, Brain API integration, Android UI,
service installation, OS changes or GitHub Actions were added. Stop signals are
not a click-free fade, OS real-time guarantee or SIGKILL cleanup guarantee.

Publication uses the existing remote tree and expected-head guarded branch updates.
Only reviewed changed files are included; the final remote commit/refs are confirmed
in the delivery message. Compiled harnesses and generated fixtures are not published.
