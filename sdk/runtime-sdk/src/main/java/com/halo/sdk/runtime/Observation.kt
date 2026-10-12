package com.halo.sdk.runtime

import java.util.UUID
import com.halo.sdk.host.ObservationSource

@JvmInline
value class ObservationId(val value: String)

enum class Modality {
    AUDIO, VIDEO, IMAGE, TEXT, SENSOR, NETWORK, SYSTEM, UNKNOWN
}

enum class Source {
    MICROPHONE, CAMERA, BLUETOOTH, GPS, OS, USER, SYSTEM, ROBOT
}

enum class Freshness {
    FRESH, STALE, UNKNOWN
}

/** Stable physical source identity. Kept separate from modality and legacy Source. */
data class ObservationOrigin(
    val source: ObservationSource,
    val hostId: String? = null
)

interface ObservationPayload

data class Observation(
    val id: ObservationId = ObservationId(UUID.randomUUID().toString()),
    val modality: Modality,
    val source: Source,
    val payload: ObservationPayload,
    val origin: ObservationOrigin? = null,
    val sourceDeviceId: String? = null,
    val freshness: Freshness = Freshness.UNKNOWN,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap(),
    val interactionId: String = UUID.randomUUID().toString()
)

data class ObservationCreated(
    override val id: String = UUID.randomUUID().toString(),
    val observation: Observation,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class SpeechObservationPayload(
    val transcript: String,
    val language: String
) : ObservationPayload

data class ImageObservationPayload(
    val imageBytes: ByteArray = byteArrayOf(),
    val mimeType: String = "image/jpeg",
    val mediaRef: com.halo.sdk.runtime.multimodal.MediaAssetRef? = null,
) : ObservationPayload {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageObservationPayload) return false
        if (mediaRef != other.mediaRef) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (mimeType != other.mimeType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + (mediaRef?.hashCode() ?: 0)
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

data class AudioObservationPayload(
    val energy: Float?,
    val isSpeechActive: Boolean
) : ObservationPayload

data class RangeObservationPayload(
    val distanceMm: Int,
    val valid: Boolean
) : ObservationPayload

data class SafetyObservationPayload(
    val state: Int,
    val fault: Int,
    val estopped: Boolean,
    val faulted: Boolean
) : ObservationPayload

data class BatteryObservationPayload(
    val levelPercent: Int
) : ObservationPayload

data class MotionObservationPayload(
    val available: Boolean,
    val active: Boolean,
    val mode: Int
) : ObservationPayload

data class ImuObservationPayload(
    val available: Boolean,
    val valid: Boolean,
    val accelXG: Float,
    val accelYG: Float,
    val accelZG: Float
) : ObservationPayload

/**
 * Typed payload used for runtime/system observations that are primarily
 * represented by [Observation.metadata]. Keeping the payload concrete makes
 * replay serialization safe while preserving the metadata contract.
 */
data class SystemObservationPayload(
    val event: String,
) : ObservationPayload
