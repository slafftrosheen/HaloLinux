package com.halo.cognition.language

enum class NativeObservationKind(val wireId: String, val phrases: List<String>) {
    LOCATION("location", listOf("show my current location", "parādi manu pašreizējo atrašanās vietu", "покажи моё текущее местоположение")),
    LIGHT("ambient_light", listOf("read ambient light sensor", "nolasi apkārtējās gaismas sensoru", "считай датчик освещённости")),
    PROXIMITY("proximity", listOf("read proximity sensor", "nolasi tuvuma sensoru", "считай датчик приближения")),
    ACCELEROMETER("accelerometer", listOf("read accelerometer", "nolasi akselerometru", "считай акселерометр")),
    NETWORK("network_quality", listOf("show network quality", "parādi tīkla kvalitāti", "покажи качество сети"));
}
data class NativeObservationIntent(val kind: NativeObservationKind) : NativeControlIntent
