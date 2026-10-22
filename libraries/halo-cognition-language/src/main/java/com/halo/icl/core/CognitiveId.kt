package com.halo.icl.core

import java.util.UUID

@JvmInline
value class CognitiveId(val value: UUID = UUID.randomUUID()) {
    override fun toString(): String = value.toString()
}
