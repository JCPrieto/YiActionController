package es.jcprieto.yiactioncontroller

import es.jcprieto.yiactioncontroller.CameraCommand.EVENT
import es.jcprieto.yiactioncontroller.CameraCommand.GET_BATTERY
import es.jcprieto.yiactioncontroller.CameraCommand.GET_CONFIG
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class CameraRequest(@SerialName("msg_id") val messageId: Int, val token: Int)

@Serializable
internal data class CameraMessage(
    @SerialName("msg_id") val messageId: Int,
    val rval: Int? = null,
    val type: String? = null,
    val param: JsonElement? = null,
)

internal val cameraJson = Json { ignoreUnknownKeys = true }
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

enum class ConnectionStatus { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED }

data class CameraState(
    val connection: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val token: Int? = null,
    val battery: Int? = null,
    val configuration: Map<String, String> = emptyMap(),
    val events: Map<String, String> = emptyMap(),
    val pending: Set<Int> = emptySet(),
    val pendingAction: CameraAction? = null,
    val previewControlRequested: Boolean = false,
    val awaitingPreviewStop: Boolean = false,
    internal val recordingStopRequested: Boolean = false,
    val recording: RecordingState = RecordingState.UNKNOWN,
    val lastRecordingEvent: String? = null,
    internal val recordingRevision: Long = 0,
    val lastPhotoPath: String? = null,
    val lastPhotoEvent: PhotoEvent? = null,
    val lastRequest: String? = null,
    val lastMessage: String? = null,
    val lastEvent: String? = null,
    val error: String? = null,
) {
    val canSendCommand: Boolean
        get() = connection == ConnectionStatus.CONNECTED &&
                token != null && token > 0 && pending.isEmpty() && pendingAction == null && !awaitingPreviewStop
    val canTakePhoto: Boolean get() = canSendCommand
    val canStartRecording: Boolean get() = canSendCommand && recording == RecordingState.IDLE

    // Stopping is an explicit recovery option when actual state cannot be established.
    val canStopRecording: Boolean
        get() = canSendCommand &&
                recording in setOf(RecordingState.RECORDING, RecordingState.UNKNOWN)
    // Primary keys verified on YDXJv25L_1.5.12; retain aliases for diagnostics.
    private fun field(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { events[it] } ?: keys.firstNotNullOfOrNull { configuration[it] }

    val firmware: String? get() = field("sw_version", "firmware_version")
    val hardware: String? get() = field("hw_version", "hardware_version")
    val sdCard: String? get() = field("sd_card_status", "sd_status")
    val videoResolution: String? get() = field("video_resolution")
    val cameraStatus: String? get() = field("app_status", "camera_status", "status")
}

internal fun CameraState.applyMessage(message: CameraMessage, raw: String): CameraState {
    val safeRaw = redactCameraJson(raw)
    var next = copy(lastMessage = safeRaw)
    if (message.rval != null && message.rval != 0) {
        return next.copy(error = "Comando ${message.messageId}: rval=${message.rval}")
    }
    if ((message.messageId == GET_BATTERY && message.rval == 0 || message.messageId == EVENT) &&
        message.type == "battery"
    ) {
        val value = message.param.text()?.toIntOrNull()?.takeIf { it in 0..100 }
        next = next.copy(battery = value, error = if (value == null) "Batería no válida" else next.error)
    }
    if (message.messageId == GET_CONFIG && message.rval == 0) {
        val values = message.param?.let(::redactCameraElement) as? JsonArray
            ?: error("La configuración no contiene un array en param")
        val configuration = buildMap {
            for (item in values) {
                val obj = item as? JsonObject ?: error("Entrada de configuración no válida")
                // Accept both {"sw_version":"..."} and {"key":"sw_version","value":"..."}.
                val key = obj["key"].text()
                if (key != null && "value" in obj) {
                    put(key, obj.getValue("value").text() ?: obj.getValue("value").toString())
                } else {
                    obj.forEach { (name, value) -> put(name, value.text() ?: value.toString()) }
                }
            }
        }
        next = next.copy(
            configuration = configuration.mapValues { (key, value) -> if (sensitiveCameraKey(key)) "[omitido]" else value },
            events = next.events - configuration.keys,
            recording = recordingFromAppStatus(configuration["app_status"]),
        )
    }
    if (message.messageId == EVENT) {
        val eventValue = if (message.type?.let(::sensitiveCameraKey) == true) "[omitido]"
        else message.param.text() ?: message.param?.let(::redactCameraElement)?.toString()
        next = next.copy(
            lastEvent = safeRaw,
            events = if (message.type != null && eventValue != null)
                next.events + (message.type to eventValue) else next.events,
        )
        next = when (message.type) {
            "app_status" -> next.withRecordingEvent(recordingFromAppStatus(message.param.text()), safeRaw)
            "start_video_record" -> next.withRecordingEvent(RecordingState.RECORDING, safeRaw)
                .copy(recordingStopRequested = false)
            // Observed after stopping on the reference firmware (return to viewfinder).
            "vf_start" -> if (previewControlRequested && recording == RecordingState.RECORDING && !recordingStopRequested)
                next else next.withRecordingEvent(RecordingState.IDLE, safeRaw).copy(recordingStopRequested = false)
            "start_photo_capture" -> next.copy(lastPhotoEvent = PhotoEvent.START_PHOTO_CAPTURE)
            "precise_capture_data_ready" -> next.copy(lastPhotoEvent = PhotoEvent.PRECISE_CAPTURE_DATA_READY)
            "photo_taken" -> next.copy(
                lastPhotoEvent = PhotoEvent.PHOTO_TAKEN,
                lastPhotoPath = (message.param as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: next.lastPhotoPath,
            )

            else -> next
        }
    }
    return next
}

private fun CameraState.withRecordingEvent(value: RecordingState, raw: String) = copy(
    recording = value,
    lastRecordingEvent = raw,
    recordingRevision = recordingRevision + 1,
)

// Exact app_status values used by the original-YI reference client linked in README.
// Mode fields and unrelated events must not imply a recording state.
private fun recordingFromAppStatus(value: String?): RecordingState = when (value) {
    "idle", "vf" -> RecordingState.IDLE
    "record", "recording" -> RecordingState.RECORDING
    else -> RecordingState.UNKNOWN
}
