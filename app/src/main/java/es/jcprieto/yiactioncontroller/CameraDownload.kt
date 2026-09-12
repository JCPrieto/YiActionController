package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import java.io.File

enum class CameraDownloadState { IDLE, PREPARING, DOWNLOADING, CAMERA_CONFIRMATION, PUBLISHING, PAUSED, COMPLETED, CANCELLED, ERROR }
enum class CameraDownloadError(val description: String) {
    BUSY("Hay otra operación en curso."),
    NOT_IDLE("La cámara debe estar inactiva. Detén la grabación o actualiza su estado."),
    INVALID_PATH("El archivo no tiene una ruta o tamaño válido."),
    CONNECT_FAILED("No se pudo abrir la conexión de datos."),
    CONTROL_TIMEOUT("La cámara no respondió al comando de descarga."),
    CAMERA_REJECTED("La cámara rechazó la descarga."),
    CONTROL_DRAIN("Falta el evento final de la transferencia anterior. Puedes reiniciar la sesión TCP y reanudar el parcial."),
    READ_TIMEOUT("La conexión de datos dejó de enviar bytes."),
    EOF_EARLY("La conexión se cerró antes de recibir todos los bytes."),
    GET_FILE_FAIL("La cámara comunicó un fallo de transferencia."),
    SIZE_MISMATCH("El tamaño remoto o el parcial no coincide. Descarta el parcial antes de empezar de nuevo."),
    NETWORK_BLOCKED("Android ha bloqueado temporalmente la red. Puedes reanudar cuando esté disponible."),
    NETWORK_LOST("Se perdió la conexión con la cámara."),
    CANCELLED("Descarga cancelada; el parcial se conserva."),
    STORAGE_ERROR("No se pudo escribir el archivo temporal."),
    MEDIASTORE_ERROR("No se pudo guardar el archivo completo en la galería. Reintenta la publicación."),
    PREVIEW_STOP("No se pudo detener la vista previa."),
}

internal class DownloadException(val reason: CameraDownloadError) : Exception(reason.description)

data class CameraDownloadStatus(
    val remotePath: String? = null,
    val fileName: String? = null,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val state: CameraDownloadState = CameraDownloadState.IDLE,
    val destination: String? = null,
    val error: CameraDownloadError? = null,
    val thumbnail: Boolean = false,
    val cameraConfirmed: Boolean = false,
    val canResume: Boolean = false,
) {
    val busy: Boolean
        get() = state in setOf(
            CameraDownloadState.PREPARING, CameraDownloadState.DOWNLOADING,
            CameraDownloadState.CAMERA_CONFIRMATION, CameraDownloadState.PUBLISHING
        )
    val percent: Int
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes * 100).toInt().coerceIn(0, 100) else 0
}

data class CameraFileRequest(val name: String, val offset: Long, val fetchSize: Long)
internal data class CameraFileAck(val size: Long, val remaining: Long)

internal fun validateFileAck(message: CameraMessage, request: CameraFileRequest, expectedSize: Long): CameraFileAck {
    if (message.rval != 0) throw DownloadException(CameraDownloadError.CAMERA_REJECTED)
    val size = (message.size as? JsonPrimitive)?.longOrNull
    val remaining = (message.remaining as? JsonPrimitive)?.longOrNull
    if (size == null || remaining == null || size <= 0 || size != expectedSize || remaining < 0 ||
        request.offset < 0 || request.offset > size || request.fetchSize < 0 ||
        request.fetchSize > size - request.offset || remaining > size - request.offset || remaining > request.fetchSize
    )
        throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
    return CameraFileAck(size, remaining)
}

internal sealed interface CameraFileTransferEvent {
    data class Complete(val bytesSent: Long?, val md5Present: Boolean) : CameraFileTransferEvent
    data class Fail(val bytesSent: Long?) : CameraFileTransferEvent
}

internal fun parseFileEvent(message: CameraMessage): CameraFileTransferEvent? {
    if (message.messageId != CameraCommand.EVENT) return null
    return when (message.type) {
        "get_file_complete" -> {
            val fields = (message.param as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            CameraFileTransferEvent.Complete(
                fields.firstNotNullOfOrNull { (it["bytes sent"] as? JsonPrimitive)?.longOrNull },
                fields.any { (it["md5sum"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true },
            )
        }

        "get_file_fail" -> CameraFileTransferEvent.Fail((message.param as? JsonPrimitive)?.longOrNull)
        else -> null
    }
}

/** One lease per control session; final events have no file/request identifier. */
internal class CameraTransferLease {
    val terminal = CompletableDeferred<CameraFileTransferEvent?>()
    var armed = false
    var released = false
}

internal fun downloadMime(type: CameraMediaType): String = when (type) {
    CameraMediaType.PHOTO, CameraMediaType.THUMBNAIL -> "image/jpeg"
    CameraMediaType.VIDEO -> "video/mp4"
    else -> throw DownloadException(CameraDownloadError.INVALID_PATH)
}

internal fun thumbnailKey(entry: CameraMediaEntry) = entry.path + "|" + entry.sizeBytes
internal fun validateDownloadEntry(entry: CameraMediaEntry) {
    val parent = entry.path.substringBeforeLast('/', "")
    if (entry.sizeBytes == null || entry.sizeBytes <= 0 ||
        entry.type !in setOf(CameraMediaType.PHOTO, CameraMediaType.VIDEO, CameraMediaType.THUMBNAIL) ||
        !isCameraPathAllowed(parent) || safeChildPath(parent, entry.name) != entry.path ||
        entry.name.endsWith('/')
    )
        throw DownloadException(CameraDownloadError.INVALID_PATH)
}

internal fun interface MediaPublisher {
    suspend fun publish(file: File, entry: CameraMediaEntry): String
}
