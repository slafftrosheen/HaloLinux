package com.halo.cognition.language

/** Closed semantic destinations. Android action/component strings never cross this boundary. */
enum class NativeDestination(val wireId: String, val phrases: List<String>) {
    CAMERA("camera", listOf("open camera", "open photo camera", "atver kameru", "открой камеру")),
    VIDEO_CAMERA("video_camera", listOf("open video camera", "atver video kameru", "открой видеокамеру")),
    GALLERY("gallery", listOf("open gallery", "atver galeriju", "открой галерею")),
    BROWSER("browser", listOf("open browser", "atver pārlūku", "открой браузер")),
    MUSIC("music", listOf("open music app", "atver mūzikas lietotni", "открой музыкальное приложение")),
    REMINDERS("reminders", listOf("open reminder app", "open google tasks", "atver atgādinājumu lietotni", "atver google tasks", "открой приложение напоминаний", "открой google tasks")),
    CALL_LOG("call_log", listOf("open call log", "show call history", "atver zvanu žurnālu", "parādi zvanu vēsturi", "открой журнал звонков", "покажи историю звонков")),
    CONTACT_PICKER("contact_picker", listOf("pick a contact", "choose a contact", "izvēlies kontaktu", "выбери контакт")),
    DOCUMENT_PICKER("document_picker", listOf("pick a document", "import a document", "izvēlies dokumentu", "выбери документ")),
    DOCUMENTS_PICKER("documents_picker", listOf("pick multiple documents", "izvēlies vairākus dokumentus", "выбери несколько документов")),
    CONTENT_PICKER("content_picker", listOf("import a file", "importē failu", "импортируй файл")),
    FOLDER_PICKER("folder_picker", listOf("pick a folder", "choose a folder", "izvēlies mapi", "выбери папку")),
    PHOTO_PICKER("photo_picker", listOf("pick a photo", "choose a photo", "izvēlies foto", "выбери фото")),
    PHOTO_CAPTURE("photo_capture", listOf("take a photo", "uzņem foto", "сделай фото")),
    VIDEO_CAPTURE("video_capture", listOf("record a video", "ieraksti video", "запиши видео")),
    SOUND_RECORDING("sound_recording", listOf("open sound recorder", "record audio", "ieraksti audio", "запиши аудио")),
    BLUETOOTH_ENABLE("bluetooth_enable", listOf("enable bluetooth", "ieslēdz bluetooth", "включи bluetooth")),
    BLUETOOTH_DISABLE("bluetooth_disable", listOf("disable bluetooth", "turn off bluetooth", "izslēdz bluetooth", "выключи bluetooth")),
    DND_ON("dnd_on", listOf("enable do not disturb", "enable halo do not disturb", "ieslēdz netraucēšanas režīmu", "включи режим не беспокоить")),
    DND_OFF("dnd_off", listOf("disable halo do not disturb", "disable do not disturb", "izslēdz halo netraucēšanas režīmu", "izslēdz netraucēšanas režīmu", "выключи режим не беспокоить halo", "выключи режим не беспокоить")),
    HAPTIC("haptic", listOf("vibrate once", "vibrē vienreiz", "вибрируй один раз")),
    DOCUMENT_SCAN("document_scan", listOf("scan a document to pdf", "skenē dokumentu pdf formātā", "сканируй документ в pdf")),
    WIFI_ADD_NETWORK("wifi_add_network", listOf("add a wifi network", "pievieno wifi tīklu", "добавь сеть wifi"));

    companion object {
        fun fromWire(value: String): NativeDestination? = entries.firstOrNull { it.wireId == value }
        fun parse(command: String): NativeDestination? = entries.firstOrNull { command in it.phrases }
    }
}

data class NativeDestinationIntent(val destination: NativeDestination) : NativeControlIntent
