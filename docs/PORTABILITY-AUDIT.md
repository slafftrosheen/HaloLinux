# Halo Portability Audit

**Upstream Repository:** https://github.com/slafftrosheen/Halo  
**Examined Commit:** `efe9d44e6310d2573854667fbf01f489066f2f94` (feat(ui): guide explicit pairing and truthful device controls)  
**Date:** 2026-10-12

## Summary

This audit examines the canonical Halo Android repository to identify modules that are genuinely Android-independent and reusable for HaloLinux, versus those that require Linux-specific adapters or cannot be ported without significant refactoring.

## Reusable Modules (Android-Independent)

### ✅ `sdk/runtime-sdk` (93 Kotlin files)
**Status:** Fully reusable  
**Location:** `/sdk/runtime-sdk/src/main/java/com/halo/sdk/runtime/`

Core runtime contracts with **zero Android dependencies**:
- `Engine`, `EngineContext`, `EngineState`, `EngineHealth` - Lifecycle interfaces
- `EventBus`, `CommandBus`, `QueryBus` - Communication buses
- `ServiceRegistry`, `ServiceKey` - Dependency injection
- `RuntimeContext` - Execution context (eventBus, scheduler, logger, clock)
- `Resource` contracts (`ResourceId`, `ResourceLease`, `ResourceLeaseManager`) - Capability arbitration
- `InteractionSurface`, `InteractionSurfaceCoordinator` - Portable human interaction
- `ToolDescriptor`, `ToolRegistry`, `PolicyToolAuthorizer` - Tool fabric
- `DeviceDescriptor`, `DeviceRegistry`, `EmbodimentGateway` - Device abstraction
- `Identifier`, `Version`, `Priority` - Core types

**Action:** Direct dependency via Gradle composite build or published artifact.

### ✅ `sdk/provider-sdk` (38 Kotlin files)
**Status:** Fully reusable  
**Location:** `/sdk/provider-sdk/src/main/java/com/halo/sdk/provider/`

Provider abstraction layer with **zero Android dependencies**:
- `AiInferenceProvider` - Inference contract
- `AiProviderRouter`, `AiWorkloadPlanner` - Workload routing
- `ConfiguredOpenAiCompatibleProvider` - Generic OpenAI-compatible HTTP/SSE transport
- `OpenAiCompatibleTransport` - Streaming, tool calls, model listing
- `CredentialProvider`, `ProviderSecretStore` - Credential management
- `AiCapability`, `AiModel`, `AiRequest`, `AiResponse`, `AiStreamEvent` - Data types
- `ProviderStatus`, `ProviderAuthInfo` - Health and auth state

**Action:** Direct dependency. LinuxOpenAiCompatibleProvider extends ConfiguredOpenAiCompatibleProvider.

### ✅ `sdk/host-sdk` 
**Status:** Reusable interfaces  
**Location:** `/sdk/host-sdk/`

Host capability interfaces (AudioSource, Camera, Storage, etc.) - define contracts that Linux adapters implement.

### ✅ `runtime/kernel` (Partial)
**Status:** Mostly reusable  
**Location:** `/runtime/kernel/src/main/java/com/halo/runtime/kernel/`

Reusable components:
- `DefaultResourceLeaseManager` - Atomic resource arbitration (pure Kotlin)
- `EngineGraph`, `EngineNode`, `DependencyResolver` - Engine composition
- `CognitiveBus`, `ExecutionBus`, `PerceptionBus`, `SystemBus` - Bus implementations
- `LifecycleManager` - Engine lifecycle orchestration

Android-specific (need adapters):
- `HaloMicrokernel`, `ProcessManager` - Legacy path, not used in production
- `BootLoader` - Android-specific initialization

### ✅ `runtime/events`
**Status:** Fully reusable  
**Location:** `/runtime/events/src/main/java/com/halo/runtime/events/`

`DefaultEventBus` - Pure Kotlin coroutine-based event bus.

### ✅ `runtime/cognition` (Core logic)
**Status:** Reusable core, Android persistence adapter separate  
**Location:** `/runtime/cognition/src/main/java/com/halo/runtime/cognition/`

