package com.halo.sdk.runtime

enum class ExecutionMode {
    LIVE,
    SIMULATION,
    REPLAY
}

interface RuntimeContext {
    val eventBus: EventBus
    val commandBus: CommandBus
    val queryBus: QueryBus
    val scheduler: RuntimeScheduler
    val registry: ServiceRegistry
    val logger: RuntimeLogger
    val clock: RuntimeClock
        get() = SystemRuntimeClock()
    val powerMode: RuntimePowerMode
        get() = RuntimePowerMode.BALANCED
    val metrics: RuntimeMetricsSink
        get() = NoopRuntimeMetrics
    val executionMode: ExecutionMode
        get() = ExecutionMode.LIVE
}
