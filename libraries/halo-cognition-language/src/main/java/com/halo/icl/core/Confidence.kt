package com.halo.icl.core

@JvmInline
value class Confidence(val value: Float) {
    init {
        require(value in 0.0f..1.0f) { "Confidence must be between 0.0 and 1.0" }
    }

    operator fun times(other: Confidence): Confidence {
        return Confidence(this.value * other.value)
    }
}
