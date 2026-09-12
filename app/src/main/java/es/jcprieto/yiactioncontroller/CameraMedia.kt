package es.jcprieto.yiactioncontroller

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.collections.ArrayDeque

const val CAMERA_SD_ROOT = "/tmp/fuse_d"
internal const val CAMERA_DCIM_ROOT = "$CAMERA_SD_ROOT/DCIM"

enum class CameraMediaType { DIRECTORY, PHOTO, VIDEO, THUMBNAIL, OTHER }

data class CameraStorageInfo(
    val totalRaw: Long,
    val freeRaw: Long,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

/** Raw capacity is interpreted as KiB based on physical tests of the reference YI firmware. */
fun storageInfo(totalRaw: Long, freeRaw: Long): CameraStorageInfo? {
    if (totalRaw < 0 || freeRaw < 0 || freeRaw > totalRaw) return null
    if (totalRaw > Long.MAX_VALUE / 1024 || freeRaw > Long.MAX_VALUE / 1024) return null
    return CameraStorageInfo(totalRaw, freeRaw, totalRaw * 1024, freeRaw * 1024)
}

data class CameraMediaEntry(
    val name: String,
    val path: String,
    val type: CameraMediaType,
    val sizeBytes: Long?,
    val timestampRaw: String?,
) {
    val extension: String? get() = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
}

data class CameraGalleryItem(val media: CameraMediaEntry, val thumbnail: CameraMediaEntry? = null)

enum class CameraMediaError(val description: String) {
    DISCONNECTED("Cámara desconectada. Conecta la cámara para explorar la microSD."),
    BLOCKED("Android ha bloqueado temporalmente el acceso a la red de la cámara."),
    SD_MISSING("La microSD no está insertada."),
    BUSY("La cámara está atendiendo otra petición. Vuelve a intentarlo."),
    TIMEOUT("La cámara no respondió a tiempo. La sesión TCP se ha cerrado."),
    REJECTED("La cámara rechazó la consulta"),
    INVALID_LISTING("La respuesta de listado no contiene un array válido."),
    INVALID_METADATA("Algunas entradas tienen metadatos inválidos."),
    INVALID_PATH("La ruta recibida o solicitada no es válida."),
    OUTSIDE_ROOT("No se permite navegar fuera de la raíz de la microSD."),
    INVALID_STORAGE("Los valores de capacidad recibidos no son válidos."),
    PROTOCOL("La respuesta de la cámara no es válida."),
}

internal class CameraMediaException(
    val kind: CameraMediaError,
    val rval: Int? = null,
) : Exception(kind.description + (rval?.let { ": rval=$it" } ?: ""))

internal fun mediaAvailability(active: Boolean, blocked: Boolean, camera: CameraState): CameraMediaError? = when {
    blocked -> CameraMediaError.BLOCKED
    !active || camera.connection != ConnectionStatus.CONNECTED || (camera.token
        ?: 0) <= 0 -> CameraMediaError.DISCONNECTED

    camera.sdCard?.lowercase(Locale.ROOT) in setOf(
        "remove",
        "removed",
        "not_insert",
        "not_inserted",
        "absent",
        "no_card"
    ) ->
        CameraMediaError.SD_MISSING

    !camera.canSendCommand -> CameraMediaError.BUSY
    else -> null
}

data class CameraMediaBrowserState(
    val loading: Boolean = false,
    val currentPath: String? = null,
    val entries: List<CameraMediaEntry> = emptyList(),
    val gallery: List<CameraGalleryItem> = emptyList(),
    val storage: CameraStorageInfo? = null,
    val error: String? = null,
    val errorType: CameraMediaError? = null,
    val malformedEntries: Int = 0,
    val hasListing: Boolean = false,
)

internal data class ParsedMediaListing(val entries: List<CameraMediaEntry>, val malformedEntries: Int)

internal fun parseMediaListing(listing: JsonElement?, directory: String): ParsedMediaListing {
    val array = listing as? JsonArray ?: throw CameraMediaException(CameraMediaError.INVALID_LISTING)
    if (resolveCameraDirectory(CAMERA_SD_ROOT, directory) != directory)
        throw CameraMediaException(CameraMediaError.INVALID_PATH)
    var malformed = 0
    val entries = array.map { item ->
        val obj = item as? JsonObject
        if (obj == null || obj.size != 1) {
            malformed++
            CameraMediaEntry("[entrada inválida]", "", CameraMediaType.OTHER, null, null)
        } else {
            val (name, value) = obj.entries.first()
            val metadata = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            val separator = metadata?.indexOf('|') ?: -1
            val left = if (separator >= 0) metadata!!.substring(0, separator) else metadata.orEmpty()
            val timestamp = if (separator >= 0) metadata!!.substring(separator + 1).takeIf { it.isNotBlank() } else null
            val size = Regex("^([0-9]+) bytes$").matchEntire(left.trim())?.groupValues?.get(1)?.toLongOrNull()
            val path = safeChildPath(directory, name)
            if (size == null || timestamp == null || path == null) malformed++
            CameraMediaEntry(
                name, path.orEmpty(), if (path == null) CameraMediaType.OTHER else classifyMedia(name),
                if (path == null) null else size, timestamp,
            )
        }
    }
    return ParsedMediaListing(entries, malformed)
}

private fun classifyMedia(name: String): CameraMediaType {
    if (name.endsWith('/')) return CameraMediaType.DIRECTORY
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> CameraMediaType.PHOTO
        "mp4" -> CameraMediaType.VIDEO
        "thm" -> CameraMediaType.THUMBNAIL
        else -> CameraMediaType.OTHER
    }
}

