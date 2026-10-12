package com.halo.sdk.runtime

import kotlinx.coroutines.flow.StateFlow

interface RuntimeSnapshotSource {
    val snapshot: StateFlow<RuntimeSnapshot>
}
