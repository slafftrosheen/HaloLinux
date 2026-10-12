package com.halo.sdk.runtime

import java.util.UUID

enum class InvocationSource { ANDROID_ASSISTANT, HALO_UI, WIDGET, HARDWARE_BUTTON, WEARABLE, SHARE }

data class HostInvocation(
    val interactionId: String,
    val source: InvocationSource,
    val invokedAtMs: Long,
    val inputDeviceId: Int? = null,
    val requestedAssistContext: Boolean,
    val requestedScreenshot: Boolean,
)

data class RectValue(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class UiSemanticType {
    TEXT, BUTTON, INPUT, CHECKBOX, SWITCH, TAB, CHIP, TOOLBAR, DIALOG,
    IMAGE, LIST, LIST_ITEM, SCROLL_CONTAINER, LINK, CONTAINER, UNKNOWN
}

/**
 * Where a node sits on the screen, as a third of the display rather than pixel geometry.
 * Pixel bounds are evidence for Halo; a third is what a model can actually reason about.
 */
enum class UiScreenRegion { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

/**
 * Whether a captured projection carries anything a model can reason about.
 * A tree that yields no labelled or interactive node is unreadable in practice
 * (a blank screen, or a non-native surface such as a canvas or WebView).
 */
enum class UiInspectability { INSPECTABLE, EMPTY_OR_ROOT_ONLY, NO_SEMANTIC_CONTENT }

data class ScreenContextNode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: RectValue?,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    /** Opaque, snapshot-scoped reference. Models must not invent or parse it. */
    val token: String? = null,
    val semanticType: UiSemanticType = UiSemanticType.UNKNOWN,
    val depth: Int = 0,
    /** True when content was intentionally withheld from model-visible context. */
    val sensitive: Boolean = false,
    /** Coarse on-screen position; null when the node has no usable bounds or the display size is unknown. */
    val region: UiScreenRegion? = null,
    /**
     * Descendants of this node that were pruned as off-screen and only counted.
     * Bounded, redacted previews of the first few live in [hiddenChildPreviews],
     * so a model can decide to scroll instead of assuming the list ended.
     */
    val hiddenChildCount: Int = 0,
    val hiddenChildPreviews: List<String> = emptyList(),
    /** Cross-generation comparison only; never authorizes an action. */
    val stableFingerprint: String = "",
    val visibleChildKeys: List<String> = emptyList(),
    val rowIndex: Int? = null,
    val valueFullyExposed: Boolean = false,
    /** Platform field hint, distinct from the editable value. */
    val labelHint: String? = null,
    val visibleRowIndices: List<Int> = emptyList(),
    val longClickable: Boolean = false,
)

typealias UiSemanticNode = ScreenContextNode
typealias UiSemanticSnapshot = ScreenContextSnapshot

data class ScreenContextSnapshot(
    val packageName: String?,
    val activityName: String?,
    val title: String?,
    val webUri: String?,
    val nodes: List<ScreenContextNode>,
    val capturedAtMs: Long,
    val provenance: String = "ANDROID_ASSIST",
    val trust: ContextTrust = ContextTrust.UNTRUSTED_CONTENT,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val truncated: Boolean = false,
    val truncationReason: String? = null,
    /** Content-free counts explaining which nodes were omitted from the projection. */
    val omissionCounts: Map<String, Int> = emptyMap(),
    /** Correlates opaque node tokens with exactly one observation generation. */
    val snapshotId: String = UUID.randomUUID().toString(),
    /** Whether this projection is worth acting on at all; see [UiInspectability]. */
    val inspectability: UiInspectability = UiInspectability.INSPECTABLE,
    /** Accessibility actionable observation generation; -1 denotes a non-actionable projection. */
    val uiRevision: Long = -1L,
    val navigationFingerprint: String? = null,
)

enum class ContextTrust { TRUSTED_RUNTIME, UNTRUSTED_CONTENT }

data class AssistantInvocationStarted(
    val invocation: HostInvocation,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = invocation.invokedAtMs,
) : HaloEvent

data class ScreenContextCaptured(
    val interactionId: String,
    val snapshot: ScreenContextSnapshot,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = snapshot.capturedAtMs,
) : HaloEvent

data class AssistantInvocationEnded(
    val interactionId: String,
    val reason: String,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent
