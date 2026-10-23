# HALO-LINUX-051 verification record

Date: 2026-10-23. Base: HaloLinux `c73e4ea34153976ae84f480771f757dcb6a0efe3`.
Android archive reference: `c474e35df750ebeb896b3eacc8bacf154c344bd6`.

This retry reconstructs a standalone offline archive verifier. The previous
interrupted workspace was unavailable and no follow-up commit existed on either
remote branch. Earlier uncommitted implementation/test claims are not reused.

## Executed

- Debian GNU/Linux 13 (Trixie), x86-64; distribution Python 3.13 and
  `cryptography` 43.0.0.
- `/usr/bin/python3 -B -m unittest discover -s scripts -p 'test_halo_brain_archive.py'`:
  **77 tests passed**, zero failures, errors or skips.
- The initial CLI tests failed because the implementation did not yet exist.
- All three profiles; plain/encrypted input; optional activity; data descriptors;
  UTF-8 records; binary attachments; bounded expansion, metadata and record sizes;
  malformed paths/ZIP/JSON/checksums/manifest/completion; FIFO/symlink rejection;
  secret-safe CLI output; temporary-file cleanup and original-file immutability.
- Authentication-failure test explicitly asserts the ZIP parser is never called.
- Plain inspection works with Python site packages disabled. Encrypted inspection
  then returns an explicit missing-cryptography error instead of a traceback.
- Archive source drift checker tested against matching, changed, missing and
  symlinked files; it checks bytes rather than a claimed checkout revision.
- The exact Android `BrainArchiveCrypto.kt` source was compiled with Kotlin 1.9
  and OpenJDK 21. Its Git blob identity was checked as
  `9a1631d5736f96d1907f47b3a848d29f10783cef` before compilation.
  A synthetic FULL_USER_BACKUP ZIP was encrypted by that unchanged source with
  its 600,000-iteration default and a non-ASCII/emoji password. The Linux tool
  authenticated and inspected it. The encrypted vector is retained in the repo.
- Author code review covered parser bounds, authentication ordering, fixed error
  output, private staging and non-mutation. No independent review is claimed.

## Not executed / not implemented

No full Gradle build, canonical cognition build, existing JVM test suite, packaged
halo-node daemon test, ARM64 run, Pi audio/model test, or actual Android app export
was executed in this retry. The container could not clone/download the complete
repository directly; live repository reads/writes used the GitHub connector.

No Linux database import, field conversion, Android edits, native JVM CLI command,
automatic source update, peer transport, pairing or bidirectional sync is added.
The runtime source pin from HALO-LINUX-050 is unchanged. The new pin covers only
archive compatibility. Passing archive inspection is not proof of record semantics,
producer identity, safe restoration or system-wide Android/Linux equivalence.
