package es.jcprieto.yiactioncontroller

import android.app.Application
import android.net.Network
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val client = CameraClient()
    val state = client.state
    val diagnosticHistory = client.diagnostics.entries
    fun clearDiagnosticHistory() = client.diagnostics.clear()
    private val networks = if (Build.VERSION.SDK_INT < 29) CameraNetworkProvider(application) else null
    private var binding: CameraNetworkBinding<Network>? = null
    private var pendingSsid: String? = null
    private var pendingPassword: String? = null
    private val mutablePermissionPending = MutableStateFlow(false)
    val permissionPending = mutablePermissionPending.asStateFlow()
    private val playback = Media3PreviewPlayer(application)
    private var previewNetwork: Network? = null
    private val preview = CameraPreviewController(
        viewModelScope, playback,
        connected = { state.value.connection == ConnectionStatus.CONNECTED },
        startControl = { client.startPreview() },
        stopControl = {
            val available = withTimeoutOrNull(6_000) {
                state.first { it.canSendCommand || it.connection == ConnectionStatus.DISCONNECTED }
            }
            if (available?.canSendCommand == true) client.stopPreview()
            else CameraControlResult(false, "No se pudo enviar STOP_PREVIEW: cámara desconectada o ocupada")
        },
        canRestart = { state.value.canSendCommand && state.value.recording == RecordingState.IDLE },
        stopAndConfirm = { client.stopPreviewAndAwaitVfStop() },
    )
    val previewState = preview.status
    val player = playback.player
    private val wifi = if (Build.VERSION.SDK_INT >= 29) CameraWifiConnectionManager(
        application,
        ready = { network ->
            val selected = CameraNetworkBinding(network, network.socketFactory) { network.bindSocket(it) }
            binding = selected
            client.connect(selected.socketFactory)
        },
        lost = { networkInvalidated() },
        diagnostic = { client.diagnostics.append("WIFI", it) },
    ) else null
    val wifiStatus = if (Build.VERSION.SDK_INT >= 29) wifi!!.status
    else MutableStateFlow(CameraWifiStatus<Network>()).asStateFlow()
    private val mutableCameraNetwork = MutableStateFlow<Network?>(null)
    val cameraNetwork = mutableCameraNetwork.asStateFlow()

    init {
        viewModelScope.launch {
            previewState.collect {
                client.diagnostics.append(
                    "PREVIEW",
                    "estado=${it.state} control=${it.control} pendiente=${it.controlPending} error=${it.errorType}"
                )
            }
        }
        viewModelScope.launch {
            state.collect {
                if (it.connection == ConnectionStatus.DISCONNECTED) {
                    preview.cameraDisconnected()
                    if (Build.VERSION.SDK_INT >= 29 && binding != null) {
                        binding = null
                        wifi?.fail(CameraWifiError.CAMERA_CONNECTION)
                    }
                }
            }
        }
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= 29) wifiStatus.collect { mutableCameraNetwork.value = it.network }
            else networks?.network?.collect {
                mutableCameraNetwork.value = it
                if (previewNetwork != null && it != previewNetwork) preview.networkLost()
            }
        }
    }

    fun connectManual() {
        if (Build.VERSION.SDK_INT >= 29) return
        val factory = networks?.refresh()?.socketFactory
        if (factory == null) client.connect() else client.connect(factory)
    }

    // Kept outside saved state/StateFlow; survives a permission-dialog rotation only in memory.
    fun prepareConnection(ssid: String, password: String): Boolean {
        if (mutablePermissionPending.value || wifiStatus.value.state in setOf(
                CameraWifiState.REQUESTING, CameraWifiState.CONNECTING, CameraWifiState.CONNECTED
            )
        ) return false
        pendingSsid = ssid
        pendingPassword = password
        mutablePermissionPending.value = true
        return true
    }

    fun permissionResult(granted: Boolean) {
        val ssid = pendingSsid
        val password = pendingPassword
        pendingSsid = null; pendingPassword = null
        mutablePermissionPending.value = false
        if (ssid == null || password == null || Build.VERSION.SDK_INT < 29) return
        if (granted) wifi?.connect(ssid, password) else wifi?.fail(CameraWifiError.PERMISSION_DENIED)
    }

    private fun networkInvalidated() {
        if (wifiStatus.value.error == CameraWifiError.NETWORK_LOST) preview.networkLost()
        else preview.cameraDisconnected()
        binding = null
        previewNetwork = null
        // A TCP failure already closed the session. Do not erase its diagnostic error during Wi-Fi cleanup.
        if (state.value.connection != ConnectionStatus.DISCONNECTED) client.disconnect()
    }

    fun disconnect() {
        preview.cameraDisconnected()
        previewNetwork = null
        binding = null
        client.disconnect()
        pendingSsid = null; pendingPassword = null
        mutablePermissionPending.value = false
        if (Build.VERSION.SDK_INT >= 29) wifi?.close()
    }

    fun startPreview() {
        client.diagnostics.append("UI", "Iniciar vista previa")
        preview.start(previewTransport())
    }

    fun stopPreview() {
        client.diagnostics.append("UI", "Detener vista previa")
        preview.stop()
    }

    fun restartPreview() {
        client.diagnostics.append("UI", "Reiniciar vista previa: un intento 260 + vf_stop + 259")
        preview.restart(previewTransport())
    }

    private fun previewTransport(): PreviewTransport? {
        if (Build.VERSION.SDK_INT >= 29) return binding?.previewTransport
        previewNetwork = networks?.refresh()
        return previewNetwork?.let { network ->
            PreviewTransport(network.socketFactory) { socket -> network.bindSocket(socket) }
        }
    }

    fun onBackground() {
        client.diagnostics.append("APP", "Segundo plano: detener preview")
        preview.stop()
    }

    fun onForeground() {
        client.diagnostics.append("APP", "Primer plano: Wi-Fi=${wifiStatus.value.state} TCP=${state.value.connection}")
    }

    fun refresh() {
        client.diagnostics.append("UI", "Consultar batería y configuración")
        client.refresh()
    }

    fun takePhoto() {
        client.diagnostics.append("UI", "Hacer foto")
        client.takePhoto()
    }

    fun startRecording() {
        client.diagnostics.append("UI", "Iniciar grabación")
        client.startRecording()
    }

    fun stopRecording() {
        client.diagnostics.append("UI", "Detener grabación")
        client.stopRecording()
    }
    override fun onCleared() {
        preview.release()
        binding = null
        client.close()
        pendingSsid = null; pendingPassword = null
        if (Build.VERSION.SDK_INT >= 29) wifi?.close()
        networks?.close()
    }
}
