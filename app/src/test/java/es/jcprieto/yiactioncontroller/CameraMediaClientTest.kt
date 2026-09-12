package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class CameraMediaClientTest {
    private fun Socket.request(): JsonObject {
        val framer = JsonObjectFramer()
        while (true) {
            val byte = getInputStream().read()
            check(byte >= 0)
            framer.feed(byte.toChar().toString()).firstOrNull()?.let { return Json.parseToJsonElement(it).jsonObject }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray())
        getOutputStream().flush()
    }

    private suspend fun initialize(client: CameraClient, server: ServerSocket, token: Int): Socket {
        client.connect()
        val peer = server.accept()
        peer.soTimeout = 3000
        assertEquals(0, peer.request()["token"]!!.jsonPrimitive.int)
        peer.send("""{"msg_id":257,"rval":0,"param":$token}""")
        peer.request()
        peer.send("""{"msg_id":13,"rval":0,"type":"battery","param":"90"}""")
        peer.request()
        peer.send("""{"msg_id":3,"rval":0,"param":[]}""")
        withTimeout(3000) { client.state.first { it.canSendCommand } }
        return peer
    }

    @Test
    fun typedOperationsShareSingleSessionAndCorrelateRepeatedIds() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                for (token in listOf(17, 29)) {
                    initialize(client, server, token).use { peer ->
                        val cases = listOf(
                            CameraMediaOperation.Total to """"param":31154688""",
                            CameraMediaOperation.Free to """"param":29852096""",
                            CameraMediaOperation.Pwd to """"pwd":"/"""",
                            CameraMediaOperation.ChangeDirectory(CAMERA_SD_ROOT) to """"pwd":"/tmp/fuse_d"""",
                            CameraMediaOperation.ListDirectory to """"listing":[{"private-file.jpg":"1 bytes|date"}]""",
                        )
                        for ((operation, payload) in cases) {
                            val response = async(Dispatchers.Unconfined) { client.requestMedia(operation) }
                            val request = peer.request()
                            assertEquals(operation.id, request["msg_id"]!!.jsonPrimitive.int)
                            assertEquals(token, request["token"]!!.jsonPrimitive.int)
                            operation.arguments()
                                .forEach { (key, value) -> assertEquals(value, request[key]!!.jsonPrimitive.content) }
                            peer.send("""{"msg_id":7,"rval":0,"type":"battery","param":"89"}""")
                            assertFalse(response.isCompleted)
                            val rejected =
                                runCatching { client.requestMedia(CameraMediaOperation.Total) }.exceptionOrNull()
                            assertEquals(CameraMediaError.BUSY, (rejected as CameraMediaException).kind)
                            peer.send("""{"msg_id":${operation.id},"rval":0,$payload}""")
                            val message = withTimeout(3000) { response.await() }
                            assertEquals(operation.id, message.messageId)
                            assertTrue(client.state.value.pending.isEmpty())
                        }
                        assertFalse(client.state.value.lastMessage.orEmpty().contains("private-file"))
                        client.disconnect()
                        assertFalse(client.state.value.authenticated)
                    }
                }
            }
        }
    }

    @Test
    fun rejectionReleasesRequestWithoutNewTokenAndTimeoutClearsSession() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort, responseTimeoutMillis = 700).use { client ->
                initialize(client, server, 31).use { peer ->
                    val rejected =
                        async(Dispatchers.Unconfined) { runCatching { client.requestMedia(CameraMediaOperation.Total) } }
                    peer.request()
                    peer.send("""{"msg_id":5,"rval":-4}""")
                    assertEquals(-4, (rejected.await().exceptionOrNull() as CameraMediaException).rval)
                    assertTrue(client.state.value.canSendCommand)
                    assertTrue(client.state.value.authenticated)
                    val timeout =
                        async(Dispatchers.Unconfined) { runCatching { client.requestMedia(CameraMediaOperation.Free) } }
                    assertEquals("free", peer.request()["type"]!!.jsonPrimitive.content)
                    assertEquals(
                        CameraMediaError.TIMEOUT,
                        (withTimeout(4000) { timeout.await() }.exceptionOrNull() as CameraMediaException).kind
                    )
                    assertFalse(client.state.value.authenticated)
                    assertTrue(client.state.value.pending.isEmpty())
                }
            }
        }
    }

    @Test
    fun cancellingAwaitDoesNotAllowAnotherWireRequestAndDisconnectCompletesPending() = runBlocking {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                initialize(client, server, 41).use { peer ->
                    val first = async(Dispatchers.Unconfined) { client.requestMedia(CameraMediaOperation.Total) }
                    peer.request()
                    first.cancelAndJoin()
                    assertFalse(client.state.value.canSendCommand)
                    peer.send("""{"msg_id":5,"rval":0,"param":100}""")
                    withTimeout(3000) { client.state.first { it.canSendCommand } }
                    val second =
                        async(Dispatchers.Unconfined) { runCatching { client.requestMedia(CameraMediaOperation.Free) } }
                    peer.request()
                    client.disconnect()
                    assertEquals(
                        CameraMediaError.DISCONNECTED,
                        (second.await().exceptionOrNull() as CameraMediaException).kind
                    )
                }
            }
        }
    }
}
