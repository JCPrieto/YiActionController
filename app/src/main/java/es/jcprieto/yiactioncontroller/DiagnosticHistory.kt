package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Bounded, process-local history. Only a whitelist of camera data is shareable. */
class DiagnosticHistory(private val timestamp: () -> String = { OffsetDateTime.now().toString() }) {
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private var sequence = 0L

    @Synchronized
    internal fun append(category: String, detail: String) {
        val safeLine = detail.replace('\n', ' ').replace('\r', ' ')
        val bounded = if (safeLine.length > MAX_DETAIL) safeLine.take(MAX_DETAIL) + "… [truncado]" else safeLine
        val line = "${++sequence} ${timestamp()} [$category] $bounded"
        mutableEntries.value = (mutableEntries.value + line).takeLast(MAX_ENTRIES)
    }

    internal fun sent(id: Int) = append("TX", "msg_id=$id (token omitido)")

    internal fun received(message: CameraMessage) {
        val summary = buildJsonObject {
            put("msg_id", message.messageId)
            message.rval?.let { put("rval", it) }
            message.type?.let { put("type", it) }
            if (message.messageId == CameraCommand.GET_CONFIG) {
                put("config_resumida", buildJsonObject {
                    (message.param as? JsonArray)?.forEach { item ->
                        (item as? JsonObject)?.forEach { (key, value) ->
                            if (key in CONFIG_FIELDS && value is JsonPrimitive) put(key, value)
                        }
                    }
                })
            } else if (message.messageId == CameraCommand.GET_BATTERY ||
                message.messageId == CameraCommand.EVENT && message.type in EVENT_PARAMS
            ) {
                (message.param as? JsonPrimitive)?.let { put("param", it) }
            }
        }
        append(if (message.messageId == CameraCommand.EVENT) "EVENT" else "RX", summary.toString())
    }

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val MAX_DETAIL = 1200
        private val CONFIG_FIELDS = setOf(
            "app_status", "preview_status", "streaming_status", "dual_stream_status",
            "system_mode", "rec_mode", "sd_card_status", "video_resolution", "sw_version", "hw_version"
        )
        private val EVENT_PARAMS = CONFIG_FIELDS + setOf(
            "battery", "adapter", "photo_taken", "start_photo_capture", "precise_capture_data_ready"
        )
    }
}
