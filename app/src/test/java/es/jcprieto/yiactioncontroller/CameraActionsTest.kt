package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class CameraActionsTest {
    @Test
    fun recoveryStopRequiresFreshEventAndAckInEitherOrder() = runBlocking {
        for (eventBeforeAck in listOf(true, false)) {
            ServerSocket(0).use { server ->
                CameraClient("127.0.0.1", server.localPort).use { client ->
                    client.connect()
                    server.accept().use { peer ->
                        peer.authenticate(client, 29)
                        peer.send("""{"msg_id":7,"type":"vf_stop"}""")
                        client.awaitState { it.lastEvent?.contains("vf_stop") == true }
                        val stopping = async(Dispatchers.Default) { client.stopPreviewAndAwaitVfStop() }
                        assertEquals(CameraRequest(CameraCommand.STOP_PREVIEW, 29), peer.request())
                        assertTrue(client.state.value.awaitingPreviewStop)
                        if (eventBeforeAck) peer.send("""{"msg_id":7,"type":"vf_stop"}""")
                        else peer.reply(CameraCommand.STOP_PREVIEW)
                        peer.assertNoRequest()
                        assertFalse(stopping.isCompleted)
                        client.startRecording()
                        client.takePhoto()
                        client.refresh()
                        peer.assertNoRequest()
                        if (eventBeforeAck) peer.reply(CameraCommand.STOP_PREVIEW)
                        else peer.send("""{"msg_id":7,"type":"vf_stop"}""")
                        assertTrue(stopping.await().accepted)
                        client.awaitState { it.canSendCommand }
                        assertFalse(client.state.value.awaitingPreviewStop)
                    }
                }
            }
        }
    }

    @Test
    fun recoveryStopRejectAndMissingEventKeepSessionAndNeverSendStart() = runBlocking {
        for (reject in listOf(true, false)) {
            ServerSocket(0).use { server ->
                CameraClient("127.0.0.1", server.localPort).use { client ->
                    client.connect()
                    server.accept().use { peer ->
                        peer.authenticate(client, 7)
                        val stopping = async(Dispatchers.Default) { client.stopPreviewAndAwaitVfStop() }
                        assertEquals(CameraCommand.STOP_PREVIEW, peer.request().messageId)
                        peer.reply(CameraCommand.STOP_PREVIEW, if (reject) -42 else 0)
                        val result = withTimeout(7_000) { stopping.await() }
                        assertFalse(result.accepted)
                        assertTrue(result.error!!.contains(if (reject) "-42" else "vf_stop"))
                        assertEquals(ConnectionStatus.CONNECTED, client.state.value.connection)
                        assertFalse(client.state.value.awaitingPreviewStop)
                        peer.assertNoRequest()
                    }
                }
            }
        }
    }

    @Test
    fun previewCommandsUseCurrentTokenAndEventsNeverCompleteThem() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                for (token in listOf(7, 29)) {
                    client.connect()
                    server.accept().use { peer ->
                        peer.authenticate(client, token)
                        val starting = async(Dispatchers.Default) { client.startPreview() }
                        assertEquals(CameraRequest(CameraCommand.START_PREVIEW, token), peer.request())
                        peer.send("""{"msg_id":7,"type":"vf_start"}""")
                        peer.assertNoRequest()
                        assertFalse(starting.isCompleted)
                        peer.reply(CameraCommand.START_PREVIEW)
                        assertTrue(starting.await().accepted)
                        client.awaitState { it.canSendCommand }
                        val stopping = async(Dispatchers.Default) { client.stopPreview() }
                        assertEquals(CameraRequest(CameraCommand.STOP_PREVIEW, token), peer.request())
                        peer.reply(CameraCommand.STOP_PREVIEW)
                        assertTrue(stopping.await().accepted)
                        client.awaitState { it.canSendCommand }
                        val rejected = async(Dispatchers.Default) { client.startPreview() }
                        assertEquals(CameraCommand.START_PREVIEW, peer.request().messageId)
                        peer.reply(CameraCommand.START_PREVIEW, -42)
                        assertFalse(rejected.await().accepted)
                        val state = client.awaitState { it.canSendCommand }
                        assertEquals(ConnectionStatus.CONNECTED, state.connection)
                        assertTrue(state.error!!.contains("-42"))
                        val history = client.diagnostics.entries.value
                        val startTx = history.indexOfFirst { it.contains("[TX] msg_id=259") }
                        val eventRx = history.indexOfFirst { it.contains("[EVENT]") && it.contains("vf_start") }
                        val startRx = history.indexOfFirst { it.contains("[RX]") && it.contains("\"msg_id\":259") }
                        assertTrue(startTx >= 0 && eventRx > startTx && startRx > eventRx)
                        assertTrue(history.last().contains("\"rval\":-42"))
                    }
                    client.disconnect()
                    assertTrue(client.diagnostics.entries.value.any { it.contains("\"rval\":-42") })
                }
            }
        }
    }

    @Test
    fun videoEventsWinOverStaleActionSnapshotsInEveryArrivalOrder() {
        for (start in listOf(true, false)) {
            for (eventOrder in 0..2) {
                ServerSocket(0).use { server ->
                    CameraClient("127.0.0.1", server.localPort).use { client ->
                        client.connect()
                        server.accept().use { peer ->
                            peer.authenticate(client, 7, if (start) "idle" else "record")
                            if (start) client.startRecording() else client.stopRecording()
                            val id = if (start) CameraCommand.START_RECORDING else CameraCommand.STOP_RECORDING
                            assertEquals(id, peer.request().messageId)
                            val event = if (start) "start_video_record" else "vf_start"
                            val raw = """{"msg_id":7,"type":"$event"}"""
                            val expected = if (start) RecordingState.RECORDING else RecordingState.IDLE
                            if (eventOrder == 0) {
                                peer.send(raw) // Event before ACK.
                                assertNotNull(client.awaitState { it.recording == expected }.pendingAction)
                            }
                            peer.reply(id)
                            assertEquals(CameraCommand.GET_CONFIG, peer.request().messageId)
                            if (eventOrder == 1) peer.send(raw) // Event while configuration is in flight.
                            peer.config(if (start) "idle" else "record") // Snapshot predates transition.
                            if (eventOrder == 2) peer.send(raw) // Screenshot sequence: snapshot then event.
                            val confirmed = client.awaitState { it.pending.isEmpty() && it.recording == expected }
                            assertNull(confirmed.pendingAction)
                            assertEquals(raw, confirmed.lastRecordingEvent)
                            // A new explicit query after the event can still reconcile actual state.
                            client.refresh()
                            assertEquals(CameraCommand.GET_BATTERY, peer.request().messageId)
                            peer.send("""{"msg_id":13,"rval":0,"type":"battery","param":"42"}""")
                            assertEquals(CameraCommand.GET_CONFIG, peer.request().messageId)
                            peer.config(if (start) "record" else "vf")
                            assertEquals(expected, client.awaitState { it.canSendCommand }.recording)
                        }
                    }
                }
            }
        }
    }

    private fun Socket.request(): CameraRequest {
        val framer = JsonObjectFramer()
        while (true) {
            val byte = getInputStream().read()
            check(byte >= 0) { "Unexpected EOF" }
            framer.feed(byte.toChar().toString()).firstOrNull()?.let {
                return cameraJson.decodeFromString(it)
            }
        }
    }

    private fun Socket.send(raw: String) = getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
    private fun Socket.reply(id: Int, rval: Int = 0) = send("""{"msg_id":$id,"rval":$rval}""")
    private fun Socket.config(status: String) = send(
        """{"msg_id":3,"rval":0,"param":[{"app_status":"$status"},{"rec_mode":"record"},{"system_mode":"record"}]}""",
    )

    private fun CameraClient.awaitState(predicate: (CameraState) -> Boolean): CameraState = runBlocking {
        withTimeout(5_000) { state.first(predicate) }
    }

    private fun Socket.authenticate(client: CameraClient, token: Int, status: String = "idle") {
        soTimeout = 3_000
        assertEquals(CameraRequest(CameraCommand.LOGIN, 0), request())
        send("""{"msg_id":257,"rval":0,"param":$token}""")
        assertEquals(CameraRequest(CameraCommand.GET_BATTERY, token), request())
        send("""{"msg_id":13,"rval":0,"type":"battery","param":"80"}""")
        assertEquals(CameraRequest(CameraCommand.GET_CONFIG, token), request())
        config(status)
        client.awaitState { it.canSendCommand }
    }

    private fun Socket.assertNoRequest() {
        soTimeout = 350
        assertThrows(SocketTimeoutException::class.java) { getInputStream().read() }
        soTimeout = 3_000
    }

    @Test
    fun allActionsUseCurrentTokenIncludingAfterReconnect() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                for (token in listOf(4, 27)) {
                    client.connect()
                    server.accept().use { peer ->
                        peer.authenticate(client, token)
                        client.takePhoto()
                        assertEquals(CameraAction.TAKE_PHOTO, client.state.value.pendingAction)
                        assertEquals(CameraRequest(CameraCommand.TAKE_PHOTO, token), peer.request())
                        peer.reply(CameraCommand.TAKE_PHOTO)
                        client.awaitState { it.canSendCommand }
                        assertNull(client.state.value.lastPhotoPath) // ACK is not a completed photo.
                        peer.send("""{"msg_id":7,"type":"photo_taken","param":"/tmp/fuse_d/DCIM/test.jpg"}""")
                        client.awaitState { it.lastPhotoPath != null }

                        client.startRecording()
                        assertEquals(RecordingState.STARTING, client.state.value.recording)
                        assertEquals(CameraRequest(CameraCommand.START_RECORDING, token), peer.request())
                        peer.reply(CameraCommand.START_RECORDING)
                        assertEquals(CameraRequest(CameraCommand.GET_CONFIG, token), peer.request())
                        assertNull(client.state.value.pendingAction)
                        assertEquals(RecordingState.UNKNOWN, client.state.value.recording)
                        peer.config("record")
                        client.awaitState { it.canStopRecording }

                        client.stopRecording()
                        assertEquals(RecordingState.STOPPING, client.state.value.recording)
                        assertEquals(CameraRequest(CameraCommand.STOP_RECORDING, token), peer.request())
                        peer.reply(CameraCommand.STOP_RECORDING)
                        assertEquals(CameraRequest(CameraCommand.GET_CONFIG, token), peer.request())
                        assertEquals(RecordingState.UNKNOWN, client.state.value.recording)
                        peer.config("idle")
                        client.awaitState { it.canStartRecording }
                        client.disconnect()
                        assertEquals(CameraState(), client.state.value)
                    }
                }
            }
        }
    }

    @Test
    fun busyActionsAreIgnoredAndEventsNeverConsumeThePendingResponse() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.takePhoto()
                client.startRecording()
                client.stopRecording()
                assertEquals(CameraState(), client.state.value)
                client.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    assertEquals(CameraCommand.LOGIN, peer.request().messageId)
                    client.takePhoto()
                    client.startRecording()
                    client.stopRecording()
                    peer.send("""{"msg_id":257,"rval":0,"param":8}""")
                    assertEquals(CameraCommand.GET_BATTERY, peer.request().messageId)
                    client.takePhoto()
                    client.startRecording()
                    client.stopRecording()
                    peer.send("""{"msg_id":13,"rval":0,"type":"battery","param":"80"}""")
                    assertEquals(CameraCommand.GET_CONFIG, peer.request().messageId)
                    peer.config("idle")
                    client.awaitState { it.canSendCommand }
                    peer.assertNoRequest()

                    client.takePhoto()
                    // Reservation happens synchronously, before the IO worker sends anything.
                    repeat(10) {
                        client.takePhoto()
                        client.startRecording()
                        client.stopRecording()
                        client.refresh()
                    }
                    assertEquals(CameraRequest(CameraCommand.TAKE_PHOTO, 8), peer.request())
                    peer.send("""{"msg_id":7,"type":"start_photo_capture"}{"msg_id":7,"type":"precise_capture_data_ready"}{"msg_id":7,"rval":0,"type":"photo_taken","param":"/DCIM/photo.jpg"}{"msg_id":13,"rval":0,"type":"battery","param":"79"}""")
                    val state = client.awaitState { it.battery == 79 }
                    assertEquals(CameraAction.TAKE_PHOTO, state.pendingAction)
                    assertEquals(setOf(CameraCommand.TAKE_PHOTO), state.pending)
                    assertEquals("/DCIM/photo.jpg", state.lastPhotoPath)
                    peer.assertNoRequest()
                    peer.reply(CameraCommand.TAKE_PHOTO)
                    client.awaitState { it.canSendCommand }
                    peer.assertNoRequest()
                }
            }
        }
    }

    @Test
    fun commandErrorsReleaseActionsWithoutClosingSession() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.authenticate(client, 12)
                    for (action in CameraAction.entries) {
                        when (action) {
                            CameraAction.TAKE_PHOTO -> client.takePhoto()
                            CameraAction.START_RECORDING -> client.startRecording()
                            CameraAction.STOP_RECORDING -> {
                                peer.send("""{"msg_id":7,"type":"app_status","param":"record"}""")
                                client.awaitState { it.canStopRecording }
                                client.stopRecording()
                            }
                        }
                        assertEquals(action.commandId, peer.request().messageId)
                        peer.reply(action.commandId, -42)
                        if (action != CameraAction.TAKE_PHOTO) {
                            assertEquals(CameraCommand.GET_CONFIG, peer.request().messageId)
                            peer.config("idle")
                        }
                        val state = client.awaitState { it.canSendCommand }
                        assertNull(state.pendingAction)
                        assertEquals(ConnectionStatus.CONNECTED, state.connection)
                        assertEquals(12, state.token)
                        assertTrue(state.error!!.contains("rval=-42"))
                    }
                }
            }
        }
    }

    @Test
    fun actionTimeoutsUseExistingDisconnectionPolicy() {
        for (action in CameraAction.entries) {
            ServerSocket(0).use { server ->
                CameraClient("127.0.0.1", server.localPort, responseTimeoutMillis = 800).use { client ->
                    client.connect()
                    server.accept().use { peer ->
                        peer.authenticate(client, 17, if (action == CameraAction.STOP_RECORDING) "record" else "idle")
                        when (action) {
                            CameraAction.TAKE_PHOTO -> client.takePhoto()
                            CameraAction.START_RECORDING -> client.startRecording()
                            CameraAction.STOP_RECORDING -> client.stopRecording()
                        }
                        assertEquals(action.commandId, peer.request().messageId)
                        val state = client.awaitState { it.connection == ConnectionStatus.DISCONNECTED }
                        assertEquals("Sin respuesta al comando ${action.commandId}", state.error)
                        assertNull(state.token)
                        assertNull(state.pendingAction)
                        assertEquals(RecordingState.UNKNOWN, state.recording)
                        assertTrue(state.pending.isEmpty())
                        assertEquals(-1, peer.getInputStream().read())
                    }
                }
            }
        }
    }

    @Test
    fun statusEventBeforeAcknowledgementIsNotOverwrittenByAcknowledgement() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.authenticate(client, 6)
                    client.startRecording()
                    assertEquals(CameraCommand.START_RECORDING, peer.request().messageId)
                    peer.send("""{"msg_id":7,"type":"app_status","param":"recording"}""")
                    assertEquals(
                        CameraAction.START_RECORDING,
                        client.awaitState { it.recording == RecordingState.RECORDING }.pendingAction
                    )
                    peer.reply(CameraCommand.START_RECORDING)
                    assertEquals(CameraCommand.GET_CONFIG, peer.request().messageId)
                    assertEquals(RecordingState.RECORDING, client.state.value.recording)
                    peer.config("recording")
                    client.awaitState { it.canStopRecording }
                }
            }
        }
    }
}
