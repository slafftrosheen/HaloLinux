package com.halo.sdk.runtime

data class RecognitionQuality(
    val audioDurationMs: Long? = null,
    val peakAmplitude: Int? = null,
    val voicedDurationMs: Long? = null,
    val voicedRatio: Float? = null,
    val averageTokenLogProbability: Float? = null,
    val segmentCount: Int? = null,
    val longestSegmentMs: Long? = null,
    val qualityEstimator: String? = null
)
