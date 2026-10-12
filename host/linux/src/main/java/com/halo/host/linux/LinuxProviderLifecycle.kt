package com.halo.host.linux

import com.halo.adapters.inference.LinuxOpenAiCompatibleProvider
import com.halo.adapters.inference.LinuxProviderConfig
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiErrorType
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiProviderException
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.AiWireProtocol
import com.halo.sdk.provider.Credential
import com.halo.sdk.provider.CredentialProvider
import com.halo.sdk.provider.OpenAiCompatibleTransport
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.endpointExecutionLocation
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

enum class LinuxProviderPhase { DISABLED, CONFIGURED, CHECKING, AVAILABLE, DEGRADED, STOPPED }

/** Content-free status. A configured endpoint is not necessarily reachable. */
data class LinuxProviderSnapshot(
    val phase: LinuxProviderPhase,
    val providerId: String?,
    val modelId: String?,
    val attempts: Long,
    val successes: Long,
    val lastFailureType: String?,
    val lastLatencyMs: Long?,
)

/**
 * One provider/model is pinned for the Linux process lifetime.
 * No automatic model/provider fallback, no implicit probe on status,
 * and bounded concurrency for a small Pi 5.
 */
class LinuxProviderLifecycle(
    private val provider: AiInferenceProvider?,
    val modelId: String?,
    private val onClose: () -> Unit = {},
    private val timeoutMs: Long = 120_000,
    private val monotonicNs: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = Any()
    private val inferencePermit = Semaphore(1)
    private var phase = if (provider == null || modelId.isNullOrBlank())
        LinuxProviderPhase.DISABLED else LinuxProviderPhase.CONFIGURED
    private var attempts = 0L
    private var successes = 0L
    private var lastFailureType: String? = null
    private var lastLatencyMs: Long? = null

    val configured: Boolean get() = provider != null && !modelId.isNullOrBlank()

    fun snapshot(): LinuxProviderSnapshot = synchronized(lock) {
        LinuxProviderSnapshot(phase, provider?.id, modelId,
            attempts, successes, lastFailureType, lastLatencyMs)
    }

    suspend fun checkConnectivity(): Boolean {
        val target = provider ?: return false
        synchronized(lock) {
            check(phase != LinuxProviderPhase.STOPPED) { "Provider is stopped" }
            phase = LinuxProviderPhase.CHECKING
        }
        return try {
            val result = withTimeout(timeoutMs.coerceAtMost(15_000)) { target.testConnection() }
            synchronized(lock) {
                phase = if (result) LinuxProviderPhase.AVAILABLE else LinuxProviderPhase.DEGRADED
                lastFailureType = if (result) null else "CONNECTION_PROBE_FAILED"
            }
            result
        } catch (cancel: CancellationException) {
            synchronized(lock) { phase = LinuxProviderPhase.DEGRADED; lastFailureType = "CANCELLED" }
            throw cancel
        } catch (_: Exception) {
            synchronized(lock) {
                phase = LinuxProviderPhase.DEGRADED
                lastFailureType = "CONNECTION_PROBE_FAILED"
            }
            false
        }
    }

    suspend fun generate(text: String, interactionId: String): AiResponse {
        require(text.isNotBlank() && text.length <= 32_000) { "Invalid text request" }
        val target = provider ?: throw AiProviderException(
            AiErrorType.PROVIDER_UNAVAILABLE, "No inference provider configured")
        val model = modelId?.takeIf(String::isNotBlank) ?: throw AiProviderException(
            AiErrorType.MODEL_NOT_SELECTED, "No model configured")
        synchronized(lock) {
            check(phase != LinuxProviderPhase.STOPPED) { "Provider is stopped" }
            attempts++
        }
        val start = monotonicNs()
        try {
            // Applies both to queue admission and generation. No duplicate retry on timeout.
            val result = withTimeout(timeoutMs) {
                inferencePermit.withPermit {
                    target.generate(
                        AiRequest(text = text, modelId = model, interactionId = interactionId,
                            toolUseMode = com.halo.sdk.provider.ToolUseMode.NONE),
                        AiContext.EMPTY
                    )
                }
            }
            if (result.toolCalls.isNotEmpty()) throw AiProviderException(
                AiErrorType.UNSUPPORTED_CAPABILITY,
                "Tool calls cannot be executed by the current Linux host")
            if (result.text.isBlank()) throw AiProviderException(
                AiErrorType.EMPTY_RESPONSE, "The provider returned no text")
            synchronized(lock) {
                phase = LinuxProviderPhase.AVAILABLE
                successes++
                lastFailureType = null
                lastLatencyMs = TimeUnit.NANOSECONDS.toMillis(monotonicNs() - start)
            }
            return result
        } catch (cancel: CancellationException) {
            synchronized(lock) {
                phase = LinuxProviderPhase.DEGRADED
                lastFailureType = "CANCELLED_OR_TIMEOUT"
            }
            throw cancel
        } catch (error: Exception) {
            synchronized(lock) {
                phase = LinuxProviderPhase.DEGRADED
                lastFailureType = (error as? AiProviderException)?.type?.name ?: "GENERATION_FAILED"
            }
            throw error
        }
    }

    override fun close() {
        synchronized(lock) { phase = LinuxProviderPhase.STOPPED }
        onClose()
    }

    companion object {
        fun create(config: NodeConfig): LinuxProviderLifecycle {
            if (!config.providerConfigured) return LinuxProviderLifecycle(null, null)
            val endpoint = checkNotNull(config.providerEndpoint)
            val uri = URI(endpoint)
            val key = config.apiKey
            val credential = object : CredentialProvider {
                private var current = if (key == null) Credential("endpoint.none", "")
                    else Credential("endpoint.bearer", key)
                override fun hasCredential(): Boolean = true
                override fun credential(): Credential = current
                override fun setCredential(credential: Credential) { current = credential }
                override fun clearCredential() { current = Credential("endpoint.none", "") }
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
            val provider = LinuxOpenAiCompatibleProvider(
                LinuxProviderConfig(
                    baseUrl = endpoint,
                    executionLocation = endpointExecutionLocation(endpoint),
                    authKind = if (key == null) ProviderAuthKind.NONE else ProviderAuthKind.BEARER_TOKEN,
                    allowInsecureLocalCredentialTransport = uri.scheme == "http" &&
                        uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1"),
                ),
                OpenAiCompatibleTransport(client, endpoint, AiWireProtocol.OPENAI_CHAT_COMPLETIONS),
                credential
            )
            return LinuxProviderLifecycle(provider, config.providerModel,
                onClose = {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                    client.cache?.close()
                })
        }
    }
}