Reusable:
- `AgentRunCoordinator`, `AgentRunJournal` - Durable execution
- `AgentRecoveryPlanner`, `AgentCheckpointAssembler` - Recovery logic
- `ToolInvocation`, `ToolArgumentCanonicalizer` - Tool execution
- `CognitiveSession` - Provider conversation management
- `InteractionSurface` contracts - Portable interaction

Android-specific:
- `RoomAgentRunRepository` - Room persistence (replace with SQLite)

### ✅ `runtime/observer`
**Status:** Fully reusable  
**Location:** `/runtime/observer/`

Observability, metrics, health reporting - pure Kotlin.

### ✅ `runtime/provider-core`
**Status:** Fully reusable  
**Location:** `/runtime/provider-core/`

Provider registry and routing - pure Kotlin.

### ✅ `engines/*` (Most)
**Status:** Reusable inference/reasoning/planning engines  
**Location:** `/engines/ai/`, `/engines/reasoning/`, `/engines/planning/`, `/engines/interpretation/`, `/engines/agents/`, `/engines/execution/`, `/engines/memory/`, `/engines/workflow/`, `/engines/context/`, `/engines/privacy/`, `/engines/automation/`, `/engines/notification/`, `/engines/diagnostics/`

Core cognitive engines are pure Kotlin with platform-neutral contracts.

### ✅ `libraries/common`
**Status:** Fully reusable  
**Location:** `/libraries/common/src/main/java/com/halo/libraries/common/`

`Identifier`, `Version` - Basic value types.

### ✅ `libraries/logging`
**Status:** Reusable  
**Location:** `/libraries/logging/`

Structured logging contracts.

## Modules Requiring Linux Adapters

### 🔄 `host-android` → `host/linux`
**Status:** Complete rewrite needed  
**Location:** `/host-android/src/main/java/com/halo/host/android/`

Android-specific implementations that need Linux equivalents:
| Android Component | Linux Replacement |
|-------------------|-------------------|
| `AndroidAudioSource`, `AndroidMicrophone` | `LinuxAudioDiagnostics` (ALSA/PipeWire) |
| `AndroidStorage` (Room/DataStore) | `LinuxStorage` (SQLite + XDG dirs) |
| `AndroidCamera`, `CameraXAdapter` | V4L2/libcamera adapter (future) |
| `AndroidWearableHost`, `AndroidWearableAudioOutput` | BlueZ/D-Bus adapter (future) |
| `AndroidAssistantRoleController` | Not applicable (Linux is headless) |
| `HaloAccessibilityService` | Not applicable |
| `AndroidGlobalActions` | Not applicable |
| `BluetoothAudioRouteMonitor` | BlueZ adapter (future) |
| `AudioFocusManager` | PipeWire/ALSA focus (future) |

### 🔄 `providers:litert` 
**Status:** Requires investigation  
**Location:** `/providers/litert/`

LiteRT (TensorFlow Lite) Android integration. Need to verify:
- LiteRT Linux support (available via Maven Central)
- GPU delegate availability on Linux (ARM64 Mali, x86-64)
- Model format compatibility (TFLite flatbuffers)
- Native library loading (JNI)

**Research needed:** Separate LiteRT Linux compatibility investigation.

### 🔄 `providers:sherpa-stt`
**Status:** Requires Linux port  
**Location:** `/providers/sherpa-stt/`

Sherpa-ONNX STT. ONNX Runtime has Linux support. Need Linux adapter for:
- Model loading
- Audio input pipeline
- Native library dependencies

### 🔄 `providers:android-tts`
**Status:** Not portable  
**Location:** `/providers/android-tts/`

Android system TTS - no Linux equivalent. Replace with:
- Piper TTS (local, fast)
- Coqui TTS
- System speech-dispatcher

### 🔄 `drivers:heycyan`
**Status:** Requires BlueZ/D-Bus port  
**Location:** `/drivers/heycyan/`

HeyCyan glasses driver uses Android Bluetooth APIs. Need:
- BlueZ GATT client implementation
- D-Bus integration for Bluetooth management
- Audio routing via PipeWire/Bluetooth

