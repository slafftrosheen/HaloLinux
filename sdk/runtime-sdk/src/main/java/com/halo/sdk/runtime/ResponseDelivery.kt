package com.halo.sdk.runtime

import java.util.UUID

/**
 * Presentation contract for one completed Halo response.
 *
 * The input transaction owns this decision. Presentation code must not infer
 * whether a response should speak from UI state or from the next event in a
 * global stream.
 */
enum class ResponseDelivery {
    VISUAL_ONLY,
    SPEECH_AND_VISUAL
}

fun Source.defaultResponseDelivery(): ResponseDelivery = when (this) {
    Source.MICROPHONE -> ResponseDelivery.SPEECH_AND_VISUAL
    else -> ResponseDelivery.VISUAL_ONLY
}

data class AssistantResponsePublished(
    override val id: String = UUID.randomUUID().toString(),
    val text: String,
    val delivery: ResponseDelivery,
    val transactionId: String,
    val interactionId: String? = null,
    override val timestamp: Long = System.currentTimeMillis(),
    val conversationId: String? = null,
    /** Text was delivered but a durable tool run still waits for review or input. */
    val continuationPending: Boolean = false,
) : HaloEvent
