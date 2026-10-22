package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Observation(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val rawData: Any, // e.g., Audio payload, JSON vision payload
    val modality: String // "Speech", "Vision", "System"
) : CognitiveObject
