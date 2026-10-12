package com.halo.sdk.runtime

import kotlinx.coroutines.CoroutineDispatcher

interface RuntimeScheduler {
    val io: CoroutineDispatcher
    val compute: CoroutineDispatcher
    val realtime: CoroutineDispatcher
    val ui: CoroutineDispatcher
}
