# Linux PCM Playback Implementation Plan

> Use superpowers:executing-plans, with failing tests before implementation.

**Goal:** Add explicit cancellable native PCM output and an operator CLI on Trixie.
**Architecture:** Canonical WAV validation plus bounded JDK-managed distro players.
**Tech Stack:** Kotlin/JDK21, aplay or pw-cat; no new JVM dependency.
**Spec:** ../specs/2026-10-23-linux-pcm-playback-design.md

## Constraints and review focus

No canonical SDK edits, Android edits, new services, recording or network listeners.
No fallback route or child-output disclosure. Stop must handle blocked writes and
process-start races without deadlock. Completion is not proof of audible playback.
All input, duration and command selection must be bounded and explicit.

## Tasks

- [x] Write failing route/validation/subprocess tests; execute the missing-code gate.
- [x] Implement LinuxAudioRoute and LinuxPcmPlayback under adapters/audio.
      Consume Pcm16Wav.wrap; expose play(pcm, rate, channels), stop(), close().
      Test success/failure/timeout/interrupt/concurrency with real processes.
- [x] Write failing LinuxAudioCli tests; implement strict play/help parsing, safe
      bounded input and fixed output. Wire the existing JVM entrypoint.
- [x] Extend regressions from author review, run focused tests as root and normal
      user, document exact evidence and untested hardware/full-build boundaries.
- [x] Prepare publication using expected-head guarded fast-forwards; the delivery
      message records final remote commit and branch verification.

## Execution rulings

This is an isolated partial checkout reconstructed through live connector reads;
direct repository/Maven DNS access failed. Do not use incomplete files to rebuild
the full repository or claim Gradle success. Publish only the reviewed changed files.
