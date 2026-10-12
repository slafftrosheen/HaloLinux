package com.halo.sdk.runtime.multimodal

enum class VisualSource { HEYCYAN_CAMERA, PHONE_CAMERA, SCREEN, EXPLICIT_REFERENCE }
enum class VisualOrigin { HEYCYAN, ANDROID_ASSISTANT, PHONE }
data class VisualSourceResolution(val source: VisualSource?, val reason: String, val failure: MediaFailure? = null)

class VisualSourceResolver {
    fun resolve(origin: VisualOrigin, hasExplicitReference: Boolean, screenRequested: Boolean, worldViewRequired: Boolean,
        available: Set<VisualSource>, screenPolicyAllows: Boolean): VisualSourceResolution {
        val selected = when {
            hasExplicitReference -> VisualSource.EXPLICIT_REFERENCE
            origin == VisualOrigin.HEYCYAN -> VisualSource.HEYCYAN_CAMERA
            origin == VisualOrigin.ANDROID_ASSISTANT && screenRequested -> VisualSource.SCREEN
            worldViewRequired -> VisualSource.PHONE_CAMERA
            else -> return VisualSourceResolution(null, "No explicit visual requirement", MediaFailure.SOURCE_UNAVAILABLE)
        }
        if (selected == VisualSource.SCREEN && !screenPolicyAllows) return VisualSourceResolution(null, "Screen policy blocked", MediaFailure.POLICY_BLOCKED)
        if (selected !in available) return VisualSourceResolution(null, "Origin source unavailable", MediaFailure.SOURCE_UNAVAILABLE)
        return VisualSourceResolution(selected, "Explicit reference or interaction origin")
    }
}

enum class MediaSharingDecision { ALLOWED_LOCAL, ALLOWED_REMOTE, BLOCKED_BY_POLICY, BLOCKED_BY_OBJECT_POLICY }
fun mediaSharingDecision(isLocal: Boolean, includeMedia: Boolean, allowRemoteMedia: Boolean, objectAllowsAi: Boolean = true, objectAllowsRemote: Boolean = true): MediaSharingDecision = when {
    !objectAllowsAi || (!isLocal && !objectAllowsRemote) -> MediaSharingDecision.BLOCKED_BY_OBJECT_POLICY
    !includeMedia || (!isLocal && !allowRemoteMedia) -> MediaSharingDecision.BLOCKED_BY_POLICY
    isLocal -> MediaSharingDecision.ALLOWED_LOCAL
    else -> MediaSharingDecision.ALLOWED_REMOTE
}
