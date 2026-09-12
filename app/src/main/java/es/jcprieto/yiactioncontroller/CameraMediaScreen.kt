package es.jcprieto.yiactioncontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.*

@Composable
internal fun CameraMediaScreen(
    state: CameraMediaBrowserState,
    camera: CameraState,
    serviceActive: Boolean,
    blocked: Boolean,
    refresh: () -> Unit,
    openDirectory: (String) -> Unit,
    parent: () -> Unit,
    root: () -> Unit,
    download: CameraDownloadStatus,
    transferBusy: Boolean,
    thumbnails: Map<String, String>,
    startDownload: (CameraMediaEntry) -> Unit,
    resumeDownload: () -> Unit,
    cancelDownload: () -> Unit,
    discardDownload: () -> Unit,
    loadThumbnail: (CameraMediaEntry) -> Unit,
    retryControl: () -> Unit,
) {
    val available = serviceActive && camera.canSendCommand && !blocked && !state.loading && !transferBusy
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(
        state.gallery,
        transferBusy,
        thumbnails,
        camera.canSendCommand,
        camera.previewControlRequested,
        lifecycle
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }.collect { visible ->
                if (!transferBusy && camera.recording == RecordingState.IDLE && !camera.previewControlRequested) {
                    state.gallery.filter { it.media.path in visible }.forEach { item ->
                        item.thumbnail?.takeIf { thumbnailKey(it) !in thumbnails }?.let(loadThumbnail)
                    }
                }
            }
        }
    }
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Medios de la cámara", style = MaterialTheme.typography.headlineMedium)
                Text("Medios de la microSD", style = MaterialTheme.typography.bodySmall)
            }
            item {
                DownloadPanel(
                    download, available && camera.recording == RecordingState.IDLE,
                    resumeDownload, cancelDownload, discardDownload, retryControl
                )
                if (transferBusy && !download.busy) Text("Cargando miniatura")
                if (camera.recording != RecordingState.IDLE) Text("Para descargar, detén la grabación y confirma el estado inactivo en Control.")
            }
            item {
                Text("MicroSD", style = MaterialTheme.typography.titleLarge)
                state.storage?.let { storage ->
                    Text("${formatMediaSize(storage.totalBytes)} total")
                    Text("${formatMediaSize(storage.freeBytes)} libres · ${formatMediaSize(storage.usedBytes)} usados")
                    LinearProgressIndicator(
                        progress = { if (storage.totalBytes > 0) (storage.usedBytes.toDouble() / storage.totalBytes).toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } ?: Text("Capacidad sin consultar")
            }
            item {
                Text(
                    state.currentPath?.removePrefix(CAMERA_SD_ROOT)?.trim('/')?.replace("/", " / ")
                        ?.ifEmpty { "Raíz SD" } ?: "Directorio sin consultar",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = root, enabled = available) { Text("Raíz SD") }
                    OutlinedButton(
                        onClick = parent,
                        enabled = available && state.currentPath != null && state.currentPath != CAMERA_SD_ROOT
                    ) {
                        Text("Subir")
                    }
                }
                Button(onClick = refresh, enabled = available) { Text("Actualizar") }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    blocked -> Text(CameraMediaError.BLOCKED.description, color = MaterialTheme.colorScheme.error)
                    !serviceActive || camera.connection != ConnectionStatus.CONNECTED ->
                        Text("Conecta la cámara desde Control para consultar los medios.")

                    !camera.canSendCommand && !state.loading -> Text("La cámara está atendiendo otra petición.")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.malformedEntries > 0) Text(
                    "${state.malformedEntries} entradas con datos inválidos; se conserva el resto del listado.",
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Orden por nombre. Las fechas de la cámara pueden ser incorrectas.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(state.entries.filter { it.type == CameraMediaType.DIRECTORY }
                .sortedByDescending { it.name.lowercase(Locale.ROOT) }) { entry ->
                OutlinedButton(
                    onClick = { openDirectory(entry.path) },
                    enabled = available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📁 ${entry.name.removeSuffix("/")}")
                }
            }
            items(state.gallery, key = { it.media.path }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThumbnailImage(item.thumbnail?.let { thumbnails[thumbnailKey(it)] }, item.media.name)
                        Text(
                            if (item.media.type == CameraMediaType.PHOTO) "📷 Foto" else "🎬 Vídeo",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(item.media.name, style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = { startDownload(item.media) },
                            enabled = serviceActive && !blocked && !state.loading && camera.recording == RecordingState.IDLE &&
                                    !download.busy && !download.canResume && item.media.sizeBytes != null
                        ) { Text("Descargar") }
                        Text(item.media.sizeBytes?.let(::formatMediaSize) ?: "Tamaño desconocido")
                        Text(item.media.timestampRaw ?: "Fecha sin datos", style = MaterialTheme.typography.bodySmall)
                        if (item.thumbnail != null) Text(
                            "Miniatura THM disponible",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                if (!state.loading && !state.hasListing && state.error == null) Text("Pulsa Actualizar para consultar el directorio.")
                if (state.hasListing && state.entries.isEmpty()) Text("Directorio vacío")
                val other = state.entries.count { it.type == CameraMediaType.OTHER }
                val thumbs = state.entries.count { it.type == CameraMediaType.THUMBNAIL }
                if (other > 0 || thumbs > 0) Text(
                    "$other otros archivos · $thumbs archivos THM en el listado original",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun formatMediaSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
