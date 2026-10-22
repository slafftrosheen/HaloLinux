package com.halo.sdk.provider

/** Independent of conversation generation and role routing. No index or executor is implied. */
interface EmbeddingProvider {
    val id: String
    suspend fun embed(request: EmbeddingRequest): EmbeddingBatch
}
data class EmbeddingRequest(val texts: List<String>, val modelSha256: String, val dimension: Int,
    val preprocessingIdentity: String = "text-special-tokens-v1") {
    init {
        require(texts.isNotEmpty() && texts.size <= 32 && texts.all { it.isNotBlank() && it.length <= 32_768 })
        require(modelSha256.matches(Regex("[a-f0-9]{64}")))
        require(dimension in 1..16_384)
        require(preprocessingIdentity in setOf("text-special-tokens-v1", "embeddinggemma2-search-asymmetric-v1"))
    }
}
data class EmbeddingSpace(val modelSha256: String, val dimension: Int, val normalized: Boolean,
    val runtimeIdentity: String? = null, val preprocessingIdentity: String? = null) {
    init {
        require(modelSha256.matches(Regex("[a-f0-9]{64}")))
        require(dimension in 1..16_384)
    }
}
/** A consumer must compare the complete space identity before combining or reusing vectors. */
class EmbeddingBatch(val space: EmbeddingSpace, vectors: List<List<Float>>, val createdAtMs: Long) {
    val vectors: List<List<Float>> = vectors.map { it.toList() }
    init {
        require(vectors.isNotEmpty() && vectors.size <= 32)
        vectors.forEach { vector ->
            require(vector.size == space.dimension && vector.all { it.isFinite() })
            if (space.normalized) {
                val squaredNorm = vector.sumOf { it.toDouble() * it.toDouble() }
                require(kotlin.math.abs(squaredNorm - 1.0) <= .001)
            }
        }
    }
    fun requireCompatible(expected: EmbeddingSpace) { require(space == expected) { "Embedding space changed; invalidate and reindex" } }
}
