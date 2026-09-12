package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class CameraSettingsClientTest {
    private fun Socket.request(): JsonObject {
        val framer = JsonObjectFramer()
        while (true) {
            val byte = getInputStream().read()
            check(byte >= 0)
            framer.feed(byte.toChar().toString()).firstOrNull()
                ?.let { return cameraJson.parseToJsonElement(it).jsonObject }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray()); getOutputStream().flush()
    }

    private suspend fun connect(client: CameraClient, server: ServerSocket, token: Int): Socket {
        client.connect()
        val peer = server.accept().apply { soTimeout = 3000 }
        peer.request()
        peer.send("""{"msg_id":257,"rval":0,"param":""" + token + "}")
        peer.request(); peer.send("""{"msg_id":13,"rval":0,"type":"battery","param":"80"}""")
        peer.request(); peer.send("""{"msg_id":3,"rval":0,"param":[{"app_status":"idle"},{"video_stamp":"off"},{"video_resolution":"reference"}]}""")
        withTimeout(3000) { client.state.first { it.canSendCommand } }
        return peer
    }

    @Test
    fun repeatedGetIdsAndSetUseSameSessionAndInterleavedEventsNeverCompletePending() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                for (token in listOf(37, 49)) {
                    connect(client, server, token).use { peer ->
                        val forbidden = runCatching {
                            client.requestSettings(
                                CameraSettingsOperation.SetValue(
                                    "video_stamp",
                                    "date"
                                )
                            )
                        }.exceptionOrNull()
                        assertEquals(CameraSettingsError.UNSUPPORTED_VALUE, (forbidden as CameraSettingsException).kind)
                        for (op in listOf(
                            CameraSettingsOperation.ReadAll, CameraSettingsOperation.ReadAllowed("video_stamp"),
                            CameraSettingsOperation.SetValue("video_stamp", "date"), CameraSettingsOperation.ReadAll
                        )) {
                            val response = async(Dispatchers.Unconfined) { client.requestSettings(op) }
                            val request = peer.request()
                            assertEquals(op.id, request.getValue("msg_id").jsonPrimitive.int)
                            assertEquals(token, request.getValue("token").jsonPrimitive.int)
                            op.arguments()
                                .forEach { (key, value) -> assertEquals(value, request[key]!!.jsonPrimitive.content) }
                            peer.send("""{"msg_id":7,"type":"battery","param":"65"}""")
                            withTimeout(3000) { client.state.first { it.battery == 65 } }
                            assertFalse(response.isCompleted)
                            val busy = runCatching { client.requestMedia(CameraMediaOperation.Total) }.exceptionOrNull()
                            assertEquals(CameraMediaError.BUSY, (busy as CameraMediaException).kind)
                            when (op) {
                                is CameraSettingsOperation.ReadAllowed ->
                                    peer.send("""{"msg_id":3,"rval":0,"param":[{"video_stamp":"settable:off#date#time#date/time"}]}""")

                                is CameraSettingsOperation.SetValue ->
                                    peer.send("""{"msg_id":2,"rval":0,"type":"video_stamp","param":"date"}""")

                                CameraSettingsOperation.ReadAll ->
                                    peer.send("""{"msg_id":3,"rval":0,"param":[{"app_status":"idle"},{"video_stamp":"off"},{"video_resolution":"reference"}]}""")
                            }
                            withTimeout(3000) { response.await() }
                            assertEquals("reference", client.state.value.videoResolution)
                            assertEquals("off", client.state.value.configuration["video_stamp"])
                            assertEquals(RecordingState.IDLE, client.state.value.recording)
                            assertEquals(
                                0,
                                cameraJson.parseToJsonElement(client.state.value.lastRequest!!).jsonObject["token"]!!.jsonPrimitive.int
                            )
                        }
                        client.disconnect()
                    }
                }
            }
        }
    }

    @Test
    fun localPolicyRejectsSecretsInvalidValuesAndActiveTransfer() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                connect(client, server, 61).use { peer ->
                    val detail =
                        async(Dispatchers.Unconfined) { client.requestSettings(CameraSettingsOperation.ReadAllowed("video_stamp")) }
                    peer.request(); peer.send("""{"msg_id":3,"rval":0,"param":[{"video_stamp":"settable:off#date#time#date/time"}]}""")
                    detail.await()
                    for ((op, expected) in listOf(
                        CameraSettingsOperation.SetValue("video_stamp", "on") to CameraSettingsError.UNSUPPORTED_VALUE,
                        CameraSettingsOperation.SetValue(
                            "wifi_password",
                            "synthetic-private"
                        ) to CameraSettingsError.UNSUPPORTED_SETTING,
                        CameraSettingsOperation.ReadAllowed("wifi_ssid") to CameraSettingsError.UNSUPPORTED_SETTING,
                    )) {
                        val error =
                            runCatching { client.requestSettings(op) }.exceptionOrNull() as CameraSettingsException
                        assertEquals(expected, error.kind)
                        assertFalse(error.toString().contains("synthetic-private"))
                    }
                    val lease = client.acquireTransfer()
                    val blocked =
                        runCatching { client.requestSettings(CameraSettingsOperation.ReadAll) }.exceptionOrNull()
                    assertEquals(CameraSettingsError.DOWNLOAD_ACTIVE, (blocked as CameraSettingsException).kind)
                    client.releaseTransfer(lease)
                    assertTrue(client.state.value.canSendCommand)
                    peer.soTimeout = 100
                    assertTrue(runCatching {
                        peer.getInputStream().read()
                    }.exceptionOrNull() is java.net.SocketTimeoutException)
                }
            }
        }
    }

    @Test
    fun unsupportedDetailAndMalformedFullReplyLeaveSessionUsableAndTimeoutIsReported() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort, responseTimeoutMillis = 700).use { client ->
                connect(client, server, 71).use { peer ->
                    val detail =
                        async(Dispatchers.Unconfined) { client.requestSettings(CameraSettingsOperation.ReadAllowed("photo_quality")) }
                    peer.request(); peer.send("""{"msg_id":3,"rval":-25}""")
                    assertEquals(-25, detail.await().rval)
                    assertTrue(client.state.value.canSendCommand)
                    val full = async(Dispatchers.Unconfined) { client.requestSettings(CameraSettingsOperation.ReadAll) }
                    peer.request(); peer.send("""{"msg_id":3,"rval":0,"param":"invalid"}""")
                    assertNull(parseCameraConfiguration(full.await().param))
                    assertTrue(client.state.value.canSendCommand)
                    val timeout =
                        async(Dispatchers.Unconfined) { runCatching { client.requestSettings(CameraSettingsOperation.ReadAll) } }
                    peer.request()
                    assertEquals(
                        CameraSettingsError.TIMEOUT,
                        (withTimeout(3000) { timeout.await() }.exceptionOrNull() as CameraSettingsException).kind
                    )
                    assertFalse(client.state.value.authenticated)
                }
            }
        }
    }
}
