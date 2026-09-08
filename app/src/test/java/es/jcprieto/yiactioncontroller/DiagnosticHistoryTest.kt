package es.jcprieto.yiactioncontroller

import org.junit.Assert.*
import org.junit.Test

class DiagnosticHistoryTest {
    @Test
    fun boundedOrderedTimestampedAndClearable() {
        val history = DiagnosticHistory { "2026-09-08T12:00:00+02:00" }
        repeat(205) { history.sent(it) }
        val entries = history.entries.value
        assertEquals(200, entries.size)
        assertTrue(entries.first().startsWith("6 2026-09-08T12:00:00+02:00 [TX] msg_id=5"))
        assertTrue(entries.last().contains("msg_id=204"))
        history.clear()
        assertTrue(history.entries.value.isEmpty())
        assertEquals(200, entries.size) // Previously emitted snapshots do not mutate.
        history.append("TEST", "x".repeat(3000))
        assertTrue(history.entries.value.single().endsWith("[truncado]"))
        assertTrue(history.entries.value.single().length < 1400)
    }

    @Test
    fun retainsRejectionAndInterleavedEventsWithoutCredentials() {
        val history = DiagnosticHistory { "now" }
        fun receive(raw: String) = history.received(cameraJson.decodeFromString<CameraMessage>(raw))
        history.sent(259)
        receive("""{"msg_id":7,"type":"vf_start"}""")
        receive("""{"msg_id":259,"rval":-21}""")
        receive("""{"msg_id":257,"rval":0,"param":987654}""")
        receive("""{"msg_id":3,"rval":0,"param":[{"wifi_password":"secret"},{"wifi_ssid":"private"},{"serial_number":"serial"},{"app_status":"vf"},{"preview_status":"on"},{"streaming_status":"off"},{"dual_stream_status":"on"}]}""")
        receive("""{"msg_id":7,"type":"unknown","param":{"password":"secret"}}""")
        receive("""{"msg_id":7,"type":"photo_taken","param":"/tmp/fuse_d/DCIM/test.jpg"}""")
        val entries = history.entries.value
        assertTrue(entries[1].contains("[EVENT]"))
        assertTrue(entries[2].contains("\"rval\":-21"))
        val text = entries.joinToString("\n")
        listOf(
            "987654",
            "secret",
            "private",
            "serial_number",
            "wifi_password"
        ).forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("\"app_status\":\"vf\""))
        assertTrue(text.contains("\"streaming_status\":\"off\""))
        assertTrue(text.contains("/tmp/fuse_d/DCIM/test.jpg"))
    }
}
