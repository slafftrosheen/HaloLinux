package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Thought(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val content: String // Semantic interpretation of an Observation
) : CognitiveObject
