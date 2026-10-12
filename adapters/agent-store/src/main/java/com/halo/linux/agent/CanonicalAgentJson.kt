package com.halo.linux.agent

import com.halo.cognition.language.InteractionId
import com.halo.runtime.cognition.agent.*
import com.halo.runtime.cognition.tools.ToolId
import com.halo.sdk.runtime.SpeechOutputFallback
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deliberately narrow, versioned codec for REAL canonical AgentRun/AgentStep
 * types. An unknown version or step MUST fail before an SQL transaction commits.
 * Never use Java object serialization or class-name-driven polymorphism here.
 *
 * v1 supports text turns, response commit, recovery claims and pending human
 * input. Tool execution evidence is intentionally NOT supported until Linux
 * has a verified tool policy, replay discipline and full canonical codec.
 */
internal object CanonicalAgentJson {
    const val VERSION = 1
    private fun JSONObject.nullString(name: String): String? =
        if (isNull(name)) null else getString(name)
    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)
    private fun JSONObject.optionalBoolean(name: String): Boolean? =
        if (!has(name) || isNull(name)) null else getBoolean(name)
    private fun JSONObject.instant(name: String): Instant? =
        optionalString(name)?.let(Instant::parse)
    private fun JSONObject.arrayStrings(name: String): Set<String> =
        getJSONArray(name).let { array -> (0 until array.length()).map(array::getString).toSet() }
    private fun putStrings(values: Set<String>): JSONArray = JSONArray(values.sorted())

    private fun requireVersion(j: JSONObject) {
        require(j.getInt("v") == VERSION) { "Unsupported canonical agent JSON schema" }
    }
    private fun envelope(type: String, id: String, runId: String, instant: Instant) =
        JSONObject().put("v", VERSION).put("type", type).put("id", id)
            .put("runId", runId).put("createdAt", instant.toString())

    fun encodeRun(run: AgentRun): String {
        // Do not discard host-specific metadata that this codec cannot reconstruct.
        require(run.origin == null && run.speechOutputTarget == null &&
            run.speechOutputFallback == SpeechOutputFallback.DISALLOW_PHONE &&
            run.sourceCaptureId == null) {
            "Unsupported origin/audio metadata in Linux agent run codec"
        }
        return JSONObject().put("v", VERSION)
            .put("id", run.id.value).put("interactionId", run.interactionId.value)
            .put("request", run.request).put("state", run.state.name)
            .put("createdAt", run.createdAt.toString()).put("updatedAt", run.updatedAt.toString())
            .put("startedAt", run.startedAt?.toString())
            .put("completedAt", run.completedAt?.toString())
            .put("currentStepId", run.currentStepId?.value)
            .put("failureReason", run.failureReason)
            .put("parentRunId", run.parentRunId?.value)
            .put("rootRunId", run.rootRunId?.value)
            .put("delegatedRole", run.delegatedRole)
            .put("delegationDepth", run.delegationDepth)
            .put("pinnedProviderId", run.pinnedProviderId)
            .put("pinnedModelId", run.pinnedModelId)
            .put("pinnedTargetLocal", run.pinnedTargetLocal)
            .put("delegatedInputRefs", run.delegatedInputRefs)
            .toString()
    }

    fun decodeRun(raw: String, steps: List<AgentStep>): AgentRun {
        val j = JSONObject(raw)
        requireVersion(j)
        require(j.getString("id").isNotBlank())
        return AgentRun(
            id = AgentRunId(j.getString("id")),
            interactionId = InteractionId(j.getString("interactionId")),
            request = j.getString("request"),
            state = AgentRunState.valueOf(j.getString("state")),
            createdAt = Instant.parse(j.getString("createdAt")),
            updatedAt = Instant.parse(j.getString("updatedAt")),
            startedAt = j.instant("startedAt"),
            completedAt = j.instant("completedAt"),
            currentStepId = j.optionalString("currentStepId")?.let(::AgentStepId),
            failureReason = j.optionalString("failureReason"),
            steps = steps,
            parentRunId = j.optionalString("parentRunId")?.let(::AgentRunId),
            rootRunId = j.optionalString("rootRunId")?.let(::AgentRunId),
            delegatedRole = j.optionalString("delegatedRole"),
            delegationDepth = j.getInt("delegationDepth"),
            pinnedProviderId = j.optionalString("pinnedProviderId"),
            pinnedModelId = j.optionalString("pinnedModelId"),
            pinnedTargetLocal = j.optionalBoolean("pinnedTargetLocal"),
            delegatedInputRefs = j.optionalString("delegatedInputRefs"),
        )
    }

    fun encodeStep(step: AgentStep): String {
        val j = envelope(step.javaClass.simpleName, step.id.value,
            step.runId.value, step.createdAt)
        when (step) {
            is AgentStep.RunPrepared -> {
                require(step.responseDelivery == null) {
                    "Response delivery metadata needs a canonical codec before persistence"
                }
                j.put("kind", step.kind.name).put("recoveryPolicy", step.recoveryPolicy.name)
                    .put("conversationId", step.conversationId)
            }
            is AgentStep.ProviderTurnStarted -> {
                j.put("turnId", step.turnId.value).put("providerId", step.providerId)
                    .put("modelId", step.modelId)
                    .put("requestIdentity", step.requestIdentity)
                    .put("exposedToolIds", putStrings(step.exposedToolIds))
                    .put("requiredCapabilities", putStrings(step.requiredCapabilities))
                    .put("contextRequirements", putStrings(step.contextRequirements))
                    .put("toolUseMode", step.toolUseMode).put("workloadKind", step.workloadKind)
            }
            is AgentStep.ProviderTurnCompleted -> {
                require(step.toolCalls.isEmpty()) {
                    "Tool-producing provider responses are not recoverable by the Linux text-only adapter"
                }
                j.put("turnId", step.turnId.value).put("assistantText", step.assistantText)
                    .put("finishReason", step.finishReason).put("payloadRefId", step.payloadRefId)
                    .put("toolCalls", JSONArray())
            }
            is AgentStep.ResponseProduced -> {
                j.put("responseId", step.responseId).put("text", step.text)
                    .put("payloadRefId", step.payloadRefId)
            }
            is AgentStep.ResponseCommitted ->
                j.put("commitId", step.commitId).put("interactionId", step.interactionId)
            is AgentStep.RecoveryClaimed ->
                j.put("planKind", step.planKind).put("evidenceStepId", step.evidenceStepId)
            is AgentStep.InputRequested -> {
                j.put("requestId", step.requestId.value).put("input", encodeInput(step.input))
            }
            is AgentStep.InputResolved ->
                j.put("requestId", step.requestId.value).put("resolution", step.resolution)
            else -> throw UnsupportedOperationException(
                "Canonical agent step ${step.javaClass.simpleName} not supported on Linux"
            )
        }
        return j.toString()
    }

    fun decodeStep(raw: String): AgentStep {
        val j = JSONObject(raw)
        requireVersion(j)
        val id = AgentStepId(j.getString("id"))
        val runId = AgentRunId(j.getString("runId"))
        val created = Instant.parse(j.getString("createdAt"))
        return when (j.getString("type")) {
            "RunPrepared" -> AgentStep.RunPrepared(
                id, runId, created, AgentRunKind.valueOf(j.getString("kind")),
                RunRecoveryPolicy.valueOf(j.getString("recoveryPolicy")),
                j.optionalString("conversationId"),
            )
            "ProviderTurnStarted" -> AgentStep.ProviderTurnStarted(
                id, runId, created, ProviderTurnId(j.getString("turnId")),
                j.getString("providerId"), j.optionalString("modelId"),
                j.getString("requestIdentity"), j.arrayStrings("exposedToolIds"),
                j.arrayStrings("requiredCapabilities"), j.arrayStrings("contextRequirements"),
                j.optionalString("toolUseMode"), j.optionalString("workloadKind")
            )
            "ProviderTurnCompleted" -> {
                require(j.getJSONArray("toolCalls").length() == 0) {
                    "Tool response decoding is not enabled on Linux"
                }
                AgentStep.ProviderTurnCompleted(
                    id, runId, created, ProviderTurnId(j.getString("turnId")),
                    j.getString("assistantText"), emptyList(),
                    j.getString("finishReason"), j.optionalString("payloadRefId")
                )
            }
            "ResponseProduced" -> AgentStep.ResponseProduced(
                id, runId, created, j.getString("responseId"),
                j.getString("text"), j.optionalString("payloadRefId")
            )
            "ResponseCommitted" -> AgentStep.ResponseCommitted(
                id, runId, created, j.getString("commitId"), j.getString("interactionId")
            )
            "RecoveryClaimed" -> AgentStep.RecoveryClaimed(
                id, runId, created, j.getString("planKind"), j.optionalString("evidenceStepId")
            )
            "InputRequested" -> AgentStep.InputRequested(
                id, runId, created, AgentInputRequestId(j.getString("requestId")),
                decodeInput(j.getJSONObject("input"))
            )
            "InputResolved" -> AgentStep.InputResolved(
                id, runId, created, AgentInputRequestId(j.getString("requestId")),
                j.getString("resolution")
            )
            else -> throw UnsupportedOperationException(
                "Unknown agent journal step: ${j.getString("type")}"
            )
        }
    }

    private fun encodeInput(input: AgentInputRequest): JSONObject {
        val j = JSONObject().put("id", input.id.value).put("runId", input.runId.value)
            .put("createdAt", input.createdAt.toString())
            .put("expiresAt", input.expiresAt?.toString())
        return when (input) {
            is AgentInputRequest.Approval ->
                j.put("type", "Approval").put("invocationId", input.invocationId.value)
                    .put("toolId", input.toolId.value).put("action", input.action)
                    .put("detail", input.detail).put("risk", input.risk)
                    .put("argumentsDigest", input.argumentsDigest)
                    .put("speechPrompt", input.speechPrompt)
            is AgentInputRequest.Clarification -> {
                val expected = input.expected
                require(expected is ExpectedInput.Text && !expected.sensitive) {
                    "Only non-sensitive text clarifications are supported on this Linux host"
                }
                j.put("type", "Clarification").put("question", input.question)
                    .put("field", input.field).put("speechPrompt", input.speechPrompt)
                    .put("placeholder", expected.placeholder)
                    .put("initialValue", expected.initialValue)
                    .put("minLength", expected.minLength).put("maxLength", expected.maxLength)
                    .put("singleLine", expected.singleLine)
            }
        }
    }

    private fun decodeInput(j: JSONObject): AgentInputRequest {
        val id = AgentInputRequestId(j.getString("id"))
        val run = AgentRunId(j.getString("runId"))
        val created = Instant.parse(j.getString("createdAt"))
        val expiry = j.instant("expiresAt")
        return when (j.getString("type")) {
            "Approval" -> AgentInputRequest.Approval(
                id, run, created, expiry, ToolInvocationId(j.getString("invocationId")),
                ToolId(j.getString("toolId")), j.getString("action"),
                j.optionalString("detail"), j.getString("risk"),
                j.getString("argumentsDigest"), j.optionalString("speechPrompt"),
            )
            "Clarification" -> AgentInputRequest.Clarification(
                id, run, created, expiry, j.getString("question"), j.getString("field"),
                ExpectedInput.Text(
                    placeholder = j.optionalString("placeholder"),
                    initialValue = j.optionalString("initialValue"),
                    minLength = j.getInt("minLength"),
                    maxLength = j.getInt("maxLength"),
                    singleLine = j.getBoolean("singleLine"),
                ),
                j.optionalString("speechPrompt")
            )
            else -> throw UnsupportedOperationException("Unknown pending-input journal type")
        }
    }
}
