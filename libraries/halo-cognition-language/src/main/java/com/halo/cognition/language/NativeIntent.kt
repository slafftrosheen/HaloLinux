package com.halo.cognition.language

/** Semantic intent with resolved slots; no Android or provider dependency. */
sealed interface NativeControlIntent : Intent
data class NativeClarificationIntent(val reason: String) : NativeControlIntent

data class TimerIntent(val seconds: Int) : NativeControlIntent
/** Official AlarmClock actions; no inferred alarm/timer state. */
data class NativeClockIntent(val action: String) : NativeControlIntent
/** User-reviewable external editor handoffs: dispatch never means saved. */
data class NativeContactEditorIntent(val mode: String) : NativeControlIntent
data object NativeCalendarEditorIntent : NativeControlIntent
/** Exact Halo-owned pages, never an arbitrary Android package or deep link. */
data class NativeHaloScreenIntent(val screen: String) : NativeControlIntent
data class VolumeIntent(val percent: Int? = null, val direction: String? = null) : NativeControlIntent
data class FlashlightIntent(val enabled: Boolean) : NativeControlIntent
data class RingerIntent(val mode: String) : NativeControlIntent
data class BrightnessIntent(val percent: Int) : NativeControlIntent
data class OpenSettingsIntent(val section: String) : NativeControlIntent
data class ShareIntent(val text: String) : NativeControlIntent
data object ClipboardReadIntent : NativeControlIntent
data class ClipboardWriteIntent(val text: String) : NativeControlIntent
data object DeviceInfoIntent : NativeControlIntent
data object BatteryStatusIntent : NativeControlIntent
data object NetworkStatusIntent : NativeControlIntent

