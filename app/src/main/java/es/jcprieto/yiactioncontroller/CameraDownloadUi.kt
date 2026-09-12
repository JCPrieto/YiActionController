package es.jcprieto.yiactioncontroller

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DownloadPanel(
    download: CameraDownloadStatus, available: Boolean, resume: () -> Unit, cancel: () -> Unit,
    discard: () -> Unit, retryControl: () -> Unit
) {
    if (download.fileName == null) return
    val context = LocalContext.current
    Text(download.fileName, style = MaterialTheme.typography.titleMedium)
    Text(formatMediaSize(download.downloadedBytes) + " / " + formatMediaSize(download.totalBytes))
    LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
    Text(
        when (download.state) {
            CameraDownloadState.PREPARING -> "Preparando descarga"
            CameraDownloadState.DOWNLOADING -> "Descargando " + download.percent + " %"
            CameraDownloadState.CAMERA_CONFIRMATION -> "Bytes recibidos; esperando confirmación"
            CameraDownloadState.PUBLISHING -> "Guardando en la galería"
            CameraDownloadState.COMPLETED -> if (download.cameraConfirmed) "Descargado" else "Descargado; confirmación de cámara ausente"
            CameraDownloadState.PAUSED -> "Descarga pausada"
            CameraDownloadState.CANCELLED -> "Descarga cancelada"
            CameraDownloadState.ERROR -> "Error de descarga"
            else -> ""
        }
    )
    download.error?.let { Text(it.description, color = MaterialTheme.colorScheme.error) }
    if (download.error == CameraDownloadError.CONTROL_DRAIN)
        OutlinedButton(onClick = retryControl, enabled = available) { Text("Reiniciar sesión TCP") }
    if (download.busy) OutlinedButton(
        onClick = cancel,
        enabled = download.state != CameraDownloadState.PUBLISHING
    ) { Text("Cancelar") }
    else if (download.state != CameraDownloadState.COMPLETED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (download.canResume) Button(onClick = resume, enabled = available) {
            Text(if (download.error == CameraDownloadError.MEDIASTORE_ERROR) "Guardar de nuevo" else "Reanudar")
        }
        OutlinedButton(onClick = discard) { Text("Descartar") }
    }
    if (download.state == CameraDownloadState.COMPLETED && download.destination != null) Button(onClick = {
        val uri = download.destination.toUri()
        val mime = if (download.fileName.endsWith(".mp4", true)) "video/mp4" else "image/jpeg"
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                "No hay una aplicación para abrir este archivo",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }) { Text("Abrir") }
}

@Composable
internal fun ThumbnailImage(path: String?, name: String) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) null
                    else BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 640)
                    })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    bitmap?.let {
        Image(
            it,
            "Miniatura de " + name,
            Modifier.fillMaxWidth().height(180.dp),
            contentScale = ContentScale.Crop
        )
    }
}
