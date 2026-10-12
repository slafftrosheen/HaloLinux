# HaloLinux Security Model

## Threat Model

### Assets to Protect
| Asset | Sensitivity | Protection |
|-------|-------------|------------|
| Node private key (Ed25519) | Critical | File perms 0600, encrypted at rest |
| Inference API keys | High | Stored in secret store, not logged |
| Conversation history | High | SQLite with file perms, optional encryption |
| Personal memories | High | SQLite with file perms, optional encryption |
| Sync peer keys | Medium | Stored with file perms |
| Audio recordings | Medium | Temporary, auto-deleted |

### Threat Actors
1. **Local user** - Same machine, different account
2. **Malicious software** - Compromised applications
3. **Network attacker** - LAN/WAN eavesdropping, MITM
4. **Physical attacker** - Device theft
5. **Remote inference provider** - Data sent to cloud APIs

### Trust Boundaries
```
┌─────────────────────────────────────────────────────────┐
│                    User Session                          │
│  ┌───────────────────────────────────────────────────┐  │
│  │              halo-node process                     │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌───────────┐  │  │
│  │  │  Inference  │  │  Storage    │  │  Network  │  │  │
│  │  │  Adapter    │  │  (SQLite)   │  │  (TLS)    │  │  │
│  │  └─────────────┘  └─────────────┘  └───────────┘  │  │
│  └───────────────────────────────────────────────────┘  │
│                          │                               │
│  ┌───────────────────────┼───────────────────────────┐  │
│  │           Unix Domain Socket (Management API)     │  │
│  │         - Auth token required                      │  │
│  │         - Loopback only (abstract namespace)       │  │
│  └───────────────────────┼───────────────────────────┘  │
└──────────────────────────┼──────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
       ┌─────────────┐            ┌─────────────┐
       │ Local Model │            │ Remote API  │
       │ (Ollama,    │            │ (OpenAI,    │
       │  llama.cpp) │            │  Groq, etc) │
       └─────────────┘            └─────────────┘
```

## Security Controls

### 1. No Unauthenticated Network Exposure
- Management API binds to **Unix domain socket** only (abstract namespace `@halo-node-mgmt`)
- No TCP ports opened by default
- Inference adapters use **outbound connections only** to configured endpoints
- mDNS discovery announces presence but requires explicit pairing

### 2. Least Privilege Execution
- Runs as **unprivileged user** (no root)
- systemd service with hardening:
  - `NoNewPrivileges=yes`
  - `PrivateTmp=yes`
  - `ProtectSystem=strict`
  - `ProtectHome=read-only`
  - `MemoryDenyWriteExecute=yes`
  - `SystemCallFilter=@system-service`

### 3. Secrets Management
- API keys stored in `~/.config/halo-linux/secrets/` (mode 0600)
- **Never logged** - credential values redacted in all logs
- **Never in config output** - `halo-node config show` masks secrets
- In-memory only during active use

### 4. Data Encryption
- **At rest**: File permissions (0600) + optional SQLCipher for SQLite
- **In transit**: TLS 1.3 for all remote connections
- **Peer sync**: Noise_XK or TLS with mutual auth

### 5. Input Validation
- All CLI arguments validated
- Management API: strict JSON schema validation
- Inference requests: size limits, timeout enforcement
- SQLite: parameterized queries only (no injection)

### 6. Audit Logging
- Structured JSON logs via SLF4J/Logback
- Security events: auth failures, config changes, pairing
- No PII in logs (conversation content, API keys redacted)

## Configuration Security

### Secure Defaults
```yaml
# Default configuration (secure by default)
inference:
  provider: "ollama-local"      # Local-first
  endpoint: "http://localhost:11434/v1"
  # No API key by default

sync:
  enabled: false                # Opt-in only
  require_explicit_confirmation: true

management_api:
  enabled: true
  auth_required: true
  bind: "unix:@halo-node-mgmt"  # Not TCP
```

### Hardening Checklist
- [ ] Change default node display name
- [ ] Configure inference provider with local endpoint
- [ ] Disable sync if not needed
- [ ] Set up firewall (UFW/iptables) - block inbound except SSH
- [ ] Enable automatic security updates
- [ ] Monitor journalctl for anomalies

## Inference Provider Security

### Local Providers (Preferred)
- **Ollama/llama.cpp/vLLM** on localhost
- No authentication needed (local loopback)
- Data never leaves machine
- Verify model integrity (checksums)

### Remote Providers (Explicit Opt-In)
```yaml
inference:
  provider: "openai"
  endpoint: "https://api.openai.com/v1"
  api_key: "sk-..."              # Stored securely
  # WARNING: Sends data to third party
```
- TLS verification enforced (certificate pinning optional)
- Request/response logging: **metadata only**, no content
- Timeout limits prevent hanging connections

## Sync Security

### Pairing Requirements
1. **Physical proximity** or **verified channel** for initial pairing
2. **QR code** or **6-digit code** for fingerprint verification
3. **Explicit confirmation on BOTH devices**
4. **Revocable** - either party can revoke at any time

### Data Scope Control
```yaml
sync:
  scopes:
    - personal: false      # Requires explicit enable per peer
    - shared: false        # Requires explicit enable per peer
    - conversations: false # Opt-in only
    - memories: false      # Opt-in only
```

## Incident Response

### Compromise Indicators
- Unexpected outbound connections
- Unknown paired peers
- Configuration changes not initiated by user
- Unusual inference usage patterns

### Response Actions
```bash
# 1. Stop service
systemctl --user stop halo-node

# 2. Revoke all pairings
halo-node sync revoke --all

# 3. Rotate keys
rm ~/.config/halo-linux/secrets/*
rm ~/.local/share/halo-linux/halo.db

# 4. Reinstall
./scripts/install.sh

# 5. Review logs
journalctl --user -u halo-node --since "24 hours ago"
```

## Compliance Notes

- **GDPR**: Local-first design, user controls data, right to deletion via `rm -rf ~/.local/share/halo-linux`
- **No telemetry** by default
- **No auto-updates** - user controls upgrades
- **Open source** - auditable codebase

## Future Security Enhancements

- [ ] SQLCipher for encrypted SQLite
- [ ] Hardware-backed keystore (TPM2, Secure Enclave)
- [ ] SELinux/AppArmor profiles
- [ ] Remote attestation for paired peers
- [ ] Differential privacy for sync metadata