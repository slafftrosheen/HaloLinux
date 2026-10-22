package com.halo.cognition.language

enum class NativeTargetAction(val wireId: String) { APP_INFO("app_info"), APP_NOTIFICATIONS("app_notifications"), UNINSTALL("uninstall"), CALL("call") }
data class NativeTargetIntent(val action: NativeTargetAction, val target: String) : NativeControlIntent
data class NativeMapDirectionsIntent(val destination: String, val mode: String) : NativeControlIntent

internal object NativeTargetParser {
    fun parse(raw: String): NativeControlIntent? {
        val lower = raw.lowercase(java.util.Locale.ROOT)
        val actions = mapOf(
            NativeTargetAction.APP_INFO to listOf("open app info for ", "atver lietotnes informāciju par ", "открой сведения о приложении "),
            NativeTargetAction.APP_NOTIFICATIONS to listOf("open notification settings for ", "atver paziņojumu iestatījumus lietotnei ", "открой настройки уведомлений приложения "),
            NativeTargetAction.UNINSTALL to listOf("uninstall ", "atinstalē ", "удали приложение "),
            NativeTargetAction.CALL to listOf("place a call to ", "veic zvanu uz ", "позвони на номер "))
        for ((action, prefixes) in actions) for (prefix in prefixes) if (lower.startsWith(prefix)) {
            var target = raw.substring(prefix.length).trim()
            if (action == NativeTargetAction.CALL) {
                if (!target.matches(Regex("[+0-9 ()-]{3,40}"))) return NativeClarificationIntent("Specify an exact dialable number.")
                target = target.replace(Regex("[ ()-]"), "")
                if (!target.matches(Regex("\\+?[0-9]{3,20}"))) return NativeClarificationIntent("Specify an exact dialable number.")
            }
            return if (target.isNotBlank() && target.length <= 150) NativeTargetIntent(action, target)
                else NativeClarificationIntent("Specify an exact application or phone number.")
        }
        val directions = mapOf(
            "driving" to listOf("driving directions to ", "braukšanas norādes uz ", "маршрут на машине до "),
            "walking" to listOf("walking directions to ", "iešanas norādes uz ", "пеший маршрут до "),
            "transit" to listOf("transit directions to ", "sabiedriskā transporta norādes uz ", "маршрут общественным транспортом до "),
            "bicycling" to listOf("cycling directions to ", "velobraukšanas norādes uz ", "велосипедный маршрут до "))
        for ((mode, prefixes) in directions) for (prefix in prefixes) if (lower.startsWith(prefix)) {
            val destination = raw.substring(prefix.length).trim()
            return if (destination.isNotBlank() && destination.length <= 500) NativeMapDirectionsIntent(destination, mode)
                else NativeClarificationIntent("Specify a destination.")
        }
        return null
    }
}
