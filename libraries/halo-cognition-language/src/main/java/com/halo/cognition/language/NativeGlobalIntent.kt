package com.halo.cognition.language

enum class NativeGlobalAction(val wireId: String, val phrases: List<String>) {
    RECENTS("recents", listOf("open recent apps", "atver nesenās lietotnes", "открой недавние приложения")),
    NOTIFICATIONS("notifications", listOf("open notification shade", "atver paziņojumu paneli", "открой шторку уведомлений")),
    QUICK_SETTINGS("quick_settings", listOf("open quick settings", "atver ātros iestatījumus", "открой быстрые настройки")),
    LOCK_SCREEN("lock_screen", listOf("lock the screen", "bloķē ekrānu", "заблокируй экран")),
    SCREENSHOT("take_screenshot", listOf("take a screenshot", "uzņem ekrānuzņēmumu", "сделай скриншот")),
    SPLIT_SCREEN("toggle_split_screen", listOf("toggle split screen", "pārslēdz dalīto ekrānu", "переключи разделённый экран"));
}
data class NativeGlobalIntent(val action: NativeGlobalAction) : NativeControlIntent
