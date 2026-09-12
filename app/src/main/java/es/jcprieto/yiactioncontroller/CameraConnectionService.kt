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
import java.io.File

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
    private val notificationUpdates = CameraNotificationUpdates()
    private val previewClients = mutableMapOf<Any, (Boolean) -> Unit>()
    private val previewStoppers = mutableMapOf<Any, suspend () -> Boolean>()
    private val settingsPreviewStoppers = mutableMapOf<Any, suspend () -> Boolean>()
    private var explicitDownloadPending = false
    private val attemptedThumbnails = mutableSetOf<String>()
    private val downloader: CameraMediaDownloader by lazy {
        CameraMediaDownloader(
            scope, client, { recovery.binding?.socketFactory },
            { File(filesDir, "yi-transfers") }, AndroidMediaPublisher(this), ::downloadAvailability,
            preparePreview = {
                if (previewStoppers.isEmpty()) !cameraState.value.previewControlRequested
                else previewStoppers.values.toList().all { it() }
            },
            diagnostic = { diagnostics.append("DOWNLOAD", it) },
            directoryChanged = { media.workingDirectoryChanged(it) },
        )
    }
    val downloadStatus get() = downloader.status
    private val mutableUserDownload = MutableStateFlow(CameraDownloadStatus())
    val userDownload = mutableUserDownload.asStateFlow()
    val thumbnails get() = downloader.thumbnails
    private val recovery = CameraSessionRecovery<Network>(
        createBinding = { network -> CameraNetworkBinding(network, network.socketFactory) { network.bindSocket(it) } },
        tcpState = { cameraState.value }, connectTcp = { client.connect(it) },
        disconnectTcp = { client.disconnect() }, diagnostic = { diagnostics.append("TCP", it) },
    )
    private val media = CameraMediaRepository(
        scope, { client.requestMedia(it) }, ::mediaAvailability, client::sessionIdentity,
        diagnostic = { diagnostics.append("MEDIA", it) },
    )
    val mediaState = media.state
    private val settings: CameraSettingsRepository by lazy {
        CameraSettingsRepository(
            scope, client::requestSettings, ::settingsAvailability, client::sessionIdentity,
            preparePreview = {
                diagnostics.append("SETTINGS", "Esperando parada confirmada de preview antes de SET")
                if (settingsPreviewStoppers.isEmpty()) !cameraState.value.previewControlRequested
                else settingsPreviewStoppers.values.toList().all { it() }
            },
            diagnostic = { diagnostics.append("SETTINGS", it) },
        )
    }
    val settingsState get() = settings.state
    fun setSettingsForeground(value: Boolean) = settings.setForeground(value)
    fun openSettings() = settings.open()
    fun refreshSettings() = settings.refresh()
    fun applySetting(key: String, value: String) = settings.apply(key, value)

    private fun settingsAvailability(write: Boolean): CameraSettingsError? = when {
        wifiStatus.value.state == CameraWifiState.BLOCKED -> CameraSettingsError.BLOCKED
        !active.value || cameraState.value.connection != ConnectionStatus.CONNECTED || client.sessionIdentity() == null ->
            CameraSettingsError.DISCONNECTED

        downloader.busy || explicitDownloadPending -> CameraSettingsError.DOWNLOAD_ACTIVE
        media.state.value.loading || !cameraState.value.canSendCommand -> CameraSettingsError.BUSY
        write && cameraState.value.recording != RecordingState.IDLE -> CameraSettingsError.NOT_IDLE
        else -> null
    }

    private fun mediaAvailability(): CameraMediaError? =
        if (downloader.busy || explicitDownloadPending || settings.state.value.busy) CameraMediaError.BUSY else mediaAvailability(
            active.value, wifiStatus.value.state == CameraWifiState.BLOCKED, cameraState.value,
        )

    private fun downloadAvailability(): CameraDownloadError? = when {
        wifiStatus.value.state == CameraWifiState.BLOCKED -> CameraDownloadError.NETWORK_BLOCKED
        !active.value || recovery.binding == null || cameraState.value.connection != ConnectionStatus.CONNECTED ->
            CameraDownloadError.NETWORK_LOST

        settings.state.value.busy || media.state.value.loading || !cameraState.value.canSendCommand -> CameraDownloadError.BUSY
        cameraState.value.recording != RecordingState.IDLE -> CameraDownloadError.NOT_IDLE
        else -> null
    }

    fun download(entry: CameraMediaEntry) {
        if (settings.state.value.busy) return
        if (explicitDownloadPending || downloader.busy && !downloader.status.value.thumbnail) return
        explicitDownloadPending = true
        scope.launch {
            try {
                downloader.preemptThumbnail()
                withTimeoutOrNull(6_000) {
                    cameraState.first { it.canSendCommand || it.connection == ConnectionStatus.DISCONNECTED }
                }
                if (!downloader.status.value.thumbnail && downloader.status.value.canResume) return@launch
                if (downloader.status.value.thumbnail) downloader.discard()
                downloader.start(entry)
            } finally {
                explicitDownloadPending = false
            }
        }
    }

    fun resumeDownload() {
        if (!explicitDownloadPending) downloader.resume()
    }

    fun cancelDownload() = downloader.cancel()
    fun discardDownload() = downloader.discard()
    fun loadThumbnail(entry: CameraMediaEntry) {
        if (downloader.busy || explicitDownloadPending || downloader.status.value.canResume ||
            media.state.value.loading || cameraState.value.previewControlRequested || downloadAvailability() != null ||
            entry.type != CameraMediaType.THUMBNAIL || (entry.sizeBytes ?: Long.MAX_VALUE) > 8 * 1024 * 1024
        ) return
        val key = thumbnailKey(entry)
        if (key in thumbnails.value || !attemptedThumbnails.add(key)) return
        if (attemptedThumbnails.size > 256) attemptedThumbnails.remove(attemptedThumbnails.first())
        downloader.start(entry)
    }

    fun setMediaForeground(foreground: Boolean) = media.setForeground(foreground)
    fun openMedia() = media.open()
    fun refreshMedia() = media.refresh()
    fun openMediaDirectory(path: String) = media.openDirectory(path)
    fun mediaParent() = media.parent()
    fun mediaRoot() = media.root()

    inner class LocalBinder : Binder() {
        val service: CameraConnectionService get() = this@CameraConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        // No persisted resume metadata: remove only files created in our dedicated temporary directory.
        File(filesDir, "yi-transfers").listFiles()
            ?.filter { it.isFile && it.name.startsWith("yi-") && it.extension == "part" }
            ?.forEach { it.delete() }
        scope.launch {
            downloadStatus.collect {
                if (!it.thumbnail) mutableUserDownload.value = it
                else if (it.state == CameraDownloadState.COMPLETED)
                    attemptedThumbnails.remove(it.remotePath + "|" + it.totalBytes)
                updateNotification()
            }
        }
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
                    if (status.state == CameraWifiState.BLOCKED) downloader.cancel(CameraDownloadError.NETWORK_BLOCKED)
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
                settings.sessionChanged()
                if (it.connection == ConnectionStatus.DISCONNECTED) {
                    media.onDisconnected()
                    downloader.cancel(
                        if (wifiStatus.value.state == CameraWifiState.BLOCKED)
                            CameraDownloadError.NETWORK_BLOCKED else CameraDownloadError.NETWORK_LOST
                    )
                }
                if (active.value) {
                    recovery.tcp(it)
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                diagnostics.append(
                    "DOWNLOAD",
                    "Cancelar recibido desde notificación; estado=" + downloadStatus.value.state
                )
                cancelDownload()
            }
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
        previewStoppers.remove(owner)
        settingsPreviewStoppers.remove(owner)
    }

    internal fun attachDownloadPreparation(owner: Any, stop: suspend () -> Boolean) {
        previewStoppers[owner] = stop
    }

    internal fun attachSettingsPreparation(owner: Any, stop: suspend () -> Boolean) {
        settingsPreviewStoppers[owner] = stop
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

    internal fun canRestartPreview(): Boolean = previewRecoveryAllowed(
        cameraState.value,
        currentPreviewTransport() != null,
        canControl() && !mediaState.value.loading,
    )

    private fun canControl() =
        active.value && wifiStatus.value.state != CameraWifiState.BLOCKED && !downloader.busy && !explicitDownloadPending &&
                !settings.state.value.busy
    fun retryTcp() {
        if (!canControl()) return
        if (client.hasTransferBarrier()) {
            val factory = recovery.binding?.socketFactory ?: return
            diagnostics.append(
                "DOWNLOAD",
                "Reinicio TCP solicitado para liberar confirmación pendiente; parcial conservado"
            )
            client.disconnect()
            client.connect(factory)
        } else recovery.retry()
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
        // Cleanup is allowed during PREPARING; manual controls remain disabled.
        fun mayStop() = active.value && wifiStatus.value.state != CameraWifiState.BLOCKED &&
                (!downloader.busy || downloadStatus.value.state == CameraDownloadState.PREPARING)
        if (!mayStop()) return unavailableControl()
        val available = withTimeoutOrNull(6_000) {
            cameraState.first { it.canSendCommand || it.connection == ConnectionStatus.DISCONNECTED }
        }
        return if (mayStop() && available?.canSendCommand == true) client.stopPreview() else unavailableControl()
    }

    suspend fun stopPreviewAndAwaitVfStop(): CameraControlResult =
        if (canControl()) client.stopPreviewAndAwaitVfStop() else unavailableControl()

    /** Cleanup owned by the active settings mutation, which intentionally blocks normal controls. */
    internal suspend fun stopPreviewForSettings(): CameraControlResult {
        if (!active.value || wifiStatus.value.state != CameraWifiState.CONNECTED ||
            settings.state.value.mutation !is CameraSettingsMutationState.Applying ||
            downloader.busy || explicitDownloadPending || mediaState.value.loading
        ) return unavailableControl()
        return client.stopPreviewAndAwaitVfStop()
    }

    private fun unavailableControl() = CameraControlResult(false, "Sesión de cámara no disponible o red bloqueada")

    fun disconnect() = terminate(releaseWifi = true)

    private fun terminate(releaseWifi: Boolean, networkLost: Boolean = false) {
        if (cleaning) return
        cleaning = true
        try {
            awaitingCredentials?.cancel(); awaitingCredentials = null
            if (networkLost) downloader.cancel(CameraDownloadError.NETWORK_LOST) else downloader.shutdown()
            attemptedThumbnails.clear()
            media.reset()
            settings.reset()
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
            notificationUpdates.reset()
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
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera_connection)
            .setContentTitle("Conexión con cámara YI").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Desconectar", disconnect)
        if (downloadStatus.value.busy) {
            val cancel = PendingIntent.getService(
                this, 2,
                Intent(this, CameraConnectionService::class.java).setAction(ACTION_CANCEL_DOWNLOAD), flags
            )
            builder.setProgress(
                100,
                downloadStatus.value.percent,
                downloadStatus.value.state == CameraDownloadState.PREPARING
            )
            if (downloadStatus.value.state != CameraDownloadState.PUBLISHING) builder.addAction(
                0,
                "Cancelar descarga",
                cancel
            )
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (!active.value) return
        val download = downloadStatus.value
        // Phase/action changes are immediate; byte progress alone is limited to once per second.
        val key = listOf(
            download.state, download.remotePath, wifiStatus.value.state,
            cameraState.value.connection, cameraState.value.error
        ).joinToString("|")
        if (!notificationUpdates.shouldPublish(key, download.percent, android.os.SystemClock.elapsedRealtime())) return
        val text = when {
            downloadStatus.value.busy -> "Descargando " + downloadStatus.value.fileName + " · " + downloadStatus.value.percent + " %"
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
        previewStoppers.clear()
        settingsPreviewStoppers.clear()
        client.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL_DOWNLOAD = "es.jcprieto.yiactioncontroller.CANCEL_DOWNLOAD"
        const val ACTION_CONNECT = "es.jcprieto.yiactioncontroller.CONNECT"
        const val ACTION_DISCONNECT = "es.jcprieto.yiactioncontroller.DISCONNECT"
        private const val CHANNEL = "camera_connection"
        private const val NOTIFICATION_ID = 1
    }
}
