package es.jcprieto.yiactioncontroller

import org.junit.Assert.*
import org.junit.Test

class CameraActionStateTest {
    @Test
    fun observedVideoEventsDriveStateAndSurviveBatteryEvents() {
        val started = CameraState().receive("""{"msg_id":7,"type":"start_video_record"}""")
        assertEquals(RecordingState.RECORDING, started.recording)
        assertEquals(
            RecordingState.RECORDING,
            started.receive("""{"msg_id":7,"type":"vf_stop"}""").recording
        )
        val stopped = started.receive("""{"msg_id":7,"type":"vf_start"}""")
        assertEquals(RecordingState.IDLE, stopped.recording)
        val battery = stopped.receive("""{"msg_id":7,"type":"battery","param":"42"}""")
        assertEquals(RecordingState.IDLE, battery.recording)
        assertEquals(stopped.lastRecordingEvent, battery.lastRecordingEvent)
        assertEquals(
            RecordingState.IDLE,
            battery.receive("""{"msg_id":3,"rval":0,"param":[{"app_status":"vf"}]}""").recording
        )
        assertEquals(
            RecordingState.UNKNOWN,
            battery.receive("""{"msg_id":3,"rval":0,"param":[{"app_status":"unrecognised"}]}""").recording
        )
    }

    private fun CameraState.receive(raw: String) = applyMessage(cameraJson.decodeFromString(raw), raw)

    @Test
    fun photoMilestonesRequireEventsAndValidPath() {
        val accepted = CameraState().receive("""{"msg_id":769,"rval":0}""")
        assertNull(accepted.lastPhotoEvent)
        assertNull(accepted.lastPhotoPath)
        val started = accepted.receive("""{"msg_id":7,"type":"start_photo_capture","extra":true}""")
        assertEquals(PhotoEvent.START_PHOTO_CAPTURE, started.lastPhotoEvent)
        val ready = started.receive("""{"msg_id":7,"type":"precise_capture_data_ready"}""")
        assertEquals(PhotoEvent.PRECISE_CAPTURE_DATA_READY, ready.lastPhotoEvent)
        val taken = ready.receive("""{"msg_id":7,"type":"photo_taken","param":"/DCIM/100MEDIA/YI.jpg"}""")
        assertEquals(PhotoEvent.PHOTO_TAKEN, taken.lastPhotoEvent)
        assertEquals("/DCIM/100MEDIA/YI.jpg", taken.lastPhotoPath)
        for (param in listOf("null", "42", "{}", "\"\"")) {
            assertEquals(
                taken.lastPhotoPath,
                taken.receive("""{"msg_id":7,"type":"photo_taken","param":$param}""").lastPhotoPath
            )
        }
        assertEquals(
            taken.lastPhotoPath,
            taken.receive("""{"msg_id":7,"type":"battery","param":"80"}""").lastPhotoPath
        )
    }

    @Test
    fun modesAndAcknowledgementsDoNotProveRecording() {
        val state =
            CameraState().receive("""{"msg_id":3,"rval":0,"param":[{"system_mode":"record"},{"rec_mode":"record"}]}""")
        assertEquals(RecordingState.UNKNOWN, state.recording)
        assertEquals(RecordingState.UNKNOWN, state.receive("""{"msg_id":513,"rval":0}""").recording)
        assertEquals(RecordingState.UNKNOWN, state.receive("""{"msg_id":514,"rval":0}""").recording)
        val recording = state.receive("""{"msg_id":7,"type":"app_status","param":"record"}""")
        assertEquals(RecordingState.RECORDING, recording.recording)
        assertEquals(
            RecordingState.UNKNOWN,
            recording.receive("""{"msg_id":7,"type":"app_status","param":"unexpected"}""").recording
        )
        assertEquals(
            RecordingState.IDLE,
            recording.receive("""{"msg_id":3,"rval":0,"param":[{"app_status":"idle"}]}""").recording
        )
    }

    @Test
    fun controlAvailabilityRequiresSessionTokenAndNoPendingWork() {
        val idle = CameraState(connection = ConnectionStatus.CONNECTED, token = 9, recording = RecordingState.IDLE)
        assertTrue(idle.canTakePhoto)
        assertTrue(idle.canStartRecording)
        assertFalse(idle.canStopRecording)
        val unknown = idle.copy(recording = RecordingState.UNKNOWN)
        assertFalse(unknown.canStartRecording)
        assertTrue(unknown.canStopRecording)
        assertTrue(idle.copy(recording = RecordingState.RECORDING).canStopRecording)
        for (disabled in listOf(
            idle.copy(token = null), idle.copy(token = 0),
            idle.copy(connection = ConnectionStatus.DISCONNECTED),
            idle.copy(pending = setOf(CameraCommand.GET_CONFIG)),
            idle.copy(pendingAction = CameraAction.TAKE_PHOTO)
        )) {
            assertFalse(disabled.canTakePhoto)
            assertFalse(disabled.canStartRecording)
            assertFalse(disabled.canStopRecording)
        }
    }
}
