package es.jcprieto.yiactioncontroller

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Network
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val service = MutableStateFlow<CameraConnectionService?>(null)
    private var bound = false
    private var connectJob: Job? = null

    // Memory only, never SavedState or StateFlow; cleared immediately after Binder delivery.
    private var pendingSsid: String? = null
    private var pendingPassword: String? = null
    private var permissionsReady = false
    private val mutablePermissionPending = MutableStateFlow(false)
    val permissionPending = mutablePermissionPending.asStateFlow()
    private val uiError = MutableStateFlow<String?>(null)
    val connectionError =
        combine(uiError, service.flatMapLatest { it?.serviceError ?: flowOf(null) }) { local, remote ->
            local ?: remote
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val state = service.flatMapLatest { it?.cameraState ?: flowOf(CameraState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraState())
    val wifiStatus = service.flatMapLatest { it?.wifiStatus ?: flowOf(CameraWifiStatus<Network>()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraWifiStatus())
    val serviceActive = service.flatMapLatest { it?.active ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val diagnosticHistory = service.flatMapLatest { it?.diagnostics?.entries ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cameraNetwork = wifiStatus.map { it.network }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val playback = Media3PreviewPlayer(application)
    private val preview = CameraPreviewController(
        viewModelScope, playback,
        connected = { service.value?.cameraState?.value?.connection == ConnectionStatus.CONNECTED },
        startControl = { service.value?.startPreview() ?: unavailable() },
        stopControl = { service.value?.stopPreview() ?: unavailable() },
        canRestart = {
            state.value.canSendCommand && state.value.recording == RecordingState.IDLE &&
                    wifiStatus.value.state != CameraWifiState.BLOCKED
        },
        stopAndConfirm = { service.value?.stopPreviewAndAwaitVfStop() ?: unavailable() },
    )
    val previewState = preview.status
    val player = playback.player
    private fun unavailable() = CameraControlResult(false, "Sesión de cámara no disponible")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = (binder as CameraConnectionService.LocalBinder).service
            service.value = connected
            connected.attachPreview(this@CameraViewModel) { lost ->
                if (lost) preview.networkLost() else preview.cameraDisconnected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service.value = null
            preview.cameraDisconnected()
        }

        override fun onBindingDied(name: ComponentName) {
            unbind()
            preview.cameraDisconnected()
            uiError.value = "El servicio dejó de estar disponible. Vuelve a conectar desde la app."
        }

        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }

    init {
        bind()
        viewModelScope.launch {
            previewState.collect {
                log(
                    "PREVIEW",
                    "estado=${it.state} control=${it.control} pendiente=${it.controlPending} error=${it.errorType}"
                )
            }
        }
        viewModelScope.launch {
            state.collect { if (it.connection == ConnectionStatus.DISCONNECTED) preview.cameraDisconnected() }
        }
        viewModelScope.launch {
            wifiStatus.collect { if (it.state == CameraWifiState.BLOCKED) preview.stop() }
        }
    }

    private fun bind() {
        if (bound) return
        try {
            bound =
                app.bindService(Intent(app, CameraConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) uiError.value = "No se pudo vincular el servicio de conexión."
        } catch (_: RuntimeException) {
            uiError.value = "No se pudo vincular el servicio de conexión."
        }
    }

    private fun unbind() {
        service.value?.detachPreview(this)
        if (bound) {
            bound = false; app.unbindService(connection)
        }
        service.value = null
    }

    fun prepareConnection(ssid: String, password: String): Boolean {
        if (permissionPending.value || connectJob?.isActive == true || serviceActive.value) return false
        pendingSsid = ssid
        pendingPassword = password
        mutablePermissionPending.value = true
        uiError.value = null
        return true
    }
    fun permissionResult(granted: Boolean) {
        if (pendingSsid == null) return
        if (granted) permissionsReady = true
        else {
            clearCredentials(); uiError.value = CameraWifiError.PERMISSION_DENIED.description
        }
    }

    /** Called by the visible Activity, including after a runtime permission dialog. */
    fun startConnectionIfReady() {
        if (!permissionsReady || connectJob?.isActive == true) return
        permissionsReady = false
        bind()
        if (!bound) {
            clearCredentials(); return
        }
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, CameraConnectionService::class.java).setAction(CameraConnectionService.ACTION_CONNECT)
            )
        } catch (_: RuntimeException) {
            clearCredentials()
            uiError.value = "Android no permitió iniciar el servicio. Reintenta desde la app visible."
            return
        }
        connectJob = viewModelScope.launch {
            val connected = withTimeoutOrNull(10_000) { service.filterNotNull().first() }
            val started = connected?.let { withTimeoutOrNull(10_000) { it.active.first { active -> active } } }
            if (started == true) {
                val ssid = pendingSsid
                val password = pendingPassword
                clearCredentials()
                if (ssid != null && password != null) connected.connect(ssid, password)
            } else {
                clearCredentials()
                uiError.value = "No se pudo iniciar el servicio de conexión."
                connected?.disconnect()
            }
        }
    }

    private fun clearCredentials() {
        pendingSsid = null
        pendingPassword = null
        permissionsReady = false
        mutablePermissionPending.value = false
    }
    fun disconnect() {
        connectJob?.cancel()
        clearCredentials()
        preview.cameraDisconnected()
        service.value?.disconnect()
    }

    private fun log(tag: String, message: String) {
        service.value?.diagnostics?.append(tag, message)
    }

    fun clearDiagnosticHistory() {
        service.value?.diagnostics?.clear()
    }

    fun retryTcp() {
        log("UI", "Reintentar sesión TCP"); service.value?.retryTcp()
    }

    fun startPreview() {
        log("UI", "Iniciar vista previa"); preview.start(service.value?.currentPreviewTransport())
    }

    fun stopPreview() {
        log("UI", "Detener vista previa"); preview.stop()
    }
    fun restartPreview() {
        log("UI", "Reiniciar vista previa: un intento 260 + vf_stop + 259")
        preview.restart(service.value?.currentPreviewTransport())
    }

    fun onBackground() {
        log("APP", "Segundo plano: detener preview"); preview.stop()
    }

    fun onForeground() {
        log("APP", "Primer plano: Wi-Fi=${wifiStatus.value.state} TCP=${state.value.connection}")
    }

    fun refresh() {
        log("UI", "Consultar batería y configuración"); service.value?.refresh()
    }

    fun takePhoto() {
        log("UI", "Hacer foto"); service.value?.takePhoto()
    }

    fun startRecording() {
        log("UI", "Iniciar grabación"); service.value?.startRecording()
    }

    fun stopRecording() {
        log("UI", "Detener grabación"); service.value?.stopRecording()
    }
    override fun onCleared() {
        connectJob?.cancel()
        clearCredentials()
        preview.release()
        unbind() // Started service owns the connection independently of the UI.
    }
}
