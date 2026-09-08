package es.jcprieto.yiactioncontroller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

@UnstableApi
class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[CameraViewModel::class.java] }
    private var permissionAction: (() -> Unit)? = null
    private val nearbyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // SDK 36 without experimental protection can still use the LAN if permission is denied.
        permissionAction?.invoke()
        permissionAction = null
    }

    private fun withNearbyPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            permissionAction = action
            nearbyPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else action()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.onBackground()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val state by model.state.collectAsStateWithLifecycle()
                val preview by model.previewState.collectAsStateWithLifecycle()
                val player by model.player.collectAsStateWithLifecycle()
                val network by model.cameraNetwork.collectAsStateWithLifecycle()
                val history by model.diagnosticHistory.collectAsStateWithLifecycle()
                Diagnostics(
                    state, { withNearbyPermission(model::connect) }, model::disconnect, model::refresh,
                    model::takePhoto, model::startRecording, model::stopRecording,
                    preview, player, network != null, { withNearbyPermission(model::startPreview) }, model::stopPreview,
                    history, model::clearDiagnosticHistory
                )
            }
        }
    }
}

@Composable
@UnstableApi
private fun Diagnostics(
    state: CameraState,
    connect: () -> Unit,
    disconnect: () -> Unit,
    refresh: () -> Unit,
    takePhoto: () -> Unit,
    startRecording: () -> Unit,
    stopRecording: () -> Unit,
    preview: PreviewStatus,
    player: Player?,
    networkFound: Boolean,
    startPreview: () -> Unit,
    stopPreview: () -> Unit,
    history: List<String>,
    clearHistory: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("YI · Diagnóstico", style = MaterialTheme.typography.headlineMedium)
            DiagnosticHistorySection(history, clearHistory)
            Text("Conecta el teléfono manualmente al Wi-Fi de la cámara y después pulsa Conectar.")
            Text("192.168.42.1:7878", style = MaterialTheme.typography.bodySmall)
            val connection = when (state.connection) {
                ConnectionStatus.DISCONNECTED -> "Desconectada"
                ConnectionStatus.CONNECTING -> "Conectando…"
                ConnectionStatus.AUTHENTICATING -> "Obteniendo token…"
                ConnectionStatus.CONNECTED -> "Conectada"
            }
            Text(connection, style = MaterialTheme.typography.titleLarge)
            if (state.connection == ConnectionStatus.DISCONNECTED) {
                Button(onClick = connect) { Text("Conectar") }
            } else {
                OutlinedButton(onClick = disconnect) { Text("Desconectar") }
            }
            Button(
                onClick = refresh,
                enabled = state.canSendCommand,
            ) { Text(if (state.pending.isEmpty()) "Consultar batería y configuración" else "Consultando…") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Vista previa", style = MaterialTheme.typography.titleLarge)
            Text("Transporte: RTP/UDP · RTSP por TCP", style = MaterialTheme.typography.bodySmall)
            Text(
                when (preview.state) {
                    PreviewState.IDLE -> "Sin vista previa"
                    PreviewState.STARTING -> "Iniciando…"
                    PreviewState.BUFFERING -> "Buffering…"
                    PreviewState.PLAYING -> "En directo"
                    PreviewState.STOPPING -> "Deteniendo…"
                    PreviewState.ERROR -> "Error"
                }
            )
            Text(if (networkFound) "Wi-Fi con ruta a la YI identificada" else "Wi-Fi de la YI no encontrada")
            Text("Control de streaming: ${preview.control}", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = startPreview, enabled = state.canSendCommand && !preview.controlPending &&
                        preview.state in setOf(PreviewState.IDLE, PreviewState.ERROR)
            ) { Text("Iniciar vista previa") }
            OutlinedButton(
                onClick = stopPreview,
                enabled = preview.state != PreviewState.IDLE
            ) { Text("Detener vista previa") }
            preview.error?.let { Text("${preview.errorType}: $it", color = MaterialTheme.colorScheme.error) }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                if (player == null) Text("Sin vídeo") else AndroidView(
                    factory = { context -> PlayerView(context).apply { useController = false } },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            HorizontalDivider()
            Text("Control", style = MaterialTheme.typography.titleLarge)
            Button(onClick = takePhoto, enabled = state.canTakePhoto) { Text("Hacer foto") }
            Button(onClick = startRecording, enabled = state.canStartRecording) { Text("Iniciar grabación") }
            OutlinedButton(onClick = stopRecording, enabled = state.canStopRecording) { Text("Detener grabación") }
            if (state.recording == RecordingState.UNKNOWN) {
                Text("Consulta la configuración para conocer el estado. Detener está disponible como recuperación.")
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticField(
                        "Estado de grabación", when (state.recording) {
                            RecordingState.UNKNOWN -> "Desconocido"
                            RecordingState.IDLE -> "Inactiva"
                            RecordingState.STARTING -> "Solicitando inicio…"
                            RecordingState.RECORDING -> "Grabando"
                            RecordingState.STOPPING -> "Solicitando detención…"
                        }
                    )
                    DiagnosticField(
                        "Acción pendiente", when (state.pendingAction) {
                            CameraAction.TAKE_PHOTO -> "Hacer foto"
                            CameraAction.START_RECORDING -> "Iniciar grabación"
                            CameraAction.STOP_RECORDING -> "Detener grabación"
                            null -> "Ninguna"
                        }
                    )
                    DiagnosticField("Última foto", state.lastPhotoPath)
                    DiagnosticField("Último evento de foto", state.lastPhotoEvent?.name)
                    DiagnosticField("Último evento de grabación", state.lastRecordingEvent)
                    DiagnosticField("Token", state.token?.toString())
                    DiagnosticField("Batería", state.battery?.let { "$it %" })
                    DiagnosticField("Firmware", state.firmware)
                    DiagnosticField("Hardware", state.hardware)
                    DiagnosticField("Tarjeta SD", state.sdCard)
                    DiagnosticField("Resolución de vídeo", state.videoResolution)
                    DiagnosticField("Estado de la cámara", state.cameraStatus)
                    HorizontalDivider()
                    Text("Configuración recibida", style = MaterialTheme.typography.titleMedium)
                    if (state.configuration.isEmpty()) Text("Sin datos")
                    state.configuration.toSortedMap().forEach { (key, value) -> DiagnosticField(key, value) }
                    HorizontalDivider()
                    DiagnosticField("Último evento asíncrono", state.lastEvent)
                    DiagnosticField("Última petición", state.lastRequest)
                    DiagnosticField("Último mensaje", state.lastMessage)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticHistorySection(history: List<String>, clear: () -> Unit) {
    val context = LocalContext.current
    Text(
        "Historial de diagnóstico (${history.size}/${DiagnosticHistory.MAX_ENTRIES})",
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        "Solo en memoria. Sin tokens ni credenciales; incluye rutas de fotos y configuración resumida.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = history.isNotEmpty(), onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Diagnóstico YI", history.joinToString("\n"))
            )
        }) { Text("Copiar historial") }
        OutlinedButton(enabled = history.isNotEmpty(), onClick = clear) { Text("Limpiar") }
    }
    SelectionContainer {
        Text(
            if (history.isEmpty()) "Sin entradas" else history.joinToString("\n"),
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DiagnosticField(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value ?: "Sin datos", style = MaterialTheme.typography.bodyMedium)
    }
}
