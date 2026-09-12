package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CameraClientTest {
    private fun Socket.request(): CameraRequest {
        val framer = JsonObjectFramer()
        while (true) {
            val byte = getInputStream().read()
            check(byte >= 0) { "Client closed before sending request" }
            framer.feed(byte.toChar().toString()).firstOrNull()?.let {
                return cameraJson.decodeFromString(it)
            }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
        getOutputStream().flush()
    }

    private fun CameraClient.awaitState(predicate: (CameraState) -> Boolean): CameraState = runBlocking {
        withTimeout(4_000) { state.first(predicate) }
    }

    @Test
    fun dynamicTokensContinuousEventsAndReconnect() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                for (token in listOf(4, 19)) {
                    client.connect()
                    server.accept().use { peer ->
                        peer.soTimeout = 3_000
                        assertEquals(CameraRequest(257, 0), peer.request())
                        peer.send("""{"rval":0,"msg_id":257,"param":$token}""")
                        assertEquals(CameraRequest(13, token), peer.request())
                        peer.soTimeout = 300
                        assertThrows(SocketTimeoutException::class.java) { peer.getInputStream().read() }
                        peer.soTimeout = 3_000
                        // Complete the battery query before the next command may be sent.
                        peer.send("""{"rval":0,"msg_id":13,"type":"battery","param":"93"}{"msg_id":7,"type":"battery","param":"92"}""")
                        assertEquals(CameraRequest(3, token), peer.request())
                        peer.send("""{"rval":0,"msg_id":3,"param":[{"sw_version":"test"}]}""")
                        val state = client.awaitState { it.battery == 92 && it.pending.isEmpty() }
                        assertTrue(state.authenticated)
                        assertEquals("test", state.firmware)
                        assertEquals(ConnectionStatus.CONNECTED, state.connection)
                        client.refresh()
                        assertEquals(CameraRequest(13, token), peer.request())
                        peer.send("""{"rval":0,"msg_id":13,"type":"battery","param":"92"}""")
                        assertEquals(CameraRequest(3, token), peer.request())
                        client.disconnect()
                        assertEquals(-1, peer.getInputStream().read())
                        assertEquals(CameraState(), client.state.value)
                    }
                }
            }
        }
    }

    @Test
    fun utf8CharacterCanSpanReadsAndSocketTimeouts() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    peer.request()
                    peer.send("""{"rval":0,"msg_id":257,"param":11}""")
                    peer.request()
                    peer.send("""{"rval":0,"msg_id":13,"type":"battery","param":"93"}""")
                    peer.request()
                    val raw = """{"rval":0,"msg_id":3,"param":[{"sw_version":"versión"}]}""".toByteArray()
                    val split = raw.indexOf(0xc3.toByte()) + 1
                    peer.getOutputStream().write(raw, 0, split)
                    Thread.sleep(350) // Cross the client's read timeout in the middle of ó.
                    peer.getOutputStream().write(raw, split, raw.size - split)
                    assertEquals("versión", client.awaitState { it.firmware != null }.firmware)
                }
            }
        }
    }

    @Test
    fun eofClearsTokenAndAllowsReconnect() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    peer.request()
                    peer.send("""{"rval":0,"msg_id":257,"param":4}""")
                    peer.request()
                }
                val state = client.awaitState { it.error != null }
                assertFalse(state.authenticated)
                assertEquals(ConnectionStatus.DISCONNECTED, state.connection)
                assertTrue(client.diagnostics.entries.value.any { it.contains("tipo=EOF") })
            }
        }
    }

    @Test
    fun authenticationErrorsInvalidJsonAndMissingRepliesDisconnect() {
        for (reply in listOf(
            """{"rval":-1,"msg_id":257}""",
            """{"rval":0,"msg_id":257,"param":0}""",
            "{invalid}",
            ""
        )) {
            ServerSocket(0).use { server ->
                CameraClient("127.0.0.1", server.localPort, responseTimeoutMillis = 400).use { client ->
                    client.connect()
                    server.accept().use { peer ->
                        peer.soTimeout = 3_000
                        peer.request()
                        peer.send(reply)
                        val state = client.awaitState { it.error != null }
                        assertFalse(state.authenticated)
                        assertEquals(ConnectionStatus.DISCONNECTED, state.connection)
                    }
                }
            }
        }
    }

    @Test
    fun disconnectUnblocksAnIdleRead() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.request()
                    val eof = CompletableFuture.supplyAsync { peer.getInputStream().read() }
                    client.disconnect()
                    assertEquals(-1, eof.get(2, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun batteryEventDoesNotSatisfyPendingBatteryQuery() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort, responseTimeoutMillis = 700).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    peer.request()
                    peer.send("""{"rval":0,"msg_id":257,"param":4}""")
                    peer.request()
                    peer.send("""{"msg_id":7,"type":"battery","param":"92"}""")
                    assertTrue(13 in client.awaitState { it.battery == 92 }.pending)
                    val expired = client.awaitState { it.error != null }
                    assertTrue(expired.error!!.contains("comando 13"))
                    assertEquals(
                        CameraRequest(13, 0), // Diagnostic snapshot redacts the live token.
                        cameraJson.decodeFromString<CameraRequest>(expired.lastRequest!!)
                    )
                    assertEquals("""{"msg_id":7,"type":"battery","param":"92"}""", expired.lastMessage)
                    // No configuration request was sent after an event or after the timeout.
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
        }
    }

    @Test
    fun truncatedJsonAtEofReportsFramingError() {
        ServerSocket(0).use { server ->
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    peer.request()
                    peer.send("{\"msg_id\":257")
                }
                assertTrue(client.awaitState { it.error != null }.error!!.contains("JSON incompleto"))
            }
        }
    }
}
