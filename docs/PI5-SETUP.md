# Raspberry Pi 5 Setup Guide

## Hardware Requirements

| Component | Specification | Status |
|-----------|--------------|--------|
| Board | Raspberry Pi 5 (8 GB RAM) | ✅ Reference target |
| Storage | NVMe SSD via M.2 HAT | ✅ Recommended |
| Audio | HiFiBerry DAC+ ADC Pro | ⚠️ **UNVERIFIED** |
| Power | 27W USB-C PD (5V/5A) | ✅ Required |
| OS | Raspberry Pi OS (64-bit) Bookworm | ✅ Tested |

> **⚠️ Hardware Verification Notice**: The HiFiBerry DAC+ ADC Pro + NVMe HAT combination has **NOT been physically tested** by the HaloLinux team. GPIO/I2S overlays and compatibility checks are documented below based on manufacturer specifications. Actual hardware validation is pending.

## OS Installation

```bash
# Download Raspberry Pi OS (64-bit) Bookworm Lite
# Flash to SD card using Raspberry Pi Imager
# Enable SSH, set username/password, configure WiFi

# First boot - update system
sudo apt update && sudo apt full-upgrade -y
sudo reboot
```

## Java 21 Installation

```bash
# Install OpenJDK 21
sudo apt install -y openjdk-21-jdk

# Verify
java -version
# Should show: openjdk version "21.0.x" 2024-xx-xx
```

## NVMe Storage Setup

```bash
# Identify NVMe device
lsblk
# Typically /dev/nvme0n1

# Create partition
sudo fdisk /dev/nvme0n1
# n (new), p (primary), 1 (partition), Enter, Enter, w (write)

# Format as ext4
sudo mkfs.ext4 /dev/nvme0n1p1

# Mount at /opt/halo-data (or preferred location)
sudo mkdir -p /opt/halo-data
sudo mount /dev/nvme0n1p1 /opt/halo-data

# Persistent mount
echo "/dev/nvme0n1p1 /opt/halo-data ext4 defaults,noatime 0 2" | sudo tee -a /etc/fstab

# Set ownership for halo user
sudo chown -R $USER:$USER /opt/halo-data
```

## Audio Hardware: HiFiBerry DAC+ ADC Pro

### ⚠️ UNVERIFIED - Based on Manufacturer Docs

**Claimed Specifications:**
- 192kHz/24bit DAC + ADC
- I2S interface via GPIO header
- Hardware volume control
- Low-noise analog stage

### Required Configuration

```bash
# Edit /boot/firmware/config.txt
sudo tee -a /boot/firmware/config.txt << 'EOF'

# HiFiBerry DAC+ ADC Pro
dtoverlay=hifiberry-dacplusadcpro
# Alternative if above doesn't work:
# dtoverlay=hifiberry-dacplus

# Enable I2S
dtparam=i2s=on

# Optional: Disable onboard audio to avoid conflicts
# dtparam=audio=off
EOF

# Reboot to apply
sudo reboot
```

### Verification (Post-Reboot)

```bash
# Check ALSA recognition
cat /proc/asound/cards
# Should show: "sndrpihifiberry" or similar

# Check playback devices
aplay -l

# Check capture devices
arecord -l

# Test playback
speaker-test -c 2 -t wav

# Test recording
arecord -D hw:0,0 -f cd -t wav -d 5 test.wav
aplay test.wav
```

### PipeWire Configuration (Recommended)

```bash
# Install PipeWire
sudo apt install -y pipewire wireplumber pipewire-audio-client-libraries

# Enable for user
systemctl --user enable pipewire pipewire-pulse wireplumber
systemctl --user start pipewire pipewire-pulse wireplumber

# Verify
pw-cli info 0
wpctl status
```

## HaloLinux Installation on Pi

