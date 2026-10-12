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

# Build the halo-node application (fat JAR)
./gradlew :host:linux:shadowJar
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
# After ./gradlew :host:linux:shadowJar
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

Create a configuration file at `~/.config/halo-linux/config.yaml`:

```yaml
node:
  display_name: "My Halo Node"

inference:
  provider: "ollama-local"
  endpoint: "http://localhost:11434/v1"
  model: "llama3.1:8b"
  # For authenticated endpoints:
  # api_key: "your-api-key"

storage:
  data_dir: "~/.local/share/halo-linux"

audio:
  enabled: true

sync:
  enabled: false
```

## Verifying Installation

```bash
# Run diagnostics
halo-node doctor

# Expected output includes:
# - Java version (21+)
# - CPU/memory info
# - Audio system (ALSA/PipeWire)
# - Network interfaces
# - HaloLinux data directories
# - Systemd service status
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

# Check PipeWire
pw-cli info 0

# Install PipeWire if missing (Ubuntu)
sudo apt install pipewire wireplumber
```

### "Inference provider not configured"
```bash
# Configure Ollama
halo-node config show

# Edit ~/.config/halo-linux/config.yaml with your provider details
```

## Next Steps

1. Configure an inference provider (Ollama recommended)
2. Test text inference: `halo-node ask "Hello, Halo!"`
3. Explore memory: `halo-node ask "Remember that my favorite color is blue"`
4. Search memories: `halo-node ask "What's my favorite color?"`
5. Review [Architecture](ARCHITECTURE.md) for system design
6. Review [Pi 5 Setup](PI5-SETUP.md) for Raspberry Pi deployment