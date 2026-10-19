# Getting Started with HaloLinux

## Prerequisites

### Java 21+
```bash
# Ubuntu/Debian
sudo apt update && sudo apt install openjdk-21-jdk

# Fedora/RHEL
sudo dnf install java-21-openjdk-devel

# Arch
sudo pacman -S jdk21-openjdk

# Verify
java -version
```

### Gradle (Wrapper Included)
The project includes a Gradle wrapper. No separate installation needed.

### Optional: Inference Providers
For AI inference, install one or more:

**Ollama (Recommended for local models):**
```bash
curl -fsSL https://ollama.ai/install.sh | sh
ollama pull llama3.1:8b
ollama serve
```

**llama.cpp Server:**
```bash
# Build from source or download release
# See: https://github.com/ggerganov/llama.cpp
./llama-server -m models/llama3.1-8b-q4_k_m.gguf --port 8080
```

**vLLM (High throughput):**
```bash
pip install vllm
vllm serve meta-llama/Llama-3.1-8B-Instruct --port 8000
```

## Building from Source

```bash
git clone https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux

# Build all modules
./gradlew build

# Run tests
./gradlew test
```

## Running HaloLinux

### Development Mode (Direct Gradle)
```bash
# Show version
./gradlew :host:linux:run --args="--version"

# Run diagnostics
./gradlew :host:linux:run --args="doctor"

# Show status
./gradlew :host:linux:run --args="status"

# Ask a question (requires configured provider)
./gradlew :host:linux:run --args="ask \"What is Halo?\""
```

### Using the Built JAR
```bash
# Build the application JAR
./gradlew :host:linux:jar

JAR_PATH=$(find host/linux/build/libs -name "*.jar" | grep -v sources | grep -v javadoc | head -1)

java -jar "$JAR_PATH" --version
java -jar "$JAR_PATH" doctor
java -jar "$JAR_PATH" status
java -jar "$JAR_PATH" ask "What is Halo?"
```

### Installation (Systemd Service)
```bash
# Build and install
./scripts/install.sh

# Start service
systemctl --user start halo-node
systemctl --user enable halo-node

# Check status
systemctl --user status halo-node

# View logs
journalctl --user -u halo-node -f
```

## Configuration

HaloLinux stores configuration in SQLite preferences (not YAML). Use the CLI to configure:

```bash
# Show current configuration
halo-node config show

# Configure an OpenAI-compatible provider (e.g., Ollama)
halo-node config set-provider \
  --id ollama-local \
  --name "Ollama (Local)" \
  --url http://localhost:11434/v1 \
  --auth none

# Configure a remote provider with API key
halo-node config set-provider \
  --id my-provider \
  --name "My Provider" \
  --url https://api.example.com/v1 \
  --auth bearer \
  --credential "sk-..."

# Set default model
halo-node config set-model "llama3.1:8b"
```

Configuration is persisted in `~/.local/share/halo-linux/preferences.db`.

## Verifying Installation

```bash
# Run diagnostics
halo-node doctor

# Expected output includes:
# - Java version (21+)
# - OS/architecture info
# - Directories (data, config, cache)
# - Database migration status
# - Audio system (ALSA availability)
# - Inference provider status (if configured)
# - Agent run store status (non-terminal runs)
```

## Troubleshooting

### "Java not found"
Ensure Java 21+ is installed and on PATH:
```bash
which java
java -version
```

### "Gradle build fails"
```bash
# Clean and retry
./gradlew clean build --refresh-dependencies
```

### "No audio devices found"
```bash
# Check ALSA
cat /proc/asound/cards

# Install PipeWire if missing (Ubuntu)
sudo apt install pipewire wireplumber
```

Note: PipeWire device enumeration is not implemented to avoid unbounded external process calls. ALSA devices are reported with unknown capabilities (empty sample rates/formats) rather than fabricated values.

### "Inference provider not configured"
```bash
# Configure a provider
halo-node config show
halo-node config set-provider --id ollama-local --name "Ollama" --url http://localhost:11434/v1 --auth none
```

## CLI Commands Reference

| Command | Description |
|---------|-------------|
| `halo-node --version` | Show version |
| `halo-node doctor` | Run diagnostics |
| `halo-node status` | Show node status |
| `halo-node start` | Start daemon (foreground) |
| `halo-node ask "query"` | Ask a question (requires provider) |
| `halo-node config show` | Show configuration |
| `halo-node config set-provider` | Configure inference provider |
| `halo-node config set-model` | Set default model |

## Next Steps

1. Configure an inference provider (Ollama recommended)
2. Test text inference: `halo-node ask "Hello, Halo!"`
3. Explore memory: `halo-node ask "Remember that my favorite color is blue"`
4. Search memories: `halo-node ask "What's my favorite color?"`
5. Review [Architecture](ARCHITECTURE.md) for system design
6. Review [Pi 5 Setup](PI5-SETUP.md) for Raspberry Pi deployment
7. Review [HALO-LINUX-003 Implementation Report](HALO-LINUX-003-IMPLEMENTATION-REPORT.md) for detailed changes