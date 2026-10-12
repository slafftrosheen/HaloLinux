package com.halo.host.linux

import com.halo.runtime.events.DefaultEventBus
import com.halo.runtime.kernel.resource.DefaultResourceLeaseManager
import com.halo.sdk.runtime.CommandBus
import com.halo.sdk.runtime.EngineContext
import com.halo.sdk.runtime.EventBus
import com.halo.sdk.runtime.Query
import com.halo.sdk.runtime.QueryBus
import com.halo.sdk.runtime.RuntimeCommand
import com.halo.sdk.runtime.RuntimeContext
import com.halo.sdk.runtime.RuntimeLogger
import com.halo.sdk.runtime.RuntimeScheduler
import com.halo.sdk.runtime.ServiceKey
import com.halo.sdk.runtime.ServiceRegistry
import com.halo.sdk.runtime.resource.ResourceLeaseManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Linux composition for currently portable runtime infrastructure.
 * Query/command execution is deliberately fail-closed until the canonical
 * cognition/tool executors have been wired; no command disappears silently.
 */
class LinuxRuntimeBridge {
    private val services = LocalServiceRegistry()
    private val events = DefaultEventBus()
    private val resources = DefaultResourceLeaseManager()
    val engine = HaloNodeApplication()

    private val runtime = object : RuntimeContext {
        override val eventBus: EventBus = events
        override val commandBus: CommandBus = object : CommandBus {
            override suspend fun dispatch(command: RuntimeCommand): Nothing =
                throw UnsupportedOperationException("Linux host command execution is not yet wired")
            override fun commands(): Flow<RuntimeCommand> = emptyFlow()
        }
        override val queryBus: QueryBus = object : QueryBus {
            override suspend fun <R> execute(query: Query<R>): R =
                throw UnsupportedOperationException("Linux host query execution is not yet wired")
        }
        override val scheduler: RuntimeScheduler = object : RuntimeScheduler {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val compute: CoroutineDispatcher = Dispatchers.Default
            override val realtime: CoroutineDispatcher = Dispatchers.Default
            // No UI dispatcher on a headless service. A GUI is not an Android Main dispatcher.
            override val ui: CoroutineDispatcher = Dispatchers.Default
        }
        override val registry: ServiceRegistry = services
        override val logger: RuntimeLogger = object : RuntimeLogger {
            private val log = LoggerFactory.getLogger("halo.linux.runtime")
            override fun trace(category: String, message: String) = log.trace("{}: {}", category, message)
            override fun debug(category: String, message: String) = log.debug("{}: {}", category, message)
            override fun info(category: String, message: String) = log.info("{}: {}", category, message)
            override fun warn(category: String, message: String) = log.warn("{}: {}", category, message)
            override fun error(category: String, message: String, throwable: Throwable?) =
                log.error("{}: {}", category, message, throwable)
        }
    }

    suspend fun start() {
        services.register(ServiceKey(EventBus::class), events)
        services.register(ServiceKey(ResourceLeaseManager::class), resources)
        engine.initialize(EngineContext(services, runtime))
        engine.start()
    }

    suspend fun stop() {
        engine.stop()
        engine.dispose()
    }

    fun registeredResources(): ResourceLeaseManager = resources
}

private class LocalServiceRegistry : ServiceRegistry {
    private val entries = ConcurrentHashMap<ServiceKey<*>, Any>()
    override fun <T : Any> register(key: ServiceKey<T>, service: T) {
        check(entries.putIfAbsent(key, service) == null) { "Duplicate service: ${key.name}" }
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> resolve(key: ServiceKey<T>): T =
        (entries[key] ?: throw NoSuchElementException("Service not registered: ${key.name}")) as T
}
