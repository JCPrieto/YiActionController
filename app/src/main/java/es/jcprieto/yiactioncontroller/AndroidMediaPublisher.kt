package es.jcprieto.yiactioncontroller

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Private source is complete. Only our newly inserted URI is removed if publication fails. */
internal class AndroidMediaPublisher(private val context: Context) : MediaPublisher {
    override suspend fun publish(file: File, entry: CameraMediaEntry): String = withContext(Dispatchers.IO) {
        if (entry.type !in setOf(CameraMediaType.PHOTO, CameraMediaType.VIDEO) || file.length() != entry.sizeBytes)
            throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
        val resolver = context.contentResolver
        val video = entry.type == CameraMediaType.VIDEO
        val collection =
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, downloadMime(entry.type))
            if (Build.VERSION.SDK_INT >= 29) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (video) "Movies/YI Action Camera" else "Pictures/YI Action Camera"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw DownloadException(CameraDownloadError.MEDIASTORE_ERROR)
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input -> copyCameraBytes(input, output, file.length()) {} }
            } ?: throw DownloadException(CameraDownloadError.MEDIASTORE_ERROR)
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 29) {
                val published = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                if (published != 1) throw DownloadException(CameraDownloadError.MEDIASTORE_ERROR)
            }
            uri.toString()
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
