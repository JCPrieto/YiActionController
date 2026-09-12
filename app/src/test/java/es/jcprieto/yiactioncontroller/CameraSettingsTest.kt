package es.jcprieto.yiactioncontroller

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CameraSettingsTest {
    @Test
    fun parsesConfigurationAndCountsMalformedAndDuplicates() {
        val parsed = parseCameraConfiguration(
            cameraJson.parseToJsonElement(
                """[{"video_stamp":"off"},{"unknown_firmware_key":"with spaces"},9,{},{"a":"1","b":"2"},{"bad":4},{"video_stamp":"date"}]"""
            )
        )!!
        assertEquals(mapOf("video_stamp" to "date", "unknown_firmware_key" to "with spaces"), parsed.values)
        assertEquals(5, parsed.malformedEntries)
        assertEquals(emptyMap<String, String>(), parseCameraConfiguration(JsonArray(emptyList()))!!.values)
        assertNull(parseCameraConfiguration(JsonPrimitive("bad")))
    }

    @Test
    fun settableIsExactAndNeverInventsValues() {
        val fixtures = mapOf(
            "settable:off#date#time#date/time" to listOf("off", "date", "time", "date/time"),
            "settable:high#low#mute" to listOf("high", "low", "mute"),
            "settable:on#off" to listOf("on", "off"),
            "settable:off#on" to listOf("off", "on"),
            "settable:S.Fine#Fine#Normal" to listOf("S.Fine", "Fine", "Normal"),
            "settable:all enable#all disable#status enable" to listOf("all enable", "all disable", "status enable"),
            "settable:one##two" to listOf("one", "two"),
            "settable: leading #trailing " to listOf(" leading ", "trailing "),
        )
        fixtures.forEach { (raw, expected) -> assertEquals(expected, parseSettable(raw)) }
        listOf("", "off", "settable:", "Settable:on#off", " settable:on#off").forEach {
            assertTrue(parseSettable(it).isEmpty())
        }
    }

    @Test
    fun referenceDomainsRemainFixturesOnly() {
        val resolutions = listOf(
            "1920x1080 60P 16:9", "1920x1080 30P 16:9", "1920x1080 48P 16:9",
            "1920x1080 24P 16:9", "2304x1296 30P 16:9", "1280x960 60P 4:3", "1280x960 48P 4:3",
            "1280x720 60P 16:9", "1280x720 48P 16:9", "1280x720 120P 16:9", "848x480 240P 16:9"
        )
        val photos = listOf(
            "16M (4608x3456 4:3)", "13M (4128x3096 4:3)", "8M (3264x2448 4:3)",
            "5M (2560x1920 4:3)", "12M (4608x2592 16:9)"
        )
        for (values in listOf(resolutions, photos)) assertEquals(
            values,
            parseSettable("settable:" + values.joinToString("#"))
        )
    }

    @Test
    fun sensitiveValuesNeverReachParsedStateOrDiagnosticsOrMessageToString() {
        val secrets = SENSITIVE_SETTING_KEYS.associateWith { "synthetic-private-" + it }
        val param = JsonArray((secrets + ("video_stamp" to "off")).map { (key, value) ->
            buildJsonObject { put(key, value) }
        })
        val raw = buildJsonObject { put("msg_id", 3); put("rval", 0); put("param", param) }.toString()
        val message = cameraJson.decodeFromString<CameraMessage>(raw)
        val camera = CameraState().applyMessage(message, raw)
        val parsed = parseCameraConfiguration(param)!!
        val history = DiagnosticHistory()
        history.received(message)
        val visible = listOf(
            camera.toString(), camera.configuration.toString(), camera.lastMessage,
            parsed.toString(), message.toString(), history.entries.value.toString()
        ).joinToString()
        secrets.forEach { (key, value) ->
            assertFalse(value, visible.contains(value))
            assertFalse(parsed.values.containsKey(key))
            assertFalse(camera.configuration.containsKey(key))
        }
        for ((key, value) in secrets) {
            val event = buildJsonObject { put("msg_id", 7); put("type", key); put("param", value) }.toString()
            assertFalse(camera.applyMessage(cameraJson.decodeFromString(event), event).lastEvent!!.contains(value))
        }
    }
}
