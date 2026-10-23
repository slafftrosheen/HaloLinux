package com.halo.sdk.provider

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Captures intent at newCall, authorizes again before DNS/connect, and retains the
 * revocable lease until the response body is consumed/closed (including SSE).
 * A queued call cannot acquire a newer mode/profile's authority after a switch.
 */
fun OkHttpClient.withRemoteDispatchPolicy(
    policy: RemoteDispatchPolicy,
    classify: (okhttp3.Request) -> RemoteDispatchRequest,
): OkHttpClient {
    class Pending(val revision: Long, val host: String, val intent: RemoteDispatchIntent?, val costs: RemoteCostObservation?, val provenance: RemoteDispatchProvenance?) {
        @Volatile var lease: RemoteDispatchLease? = null
        @Volatile var httpSuccessful = false
    }
    val pending = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Call, Pending>())
    val previousFactory = eventListenerFactory
    return newBuilder()
        // A configured destination cannot delegate authority to another host via redirect.
        .followRedirects(false).followSslRedirects(false)
        .eventListenerFactory { call ->
            val intent = RemoteDispatchContext.intent.get()
            val provenance = RemoteDispatchContext.provenance.get()
            pending[call] = Pending(provenance?.revision ?: intent?.revision ?: policy.revision,
                call.request().url.host, intent, RemoteDispatchContext.costs.get(), provenance)
            val previous = previousFactory.create(call)
            object : ForwardingRemoteEventListener(previous) {
                override fun callStart(call: Call) { previous.callStart(call) }
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                    pending[call]?.httpSuccessful = response.isSuccessful
                    previous.responseHeadersEnd(call, response)
                }
                override fun callEnd(call: Call) {
                    pending.remove(call)?.let { state -> state.lease?.finish(if (state.httpSuccessful)
                        RemoteDispatchOutcome.REPLIED else RemoteDispatchOutcome.FAILED) }
                    previous.callEnd(call)
                }
                override fun callFailed(call: Call, ioe: IOException) {
                    pending.remove(call)?.lease?.finish(if (call.isCanceled())
                        RemoteDispatchOutcome.CANCELLED else RemoteDispatchOutcome.FAILED)
                    previous.callFailed(call, ioe)
                }
                override fun canceled(call: Call) {
                    pending.remove(call)?.lease?.finish(RemoteDispatchOutcome.CANCELLED)
                    previous.canceled(call)
                }
            }
        }
        .addInterceptor { chain ->
            val call = chain.call()
            val state = pending[call] ?: throw RemoteDispatchBlocked()
            val classified = classify(chain.request())
            val intent = state.intent
            if (intent != null && intent.request.providerId != classified.providerId) throw RemoteDispatchBlocked()
            val request = (intent?.request ?: classified).let { it.copy(categories = it.categories + state.provenance?.categories.orEmpty()) }
            val lease = policy.acquire(request, state.revision, cancel = call::cancel)
            state.lease = lease
            state.costs?.bind(lease)
            try {
                lease.requireCurrent()
                chain.proceed(chain.request())
            } catch (failure: Exception) {
                lease.finish(if (call.isCanceled()) RemoteDispatchOutcome.CANCELLED else RemoteDispatchOutcome.FAILED)
                throw failure
            }
        }
        .addNetworkInterceptor { chain ->
            val state = pending[chain.call()] ?: throw RemoteDispatchBlocked()
            if (chain.request().url.host != state.host) throw RemoteDispatchBlocked()
            (state.lease ?: throw RemoteDispatchBlocked()).requireCurrent()
            chain.proceed(chain.request())
        }.build()
}

/** Conservative metadata for a provider client. Rich context categories are checked by its request owner. */
fun OkHttpClient.withProviderDispatchPolicy(policy: RemoteDispatchPolicy, providerId: String): OkHttpClient =
    withRemoteDispatchPolicy(policy) { request ->
        RemoteDispatchRequest(providerId,
            if (request.method == "GET") RemoteOperation.PROVIDER_CHECK else RemoteOperation.INFERENCE,
            setOf(if (request.method == "GET") RemoteDataCategory.ACCOUNT_METADATA else RemoteDataCategory.TEXT))
    }
