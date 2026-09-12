package es.jcprieto.yiactioncontroller

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class CameraSettingAccess { EDITABLE, READ_ONLY, SENSITIVE }
enum class CameraSettingCategory(val label: String) { VIDEO("Vídeo"), PHOTO("Foto"), GENERAL("Cámara"), INFORMATION("Información") }

internal val EDITABLE_SETTING_KEYS = linkedSetOf(
    "video_resolution", "video_quality", "video_stamp", "photo_size", "photo_quality", "photo_stamp",
    "buzzer_volume", "led_mode", "meter_mode", "auto_low_light", "video_rotate", "loop_record"
)

data class CameraSetting(
    val key: String,
    val currentValue: String?,
    val allowedValues: List<String> = emptyList(),
    val access: CameraSettingAccess = CameraSettingAccess.READ_ONLY,
    val category: CameraSettingCategory = CameraSettingCategory.INFORMATION,
)

enum class CameraSettingsError(val description: String) {
    DISCONNECTED("Cámara desconectada."),
    BLOCKED("Android ha bloqueado temporalmente la red de la cámara."),
    BUSY("Hay otra operación en curso."),
    NOT_IDLE("Detén la grabación antes de cambiar este ajuste."),
    DOWNLOAD_ACTIVE("Configuración no disponible durante una descarga."),
    TIMEOUT("La cámara no respondió a tiempo."),
    CAMERA_REJECTED("La cámara rechazó el ajuste o valor."),
    INVALID_RESPONSE("No se pudo leer la configuración de la cámara."),
    VERIFY_FAILED("No se pudo verificar el cambio. Se muestra el valor leído de la cámara."),
    UNSUPPORTED_SETTING("Este ajuste no se puede modificar."),
    UNSUPPORTED_VALUE("Ese valor no está admitido por la cámara."),
    PREVIEW_STOP_FAILED("No se pudo detener la vista previa. No se ha enviado el cambio.")
}

sealed interface CameraSettingsMutationState {
    data object Idle : CameraSettingsMutationState
    data class Applying(val key: String, val requestedValue: String) : CameraSettingsMutationState
    data class Verified(val key: String, val value: String) : CameraSettingsMutationState
    data class Failed(val key: String, val error: CameraSettingsError) : CameraSettingsMutationState
}

data class CameraSettingsState(
    val loading: Boolean = false,
    val settings: List<CameraSetting> = emptyList(),
    val mutation: CameraSettingsMutationState = CameraSettingsMutationState.Idle,
    val error: CameraSettingsError? = null,
    val malformedEntries: Int = 0,
    val discovered: Boolean = false,
) {
    val busy: Boolean get() = loading || mutation is CameraSettingsMutationState.Applying
}

internal sealed interface CameraSettingsOperation {
    data object ReadAll : CameraSettingsOperation
    data class ReadAllowed(val key: String) : CameraSettingsOperation
    data class SetValue(val key: String, val value: String) : CameraSettingsOperation

    val id: Int get() = if (this is SetValue) CameraCommand.SET_CONFIG else CameraCommand.GET_CONFIG
    fun arguments(): Map<String, String> = when (this) {
        ReadAll -> emptyMap()
        is ReadAllowed -> mapOf("param" to key)
        is SetValue -> mapOf("type" to key, "param" to value)
    }
}

internal class CameraSettingsException(val kind: CameraSettingsError, val rval: Int? = null) :
    Exception(kind.description)

internal data class ParsedCameraConfiguration(val values: Map<String, String>, val malformedEntries: Int)

/** Sanitization happens while parsing, before maps can enter public state. */
internal fun parseCameraConfiguration(param: JsonElement?, legacy: Boolean = false): ParsedCameraConfiguration? {
    val array = param as? JsonArray ?: return null
    val values = linkedMapOf<String, String>()
    var malformed = 0
    for (item in array) {
        val obj = item as? JsonObject
        if (obj == null || obj.isEmpty()) {
            malformed++; continue
        }
        val entries = if (legacy && obj["key"].text() != null && "value" in obj)
            mapOf(obj["key"].text()!! to obj.getValue("value"))
        else obj
        if (entries.size != 1) {
            malformed++
            if (!legacy) continue
        }
        for ((key, value) in entries) {
            if (sensitiveCameraKey(key)) continue
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: if (legacy) redactCameraElement(value).toString() else null
            if (text == null || key.isBlank()) {
                malformed++; continue
            }
            if (key in values) malformed++
            values[key] = text
        }
    }
    return ParsedCameraConfiguration(values, malformed)
}

