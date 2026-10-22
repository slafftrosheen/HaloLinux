package com.halo.sdk.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reusable OpenAI-compatible transport for chat-completions and Responses wire
 * protocols.
 *
 * Responsibilities: request serialization, conversation history, multimodal
 * content, native tool definitions, tool-call parsing, SSE parsing, HTTP error
 * mapping, rate-limit parsing, model-list parsing and cancellation/timeouts.
 *
 * NOT responsibilities: credential storage, provider selection, billing policy,
 * Halo tool execution or role routing. Those stay with provider/router/UI owners.
 */
class OpenAiCompatibleTransport(
    private val client: OkHttpClient,
    baseUrl: String,
    private val protocol: AiWireProtocol,
    private val quotaSnapshot: (ProviderQuotaSnapshot) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var baseUrl: String = baseUrl.trim().trimEnd('/')

    private val json = "application/json".toMediaType()
    fun withProtocol(protocol: AiWireProtocol): OpenAiCompatibleTransport =
        OpenAiCompatibleTransport(client, baseUrl, protocol, quotaSnapshot, nowMs)

    /**
     * Rebinds a configurable provider to its persisted endpoint without recreating the provider
     * graph. Fixed provider presets never call this; custom/companion providers do after setup.
     */
    fun updateBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Provider base URL must not be blank" }
        baseUrl = normalized
    }

    /**
     * Credential-bearing request security gate.
     *
     * Remote providers must use HTTPS or explicitly trusted local transport.
     * Public cleartext HTTP must not receive credentials.
     */
    fun credentialsAllowedFor(url: String, allowInsecureLocal: Boolean): CredentialTransportResult =
        CredentialTransportPolicy.evaluate(url, allowInsecureLocal)

    /** Basic connectivity + auth probe for OpenAI-compatible `/models`. */
    suspend fun testConnection(
        credentialHeader: String?,
        credentialHeaderValue: String?,
        allowInsecureLocal: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("models").build().toString()
        if (credentialHeader != null && credentialHeaderValue != null) {
            val security = credentialsAllowedFor(url, allowInsecureLocal)
            if (security == CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT) {
                throw AiProviderException(
                    AiErrorType.AUTHENTICATION_FAILED,
                    "Credential transport requires a secure endpoint",
                    AiFailureDetails(recoverability = Recoverability.USER_ACTION_REQUIRED),
                )
            }
        }
        val request = Request.Builder()
            .url(url)
            .apply {
                credentialHeader?.let { h ->
                    credentialHeaderValue?.let { v -> addHeader(h, v) }
                }
            }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw transportError(response.code, response.body?.string(), response.header("Retry-After"))
                JSONObject(response.body?.string().orEmpty()).getJSONArray("data")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiProviderException(AiErrorType.TIMEOUT, "Remote provider connectivity check timed out", e)
        } catch (e: UnknownHostException) {
            throw AiProviderException(AiErrorType.NETWORK_UNAVAILABLE, "Remote provider host unavailable", e)
        } catch (e: AiProviderException) {
            throw e
        } catch (_: org.json.JSONException) {
            throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote provider returned invalid model metadata")
        } catch (e: Exception) {
            throw AiProviderException(AiErrorType.NETWORK_ERROR, "Remote provider connectivity check failed", e)
        }
    }

    /** Fetch a remote model list from `/models`. */
    suspend fun fetchRemoteModelList(
        credentialHeader: String?,
        credentialHeaderValue: String?,
        allowInsecureLocal: Boolean,
        staticHints: List<AiModel>,
        freeModelIds: Set<String>,
        fallbackWhenUnauthenticated: Boolean,
    ): List<AiModel> = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("models").build().toString()
        if (credentialHeader != null && credentialHeaderValue != null) {
            val security = credentialsAllowedFor(url, allowInsecureLocal)
            if (security == CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT) {
                throw AiProviderException(
                    AiErrorType.AUTHENTICATION_FAILED,
                    "Credential transport requires a secure endpoint",
                    AiFailureDetails(recoverability = recoverabilityForUnknownHost(url)),
                )
            }
        }
        val request = Request.Builder()
            .url(url)
            .apply {
                credentialHeader?.let { h ->
                    credentialHeaderValue?.let { v -> addHeader(h, v) }
                }
            }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        if (fallbackWhenUnauthenticated && staticHints.isNotEmpty()) return@withContext staticHints
                        throw transportError(response.code, response.body?.string(), response.header("Retry-After"))
                    }
                    throw transportError(response.code, response.body?.string(), response.header("Retry-After"))
                }
                val body = response.body?.string() ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Empty model metadata response")
                parseModelList(body, freeModelIds)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiProviderException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiProviderException(AiErrorType.TIMEOUT, "Remote model discovery timed out", e)
        } catch (e: UnknownHostException) {
            throw AiProviderException(AiErrorType.NETWORK_UNAVAILABLE, "Remote model discovery: host unavailable", e)
        } catch (_: org.json.JSONException) {
            throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote provider returned invalid model metadata")
        } catch (e: Exception) {
            throw AiProviderException(AiErrorType.NETWORK_ERROR, "Remote model discovery failed", e)
        }
    }

    /** Generate through the configured wire protocol. */
    suspend fun generate(
        request: AiRequest,
        context: AiContext,
        model: String,
        credentialHeader: String?,
        credentialHeaderValue: String?,
        systemPrompt: String?,
        allowInsecureLocal: Boolean,
    ): AiResponse = withContext(Dispatchers.IO) {
        request.validateForTextGeneration()
        val url = wireUrl("generate")
        if (credentialHeader != null && credentialHeaderValue != null) {
            val security = credentialsAllowedFor(url, allowInsecureLocal)
            if (security == CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT) {
                throw AiProviderException(
                    AiErrorType.AUTHENTICATION_FAILED,
                    "Credential transport requires a secure endpoint",
                    AiFailureDetails(recoverability = Recoverability.USER_ACTION_REQUIRED),
                )
            }
        }
        val body = buildRequestPayload(request, context, model, systemPrompt, false).toString().toRequestBody(json)
        val httpRequest = Request.Builder()
            .url(url)
            .apply {
                credentialHeader?.let { h ->
                    credentialHeaderValue?.let { v -> addHeader(h, v) }
                }
                addHeader("Content-Type", "application/json")
            }
            .post(body)
            .build()
        execute(httpRequest) { parseGenerateResponse(it, model, request.modelId) }
    }

    private suspend fun <T> execute(
        request: Request,
        parser: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw transportError(response.code, body, response.header("Retry-After"))
                }
                if (body.isBlank()) throw AiProviderException(AiErrorType.EMPTY_RESPONSE, "Remote provider returned empty body")
                parser(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiProviderException(AiErrorType.TIMEOUT, "Remote provider request timed out", e)
        } catch (e: UnknownHostException) {
            throw AiProviderException(AiErrorType.NETWORK_UNAVAILABLE, "Remote provider host unavailable", e)
        } catch (e: AiProviderException) {
            throw e
        } catch (e: Exception) {
            throw AiProviderException(AiErrorType.NETWORK_ERROR, "Remote provider request failed", e)
        }
    }

    /** Stream through the configured wire protocol. */
    fun stream(
        request: AiRequest,
        context: AiContext,
        model: String,
        credentialHeader: String?,
        credentialHeaderValue: String?,
        systemPrompt: String?,
        allowInsecureLocal: Boolean,
    ): Flow<AiStreamEvent> = flow {
        emit(AiStreamEvent.Started)
        val url = wireUrl("stream")
        if (credentialHeader != null && credentialHeaderValue != null) {
            val security = credentialsAllowedFor(url, allowInsecureLocal)
            if (security == CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT) {
                throw AiProviderException(
                    AiErrorType.AUTHENTICATION_FAILED,
                    "Credential transport requires a secure endpoint",
                    AiFailureDetails(recoverability = Recoverability.USER_ACTION_REQUIRED),
                )
            }
        }
        val body = buildRequestPayload(request, context, model, systemPrompt, true).toString().toRequestBody(json)
        val httpRequest = Request.Builder()
            .url(url)
            .apply {
                credentialHeader?.let { h ->
                    credentialHeaderValue?.let { v -> addHeader(h, v) }
                }
                addHeader("Content-Type", "application/json")
            }
            .post(body)
            .build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) throw transportError(response.code, response.body?.string(), response.header("Retry-After"))
                val answer = parseStreamEvents(response.body?.source(), { quotaSnapshot(it) }, { event: AiStreamEvent -> emit(event) })
                emit(AiStreamEvent.Completed(answer.copy(
                    modelId = answer.modelId.orEmpty().ifBlank { model },
                    requestedModelId = request.modelId,
                    resolvedModelId = answer.resolvedModelId ?: model,
                )))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiProviderException) {
            emit(AiStreamEvent.Failed(e))
        } catch (e: SocketTimeoutException) {
            emit(AiStreamEvent.Failed(AiProviderException(AiErrorType.TIMEOUT, "Remote stream timed out (model=$model)", e)))
        } catch (e: UnknownHostException) {
            emit(AiStreamEvent.Failed(AiProviderException(AiErrorType.NETWORK_UNAVAILABLE, "Remote stream: host unavailable", e)))
        } catch (e: Exception) {
            emit(AiStreamEvent.Failed(AiProviderException(AiErrorType.NETWORK_ERROR, "Remote stream failed", e)))
        }
    }.flowOn(Dispatchers.IO)

    private fun wireUrl(endpoint: String): String = when (protocol) {
        AiWireProtocol.OPENAI_CHAT_COMPLETIONS -> "$baseUrl/chat/completions"
        AiWireProtocol.OPENAI_RESPONSES -> "$baseUrl/responses"
        else -> throw UnsupportedOperationException("Wire protocol $protocol is not supported by the generic OpenAI-compatible transport")
    }

    private fun buildRequestPayload(request: AiRequest, context: AiContext, model: String, systemPrompt: String?, stream: Boolean): JSONObject {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("stream", stream)
        request.maxOutputTokens?.takeIf { it > 0 }?.let { max ->
            when (protocol) {
                AiWireProtocol.OPENAI_CHAT_COMPLETIONS -> payload.put("max_tokens", max)
                AiWireProtocol.OPENAI_RESPONSES -> payload.put("max_output_tokens", max)
                else -> Unit
            }
        }
        if (protocol == AiWireProtocol.OPENAI_RESPONSES) {
            val messages = JSONArray()
            val systemText = buildList {
                add(systemPrompt ?: "You are Halo, a helpful AI assistant.")
                context.conversation.filter { it.role == Role.SYSTEM }.mapTo(this) { it.content }
            }.filter { it.isNotBlank() }.joinToString("\n\n")
            if (systemText.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", systemText))
            }
            context.conversation.filterNot { it.role == Role.SYSTEM }.forEach { messages.put(it.messageToResponsesJson()) }
            context.screenContext?.let { messages.put(JSONObject().put("role", "user").put("content", it.asUntrustedPromptSection())) }
            request.text?.takeIf { request.appendTextAsUserMessage }?.let { text ->
                val content: Any = request.image?.let { image ->
                    JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", text))
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:${request.imageMimeType()};base64,${Base64.getEncoder().encodeToString(image)}"))
                        })
                    }
                } ?: text
                messages.put(JSONObject().put("role", "user").put("content", content))
            }
            payload.put("input", messages)
        } else {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt ?: "You are Halo, a helpful AI assistant."))
            context.conversation.forEach { messages.put(it.toChatCompletionsJson()) }
            context.screenContext?.let { messages.put(JSONObject().put("role", "user").put("content", it.asUntrustedPromptSection())) }
            request.text?.takeIf { request.appendTextAsUserMessage }?.let { text ->
                val content: Any = request.image?.let { image ->
                    JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", text))
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:${request.imageMimeType()};base64,${Base64.getEncoder().encodeToString(image)}"))
                        })
                    }
                } ?: text
                messages.put(JSONObject().put("role", "user").put("content", content))
            }
            payload.put("messages", messages)
        }
        if (request.toolUseMode != com.halo.sdk.provider.ToolUseMode.NONE && context.availableTools.isNotEmpty()) {
            payload.put("tool_choice", "auto")
            payload.put("tools", JSONArray().apply {
                context.availableTools.forEach { tool ->
                    put(JSONObject().apply {
                        put("type", "function")
                        val function = JSONObject().put("name", tool.id).put("description", tool.description).put("parameters", JSONObject(tool.parameters))
                        if (protocol == AiWireProtocol.OPENAI_RESPONSES) {
                            put("name", tool.id); put("description", tool.description); put("parameters", JSONObject(tool.parameters))
                        } else put("function", function)
                    })
                }
            })
        }
        return payload
    }

    private fun AIMessage.toChatCompletionsJson(): JSONObject = JSONObject()
        .put("role", role.name.lowercase())
        .put("content", content)
        .apply {
            toolCallId?.let { put("tool_call_id", it) }
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", JSONArray().apply {
                    toolCalls.forEach { call ->
                        put(JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", call.functionName)
                                put("arguments", JSONObject(call.arguments).toString())
                            })
                        })
                    }
                })
            }
        }

    private fun AIMessage.messageToResponsesJson(): JSONObject = JSONObject()
        .put("role", if (role == Role.ASSISTANT) "assistant" else if (role == Role.TOOL) "tool" else role.name.lowercase())
        .put("content", content)
        .apply {
            toolCallId?.let { put("tool_call_id", it) }
            if (toolCalls.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    toolCalls.forEach { call ->
                        put(JSONObject().apply {
                            put("tool_call", JSONObject().apply {
                                put("id", call.id)
                                put("tool_name", call.functionName)
                                put("input", JSONObject(call.arguments))
                            })
                        })
                    }
                })
            }
        }

    private fun parseGenerateResponse(body: String, model: String, requestedModelId: String?): AiResponse {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        if (error != null) throw streamError(error)
        val response = json.optJSONObject("response") ?: json
        return if (protocol == AiWireProtocol.OPENAI_RESPONSES) {
            val output = response.optJSONArray("output") ?: JSONArray()
            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "message" -> {
                        val content = item.optJSONArray("content") ?: JSONArray()
                        for (part in 0 until content.length()) {
                            val value = content.optJSONObject(part) ?: continue
                            if (value.optString("type") == "output_text") text.append(value.optString("text"))
                        }
                    }
                    "output_text" -> text.append(item.optString("text"))
                    "function_call" -> {
                        val callId = item.optString("call_id").takeIf { it.isNotBlank() }
                            ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Responses function call has no call id")
                        toolCalls += ToolCall(callId, item.getString("name"), JSONObject(item.getString("arguments")).toMap())
                    }
                    "text" -> {
                        val textObj = item.optJSONObject("text")
                        if (textObj != null) {
                            val parts = textObj.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val partObj = parts.optJSONObject(0)
                                if (partObj != null) {
                                    text.append(partObj.optString("text"))
                                }
                            }
                        }
                    }
                    "tool_call" -> {
                        val toolCall = item.optJSONObject("tool_call") ?: continue
                        val callId = toolCall.optString("id").takeIf { it.isNotBlank() }
                            ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Responses tool call has no id")
                        toolCalls += ToolCall(
                            callId,
                            toolCall.optString("tool_name"),
                            toolCall.optJSONObject("input")?.toMap() ?: emptyMap(),
                        )
                    }
                }
            }
            if (text.isEmpty() && toolCalls.isEmpty()) throw AiProviderException(AiErrorType.EMPTY_RESPONSE, "Remote provider returned an empty response")
            AiResponse(
                text = text.toString(),
                toolCalls = toolCalls,
                finishReason = response.optString("status").takeIf { it.isNotBlank() },
                providerId = json.optString("providerId", model),
                modelId = model,
                requestedModelId = requestedModelId,
                resolvedModelId = response.optString("model", model),
            )
        } else {
            val choices = json.optJSONArray("choices") ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote provider returned no choices/output")
            if (choices.length() == 0) throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote provider returned no choices")
            val first = choices.getJSONObject(0)
            val message = first.optJSONObject("message") ?: JSONObject()
            val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
            if (message.optString("content").isNullOrBlank() && toolCalls.isEmpty()) {
                throw AiProviderException(AiErrorType.EMPTY_RESPONSE, "Remote provider returned an empty response")
            }
            AiResponse(
                text = message.optString("content"),
                toolCalls = toolCalls,
                finishReason = first.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" },
                providerId = json.optString("providerId", model),
                modelId = model,
                requestedModelId = requestedModelId,
                resolvedModelId = json.optString("model", model),
            )
        }
    }

    private fun JSONObject.toMap(): Map<String, Any> = buildMap {
        keys().forEach { put(it, get(it)) }
    }

    private fun parseToolCalls(calls: JSONArray?): List<ToolCall> = buildList {
        if (calls == null) return@buildList
        for (i in 0 until calls.length()) {
            val tc = calls.getJSONObject(i)
            val fn = tc.optJSONObject("function") ?: tc.optJSONObject("tool_call") ?: continue
            val id = tc.optString("id").takeIf { it.isNotBlank() }
                ?: if (fn.has("id")) fn.getString("id") else throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote tool call has no id")
            val name = fn.optString("name").takeIf { it.isNotBlank() }
                ?: if (fn.has("tool_name")) fn.getString("tool_name") else throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote tool call has no name")
            add(ToolCall(id, name, fn.optJSONObject("arguments")?.toMap() ?: fn.optJSONObject("input")?.toMap() ?: emptyMap()))
        }
    }

    private suspend fun parseStreamEvents(source: BufferedSource?, quotaCollector: (ProviderQuotaSnapshot) -> Unit, emitEvent: suspend (AiStreamEvent) -> Unit): AiResponse {
        val text = StringBuilder()
        val tools = linkedMapOf<Int, StreamingToolCall>()
        var finish: String? = null
        var resolvedModel: String? = null
        var streamFinishedCleanly = false
        if (source != null) {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trimStart()
                if (data.isBlank()) continue
                if (data == "[DONE]") {
                    streamFinishedCleanly = true
                    break
                }
                val event = runCatching { JSONObject(data) }.getOrElse { throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote stream returned malformed SSE") }
                event.optJSONObject("error")?.let { throw streamError(it) }
                when (event.optString("type")) {
                    "error" -> throw streamError(event)
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotEmpty()) { text.append(delta); emitEvent(AiStreamEvent.Token(delta)) }
                        continue
                    }
                    "response.failed" -> throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote response failed")
                    "response.completed", "response.incomplete" -> {
                        val completed = event.optJSONObject("response")
                            ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote stream omitted final response")
                        val answer = parseGenerateResponse(completed.toString(), resolvedModel.orEmpty(), null)
                        quotaCollector(parseQuota(completed))
                        return answer
                    }
                }
                event.optString("model").takeIf { it.isNotBlank() }?.let { resolvedModel = it }
                event.optJSONObject("response")?.let {
                    it.optJSONArray("output")?.let { output ->
                        for (i in 0 until output.length()) {
                            val item = output.optJSONObject(i) ?: continue
                            when (item.optString("type")) {
                                "text" -> item.optJSONObject("text")?.optJSONArray("parts")?.let { parts ->
                                    if (parts.length() > 0) text.append(parts.optJSONObject(0)?.optString("text") ?: "")
                                }
                                "tool_call" -> {
                                    val tc = item.optJSONObject("tool_call") ?: continue
                                    val index = tc.optInt("index", tools.size)
                                    val assembled = tools.getOrPut(index) { StreamingToolCall() }
                                    tc.optString("id").takeIf { it.isNotBlank() }?.let { assembled.id = it }
                                    tc.optString("tool_name").takeIf { it.isNotBlank() }?.let { assembled.name = it }
                                    tc.optJSONObject("input")?.let { assembled.arguments.append(it.toString()) }
                                    emitEvent(AiStreamEvent.ToolCallDelta(index, tc.optString("tool_name"), null, tc.optString("id").takeIf { it.isNotBlank() }))
                                }
                            }
                        }
                    }
                    it.optString("status").takeIf { it.isNotBlank() }?.let { finish = it }
                }
                event.optJSONArray("choices")?.let { choices ->
                    if (choices.length() == 0) return@let
                    val choice = choices.getJSONObject(0)
                    choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
                    val delta = choice.optJSONObject("delta") ?: return@let
                    delta.optString("content")?.takeIf { it.isNotEmpty() }?.let { text.append(it); emitEvent(AiStreamEvent.Token(it)) }
                    delta.optJSONArray("tool_calls")?.let { calls ->
                        for (i in 0 until calls.length()) {
                            val tc = calls.getJSONObject(i)
                            val fn = tc.optJSONObject("function") ?: continue
                            val index = tc.optInt("index", tools.size)
                            val assembled = tools.getOrPut(index) { StreamingToolCall() }
                            tc.optString("id").takeIf { it.isNotBlank() }?.let { assembled.id = it }
                            fn.optString("name").takeIf { it.isNotBlank() }?.let { assembled.name = it }
                            fn.optString("arguments")?.let { assembled.arguments.append(it) }
                            emitEvent(AiStreamEvent.ToolCallDelta(index, fn.optString("name"), fn.optString("arguments"), tc.optString("id").takeIf { it.isNotBlank() }))
                        }
                    }
                }
                quotaCollector(parseQuota(event))
            }
        }
        if (!streamFinishedCleanly && finish == null) {
            throw AiProviderException(AiErrorType.NETWORK_ERROR, "Remote stream ended prematurely without finish or [DONE]")
        }
        val toolCalls = tools.entries.map { (index, call) ->
            val callId = call.id?.takeIf { it.isNotBlank() }
                ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote tool call $index has no id")
            val name = call.name?.takeIf { it.isNotBlank() }
                ?: throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote tool call $index has no name")
            ToolCall(callId, name, parseArguments(call.arguments.toString()))
        }
        return AiResponse(
            text = text.toString(),
            toolCalls = toolCalls,
            finishReason = finish,
            providerId = resolvedModel ?: "",
            modelId = resolvedModel ?: "",
            resolvedModelId = resolvedModel,
        )
    }

    private fun parseQuota(event: JSONObject): ProviderQuotaSnapshot {
        val headers = event.optJSONObject("headers") ?: JSONObject()
        return ProviderQuotaSnapshot(
            providerId = "",
            retrievedAtMs = nowMs(),
            requestLimit = headers.optInt("x-ratelimit-request-limit", null),
            requestRemaining = headers.optInt("x-ratelimit-request-remaining", null),
            tokenLimit = headers.optInt("x-ratelimit-token-limit", null),
            tokenRemaining = headers.optInt("x-ratelimit-token-remaining", null),
            resetAtMs = headers.optLong("x-ratelimit-reset", null)?.takeIf { it > 0L },
        )
    }

    private fun parseRetryAfter(event: org.json.JSONObject): Long? {
        val headers = event.optJSONObject("headers") ?: return null
        val header = headers.optString("retry-after", null)
        if (header != null) {
            header.toDoubleOrNull()?.let { return (it * 1000.0).toLong().takeIf { it > 0 } }
        }
        return null
    }

    private fun transportError(code: Int, body: String?, retryAfterHeader: String?): AiProviderException {
        val normalizedBody = body?.trim().orEmpty()
        val detail = runCatching { JSONObject(normalizedBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } }.getOrNull()
        val providerCode = runCatching { JSONObject(normalizedBody).optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() } }.getOrNull()
        val retryAfterMs = if (code == 429) {
            retryAfterHeader?.toDoubleOrNull()?.let { (it * 1000.0).toLong().takeIf { it > 0 } }
                ?: runCatching { JSONObject(normalizedBody).optJSONObject("error")?.optLong("retry_after_ms")?.takeIf { it > 0 } }.getOrNull()
        } else null
        val type = when (code) {
            400 -> if (ContextOverflow.matches(providerCode, detail)) AiErrorType.CONTEXT_TOO_LARGE else AiErrorType.INVALID_REQUEST
            401, 403 -> AiErrorType.AUTHENTICATION_FAILED
            402 -> AiErrorType.PROVIDER_UNAVAILABLE
            408 -> AiErrorType.TIMEOUT
            409 -> AiErrorType.PROVIDER_UNAVAILABLE
            429 -> AiErrorType.RATE_LIMITED
            500, 502, 503, 504 -> AiErrorType.PROVIDER_UNAVAILABLE
            else -> AiErrorType.NETWORK_ERROR
        }
        return AiProviderException(
            type = type,
            message = "Remote provider API error $code",
            details = AiFailureDetails(
                retryAfterMs = retryAfterMs,
                providerCode = safeProviderErrorCode(providerCode),
                httpStatus = code,
                recoverability = when (type) {
                    AiErrorType.AUTHENTICATION_FAILED -> Recoverability.USER_ACTION_REQUIRED
                    AiErrorType.RATE_LIMITED -> retryAfterMs?.let { Recoverability.RETRY_AFTER } ?: Recoverability.UNKNOWN
                    AiErrorType.PROVIDER_UNAVAILABLE, AiErrorType.TIMEOUT -> Recoverability.RETRY_IMMEDIATELY
                    AiErrorType.INVALID_REQUEST, AiErrorType.CONTEXT_TOO_LARGE -> Recoverability.PERMANENT
                    else -> Recoverability.UNKNOWN
                },
            ),
        )
    }

    private fun streamError(error: JSONObject): AiProviderException {
        val message = error.optString("message").takeIf { it.isNotBlank() } ?: "Remote stream error"
        val providerCode = error.opt("code")?.toString()?.takeIf { it.isNotBlank() }
        val numericCode = providerCode?.toIntOrNull()
        val type = when {
            ContextOverflow.matches(providerCode, message) -> AiErrorType.CONTEXT_TOO_LARGE
            numericCode == 401 || numericCode == 403 -> AiErrorType.AUTHENTICATION_FAILED
            numericCode == 408 -> AiErrorType.TIMEOUT
            numericCode == 429 -> AiErrorType.RATE_LIMITED
            numericCode in setOf(402, 409, 500, 502, 503, 504) -> AiErrorType.PROVIDER_UNAVAILABLE
            numericCode != null && numericCode in 400..499 -> AiErrorType.INVALID_REQUEST
            else -> AiErrorType.PROVIDER_UNAVAILABLE
        }
        return AiProviderException(
            type = type,
            message = "Remote stream error",
            details = AiFailureDetails(
                providerCode = safeProviderErrorCode(providerCode),
                httpStatus = numericCode,
                recoverability = when (type) {
                    AiErrorType.AUTHENTICATION_FAILED -> Recoverability.USER_ACTION_REQUIRED
                    AiErrorType.RATE_LIMITED -> Recoverability.UNKNOWN
                    AiErrorType.INVALID_REQUEST, AiErrorType.CONTEXT_TOO_LARGE -> Recoverability.PERMANENT
                    else -> Recoverability.RETRY_IMMEDIATELY
                },
            ),
        )
    }

    private fun parseModelList(body: String, freeModelIds: Set<String>): List<AiModel> {
        val data = JSONObject(body).getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i) ?: continue
                val id = item.optString("id").trim().ifEmpty { continue }
                val name = item.optString("name", id)
                val owned = item.optJSONObject("owner")?.optString("id")?.takeIf { it.isNotBlank() }?.toString()
                val modelId = if (!owned.isNullOrBlank()) "$owned/$id" else id
                val architecture = item.optJSONObject("architecture")
                val modalities = architecture?.optJSONArray("input_modalities")?.let { arr ->
                    buildSet {
                        for (j in 0 until arr.length()) add(arr.optString(j).lowercase())
                    }
                }?.takeIf { it.isNotEmpty() }
                val pricing = item.optJSONObject("pricing")
                val isFree = freeModelIds.contains(modelId) ||
                    (pricing != null && pricing.optString("prompt", "") == "0" && pricing.optString("completion", "") == "0")
                add(
                    AiModel(
                        id = modelId,
                        displayName = name,
                        providerId = owned ?: "",
                        inputCapabilities = buildSet {
                            add(AiCapability.TEXT)
                            if (modalities?.contains("image") == true) add(AiCapability.IMAGE)
                            if (modalities?.contains("audio") == true) add(AiCapability.AUDIO)
                            if (modalities?.contains("video") == true) add(AiCapability.VIDEO)
                        },
                        contextLength = item.optLong("context_length").takeIf { it > 0L },
                        supportsStreaming = true,
                        supportsTools = architecture?.optJSONArray("supported_parameters")?.let { arr ->
                            var tools = false
                            for (j in 0 until arr.length()) {
                                if (arr.optString(j) == "tools") {
                                    tools = true
                                    break
                                }
                            }
                            tools
                        } ?: false,
                        isFree = isFree,
                    ),
                )
            }
        }.sortedWith(compareBy<AiModel> { it.displayName.lowercase() })
    }

    private fun parseArguments(json: String): Map<String, Any> = try {
        org.json.JSONObject(json).toMap()
    } catch (e: Exception) {
        throw AiProviderException(AiErrorType.INVALID_RESPONSE, "Remote provider returned invalid tool arguments", e)
    }

    private fun org.json.JSONObject.optLong(key: String, defaultValue: Long?): Long? {
        return try {
            if (has(key)) getLong(key) else defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun org.json.JSONObject.optInt(key: String, defaultValue: Int?): Int? {
        return try {
            if (has(key)) getInt(key) else defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private class StreamingToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}

private fun recoverabilityForUnknownHost(url: String): Recoverability =
    if (url.startsWith("http://")) Recoverability.USER_ACTION_REQUIRED else Recoverability.RETRY_IMMEDIATELY
