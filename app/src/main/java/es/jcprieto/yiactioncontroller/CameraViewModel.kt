package es.jcprieto.yiactioncontroller

import android.app.Application
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val client = CameraClient()
    val state = client.state
    val diagnosticHistory = client.diagnostics.entries
    fun clearDiagnosticHistory() = client.diagnostics.clear()
    private val networks = CameraNetworkProvider(application)
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
        })
    val previewState = preview.status
    val player = playback.player
    val cameraNetwork = networks.network

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
            state.collect { if (it.connection == ConnectionStatus.DISCONNECTED) preview.cameraDisconnected() }
        }
        viewModelScope.launch {
            networks.network.collect { if (previewNetwork != null && it != previewNetwork) preview.networkLost() }
        }
    }

    fun connect() {
        val factory = networks.refresh()?.socketFactory
        if (factory == null) client.connect() else client.connect(factory)
    }

    fun disconnect() {
        preview.cameraDisconnected()
        previewNetwork = null
        client.disconnect()
    }

    fun startPreview() {
        client.diagnostics.append("UI", "Iniciar vista previa")
        previewNetwork = networks.refresh()
        preview.start(previewNetwork?.let { network ->
            PreviewTransport(network.socketFactory) { socket -> network.bindSocket(socket) }
        })
    }

    fun stopPreview() {
        client.diagnostics.append("UI", "Detener vista previa")
        preview.stop()
    }

    fun onBackground() {
        client.diagnostics.append("APP", "Segundo plano: detener preview")
        preview.stop()
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
        networks.close()
        client.close()
    }
}
