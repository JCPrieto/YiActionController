package es.jcprieto.yiactioncontroller

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Source/manifest contracts complement pure session tests, not Android lifecycle emulation. */
class CameraConnectionServiceContractTest {
    private fun source(name: String) = File("src/main/java/es/jcprieto/yiactioncontroller/$name.kt").readText()

    @Test
    fun serviceIsPrivateConnectedDeviceWithRequiredPermissions() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val permissions = document.getElementsByTagName("uses-permission").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        }
        for (permission in listOf(
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "CHANGE_NETWORK_STATE", "CHANGE_WIFI_STATE", "POST_NOTIFICATIONS"
        ))
            assertTrue(permissions.contains("android.permission.$permission"))
        assertFalse(permissions.contains("android.permission.WAKE_LOCK"))
        val services = document.getElementsByTagName("service")
        assertEquals(1, services.length)
        val service = services.item(0) as org.w3c.dom.Element
        assertEquals(".CameraConnectionService", service.getAttributeNS(android, "name"))
        assertEquals("false", service.getAttributeNS(android, "exported"))
        assertEquals("connectedDevice", service.getAttributeNS(android, "foregroundServiceType"))
    }

    @Test
    fun onlyServiceOwnsClientNetworkAndBindingAndUiClearOnlyUnbinds() {
        val service = source("CameraConnectionService")
        val viewModel = source("CameraViewModel")
        assertEquals(1, Regex("CameraClient\\(").findAll(service).count())
        assertFalse(viewModel.contains("CameraClient("))
        assertFalse(viewModel.contains("CameraWifiConnectionManager("))
        assertFalse(viewModel.contains("CameraNetworkBinding("))
        assertTrue(service.contains("CameraWifiConnectionManager("))
        assertTrue(service.contains("recovery.binding?.previewTransport"))
        assertTrue(viewModel.contains("currentPreviewTransport()"))
        val cleared = viewModel.substringAfter("override fun onCleared()")
        assertTrue(cleared.contains("unbind()"))
        assertTrue(cleared.contains("preview.release()"))
        assertFalse(cleared.contains("disconnect("))
        val unbind = service.substringAfter("override fun onUnbind").substringBefore("override fun onRebind")
        assertFalse(unbind.contains("terminate("))
        assertFalse(unbind.contains("disconnect("))
        assertFalse(service.contains("Media3PreviewPlayer("))
        assertTrue(viewModel.contains("Media3PreviewPlayer("))
        assertFalse(service.contains("wifi?.fail(CameraWifiError.CAMERA_CONNECTION)"))
    }

    @Test
    fun foregroundAndNotificationUseExplicitNonStickyDisconnectWithoutCredentials() {
        val service = source("CameraConnectionService")
        assertTrue(service.contains("return START_NOT_STICKY"))
        assertTrue(service.contains("ServiceCompat.startForeground("))
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(service.contains("ACTION_DISCONNECT -> disconnect()"))
        assertTrue(service.contains("fun disconnect() = terminate(releaseWifi = true)"))
        assertTrue(service.contains("previewClients.values.toList().forEach"))
        assertTrue(service.contains("recovery.clear()"))
        assertTrue(service.contains("wifi?.close()"))
        assertTrue(service.contains("STOP_FOREGROUND_REMOVE"))
        assertTrue(service.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(service.contains(".setAction(ACTION_DISCONNECT)"))
        assertTrue(service.contains("NotificationManager.IMPORTANCE_LOW"))
        assertTrue(service.contains(".setOngoing(true)"))
        assertFalse(service.contains("putExtra("))
        assertFalse(service.contains("getStringExtra("))
        assertFalse(service.contains("var password"))
        assertFalse(service.contains("val password"))
        val blockedCallback = source("CameraWifiConnectionManager")
            .substringAfter("override fun onBlockedStatusChanged").substringBefore("\n            }")
        assertTrue(blockedCallback.contains("request.blocked("))
        assertFalse(blockedCallback.contains("getNetworkCapabilities("))
        assertFalse(blockedCallback.contains("getLinkProperties("))
    }
}