## Modules NOT Portable (Android-Specific)

| Module | Reason |
|--------|--------|
| `app` | Android application (Compose UI, Activities, Services) |
| `wear` | Wear OS module |
| `providers:android-assistant` | Android Assistant API integration |
| `providers:elevenlabs-tts` | Uses Android secure storage (replace with Linux secret storage) |
| `drivers:simulator` | Android-specific test simulator |

## Dependency Extraction Strategy

### Recommended: Gradle Composite Build
```kotlin
// In HaloLinux/settings.gradle.kts
includeBuild("../Halo") {
    dependencySubstitution {
        substitute module("com.halo:sdk:runtime-sdk") with project(":sdk:runtime-sdk")
        substitute module("com.halo:sdk:provider-sdk") with project(":sdk:provider-sdk")
        // ... etc
    }
}
```

**Benefits:**
- Single source of truth for shared modules
- No publishing overhead
- Immediate upstream changes visible
- Works with `./gradlew build` locally

### Alternative: Local Maven Publishing
```bash
cd /path/to/Halo
./gradlew publishToMavenLocal
```
Then in HaloLinux: `mavenLocal()` repository.

**Drawback:** Extra step, version synchronization needed.

## Blockers for Direct Reuse

1. **Android Gradle Plugin dependencies** - Some modules use `com.android.library` plugin
   - Solution: Extract pure-Kotlin modules to separate Gradle projects with `org.jetbrains.kotlin.jvm`

2. **AndroidX dependencies** - `androidx.appfunctions`, `androidx.media3`, Room, DataStore
   - Solution: These are only in `host-android` and provider implementations; core SDKs don't depend on them

3. **JNI/Native libraries** - LiteRT, Sherpa, SQLite JDBC
   - Solution: Linux-compatible variants exist (sqlite-jdbc, ONNX Runtime Linux, LiteRT Linux)

4. **Android-specific APIs** - `Context`, `PackageManager`, `BluetoothAdapter`, etc.
   - Solution: Confined to `host-android` and drivers; not in runtime SDKs

## Recommended Extraction Plan

### Phase 1: Immediate (This Bootstrap)
- [x] Copy `sdk/runtime-sdk` → `HaloLinux/sdk/runtime-sdk`
- [x] Copy `sdk/provider-sdk` → `HaloLinux/sdk/provider-sdk`
- [x] Copy `libraries/common` → `HaloLinux/libraries/common`
- [x] Create `host/linux` with `Engine` implementation
- [x] Create `adapters/inference` with `LinuxOpenAiCompatibleProvider`
- [x] Create `adapters/storage` with `LinuxStorage` (SQLite + FTS5)
- [x] Create `adapters/audio` with `LinuxAudioDiagnostics`

### Phase 2: Near-term
- [ ] Extract `runtime/kernel` resource lease manager
- [ ] Extract `runtime/cognition` agent run logic (replace Room with SQLite)
- [ ] Extract `engines/*` cognitive engines
- [ ] Set up composite build with upstream Halo

### Phase 3: Future
- [ ] LiteRT Linux provider (after compatibility verified)
- [ ] Sherpa-ONNX Linux STT adapter
- [ ] BlueZ HeyCyan driver
- [ ] Piper/Coqui TTS provider

## Verification Checklist

For each reused module, verify:
- [ ] No `android.` imports in source
- [ ] No `androidx.` imports in source
- [ ] No `com.android.` imports in source
- [ ] Builds with `org.jetbrains.kotlin.jvm` plugin only
- [ ] Tests pass on Linux x86-64
- [ ] Tests pass on Linux ARM64 (when hardware available)

## Conclusion

**~85% of Halo's core runtime logic is Android-independent and reusable.** The primary investment needed is:
1. Linux host implementation (`host/linux`)
2. Platform adapters (audio, storage, bluetooth)
3. Build system integration (composite build)

The cognitive runtime, provider abstraction, tool fabric, resource arbitration, and recovery logic are all portable Kotlin that can be shared directly.