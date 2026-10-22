package com.halo.icl.core

import kotlinx.datetime.Instant
import kotlinx.datetime.Clock

interface CognitiveObject {
    val id: CognitiveId
    val created: Instant
    val confidence: Confidence
    val provenance: Provenance
    val edges: List<CognitiveEdge>
}