```bash
# Clone and build
cd /opt/halo-data
git clone https://github.com/slafftrosheen/HaloLinux.git
cd HaloLinux

# Build (takes 10-20 minutes on Pi 5)
./gradlew build --no-daemon

# Install
./scripts/install.sh

# Configure for Pi
mkdir -p ~/.config/halo-linux
cat > ~/.config/halo-linux/config.yaml << 'EOF'
node:
  display_name: "Halo Pi Node"
  
inference:
  provider: "ollama-local"
  endpoint: "http://localhost:11434/v1"
  model: "llama3.1:8b"
  
storage:
  data_dir: "/opt/halo-data/halo-linux"
  
audio:
  enabled: true
  
sync:
  enabled: false
EOF
```

## Ollama on Pi 5 (ARM64)

```bash
# Install Ollama
curl -fsSL https://ollama.ai/install.sh | sh

# Pull a model (ARM64 optimized)
ollama pull llama3.1:8b
# Or smaller: ollama pull qwen2.5:3b

# Start Ollama service
sudo systemctl enable ollama
sudo systemctl start ollama

# Verify
ollama list
curl http://localhost:11434/api/tags
```

## Systemd Service Configuration

```bash
# Enable and start
systemctl --user enable halo-node
systemctl --user start halo-node

# Check status
systemctl --user status halo-node

# View logs
journalctl --user -u halo-node -f

# Enable lingering for persistent user service (optional)
sudo loginctl enable-linger $USER
```

## GPIO/I2S Overlay Details

### HiFiBerry DAC+ ADC Pro Pinout
| Function | GPIO | Pin |
|----------|------|-----|
| I2S BCLK | 18 | 12 |
| I2S LRCLK | 19 | 35 |
| I2S DATA OUT | 20 | 38 |
| I2S DATA IN | 21 | 40 |
| I2C SDA | 2 | 3 |
| I2C SCL | 3 | 5 |
| Power 3.3V | - | 1, 17 |
| Ground | - | 6, 9, 14, 20, 25, 30, 34, 39 |

### Overlay Parameters
```bash
# In /boot/firmware/config.txt, the overlay supports:
dtoverlay=hifiberry-dacplusadcpro,slave=0,fsync=0
# slave=0: Pi is I2S master (default)
# fsync=0: Frame sync disabled (default)
```

## Performance Tuning for Pi 5

```bash
# CPU Governor - performance mode
echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor

# Disable swap (if sufficient RAM)
sudo systemctl disable dphys-swapfile
sudo dphys-swapfile uninstall

# Increase file descriptors
echo "* soft nofile 65536" | sudo tee -a /etc/security/limits.conf
echo "* hard nofile 65536" | sudo tee -a /etc/security/limits.conf

# JVM options for Pi 5 (8GB)
# In ~/.config/halo-linux/halo-node.vmoptions:
# -Xms512m
# -Xmx4g
# -XX:+UseG1GC
# -XX:MaxGCPauseMillis=100
```

## Verification Checklist

- [ ] Pi 5 boots to Raspberry Pi OS (64-bit)
- [ ] Java 21+ installed and working
- [ ] NVMe mounted at `/opt/halo-data`
- [ ] HiFiBerry DAC+ ADC Pro detected by ALSA ⚠️ **UNVERIFIED**
- [ ] PipeWire running and showing devices
- [ ] HaloLinux builds successfully
- [ ] Ollama serves models on port 11434
- [ ] `halo-node doctor` shows all green
- [ ] `halo-node ask "test"` returns response
- [ ] systemd service starts on boot

## Known Issues / TODO

1. **HiFiBerry DAC+ ADC Pro** - Physical verification needed
2. **I2S Clock Stability** - May need `dtoverlay=hifiberry-dacplusadcpro,slave=1` for external clock
3. **NVMe Power Management** - Some NVMe drives need `nvme_core.default_ps_max_latency_us=0` in kernel cmdline
4. **Thermal Throttling** - Monitor with `vcgencmd measure_temp` under load

## Recovery

```bash
# If service fails to start
journalctl --user -u halo-node -n 50

# Reset storage (CAUTION: destroys data)
rm -rf ~/.local/share/halo-linux
systemctl --user restart halo-node

# Full reinstall
./scripts/install.sh
systemctl --user daemon-reload
systemctl --user restart halo-node
```