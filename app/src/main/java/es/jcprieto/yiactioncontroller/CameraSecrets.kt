package es.jcprieto.yiactioncontroller

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun sensitiveCameraKey(key: String): Boolean =
    listOf("password", "passwd", "passphrase", "pwd", "psk", "credential").any { it in key.lowercase() }

/** GET_CONFIG may echo the Wi-Fi password. Remove it before exposing any camera state. */
internal fun redactCameraJson(raw: String): String = try {
    val original = cameraJson.parseToJsonElement(raw)
    val safe = redactCameraElement(original)
    if (safe == original) raw else safe.toString()
} catch (_: Exception) {
    "[JSON no válido omitido]"
}

internal fun redactCameraElement(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> JsonArray(element.map(::redactCameraElement))
    is JsonObject -> JsonObject(element.mapValues { (key, value) ->
        val namedSecret = (key == "value" && element["key"].text()?.let(::sensitiveCameraKey) == true) ||
                (key == "param" && element["type"].text()?.let(::sensitiveCameraKey) == true)
        if (sensitiveCameraKey(key) || namedSecret) JsonPrimitive("[omitido]") else redactCameraElement(value)
    })

    else -> element
}
