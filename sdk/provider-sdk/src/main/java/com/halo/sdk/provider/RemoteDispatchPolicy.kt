package com.halo.sdk.provider
import kotlinx.coroutines.asContextElement

/** Content-free request classification. Never put prompts, URLs or credentials in this contract. */
enum class RemoteOperation { INFERENCE, PROVIDER_CHECK, HOSTED_SPEECH, WEB, IMAGE, CONNECTED_SERVICE }
enum class RemoteDataCategory { TEXT, CONVERSATION, DEVICE_STATE, RECENT_EVENTS, TOOLS, LOCATION,
    IMAGES, AUDIO, VIDEO, WORKSPACE, SCREEN, MEMORIES, ACCOUNT_METADATA }

data class RemoteDispatchRequest(
    val providerId: String,
    val operation: RemoteOperation,
    val categories: Set<RemoteDataCategory>,
)

/**
 * A lease belongs to one intent revision. The owner revokes it when policy changes,
 * even for Private -> Full Power -> Private or an edit that restores an old value.
 */
interface RemoteDispatchLease : AutoCloseable {
    fun requireCurrent()
    fun finish(outcome: RemoteDispatchOutcome, providerReportedCostUsd: String? = null)
    /** The outcome owner may attach a provider-reported amount after body parsing. */
    fun reportProviderCostUsd(value: String) {}
    override fun close() = finish(RemoteDispatchOutcome.CANCELLED)
}

enum class RemoteDispatchOutcome { STARTED, REPLIED, COMPLETED, FAILED, CANCELLED, BLOCKED }

interface RemoteDispatchPolicy {
    val revision: Long
    /** Must validate and register revocation atomically, before any request bytes leave. */
    fun acquire(request: RemoteDispatchRequest, expectedRevision: Long, recordActivity: Boolean = true,
        cancel: () -> Unit): RemoteDispatchLease
}

class RemoteDispatchBlocked : java.io.IOException("Remote processing is unavailable under the current mode profile")

/** Carries only dispatch intent across coroutine thread switches into synchronous HTTP clients. */
data class RemoteDispatchIntent(val request: RemoteDispatchRequest, val revision: Long)
/** Admitted model inputs can influence later tool arguments, even after local inference. */
data class RemoteDispatchProvenance(val categories: Set<RemoteDataCategory>, val revision: Long)

object RemoteDispatchContext {
    val provenance = ThreadLocal<RemoteDispatchProvenance?>()
    val intent = ThreadLocal<RemoteDispatchIntent?>()
    val costs = ThreadLocal<RemoteCostObservation?>()
}

/** Ephemeral correlation only; never associates costs by time, provider name or prompt text. */
class RemoteCostObservation {
    @Volatile private var lease: RemoteDispatchLease? = null
    internal fun bind(value: RemoteDispatchLease) { lease = value }
    fun providerReportedCostUsd(value: String) { lease?.reportProviderCostUsd(value) }
}

/** Optional capability of the host's existing preference view; credentials remain Storage-owned. */
interface RemoteDispatchPolicySource { val remoteDispatchPolicy: RemoteDispatchPolicy }

fun com.halo.sdk.runtime.Storage.remoteAiContext(providerId: String, request: AiRequest, context: AiContext): kotlin.coroutines.CoroutineContext {
    val policy = (this as? RemoteDispatchPolicySource)?.remoteDispatchPolicy ?: return kotlin.coroutines.EmptyCoroutineContext
    val inherited = RemoteDispatchContext.intent.get()
    if (inherited != null && inherited.request.providerId != providerId) throw RemoteDispatchBlocked()
    val categories = context.remoteDispatchCategories(request) + inherited?.request?.categories.orEmpty()
    val intent = RemoteDispatchIntent(RemoteDispatchRequest(providerId, RemoteOperation.INFERENCE, categories),
        inherited?.revision ?: policy.revision)
    return RemoteDispatchContext.intent.asContextElement(intent)
}

fun AiContext.remoteDispatchCategories(request: AiRequest): Set<RemoteDataCategory> = buildSet {
        addAll(remoteDataCategories)
        add(RemoteDataCategory.TEXT)
        if (request.image != null) add(RemoteDataCategory.IMAGES)
        if (request.audio != null) add(RemoteDataCategory.AUDIO)
        if (request.video != null) add(RemoteDataCategory.VIDEO)
        if (memories.isNotEmpty()) add(RemoteDataCategory.MEMORIES)
        if (screenContext != null) add(RemoteDataCategory.SCREEN)
        if (deviceState != null) add(RemoteDataCategory.DEVICE_STATE)
        if (recentEvents.isNotEmpty()) add(RemoteDataCategory.RECENT_EVENTS)
        if (availableTools.isNotEmpty()) add(RemoteDataCategory.TOOLS)
}

fun com.halo.sdk.runtime.Storage.remoteImageContext(providerId: String, request: AiImageRequest): kotlin.coroutines.CoroutineContext {
    val policy = (this as? RemoteDispatchPolicySource)?.remoteDispatchPolicy ?: return kotlin.coroutines.EmptyCoroutineContext
    val inherited = RemoteDispatchContext.intent.get()
    if (inherited != null && inherited.request.providerId != providerId) throw RemoteDispatchBlocked()
    val categories = request.contextCategories + if (request.reference == null) setOf(RemoteDataCategory.TEXT)
        else setOf(RemoteDataCategory.TEXT, RemoteDataCategory.IMAGES)
    return RemoteDispatchContext.intent.asContextElement(RemoteDispatchIntent(
        RemoteDispatchRequest(providerId, RemoteOperation.IMAGE, categories), inherited?.revision ?: policy.revision))
}

fun com.halo.sdk.runtime.Storage.remoteSpeechContext(providerId: String, request: SpeechRequest? = null): kotlin.coroutines.CoroutineContext {
    val policy = (this as? RemoteDispatchPolicySource)?.remoteDispatchPolicy ?: return kotlin.coroutines.EmptyCoroutineContext
    val inherited = RemoteDispatchContext.intent.get()
    if (inherited != null && inherited.request.providerId != providerId) throw RemoteDispatchBlocked()
    val provenance = request?.remoteProvenance ?: RemoteDispatchContext.provenance.get()
    return RemoteDispatchContext.intent.asContextElement(RemoteDispatchIntent(RemoteDispatchRequest(providerId,
        RemoteOperation.HOSTED_SPEECH, setOf(RemoteDataCategory.TEXT) + provenance?.categories.orEmpty()),
        provenance?.revision ?: inherited?.revision ?: policy.revision)) + RemoteDispatchContext.provenance.asContextElement(provenance)
}
