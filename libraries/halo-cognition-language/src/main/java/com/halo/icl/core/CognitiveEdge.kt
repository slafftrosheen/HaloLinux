package com.halo.icl.core

enum class EdgeType {
    Caused,
    Created,
    Depends,
    Derived,
    Supports,
    Contradicts,
    Explains,
    Related
}

data class CognitiveEdge(
    val targetId: CognitiveId,
    val type: EdgeType
)
