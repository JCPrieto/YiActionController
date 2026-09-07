package es.jcprieto.yiactioncontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraNetworkSelectionTest {
    @Test
    fun neverSelectsCellularOrDefaultRoute() {
        assertNull(
            selectCameraNetwork(
                listOf(
                    CameraNetworkCandidate("mobile", false, true, 32, true),
                    CameraNetworkCandidate("unrelated wifi", true, false, 0, true),
                )
            )
        )
    }

    @Test
    fun acceptsLocalOnlyWifiWithoutInternet() {
        assertEquals("yi", selectCameraNetwork(listOf(CameraNetworkCandidate("yi", true, false, 24, false))))
    }

    @Test
    fun prefersSpecificCameraSubnetOverBroadWifiRoute() {
        assertEquals(
            "yi", selectCameraNetwork(
                listOf(
                    CameraNetworkCandidate("broad wifi", true, false, 16, true),
                    CameraNetworkCandidate("yi", true, false, 24, false),
                    CameraNetworkCandidate("mobile", false, true, 32, true),
                )
            )
        )
        assertNull(selectCameraNetwork(listOf(CameraNetworkCandidate("no route", true, false, null))))
    }
}
