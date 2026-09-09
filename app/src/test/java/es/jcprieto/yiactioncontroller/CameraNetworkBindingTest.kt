package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class CameraNetworkBindingTest {
    @Test
    fun requestedNetworkFactoryReachesClientAndPreviewAndBindsBothUdpSockets() = runBlocking {
        val created = AtomicInteger()
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket {
                created.incrementAndGet(); return Socket()
            }

            override fun createSocket(host: String, port: Int): Socket = error("Unexpected overload")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                error("Unexpected overload")

            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unexpected overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                error("Unexpected overload")
        }
        val requestedNetwork = Any()
        val bound = mutableListOf<Pair<Any, DatagramSocket>>()
        val binding = CameraNetworkBinding(requestedNetwork, factory) { bound += requestedNetwork to it }
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            CameraClient("127.0.0.1", server.localPort).use { client ->
                client.connect(binding.socketFactory)
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    assertEquals('{'.code, peer.getInputStream().read())
                    assertEquals(1, created.get())
                }
            }
        }
        var received: PreviewTransport? = null
        val engine = object : PreviewPlayback {
            val signals = PreviewPlaybackSignals()
            override val status = signals.status
            override fun start(transport: PreviewTransport) {
                received = transport
            }

            override fun release() {
                signals.idle()
            }
        }
        val preview = CameraPreviewController(
            this,
            engine,
            { true },
            { CameraControlResult(true) },
            { CameraControlResult(true) })
        try {
            preview.start(binding.previewTransport)
            yield()
            assertSame(factory, received!!.socketFactory)
            DatagramSocket(null).use { rtp ->
                DatagramSocket(null).use { rtcp ->
                    received!!.bindDatagramSocket(rtp)
                    received!!.bindDatagramSocket(rtcp)
                    assertEquals(listOf(requestedNetwork to rtp, requestedNetwork to rtcp), bound)
                }
            }
        } finally {
            preview.release()
        }
    }

    @Test
    fun productionSourcesKeepRoutingLocalAndRequestOwnedOutsideUi() {
        val sourceRoot = File("src/main/java")
        check(sourceRoot.isDirectory)
        val sources = sourceRoot.walkTopDown().filter { it.isFile }.associate { it.name to it.readText() }
        val forbidden = Regex("\\b(bindProcessToNetwork|setProcessDefaultNetwork|startScan|getScanResults)\\s*\\(")
        sources.values.forEach { assertFalse(forbidden.containsMatchIn(it)) }
        val manager = sources.getValue("CameraWifiConnectionManager.kt")
        assertTrue(manager.contains(".removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)"))
        assertFalse(manager.contains(".addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)"))
        val ui = sources.getValue("MainActivity.kt")
        assertFalse(ui.contains("requestNetwork("))
        assertFalse(ui.contains("CameraWifiConnectionManager("))
        assertFalse(ui.contains("rememberSaveable("))
        assertTrue(sources.getValue("CameraViewModel.kt").contains("CameraWifiConnectionManager("))
    }
}