/** Normalize locally before sending; camera paths are case-sensitive POSIX paths. */
internal fun normalizeCameraPath(path: String): String? {
    if (!path.startsWith('/') || path.any { it.isISOControl() || it == '\\' }) return null
    val result = ArrayDeque<String>()
    for (part in path.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (result.isNotEmpty()) result.removeLast() else return null
        else -> result.addLast(part)
    }
    return "/" + result.joinToString("/")
}

internal fun isCameraPathAllowed(path: String): Boolean =
    normalizeCameraPath(path)?.let { it == CAMERA_SD_ROOT || it.startsWith("$CAMERA_SD_ROOT/") } == true

internal fun resolveCameraDirectory(current: String, requested: String): String? {
    if (requested.isBlank()) return null
    val normalized = normalizeCameraPath(if (requested.startsWith('/')) requested else "$current/$requested")
    return normalized?.takeIf(::isCameraPathAllowed)
}

/** Listing keys must describe one child, never an absolute path, traversal, or nested path. */
internal fun safeChildPath(parent: String, name: String): String? {
    val child = name.removeSuffix("/")
    if (child.isBlank() || child == "." || child == ".." || child.contains('/')) return null
    return resolveCameraDirectory(parent, child)
}

internal fun buildGallery(entries: List<CameraMediaEntry>): List<CameraGalleryItem> {
    val thumbs = entries.filter { it.type == CameraMediaType.THUMBNAIL && it.path.isNotEmpty() }
        .associateBy { it.path.substringBeforeLast('.').lowercase(Locale.ROOT) }
    return entries.filter { it.type == CameraMediaType.PHOTO || it.type == CameraMediaType.VIDEO }
        .sortedByDescending { it.name.lowercase(Locale.ROOT) }
        .map {
            CameraGalleryItem(
                it, if (it.type == CameraMediaType.VIDEO)
                    thumbs[it.path.substringBeforeLast('.').lowercase(Locale.ROOT)] else null
            )
        }
}

/** Typed, closed set: operations sharing an id retain their own arguments and response. */
internal sealed class CameraMediaOperation(val id: Int, val label: String) {
    data object Total : CameraMediaOperation(CameraCommand.GET_STORAGE, "TOTAL")
    data object Free : CameraMediaOperation(CameraCommand.GET_STORAGE, "FREE")
    data object Pwd : CameraMediaOperation(CameraCommand.CHANGE_DIRECTORY, "PWD")
    data class ChangeDirectory(val path: String) : CameraMediaOperation(CameraCommand.CHANGE_DIRECTORY, "CD")
    data object ListDirectory : CameraMediaOperation(CameraCommand.LIST_DIRECTORY, "LIST")
    data class GetFile(val request: CameraFileRequest) : CameraMediaOperation(CameraCommand.GET_FILE, "GET_FILE")

    fun arguments(): Map<String, String> = when (this) {
        Total -> mapOf("type" to "total")
        Free -> mapOf("type" to "free")
        Pwd -> mapOf("param" to ".")
        is ChangeDirectory -> mapOf("param" to path)
        ListDirectory -> mapOf("param" to " -D -S")
        is GetFile -> mapOf(
            "param" to request.name,
            "offset" to request.offset.toString(),
            "fetch_size" to request.fetchSize.toString()
        )
    }
}
