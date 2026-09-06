package es.jcprieto.yiactioncontroller

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
    val lastRequest: String? = null,
    val lastMessage: String? = null,
    val lastEvent: String? = null,
    val error: String? = null,
) {
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
    var next = copy(lastMessage = raw)
    if (message.rval != null && message.rval != 0) {
        return next.copy(error = "Comando ${message.messageId}: rval=${message.rval}")
    }
    if ((message.messageId == 13 && message.rval == 0 || message.messageId == 7) &&
        message.type == "battery"
    ) {
        val value = message.param.text()?.toIntOrNull()?.takeIf { it in 0..100 }
        next = next.copy(battery = value, error = if (value == null) "Batería no válida" else next.error)
    }
    if (message.messageId == 3 && message.rval == 0) {
        val values = message.param as? JsonArray
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
        next = next.copy(configuration = configuration, events = next.events - configuration.keys)
    }
    if (message.messageId == 7) {
        val eventValue = message.param.text() ?: message.param?.toString()
        next = next.copy(
            lastEvent = raw,
            events = if (message.type != null && eventValue != null)
                next.events + (message.type to eventValue) else next.events,
        )
    }
    return next
}