internal fun parseSettable(value: String?): List<String> =
    if (value?.startsWith("settable:") == true)
        value.removePrefix("settable:").split('#').filter { it.isNotEmpty() }.distinct()
    else emptyList()

internal fun settingCategory(key: String): CameraSettingCategory = when (key) {
    "video_resolution", "video_quality", "video_stamp", "auto_low_light", "video_rotate", "loop_record" -> CameraSettingCategory.VIDEO
    "photo_size", "photo_quality", "photo_stamp" -> CameraSettingCategory.PHOTO
    "buzzer_volume", "led_mode", "meter_mode" -> CameraSettingCategory.GENERAL
    else -> CameraSettingCategory.INFORMATION
}

internal val SETTING_LABELS = mapOf(
    "video_resolution" to "Resolución de vídeo",
    "video_quality" to "Calidad de vídeo",
    "video_stamp" to "Marca en vídeo",
    "photo_size" to "Resolución de foto",
    "photo_quality" to "Calidad de foto",
    "photo_stamp" to "Marca en foto",
    "buzzer_volume" to "Volumen",
    "led_mode" to "LEDs",
    "meter_mode" to "Medición",
    "auto_low_light" to "Luz baja automática",
    "video_rotate" to "Rotar vídeo",
    "loop_record" to "Grabación en bucle",
    "camera_clock" to "Reloj de la cámara",
    "video_standard" to "Estándar de vídeo",
    "app_status" to "Estado de cámara",
    "capture_mode" to "Modo de captura",
    "timelapse_video" to "Intervalo de vídeo",
    "timelapse_photo" to "Intervalo de foto",
    "preview_status" to "Vista previa",
    "capture_default_mode" to "Captura predeterminada",
    "precise_cont_time" to "Intervalo de captura continua",
    "burst_capture_number" to "Ráfaga",
    "sd_card_status" to "Estado microSD",
    "sdcard_need_format" to "Estado del formato microSD",
    "video_output_dev_type" to "Salida de vídeo",
    "sw_version" to "Firmware",
    "hw_version" to "Hardware",
    "dual_stream_status" to "Doble flujo",
    "streaming_status" to "Emisión",
    "precise_cont_capturing" to "Captura continua activa",
    "piv_enable" to "Foto durante vídeo",
    "support_auto_low_light" to "Soporte de luz baja",
    "precise_selftime" to "Temporizador",
    "precise_self_running" to "Temporizador activo",
    "system_mode" to "Modo del sistema",
    "system_default_mode" to "Modo predeterminado",
    "quick_record_time" to "Grabación rápida",
    "precise_self_remain_time" to "Tiempo restante",
    "emergency_file_backup" to "Copia de emergencia",
    "osd_enable" to "Información en pantalla",
    "rec_default_mode" to "Grabación predeterminada",
    "rec_mode" to "Modo de grabación",
    "record_photo_time" to "Intervalo de foto en vídeo",
    "rc_button_mode" to "Botón remoto",
    "timelapse_video_duration" to "Duración de vídeo por intervalos",
    "timelapse_video_resolution" to "Resolución de vídeo por intervalos",
    "save_log" to "Registro de cámara",
    "auto_power_off" to "Apagado automático",
    "start_wifi_while_booted" to "Wi-Fi al encender"
)

internal fun settingValueLabel(value: String): String = when (value) {
    "on" -> "Activado"; "off" -> "Desactivado"; "S.Fine" -> "Súper fina"; "Fine" -> "Fina"
    "date" -> "Fecha"; "time" -> "Hora"; "date/time" -> "Fecha y hora"
    "high" -> "Alto"; "low" -> "Bajo"; "mute" -> "Silencio"
    "all enable" -> "Todos encendidos"; "all disable" -> "Todos apagados"; "status enable" -> "Solo estado"
    else -> value
}
