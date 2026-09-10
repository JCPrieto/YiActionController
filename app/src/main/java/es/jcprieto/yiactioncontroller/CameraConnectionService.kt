package es.jcprieto.yiactioncontroller

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Binder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Started + locally bound. Owns the camera connection, never the player or UI. */
class CameraConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = CameraClient()
    val cameraState = client.state
    val diagnostics = client.diagnostics
    private val mutableActive = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()
    private val mutableWifi = MutableStateFlow(CameraWifiStatus<Network>())
    val wifiStatus = mutableWifi.asStateFlow()
    private val mutableServiceError = MutableStateFlow<String?>(null)
    val serviceError = mutableServiceError.asStateFlow()
    private var wifi: CameraWifiConnectionManager? = null
    private var manual: CameraNetworkProvider? = null
    private var manualJob: Job? = null
    private var awaitingCredentials: Job? = null
    private var cleaning = false
    private val previewClients = mutableMapOf<Any, (Boolean) -> Unit>()
    private val recovery = CameraSessionRecovery<Network>(
        createBinding = { network -> CameraNetworkBinding(network, network.socketFactory) { network.bindSocket(it) } },
        tcpState = { cameraState.value }, connectTcp = { client.connect(it) },
        disconnectTcp = { client.disconnect() }, diagnostic = { diagnostics.append("TCP", it) },
    )

    inner class LocalBinder : Binder() {
        val service: CameraConnectionService get() = this@CameraConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Conexión con cámara YI", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            wifi = CameraWifiConnectionManager(
                this, ready = {}, lost = {},
                diagnostic = { diagnostics.append("WIFI", it) })
            scope.launch {
                wifi!!.status.collect { status ->
                    mutableWifi.value = status
                    if (active.value && !cleaning) {
                        recovery.network(status)
                        if (status.state in setOf(
                                CameraWifiState.LOST,
                                CameraWifiState.ERROR,
                                CameraWifiState.UNAVAILABLE
                            )
                        )
                            terminate(releaseWifi = false, networkLost = status.state == CameraWifiState.LOST)
                        else updateNotification()
                    }
                }
            }
        }
        scope.launch {
            cameraState.collect {
                if (active.value) {
                    recovery.tcp(it)
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_CONNECT -> {
                if (!active.value) {
                    try {
                        ServiceCompat.startForeground(
                            this, NOTIFICATION_ID, notification("Conectando con cámara YI…"),
                            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
                        )
                        mutableServiceError.value = null
                        diagnostics.append("SERVICE", "Foreground iniciado")
                        // Binder delivers credentials only after this promotion, never via Intent extras.
                        awaitingCredentials = scope.launch {
                            delay(30_000)
                            disconnect()
                        }
                        mutableActive.value = true
                    } catch (_: RuntimeException) {
                        mutableServiceError.value =
                            "Android no permitió iniciar el servicio de conexión. Reintenta desde la app visible."
                        diagnostics.append("SERVICE", "No se pudo iniciar foreground")
                        stopSelf()
                    }
                }
            }

            else -> if (!active.value) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): Binder {
        diagnostics.append("SERVICE", "UI vinculada")
        return LocalBinder()
    }

    override fun onUnbind(intent: Intent): Boolean {
        diagnostics.append("SERVICE", "UI desvinculada")
        return true
    }

    override fun onRebind(intent: Intent) {
        super.onRebind(intent)
        diagnostics.append("SERVICE", "UI vinculada")
    }

    internal fun attachPreview(owner: Any, release: (Boolean) -> Unit) {
        previewClients[owner] = release
    }

    internal fun detachPreview(owner: Any) {
        previewClients.remove(owner)
    }

    fun connect(ssid: String, password: String) {
        if (!active.value || cleaning) return
        awaitingCredentials?.cancel(); awaitingCredentials = null
        recovery.activate()
        if (Build.VERSION.SDK_INT >= 29) wifi?.connect(ssid, password) else connectManual()
    }

    private fun connectManual() {
        if (manual != null || cameraState.value.connection != ConnectionStatus.DISCONNECTED) return
        manual = CameraNetworkProvider(this)
        val network = manual!!.refresh()
        if (network != null) {
            mutableWifi.value = CameraWifiStatus(CameraWifiState.CONNECTED, network)
            recovery.network(wifiStatus.value)
        } else client.connect() // API 26–28 fallback retains the existing manually selected route.
        manualJob = scope.launch {
            manual!!.network.collect { available ->
                if (recovery.binding != null && recovery.binding!!.network != available) {
                    mutableWifi.value = CameraWifiStatus(CameraWifiState.LOST, error = CameraWifiError.NETWORK_LOST)
                    terminate(releaseWifi = false, networkLost = true)
                } else if (available != null && recovery.binding == null && active.value) {
                    mutableWifi.value = CameraWifiStatus(CameraWifiState.CONNECTED, available)
                    recovery.network(wifiStatus.value)
                }
            }
        }
    }

    internal fun currentPreviewTransport(): PreviewTransport? =
        if (active.value && wifiStatus.value.state == CameraWifiState.CONNECTED) recovery.binding?.previewTransport else null

    private fun canControl() = active.value && wifiStatus.value.state != CameraWifiState.BLOCKED
    fun retryTcp() {
        if (canControl()) recovery.retry()
    }

    fun refresh() {
        if (canControl()) client.refresh()
    }

    fun takePhoto() {
        if (canControl()) client.takePhoto()
    }

    fun startRecording() {
        if (canControl()) client.startRecording()
    }

    fun stopRecording() {
        if (canControl()) client.stopRecording()
    }

    suspend fun startPreview(): CameraControlResult =
        if (canControl()) client.startPreview() else unavailableControl()

    suspend fun stopPreview(): CameraControlResult {
        if (!canControl()) return unavailableControl()
        val available = withTimeoutOrNull(6_000) {
            cameraState.first { it.canSendCommand || it.connection == ConnectionStatus.DISCONNECTED }
        }
        return if (canControl() && available?.canSendCommand == true) client.stopPreview() else unavailableControl()
    }

    suspend fun stopPreviewAndAwaitVfStop(): CameraControlResult =
        if (canControl()) client.stopPreviewAndAwaitVfStop() else unavailableControl()

    private fun unavailableControl() = CameraControlResult(false, "Sesión de cámara no disponible o red bloqueada")

    fun disconnect() = terminate(releaseWifi = true)

    private fun terminate(releaseWifi: Boolean, networkLost: Boolean = false) {
        if (cleaning) return
        cleaning = true
        try {
            awaitingCredentials?.cancel(); awaitingCredentials = null
            previewClients.values.toList().forEach { it(networkLost) }
            recovery.clear()
            if (releaseWifi) {
                if (Build.VERSION.SDK_INT >= 29) wifi?.close()
                mutableWifi.value = CameraWifiStatus()
            }
            manualJob?.cancel(); manualJob = null
            manual?.close(); manual = null
            if (active.value) diagnostics.append("SERVICE", "Detenido")
            mutableActive.value = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            cleaning = false
        }
    }

    // The target Activity hosts Media3; the service itself never creates a player.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, CameraConnectionService::class.java).setAction(ACTION_DISCONNECT), flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera_connection)
            .setContentTitle("Conexión con cámara YI").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Desconectar", disconnect).build()
    }

    private fun updateNotification() {
        if (!active.value) return
        val text = when {
            wifiStatus.value.state == CameraWifiState.BLOCKED -> "Conexión con cámara temporalmente bloqueada"
            cameraState.value.connection == ConnectionStatus.CONNECTED -> "YI Action Camera conectada"
            cameraState.value.error != null -> "Sesión TCP desconectada; abre la app para reintentar"
            else -> "Conectando con cámara YI…"
        }

        // Updating an existing FGS notification does not require a notification permission grant.
        @Suppress("MissingPermission")
        fun notifyConnection() =
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        notifyConnection()
    }

    override fun onDestroy() {
        terminate(releaseWifi = true)
        previewClients.clear()
        client.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "es.jcprieto.yiactioncontroller.CONNECT"
        const val ACTION_DISCONNECT = "es.jcprieto.yiactioncontroller.DISCONNECT"
        private const val CHANNEL = "camera_connection"
        private const val NOTIFICATION_ID = 1
    }
}
