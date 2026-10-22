package com.halo.cognition.language

/** Explicit commands only: no fuzzy matches against quoted messages or arbitrary intent actions. */
internal object NativeSettingsPhrases {
    private val destinations = mapOf(
        "general" to listOf("atver iestatījumus", "открой настройки"),
        "wifi" to listOf("atver wifi iestatījumus", "открой настройки wifi", "открой настройки wi-fi"),
        "bluetooth" to listOf("atver bluetooth iestatījumus", "открой настройки bluetooth"),
        "internet" to listOf("atver interneta paneli", "открой панель интернета"),
        "airplane" to listOf("atver lidmašīnas režīma iestatījumus", "открой настройки режима полёта"),
        "mobile_network" to listOf("open mobile network settings", "atver mobilā tīkla iestatījumus", "открой настройки мобильной сети"),
        "sim" to listOf("open sim settings", "atver sim iestatījumus", "открой настройки sim"),
        "hotspot" to listOf("open hotspot settings", "atver tīklāja iestatījumus", "открой настройки точки доступа"),
        "location" to listOf("atver atrašanās vietas iestatījumus", "открой настройки местоположения"),
        "nfc" to listOf("atver nfc iestatījumus", "открой настройки nfc"),
        "volume" to listOf("atver skaļuma paneli", "открой панель громкости"),
        "sound" to listOf("atver skaņas iestatījumus", "открой настройки звука"),
        "display" to listOf("atver ekrāna iestatījumus", "открой настройки экрана"),
        "brightness" to listOf("open brightness settings", "atver spilgtuma iestatījumus", "открой настройки яркости"),
        "auto_rotate" to listOf("open auto rotate settings", "atver automātiskās pagriešanas iestatījumus", "открой настройки автоповорота"),
        "accessibility" to listOf("atver pieejamības iestatījumus", "открой настройки специальных возможностей"),
        "keyboard" to listOf("atver tastatūras iestatījumus", "открой настройки клавиатуры"),
        "language" to listOf("atver valodas iestatījumus", "открой настройки языка"),
        "app_language" to listOf("atver lietotnes valodas iestatījumus", "открой настройки языка приложения"),
        "date_time" to listOf("atver datuma un laika iestatījumus", "открой настройки даты и времени"),
        "battery" to listOf("atver akumulatora iestatījumus", "открой настройки батареи"),
        "battery_optimization" to listOf("open battery optimization settings", "atver akumulatora optimizācijas iestatījumus", "открой настройки оптимизации батареи"),
        "storage" to listOf("atver krātuves iestatījumus", "открой настройки хранилища"),
        "data_usage" to listOf("atver datu lietojuma iestatījumus", "открой настройки использования данных"),
        "vpn" to listOf("atver vpn iestatījumus", "открой настройки vpn"),
        "privacy" to listOf("atver privātuma iestatījumus", "открой настройки конфиденциальности"),
        "security" to listOf("atver drošības iestatījumus", "открой настройки безопасности"),
        "biometric_enrollment" to listOf("open biometric enrollment", "atver biometrijas reģistrāciju", "открой регистрацию биометрии"),
        "accounts" to listOf("open accounts settings", "atver kontu iestatījumus", "открой настройки аккаунтов"),
        "add_account" to listOf("open add account", "atver konta pievienošanu", "открой добавление аккаунта"),
        "default_apps" to listOf("atver noklusējuma lietotnes", "открой приложения по умолчанию"),
        "app" to listOf("atver halo lietotnes informāciju", "открой сведения о halo"),
        "notifications" to listOf("atver halo paziņojumu iestatījumus", "открой настройки уведомлений halo"),
        "notification_access" to listOf("atver paziņojumu piekļuves iestatījumus", "открой доступ к уведомлениям"),
        "write_settings" to listOf("open write settings access", "atver sistēmas iestatījumu rakstīšanas atļauju", "открой разрешение изменения настроек"),
        "overlay" to listOf("atver pārklājuma atļauju", "открой разрешение наложения"),
        "usage_access" to listOf("open usage access settings", "atver lietojuma piekļuvi", "открой доступ к статистике использования"),
        "unknown_sources" to listOf("atver nezināmo avotu iestatījumus", "открой настройки неизвестных источников"),
        "dnd_access" to listOf("open do not disturb access", "atver netraucēšanas piekļuvi", "открой доступ к режиму не беспокоить"),
        "cast" to listOf("atver apraides iestatījumus", "открой настройки трансляции"),
        "developer" to listOf("atver izstrādātāja iestatījumus", "открой настройки разработчика")
    )
    fun parse(command: String): String? = destinations.entries.firstOrNull { command in it.value }?.key
}
