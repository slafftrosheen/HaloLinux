package com.halo.sdk.runtime.multimodal

enum class MediaKind { IMAGE, AUDIO, VIDEO }
enum class MediaProvenance { PHONE_CAMERA, HEYCYAN_CAMERA, SHARED_FILE, WORKSPACE, SCREEN_CAPTURE, AUDIO_CAPTURE, IMPORTED, GENERATED, UNKNOWN }
enum class MediaLifetime { EPHEMERAL_INTERACTION, WORKSPACE_PERSISTED, USER_LIBRARY }

/** Portable identity only. Host paths, URIs and payloads never belong in durable context. */
data class MediaAssetRef(
    val id: String,
    val kind: MediaKind,
    val mimeType: String,
    val bytes: Long?,
    val sha256: String?,
    val createdAtMs: Long,
    val provenance: MediaProvenance,
    val lifetime: MediaLifetime = MediaLifetime.EPHEMERAL_INTERACTION,
    val interactionId: String? = null,
    val sourceDeviceId: String? = null,
    val workspaceObjectId: String? = null,
)

data class AudioAssetMetadata(val durationMs: Long, val sampleRateHz: Int, val channels: Int, val encoding: String)
data class MediaAssetMetadata(val ref: MediaAssetRef, val width: Int? = null, val height: Int? = null, val audio: AudioAssetMetadata? = null)
class MediaAssetContent(val bytes: ByteArray, val mimeType: String)
interface MediaAssetResolver {
    suspend fun metadata(ref: MediaAssetRef): MediaAssetMetadata?
    suspend fun open(ref: MediaAssetRef): MediaAssetContent
}
interface MediaAssetStore : MediaAssetResolver {
    suspend fun ingest(content: MediaAssetContent, provenance: MediaProvenance, interactionId: String, sourceDeviceId: String? = null, workspaceObjectId: String? = null): MediaAssetRef
    suspend fun transferOwnership(ref: MediaAssetRef, interactionId: String): MediaAssetRef
    suspend fun releaseInteraction(interactionId: String)
}

fun interface MediaObjectPolicyResolver {
    suspend fun permits(ref: MediaAssetRef, isLocal: Boolean): Boolean
}

enum class MediaFailure {
    SOURCE_UNAVAILABLE, SOURCE_BUSY, SOURCE_DISCONNECTED, PERMISSION_REQUIRED,
    POLICY_BLOCKED, CAPTURE_TIMEOUT, TRANSFER_FAILED, UNSUPPORTED_FORMAT,
    TOO_LARGE, DECODE_FAILED, VISION_MODEL_UNAVAILABLE, AUDIO_MODEL_UNAVAILABLE,
}
class MediaAcquisitionException(val failure: MediaFailure, cause: Throwable? = null) :
    Exception(failure.userMessage(), cause)

fun MediaFailure.userMessage(): String = when (this) {
    MediaFailure.SOURCE_UNAVAILABLE -> "Visual source unavailable."
    MediaFailure.SOURCE_BUSY -> "Camera is busy."
    MediaFailure.SOURCE_DISCONNECTED -> "Capture device disconnected."
    MediaFailure.PERMISSION_REQUIRED -> "Media permission required."
    MediaFailure.POLICY_BLOCKED -> "Media sharing blocked by privacy policy."
    MediaFailure.CAPTURE_TIMEOUT -> "Capture timed out."
    MediaFailure.TRANSFER_FAILED -> "Media transfer failed."
    MediaFailure.UNSUPPORTED_FORMAT -> "Unsupported media format."
    MediaFailure.TOO_LARGE -> "Media exceeds the input budget."
    MediaFailure.DECODE_FAILED -> "Media could not be decoded."
    MediaFailure.VISION_MODEL_UNAVAILABLE -> "Vision model unavailable."
    MediaFailure.AUDIO_MODEL_UNAVAILABLE -> "Audio transcription model unavailable."
}

data class MultimodalBudget(
    val imageCount: Int = 1,
    val totalImageBytes: Long = 4 * 1024 * 1024,
    val audioDurationMs: Long = 30_000,
    val audioBytes: Long = 8 * 1024 * 1024,
) {
    init { require(imageCount >= 0 && totalImageBytes >= 0 && audioDurationMs >= 0 && audioBytes >= 0) }
}

data class ImageInputLimits(
    val maxPixels: Long = 1_048_576,
    val maxWidth: Int = 1280,
    val maxHeight: Int = 1280,
    val maxBytes: Int = 4 * 1024 * 1024,
    val maxSourceBytes: Int = 20 * 1024 * 1024,
    val preferredMimeTypes: List<String> = listOf("image/jpeg", "image/png"),
) {
    init { require(maxPixels > 0 && maxWidth > 0 && maxHeight > 0 && maxBytes > 0 && maxSourceBytes > 0) }
}
data class ImagePreparationSummary(
    val sourceRef: MediaAssetRef?, val sourceWidth: Int, val sourceHeight: Int,
    val outputWidth: Int, val outputHeight: Int, val sourceBytes: Int, val outputBytes: Int,
    val mimeType: String, val transformed: Boolean, val transformationReason: String,
)
class PreparedImageInput(val content: MediaAssetContent, val summary: ImagePreparationSummary)
interface ImageInputPreparer {
    suspend fun prepare(content: MediaAssetContent, sourceRef: MediaAssetRef? = null, limits: ImageInputLimits = ImageInputLimits()): PreparedImageInput
}
