# Audio Architecture

## Overview

HaloLinux separates audio concerns into distinct layers:

```
┌─────────────────────────────────────────────────────────────┐
│                    Audio Architecture                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│  │   Capture   │    │   Routing   │    │  Playback   │      │
│  │  (Input)    │───▶│  (Matrix)   │───▶│  (Output)   │      │
│  └─────────────┘    └─────────────┘    └─────────────┘      │
│        │                   │                   │              │
│        ▼                   ▼                   ▼              │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│  │  Speech-to- │    │  Resource   │    │ Text-to-    │      │
│  │  Text (STT) │    │  Leases     │    │  Speech     │      │
│  │             │    │  (Audio I/O)│    │  (TTS)      │      │
│  └─────────────┘    └─────────────┘    └─────────────┘      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Audio Device Enumeration

The `LinuxAudioDiagnostics` class provides:

```kotlin
val diagnostics = LinuxAudioDiagnostics()
val result = diagnostics.enumerateDevices()

// Result contains:
result.inputDevices    // List of capture devices
result.outputDevices   // List of playback devices
result.defaultInput    // Default capture device ID
result.defaultOutput   // Default playback device ID
result.pipeWireAvailable
result.alsaAvailable
result.errors          // Any issues found
```

## ALSA Support

Reads from `/proc/asound/`:
- `/proc/asound/cards` - Sound card list
- `/proc/asound/devices` - Device nodes
- `/proc/asound/pcm` - PCM stream info

Provides device info:
- Hardware ID (`hw:0,0`, `hw:1,0`, etc.)
- Device name
- Supported sample rates (44.1k, 48k, 96k, 192k)
- Supported formats (S16_LE, S24_LE, S32_LE, FLOAT_LE)
- Channel count

## PipeWire Support

Uses `pw-cli` for device discovery:
- `pw-cli list-objects` - All PipeWire objects
- `pw-cli info <id>` - Object details
- Integrates with WirePlumber session manager

Advantages over raw ALSA:
- Unified device management
- Bluetooth audio support
- Per-application volume/routing
- Professional audio features

## Audio Resource Leases

Audio I/O uses the Halo resource arbitration system:

```kotlin
// Resource IDs
ResourceId("audio.input")   // Microphone capture
ResourceId("audio.output")  // Speaker playback

// Lease claims
val claim = ResourceLeaseRequest(
    resources = setOf(
        ResourceKey("audio.input", "device:alsa-hw:0,0"),
        ResourceKey("audio.output", "device:alsa-hw:0,0"),
    ),
    claimType = ResourceClaim.EXCLUSIVE,
    priority = ResourcePriority.HIGH,
    holder = ResourceHolder("stt-capture"),
    waitPolicy = ResourceWaitPolicy.Wait,
)
```

## Speech-to-Text (STT)

**Not yet implemented** - Planned providers:
- Sherpa-ONNX (ONNX Runtime, local)
- Whisper.cpp (local)
- Vosk (local, lightweight)
- Cloud APIs (OpenAI Whisper, Google, Azure)

Integration point:
```kotlin
interface SpeechRecognitionProvider {
    suspend fun recognize(audio: AudioStream): RecognitionResult
    fun stream(audio: AudioStream): Flow<PartialResult>
}
```

## Text-to-Speech (TTS)

**Not yet implemented** - Planned providers:
- Piper TTS (local, fast, multi-language)
- Coqui TTS (local, high quality)
- System speech-dispatcher (Linux standard)
- Cloud APIs (ElevenLabs, OpenAI, Google, Azure)

Integration point:
```kotlin
interface SpeechSynthesisProvider {
    suspend fun synthesize(text: String, voice: Voice?): AudioStream
    fun stream(text: String, voice: Voice?): Flow<AudioChunk>
    suspend fun listVoices(): List<Voice>
}
```

## Audio Diagnostics Command

```bash
# Run audio diagnostics
halo-node doctor

# Output includes:
# --- Audio System ---
# ALSA: Available
#  0 [Headphones     ]: USB-Audio - Headphones
#  1 [hifiberry      ]: HiFiBerry DAC+ ADC Pro
# PipeWire: Available (pw-cli found)
#   PipeWire daemon running
```

## Configuration

```yaml
audio:
  enabled: true
  # Preferred input device (optional, uses default if not set)
  input_device: "alsa-hw:1,0"
  # Preferred output device (optional, uses default if not set)
  output_device: "alsa-hw:1,0"
  # Sample rate for capture/playback
  sample_rate: 48000
  # Buffer size (frames)
  buffer_size: 1024
```

## Testing Audio

```bash
# Test playback
speaker-test -c 2 -t wav -D hw:0,0

# Test recording (5 seconds)
arecord -D hw:0,0 -f cd -t wav -d 5 test.wav
aplay test.wav

# PipeWire test
pw-play test.wav
pw-record --target=0 test.wav
```

## Future Work

1. **STT Integration** - Sherpa-ONNX adapter
2. **TTS Integration** - Piper TTS adapter
3. **Wake Word Detection** - Porcupine or openWakeWord
4. **Audio Routing** - Dynamic device switching
5. **Bluetooth Audio** - BlueZ A2DP/HFP integration
6. **Voice Activity Detection** - Silero VAD or WebRTC VAD