object NativeCommandParser {
    fun parse(text: String): NativeControlIntent? {
        val raw = ConversationSemantics.commandText(text)
        val command = raw.replace(Regex("[\\s\\u00A0]+"), " ").lowercase(java.util.Locale.ROOT).removePrefix("please ")
        if (ConversationSemantics.isExplanation(raw) || ConversationSemantics.answerFromModel(raw)) return null
        NativeDestination.parse(command)?.let { return NativeDestinationIntent(it) }
        NativeGlobalAction.entries.firstOrNull { command in it.phrases }?.let { return NativeGlobalIntent(it) }
        NativeObservationKind.entries.firstOrNull { command in it.phrases }?.let { return NativeObservationIntent(it) }
        NativeGoogleMailParser.parse(raw)?.let { return it }
        NativeTargetParser.parse(raw)?.let { return it }
        NativeSettingsPhrases.parse(command)?.let { return OpenSettingsIntent(it) }
        val clock = when (command) {
            "show alarms", "show my alarms", "open alarms", "open my alarms" -> "show_alarms"
            "show timers", "show my timers", "open timers", "open my timers" -> "show_timers"
            "dismiss next alarm", "dismiss the next alarm" -> "dismiss_next_alarm"
            "snooze alarm", "snooze the alarm", "snooze my alarm" -> "snooze_alarm"
            "dismiss expired timers", "dismiss all expired timers" -> "dismiss_expired_timers"
            else -> null
        }
        if (clock != null) return NativeClockIntent(clock)
        if (command in setOf("create contact", "create a contact", "create new contact",
                "add contact", "add a contact", "add new contact", "open contact editor"))
            return NativeContactEditorIntent("create")
        if (command in setOf("edit contact", "edit a contact", "edit existing contact",
                "edit a saved contact", "open contact picker"))
            return NativeContactEditorIntent("choose_or_edit")
        if (command in setOf("create calendar event", "create a calendar event",
                "add calendar event", "add a calendar event", "open calendar event editor"))
            return NativeCalendarEditorIntent
        val haloPage = when (command) {
            "open halo", "open halo home", "open halo home screen" -> "home"
            "open notebook", "open my notebook", "open halo notebook", "show my notebook" -> "notebook"
            "open study", "open halo study", "open study page" -> "study"
            "open halo devices", "open devices in halo" -> "devices"
            "open halo settings", "open halo preferences" -> "settings"
            "open halo activity", "open my halo activity" -> "activity"
            "open halo skills", "open skills in halo" -> "skills"
            "open halo system check" -> "system_check"
            else -> null
        }
        if (haloPage != null) return NativeHaloScreenIntent(haloPage)
        val settingsDestination = when (command) {
            "open default apps", "open default apps settings" -> "default_apps"
            "open airplane mode settings" -> "airplane"
            "open data usage settings" -> "data_usage"
            "open storage settings" -> "storage"
            "open vpn settings" -> "vpn"
            "open nfc settings" -> "nfc"
            "open cast settings" -> "cast"
            "open language settings" -> "language"
            "open keyboard settings" -> "keyboard"
            "open date and time settings", "open date settings" -> "date_time"
            "open developer options", "open developer settings" -> "developer"
            "open notification access settings" -> "notification_access"
            "open app info", "open halo app info" -> "app"
            "open overlay settings" -> "overlay"
            "open unknown sources settings" -> "unknown_sources"
            "open app language settings" -> "app_language"
            "open app links settings" -> "open_by_default"
            "open battery saver settings" -> "battery"
            "open volume settings" -> "volume"
            "open notifications settings" -> "notifications"
            "open wifi panel", "open wi-fi panel" -> "wifi_panel"
            else -> null
        }
        if (settingsDestination != null) return OpenSettingsIntent(settingsDestination)
        AppCommandParser.parse(raw)?.let { return it as? NativeControlIntent }
        if (Regex("\\b(and then|then|and)\\b").containsMatchIn(command)) return null
        Regex("(?:set|start) (?:a )?timer (?:for )?([a-z\\d -]+) (seconds?|minutes?|hours?)").matchEntire(command)?.let {
            val amount = number(it.groupValues[1]) ?: return NativeClarificationIntent("Specify a timer duration from 1 second to 24 hours.")
            val multiplier = when { it.groupValues[2].startsWith("hour") -> 3600; it.groupValues[2].startsWith("minute") -> 60; else -> 1 }
            val seconds = amount.toLong() * multiplier
            return seconds.takeIf { v -> v in 1..86400 }?.let { v -> TimerIntent(v.toInt()) } ?: NativeClarificationIntent("Specify a timer duration from 1 second to 24 hours.")
        }
        Regex("(?:set )?(brightness|volume)(?: to)? ([a-z\\d -]+)(?:%| percent)").matchEntire(command)?.let {
            val value = number(it.groupValues[2]) ?: return NativeClarificationIntent("Specify a percentage from 1 to 100 for brightness, or 0 to 100 for volume.")
            return (if (it.groupValues[1] == "brightness") value.takeIf { v -> v in 1..100 }?.let(::BrightnessIntent)
                else value.takeIf { v -> v in 0..100 }?.let { v -> VolumeIntent(percent = v) }) ?: NativeClarificationIntent("Specify a valid percentage.")
        }
        if (command in setOf("set a timer", "start a timer", "set timer", "start timer")) return NativeClarificationIntent("How long should the timer run?")
        if (command in setOf("volume up", "raise the volume", "increase volume")) return VolumeIntent(direction = "up")
        if (command in setOf("volume down", "lower the volume", "lower volume", "decrease volume")) return VolumeIntent(direction = "down")
        Regex("(?:turn|switch) (?:the )?(flashlight|torch) (on|off)|(?:turn|switch) (on|off) (?:the )?(flashlight|torch)").matchEntire(command)?.let {
            return FlashlightIntent((it.groupValues[2].ifEmpty { it.groupValues[3] }) == "on")
        }
        if (command in setOf("mute the phone", "mute my phone", "set silent mode", "silence my phone")) return RingerIntent("silent")
        if (command in setOf("set vibrate mode", "vibrate the phone")) return RingerIntent("vibrate")
        if (command in setOf("unmute the phone", "set normal mode", "enable ringing")) return RingerIntent("normal")
        Regex("open (bluetooth|wifi|wi-fi|internet|display|sound|battery|location|accessibility|privacy|security) settings").matchEntire(command)?.let {
            return OpenSettingsIntent(it.groupValues[1].replace("wi-fi", "wifi"))
        }
        if (command == "open settings") return OpenSettingsIntent("general")
        if (command in setOf("read clipboard", "read the clipboard", "show clipboard", "what is on my clipboard")) return ClipboardReadIntent
        Regex("(?:copy to clipboard|copy) (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return it.groupValues[1].takeIf { value -> value.length <= 8000 }?.let(::ClipboardWriteIntent) }
        Regex("share (.+)", RegexOption.IGNORE_CASE).matchEntire(raw)?.let { return it.groupValues[1].takeIf { value -> value.length <= 8000 }?.let(::ShareIntent) }
        if (command in setOf("show device info", "show device information", "device information")) return DeviceInfoIntent
        if (command in setOf("show battery level", "show my battery level", "check battery", "battery status", "what is my battery level")) return BatteryStatusIntent
        if (command in setOf("show network status", "network status", "check network")) return NetworkStatusIntent
        return null
    }
    private fun number(value: String): Int? {
        val normalized = value.trim().replace('-', ' ')
        normalized.toIntOrNull()?.let { return it }
        val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        units.indexOf(normalized).takeIf { it >= 0 }?.let { return it }
        if (normalized in setOf("a", "an")) return 1
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "one hundred" to 100)
        tens[normalized]?.let { return it }
        val parts = normalized.split(' ')
        return if (parts.size == 2 && parts[0] in tens && units.indexOf(parts[1]) in 1..9) tens.getValue(parts[0]) + units.indexOf(parts[1]) else null
    }
}
