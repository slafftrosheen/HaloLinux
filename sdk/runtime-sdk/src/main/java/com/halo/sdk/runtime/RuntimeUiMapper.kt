package com.halo.sdk.runtime

interface RuntimeUiMapper<UiState> {
    fun map(snapshot: RuntimeSnapshot): UiState
}
