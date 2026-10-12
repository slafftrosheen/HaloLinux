package com.halo.sdk.provider

/** Content-free identity of the currently resolved local configuration. No inventory refresh. */
interface LocalInferenceExecutionReporting {
    fun localExecutionIdentity(modelId: String): String?
    fun localExecutionBackend(modelId: String): String? = null
}
