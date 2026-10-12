package com.halo.sdk.runtime.embodiment.heycyan

/**
 * Semantic device operations for HeyCyan glasses.
 */
sealed interface HeyCyanOperation {
    data object GetBattery : HeyCyanOperation
    data object GetDeviceInfo : HeyCyanOperation
    data object GetMediaInventory : HeyCyanOperation
    data object TakePhoto : HeyCyanOperation
    data object StartVideo : HeyCyanOperation
    data object StopVideo : HeyCyanOperation
    data object StartRecording : HeyCyanOperation
    data object StopRecording : HeyCyanOperation
    /** Prepare the glasses' voice path; this is distinct from selecting speaker output. */
    data object PrepareVoiceRouting : HeyCyanOperation
    /** Select the glasses as the playback target for speech/audio. */
    data object RouteAudioToGlasses : HeyCyanOperation
    /** Select the phone as the playback target for speech/audio. */
    data object RouteAudioToPhone : HeyCyanOperation
    /** Command the glasses to enable Classic Bluetooth audio connection (0x49 / 02 01). */
    data object EnableClassicBluetooth : HeyCyanOperation
    data object GenerateAIPhoto : HeyCyanOperation
    data object EnterWifiTransferMode : HeyCyanOperation
    data object ExitWifiTransferMode : HeyCyanOperation
    data object SynchronizeMedia : HeyCyanOperation
}
