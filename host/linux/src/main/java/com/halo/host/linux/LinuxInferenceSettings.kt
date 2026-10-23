package com.halo.host.linux

/** Strict, independently testable inference configuration; no network or credentials. */
internal data class LinuxInferenceSettings(
    val endpoint: String?,
    val model: String?,
    val allowRemote: Boolean,
) {
    companion object {
        fun from(values: Map<*, *>): LinuxInferenceSettings {
            fun optionalText(name: String): String? {
                val value = values[name] ?: return null
                require(value is String) { "Inference configuration fields must be strings" }
                return value.trim().takeIf(String::isNotBlank)
            }
            val endpoint = optionalText("endpoint")
            val model = optionalText("model")
            val consent = if (values.containsKey("allow_remote")) {
                val value = values["allow_remote"]
                require(value is Boolean) { "inference.allow_remote must be a boolean" }
                value
            } else false
            require((endpoint == null) == (model == null)) {
                "Inference endpoint and model must both be set, or both omitted"
            }
            require(model == null || model.length <= 256) { "Invalid model identifier" }
            if (endpoint != null) LinuxInferenceEndpoint.parse(endpoint)
            return LinuxInferenceSettings(endpoint, model, consent)
        }
    }
}
