package com.halo.sdk.provider

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Assembles incremental [AiStreamEvent.ToolCallDelta] events into complete [ToolCall]s.
 */
class StreamingToolCallAssembler {
    private class InProgressCall(
        var id: String? = null,
        var name: String = "",
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    private val callsByIndex = mutableMapOf<Int, InProgressCall>()

    fun appendDelta(delta: AiStreamEvent.ToolCallDelta) {
        val call = callsByIndex.getOrPut(delta.index) { InProgressCall() }
        delta.id?.takeIf { it.isNotBlank() }?.let { call.id = it }
        delta.name?.let { call.name = it }
        delta.argumentsDelta?.let { call.argumentsBuilder.append(it) }
    }

    fun assemble(requestId: String = "stream"): List<ToolCall> {
        if (callsByIndex.isEmpty()) return emptyList()
        return callsByIndex.entries.sortedBy { it.key }.map { entry ->
            val call = entry.value
            val callId = call.id?.takeIf { it.isNotBlank() }
                ?: throw StreamToolCallException(entry.key, "missing provider tool-call id")
            val functionName = call.name.takeIf { it.isNotBlank() }
                ?: throw StreamToolCallException(entry.key, "missing function name")
            val rawArgs = call.argumentsBuilder.toString().trim()
            val argsMap = parseJsonMap(entry.key, rawArgs)

            ToolCall(
                id = callId,
                functionName = functionName,
                arguments = argsMap
            )
        }
    }

    fun clear() {
        callsByIndex.clear()
    }

    private fun parseJsonMap(index: Int, raw: String): Map<String, Any> {
        if (raw.isBlank()) return emptyMap()
        return try {
            argumentsAdapter.fromJson(raw)
                ?: throw StreamToolCallException(index, "arguments decoded to null")
        } catch (error: StreamToolCallException) {
            throw error
        } catch (error: Exception) {
            throw StreamToolCallException(index, "arguments are not a complete JSON object", error)
        }
    }

    fun hasDeltas(): Boolean = callsByIndex.isNotEmpty()

    companion object {
        private val argumentsAdapter = Moshi.Builder().build().adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
    }
}

class StreamToolCallException(
    val toolCallIndex: Int,
    detail: String,
    cause: Throwable? = null,
) : IllegalArgumentException("Invalid streamed tool call at index $toolCallIndex: $detail", cause)
