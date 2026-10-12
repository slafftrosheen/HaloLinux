# HaloLinux coding rules

- Canonical cognitive contracts live in https://github.com/slafftrosheen/Halo.
- Copied SDK files are TEMPORARY and must not be modified casually; audit and converge to shared artifacts.
- Runtime/cognition must never import OS or desktop APIs.
- Linux host adapters may import Linux/JVM-specific APIs.
- Do not introduce a second planning, tool authorization or agent execution engine.
- Provider selection is explicit. Test fixture providers are never silent production fallbacks.
- Local memory and text retrieval work without an inference provider.
- No plaintext credentials in logs, status output, README snippets or diagnostics.
- No outbound transmission of personal content by implicit model fallback.
- All LAN/remote management requires a separate security review; current daemon uses a private Unix socket.
- Run tests before claiming verification. Explicitly distinguish static code review from execution evidence.
- No GitHub Actions; use local scripts/tests. No surprise changes to canonical Android Halo.
