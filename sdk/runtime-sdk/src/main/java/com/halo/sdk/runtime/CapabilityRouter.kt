package com.halo.sdk.runtime

import com.halo.icl.ontology.Task
import com.halo.icl.ontology.Outcome
import com.halo.sdk.host.WearableHost

interface CapabilityRouter {
    suspend fun route(
        task: Task,
        host: WearableHost
    ): Outcome
}
