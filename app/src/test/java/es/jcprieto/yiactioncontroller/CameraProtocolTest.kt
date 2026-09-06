package es.jcprieto.yiactioncontroller

import org.junit.Assert.*
import org.junit.Test

class CameraProtocolTest {
    private fun CameraState.receive(raw: String) = applyMessage(cameraJson.decodeFromString(raw), raw)

    @Test
    fun batteryRepliesAndEventsUseTheSameState() {
        val queried = CameraState().receive("""{"rval":0,"msg_id":13,"type":"battery","param":"93","extra":true}""")
        assertEquals(93, queried.battery)
        val event = queried.receive("""{"msg_id":7,"type":"battery","param":"92"}""")
        assertEquals(92, event.battery)
        assertNotNull(event.lastEvent)
        assertNull(event.cameraStatus)
    }

    @Test
    fun parsesBothConfigurationShapesAndRetainsUnknownValues() {
        val state =
            CameraState().receive("""{"rval":0,"msg_id":3,"param":[{"sw_version":"fw","hw_version":"hw"},{"key":"sd_card_status","value":"inserted"},{"video_resolution":"1920x1080"},{"camera_status":"idle"},{"unknown":{"x":1}}]}""")
        assertEquals("fw", state.firmware)
        assertEquals("hw", state.hardware)
        assertEquals("inserted", state.sdCard)
        assertEquals("1920x1080", state.videoResolution)
        assertEquals("idle", state.cameraStatus)
        assertEquals("{\"x\":1}", state.configuration["unknown"])
    }

    @Test
    fun errorsNeverApplyResponsePayload() {
        val state = CameraState(battery = 93).receive("""{"rval":-1,"msg_id":13,"type":"battery","param":"0"}""")
        assertEquals(93, state.battery)
        assertTrue(state.error!!.contains("rval=-1"))
    }

    @Test
    fun malformedBatteryAndAbsentConfigurationAreNotInvented() {
        assertNull(CameraState().firmware)
        val state = CameraState().receive("""{"msg_id":7,"type":"battery","param":"999"}""")
        assertNull(state.battery)
        assertNotNull(state.error)
    }
}
