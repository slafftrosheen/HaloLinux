package com.halo.sdk.runtime

sealed interface HaloIntent

data class OpenApplicationIntent(val packageName: String) : HaloIntent
data class SearchIntent(val query: String) : HaloIntent
data class CallContactIntent(val contact: String) : HaloIntent
data class SendMessageIntent(val recipient: String, val message: String) : HaloIntent
object UnknownIntent : HaloIntent
