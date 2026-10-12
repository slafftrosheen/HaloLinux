package com.halo.sdk.runtime

import kotlinx.coroutines.flow.StateFlow

interface MetricsProvider {
    val metrics: StateFlow<EngineMetrics>
}
