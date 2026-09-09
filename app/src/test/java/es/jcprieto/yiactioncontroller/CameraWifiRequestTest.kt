package es.jcprieto.yiactioncontroller

import org.junit.Assert.*
import org.junit.Test

class CameraWifiRequestTest {
    private class Fixture {
        val released = mutableListOf<Long>()
        val connected = mutableListOf<String>()
        var lost = 0
        val history = DiagnosticHistory { "test" }
        val request = CameraWifiRequest<String>(
            { released += it }, { connected += it }, { lost++ },
            { history.append("WIFI", it) })

        fun begin() = request.begin("synthetic-ssid")!!
        fun ready(id: Long) {
            request.available(id, "wifi")
            request.capabilities(id, "wifi", true, false, false, false)
            request.route(id, "wifi", true)
        }
    }

    @Test
    fun doubleConnectAndNewUiObserverDoNotCreateRequests() {
        val f = Fixture();
        val id = f.begin()
        assertNull(f.request.begin("another"))
        // A recreated Activity observes the retained owner's flow, without a new begin.
        assertSame(f.request.status, f.request.status)
        f.ready(id)
        assertNull(f.request.begin("another"))
        assertEquals(listOf("wifi"), f.connected)
        assertEquals(id, f.request.active)
    }

    @Test
    fun availableWaitsForWifiAndRouteInEitherOrder() {
        for (routeFirst in listOf(false, true)) {
            val f = Fixture();
            val id = f.begin()
            f.request.available(id, "wifi")
            assertEquals(CameraWifiState.CONNECTING, f.request.status.value.state)
            assertNull(f.request.status.value.network)
            if (routeFirst) f.request.route(id, "wifi", true)
            else f.request.capabilities(id, "wifi", true, false, false, false)
            assertTrue(f.connected.isEmpty())
            if (routeFirst) f.request.capabilities(id, "wifi", true, false, false, false)
            else f.request.route(id, "wifi", true)
            assertEquals(CameraWifiState.CONNECTED, f.request.status.value.state)
            f.request.route(id, "wifi", true)
            f.request.timeout(id)
            assertEquals(listOf("wifi"), f.connected)
            assertTrue(f.released.isEmpty())
        }
    }

    @Test
    fun cellularAndNonWifiRejectedEvenWithRoute() {
        for ((wifi, cellular) in listOf(false to true, true to true, false to false)) {
            val f = Fixture();
            val id = f.begin()
            f.request.available(id, "wrong")
            f.request.route(id, "wrong", true)
            f.request.capabilities(id, "wrong", wifi, cellular, true, true)
            assertEquals(CameraWifiError.WRONG_NETWORK, f.request.status.value.error)
            assertEquals(listOf(id), f.released)
            assertTrue(f.connected.isEmpty())
        }
    }

    @Test
    fun missingRouteTimesOutAndCanRetry() {
        val f = Fixture();
        val id = f.begin()
        f.request.available(id, "wifi")
        f.request.capabilities(id, "wifi", true, false, false, false)
        f.request.route(id, "wifi", false)
        f.request.timeout(id)
        assertEquals(CameraWifiError.NO_ROUTE, f.request.status.value.error)
        assertEquals(listOf(id), f.released)
        assertTrue(f.connected.isEmpty())
        f.ready(f.begin())
        assertEquals(CameraWifiState.CONNECTED, f.request.status.value.state)
    }

    @Test
    fun unavailableAndTimeoutReleaseExactlyOnce() {
        val f = Fixture();
        val id = f.begin()
        f.request.unavailable(id); f.request.timeout(id); f.request.disconnect(); f.request.disconnect()
        assertEquals(listOf(id), f.released)
        val next = f.begin(); f.request.timeout(next)
        assertEquals(CameraWifiState.UNAVAILABLE, f.request.status.value.state)
        assertNull(f.request.status.value.network)
        assertEquals(listOf(id, next), f.released)
    }

    @Test
    fun lostNotifiesBeforeReleaseAndStaleCallbacksCannotResurrectNetwork() {
        val order = mutableListOf<String>()
        val request = CameraWifiRequest<String>({ order += "release" }, {}, { order += "lost" }, {})
        val id = request.begin("synthetic")!!
        request.available(id, "wifi")
        request.lost(id, "other")
        assertEquals(id, request.active)
        request.lost(id, "wifi")
        assertEquals(listOf("lost", "release"), order)
        assertEquals(CameraWifiState.LOST, request.status.value.state)
        assertNull(request.status.value.network)
        val next = request.begin("synthetic")!!
        request.available(id, "wifi"); request.unavailable(id); request.lost(id, "wifi")
        assertEquals(next, request.active)
        assertEquals(CameraWifiState.REQUESTING, request.status.value.state)
    }

    @Test
    fun routeLossAndTcpFailureReleaseRequest() {
        val f = Fixture();
        val id = f.begin(); f.ready(id)
        f.request.route(id, "wifi", false)
        assertEquals(CameraWifiState.LOST, f.request.status.value.state)
        assertEquals(1, f.lost)
        val next = f.begin(); f.ready(next)
        f.request.fail(CameraWifiError.CAMERA_CONNECTION)
        assertEquals(CameraWifiError.CAMERA_CONNECTION, f.request.status.value.error)
        f.request.disconnect()
        assertEquals(listOf(id, next), f.released)
    }

    @Test
    fun diagnosticsNeverIncludeSsidOrCredentialValues() {
        val f = Fixture();
        val id = f.begin(); f.ready(id)
        f.request.fail(CameraWifiError.INVALID_CREDENTIALS)
        assertFalse(f.history.entries.value.joinToString().contains("synthetic-ssid"))
        val raw =
            """{"msg_id":3,"rval":0,"param":[{"wifi_password":"synthetic-secret"},{"key":"passphrase","value":"synthetic-secret"}]}"""
        val state = CameraState().applyMessage(cameraJson.decodeFromString(raw), raw)
        f.history.received(cameraJson.decodeFromString(raw))
        assertFalse(state.toString().contains("synthetic-secret"))
        assertFalse(f.history.entries.value.joinToString().contains("synthetic-secret"))
        val event = """{"msg_id":7,"type":"wifi_password","param":"synthetic-secret"}"""
        assertFalse(
            state.applyMessage(cameraJson.decodeFromString(event), event).toString().contains("synthetic-secret")
        )
    }
}
