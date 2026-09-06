package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class CameraActionsTest {
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
