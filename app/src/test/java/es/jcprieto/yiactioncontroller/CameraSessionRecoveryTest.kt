package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

class CameraSessionRecoveryTest {
    private class Factory : SocketFactory() {
        var sockets = 0
        override fun createSocket(): Socket {
            sockets++; return Socket()
        }

        override fun createSocket(host: String, port: Int): Socket = error("Unused")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unused")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            error("Unused")
    }

    private class Fixture {
        var state = CameraState()
        val factory = Factory()
        val attempts = mutableListOf<SocketFactory>()
        var closed = 0
        val messages = mutableListOf<String>()
        val recovery = CameraSessionRecovery<String>(
            { CameraNetworkBinding(it, factory) {} }, { state },
            { attempts += it; state = CameraState(connection = ConnectionStatus.CONNECTING) },
            { closed++; state = CameraState() }, { messages += it })

        init {
            recovery.activate(); network(CameraWifiState.CONNECTED)
        }

        fun network(state: CameraWifiState) = recovery.network(CameraWifiStatus(state, "wifi"))
        fun fail() {
            state = CameraState(error = "Fallo TCP"); recovery.tcp(state)
        }
    }

    @Test
    fun tcpFailureConnectedOrBlockedPreservesBindingAndDoesNotDisconnectWifi() {
        for (blocked in listOf(false, true)) {
            val f = Fixture()
            val binding = f.recovery.binding
            if (blocked) f.network(CameraWifiState.BLOCKED)
            f.fail()
            assertSame(binding, f.recovery.binding)
            assertEquals(0, f.closed)
            assertEquals(1, f.attempts.size)
            assertFalse(f.state.authenticated)
            if (blocked) assertTrue(f.messages.any { it.contains("Suspendido") })
        }
    }

    @Test
    fun unblockRetriesOnceWithSameFactoryAndFailureDoesNotLoop() {
        val f = Fixture()
        f.network(CameraWifiState.BLOCKED); f.fail()
        f.network(CameraWifiState.CONNECTED)
        f.recovery.tcp(f.state)
        f.fail()
        repeat(3) { f.network(CameraWifiState.CONNECTED); f.recovery.tcp(f.state) }
        assertEquals(2, f.attempts.size)
        assertTrue(f.attempts.all { it === f.factory })
        assertTrue(f.messages.any { it.contains("Recuperación tras UNBLOCK fallida") })
        f.recovery.retry()
        assertEquals(3, f.attempts.size)
    }

    @Test
    fun liveTcpDoesNotReconnectAndEachNewBlockedTransitionHasOneBudget() {
        val f = Fixture()
        f.state = CameraState(connection = ConnectionStatus.CONNECTED, authenticated = true)
        f.network(CameraWifiState.BLOCKED); f.network(CameraWifiState.CONNECTED)
        assertEquals(1, f.attempts.size)
        repeat(2) {
            f.network(CameraWifiState.BLOCKED); f.fail()
            f.network(CameraWifiState.CONNECTED)
            f.state = CameraState(connection = ConnectionStatus.CONNECTED, authenticated = true)
            f.recovery.tcp(f.state)
        }
        assertEquals(3, f.attempts.size)
        assertEquals(2, f.messages.count { it.contains("Reconectado con nuevo token") })
    }

    @Test
    fun lossCancelsPendingRecoveryAndCleanupIsIdempotent() {
        val f = Fixture()
        f.network(CameraWifiState.BLOCKED); f.fail(); f.network(CameraWifiState.CONNECTED)
        assertEquals(ConnectionStatus.CONNECTING, f.state.connection)
        f.recovery.clear(); f.recovery.clear()
        f.network(CameraWifiState.CONNECTED); f.recovery.retry()
        assertEquals(1, f.closed)
        assertNull(f.recovery.binding)
        assertEquals(2, f.attempts.size)
        assertEquals(ConnectionStatus.DISCONNECTED, f.state.connection)
    }

    @Test
    fun wifiFlowUnblockDirectlyTriggersRecoveryWithoutAnotherRequest() = runBlocking {
        val f = Fixture()
        f.recovery.clear()
        f.attempts.clear()
        f.recovery.activate()
        var releases = 0
        val request = CameraWifiRequest<String>({ releases++ }, {}, { f.recovery.clear() }, {})
        val observer = launch(Dispatchers.Unconfined) { request.status.collect { f.recovery.network(it) } }
        try {
            val id = request.begin("synthetic")!!
            request.available(id, "wifi")
            request.capabilities(id, "wifi", true, false, false, false)
            request.route(id, "wifi", true)
            request.blocked(id, "wifi", true)
            f.fail()
            request.blocked(id, "wifi", false)
            assertEquals(2, f.attempts.size)
            assertEquals(id, request.active)
            assertEquals(0, releases)
            request.lost(id, "wifi")
            assertNull(f.recovery.binding)
            assertEquals(1, releases)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun realTcpRecoveryUsesSameFactoryNewLoginAndClearsOldToken() = runBlocking {
        val factory = Factory()
        ServerSocket(0).use { server ->
            server.soTimeout = 4_000
            CameraClient("127.0.0.1", server.localPort).use { client ->
                val coordinator = CameraSessionRecovery<String>(
                    { CameraNetworkBinding(it, factory) {} }, { client.state.value },
                    { client.connect(it) }, { client.disconnect() }, {})
                coordinator.activate()
                coordinator.network(CameraWifiStatus(CameraWifiState.CONNECTED, "wifi"))
                for (token in listOf(8, 21)) {
                    server.accept().use { peer ->
                        peer.soTimeout = 4_000
                        assertEquals(CameraRequest(257, 0), peer.request())
                        peer.send("""{"msg_id":257,"rval":0,"param":$token}""")
                        assertEquals(CameraRequest(13, token), peer.request())
                        peer.send("""{"msg_id":13,"rval":0,"type":"battery","param":"80"}""")
                        assertEquals(CameraRequest(3, token), peer.request())
                        peer.send("""{"msg_id":3,"rval":0,"param":[{"app_status":"idle"}]}""")
                        val state = withTimeout(4_000) { client.state.first { it.canSendCommand } }
                        assertTrue(state.authenticated)
                        coordinator.tcp(state)
                        coordinator.network(CameraWifiStatus(CameraWifiState.BLOCKED, "wifi"))
                    }
                    val failed =
                        withTimeout(4_000) { client.state.first { it.connection == ConnectionStatus.DISCONNECTED } }
                    assertFalse(failed.authenticated)
                    coordinator.tcp(failed)
                    assertNotNull(coordinator.binding)
                    if (token == 8) coordinator.network(CameraWifiStatus(CameraWifiState.CONNECTED, "wifi"))
                }
                assertEquals(2, factory.sockets)
                assertSame(factory, coordinator.binding!!.previewTransport.socketFactory)
                coordinator.clear()
            }
        }
    }

    private fun Socket.request(): CameraRequest {
        val framer = JsonObjectFramer()
        while (true) {
            val byte = getInputStream().read()
            check(byte >= 0)
            framer.feed(byte.toChar().toString()).firstOrNull()?.let { return cameraJson.decodeFromString(it) }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray())
        getOutputStream().flush()
    }
}
