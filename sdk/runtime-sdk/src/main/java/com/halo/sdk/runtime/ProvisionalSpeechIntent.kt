package com.halo.sdk.runtime

/** Advisory parser result only. It cannot contain arguments, authority or a tool invocation. */
data class ProvisionalSpeechIntent(val intentKind: String?,val confidence: Float,val ambiguous: Boolean=false)
data class SpeechProvisionalIntentUpdated(val captureId: String,val revision: Int,val hint: ProvisionalSpeechIntent?,
    val finalInputReceived: Boolean=false,override val id: String=java.util.UUID.randomUUID().toString(),
    override val timestamp: Long=System.currentTimeMillis()): HaloEvent
