package com.halo.sdk.provider

/** Image output is separate from vision input and never routes through a text completion. */
data class AiImageModel(val id: String, val displayName: String = id, val supportsEditing: Boolean = true,
    val requiresReference: Boolean = false)
data class AiImageRequest(val modelId: String, val prompt: String, val reference: ByteArray? = null, val referenceMime: String = "image/png")
data class AiImageResult(val bytes: ByteArray, val mimeType: String, val providerId: String, val modelId: String)
interface AiImageProvider : AiInferenceProvider {
    suspend fun listImageModels(): List<AiImageModel>
    suspend fun generateImage(request: AiImageRequest): AiImageResult
}
