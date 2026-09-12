package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/** Fixed-memory exact-byte copy. EOF is never the normal completion signal. */
internal suspend fun copyCameraBytes(
    input: InputStream, output: OutputStream, expected: Long, progress: (Long) -> Unit,
) {
    if (expected < 0) throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
    val buffer = ByteArray(64 * 1024)
    var received = 0L
    while (received < expected) {
        currentCoroutineContext().ensureActive()
        val count = try {
            input.read(buffer, 0, minOf(buffer.size.toLong(), expected - received).toInt())
        } catch (_: SocketTimeoutException) {
            throw DownloadException(CameraDownloadError.READ_TIMEOUT)
        }
        if (count < 0) throw DownloadException(CameraDownloadError.EOF_EARLY)
        if (count == 0) continue
        try {
            output.write(buffer, 0, count)
        } catch (_: IOException) {
            throw DownloadException(CameraDownloadError.STORAGE_ERROR)
        }
        received += count
        progress(received)
    }
}

/** Service-owned transfer. All lifecycle methods run on its main dispatcher; IO uses a fixed buffer. */
internal class CameraMediaDownloader(
    private val scope: CoroutineScope,
    private val client: CameraClient,
    private val factory: () -> SocketFactory?,
    private val directory: () -> File,
    private val publisher: MediaPublisher,
    private val allowed: () -> CameraDownloadError?,
    private val preparePreview: suspend () -> Boolean,
    private val diagnostic: (String) -> Unit,
    private val host: String = "192.168.42.1",
    private val port: Int = 8787,
    private val readTimeoutMillis: Int = 15_000,
    private val confirmationMillis: Long = 3_000,
    private val directoryChanged: (String) -> Unit = {},
) {
    private val mutableStatus = MutableStateFlow(CameraDownloadStatus())
    val status = mutableStatus.asStateFlow()
    private val mutableThumbnails = MutableStateFlow<Map<String, String>>(emptyMap())
    val thumbnails = mutableThumbnails.asStateFlow()
    private var job: Job? = null
    private var confirmationJob: Job? = null
    @Volatile
    private var dataSocket: Socket? = null
    @Volatile
    private var partial: File? = null
    private var selected: CameraMediaEntry? = null
    private var stopReason = CameraDownloadError.CANCELLED
    private var discardOnFinish = false
    private var publishingOnly = false
    private val thumbnailFiles = LinkedHashMap<String, File>()
    val busy: Boolean get() = status.value.busy

    fun start(entry: CameraMediaEntry): Boolean {
        if (busy || partial != null) return false
        selected = entry
        publishingOnly = false
        return launchTransfer(entry, resume = false)
    }

    fun resume(): Boolean {
        val entry = selected ?: return false
        if (busy || !status.value.canResume || partial?.isFile != true) return false
        return launchTransfer(entry, resume = true)
    }

    private fun launchTransfer(entry: CameraMediaEntry, resume: Boolean): Boolean {
        val isThumb = entry.type == CameraMediaType.THUMBNAIL
        val error = runCatching { validateDownloadEntry(entry) }.exceptionOrNull() as? DownloadException
        val rejection = error?.reason ?: allowed()
        if (rejection != null) {
            mutableStatus.value = status.value.copy(
                remotePath = entry.path, fileName = entry.name,
                thumbnail = isThumb, state = CameraDownloadState.ERROR, error = rejection
            )
            return false
        }
        stopReason = CameraDownloadError.CANCELLED
        confirmationJob?.cancel()
        discardOnFinish = false
        val previousConfirmation = resume && publishingOnly && status.value.cameraConfirmed
        mutableStatus.value = CameraDownloadStatus(
            entry.path,
            entry.name,
            entry.sizeBytes!!,
            if (resume) partial!!.length() else 0,
            CameraDownloadState.PREPARING,
            thumbnail = isThumb,
            cameraConfirmed = previousConfirmation
        )
        job = scope.launch {
            var lease: CameraTransferLease? = null
            var confirmed = false
            var failure: CameraDownloadError? = null
            var done = false
            try {
                if (!preparePreview()) throw DownloadException(CameraDownloadError.PREVIEW_STOP)
                allowed()?.let { throw DownloadException(it) }
                val identity = client.sessionIdentity() ?: throw DownloadException(CameraDownloadError.NETWORK_LOST)
                val socketFactory = factory() ?: throw DownloadException(CameraDownloadError.NETWORK_LOST)
                if (!resume) withContext(Dispatchers.IO) {
                    val dir = directory()
                    if (!dir.isDirectory && !dir.mkdirs()) throw DownloadException(CameraDownloadError.STORAGE_ERROR)
                    partial = File.createTempFile("yi-", ".part", dir)
                }
                val file = partial ?: throw DownloadException(CameraDownloadError.STORAGE_ERROR)
                val offset = file.length()
                if (offset < 0 || offset > entry.sizeBytes) throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
                if (!publishingOnly) {
                    if (offset == entry.sizeBytes) throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
                    if (!client.awaitTransferDrain()) throw DownloadException(CameraDownloadError.CONTROL_DRAIN)
                    if (client.sessionIdentity() !== identity) throw DownloadException(CameraDownloadError.NETWORK_LOST)
                    lease = client.acquireTransfer()
                    val parent = entry.path.substringBeforeLast('/')
                    val cd = client.requestMedia(CameraMediaOperation.ChangeDirectory(parent), lease)
                    if (client.sessionIdentity() !== identity) throw DownloadException(CameraDownloadError.NETWORK_LOST)
                    if (cd.pwd.text() != parent) throw DownloadException(CameraDownloadError.INVALID_PATH)
                    directoryChanged(parent)
                    diagnostic("start " + entry.name + " size=" + entry.sizeBytes + " offset=" + offset)
                    withContext(Dispatchers.IO) {
                        val socket = try {
                            socketFactory.createSocket()
                        } catch (_: IOException) {
                            throw DownloadException(CameraDownloadError.CONNECT_FAILED)
                        }
                        dataSocket = socket
                        try {
                            currentCoroutineContext().ensureActive()
                            socket.tcpNoDelay = true
                            socket.soTimeout = readTimeoutMillis
                            socket.connect(InetSocketAddress(host, port), 5_000)
                        } catch (error: Exception) {
                            socket.close()
                            currentCoroutineContext().ensureActive()
                            throw DownloadException(CameraDownloadError.CONNECT_FAILED)
                        }
                    }
                    diagnostic("data socket connected")
                    val request = CameraFileRequest(entry.name, offset, entry.sizeBytes - offset)
                    val ack = validateFileAck(
                        client.requestMedia(CameraMediaOperation.GetFile(request), lease),
                        request,
                        entry.sizeBytes
                    )
                    diagnostic("ack size=" + ack.size + " rem=" + ack.remaining)
                    mutableStatus.value = status.value.copy(state = CameraDownloadState.DOWNLOADING)
                    val terminal = lease.terminal
                    val watcher = launch {
                        if (terminal.await() is CameraFileTransferEvent.Fail) dataSocket?.close()
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            var lastTime = 0L
                            var bucket = -1
                            val destination = try {
                                FileOutputStream(file, true)
                            } catch (_: IOException) {
                                throw DownloadException(CameraDownloadError.STORAGE_ERROR)
                            }
                            destination.use { output ->
                                copyCameraBytes(dataSocket!!.getInputStream(), output, ack.remaining) { count ->
                                    val total = offset + count
                                    val now = System.nanoTime()
                                    val nextBucket = (total.toDouble() / entry.sizeBytes * 4).toInt()
                                    if (nextBucket != bucket) {
                                        bucket = nextBucket
                                        diagnostic("progress " + (bucket * 25) + "%")
                                    }
                                    if (now - lastTime >= 200_000_000 || count == ack.remaining) {
                                        lastTime = now
                                        mutableStatus.value = status.value.copy(downloadedBytes = total)
                                    }
                                }
                                try {
                                    output.fd.sync()
                                } catch (_: IOException) {
                                    throw DownloadException(CameraDownloadError.STORAGE_ERROR)
                                }
                            }
                        }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (terminal.isCompleted && terminal.await() is CameraFileTransferEvent.Fail)
                            throw DownloadException(CameraDownloadError.GET_FILE_FAIL)
                        throw error
                    } finally {
                        watcher.cancel()
                        dataSocket?.close()
                        dataSocket = null
                    }
                    mutableStatus.value = status.value.copy(
                        state = CameraDownloadState.CAMERA_CONFIRMATION,
                        downloadedBytes = file.length()
                    )
                    val event = withTimeoutOrNull(confirmationMillis) { terminal.await() }
                    if (event is CameraFileTransferEvent.Fail) throw DownloadException(CameraDownloadError.GET_FILE_FAIL)
                    confirmed = event is CameraFileTransferEvent.Complete
                    mutableStatus.value = status.value.copy(cameraConfirmed = confirmed)
                    if (event is CameraFileTransferEvent.Complete)
                        diagnostic("event get_file_complete bytesSent=" + event.bytesSent + " md5sum=" + if (event.md5Present) "<presente>" else "<ausente>")
                    else diagnostic("DATA_COMPLETE; confirmación de cámara ausente")
                    if (event == null && !terminal.isCompleted) confirmationJob = scope.launch confirmation@{
                        val late = terminal.await()
                        status.first { !it.busy }
                        if (status.value.state !in setOf(CameraDownloadState.COMPLETED, CameraDownloadState.PAUSED) ||
                            status.value.error != null
                        ) return@confirmation
                        if (late is CameraFileTransferEvent.Fail) {
                            mutableStatus.value = status.value.copy(
                                state = CameraDownloadState.ERROR,
                                error = CameraDownloadError.GET_FILE_FAIL
                            )
                            diagnostic("get_file_fail tardío; archivo recibido conservado")
                        } else if (late is CameraFileTransferEvent.Complete) {
                            mutableStatus.value = status.value.copy(cameraConfirmed = true)
                            diagnostic("get_file_complete tardío")
                        }
                    }
                    if (client.sessionIdentity() !== identity) throw DownloadException(CameraDownloadError.NETWORK_LOST)
                    if (file.length() != entry.sizeBytes) {
                        if (isThumb) throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
                        mutableStatus.value = status.value.copy(
                            state = CameraDownloadState.PAUSED,
                            canResume = true,
                            cameraConfirmed = confirmed
                        )
                        diagnostic("Petición parcial completa; reanudación manual disponible")
                        done = true
                        return@launch
                    }
                    publishingOnly = true
                }
                currentCoroutineContext().ensureActive()
                if (isThumb) {
                    if (entry.sizeBytes > 8 * 1024 * 1024) throw DownloadException(CameraDownloadError.SIZE_MISMATCH)
                    val key = thumbnailKey(entry)
                    thumbnailFiles[key] = file
                    while (thumbnailFiles.size > 32 || thumbnailFiles.values.sumOf { it.length() } > 16L * 1024 * 1024) {
                        val oldest = thumbnailFiles.entries.first()
                        thumbnailFiles.remove(oldest.key)
                        oldest.value.delete()
                    }
                    mutableThumbnails.value = thumbnailFiles.mapValues { it.value.absolutePath }
                    partial = null
                } else {
                    mutableStatus.value = status.value.copy(state = CameraDownloadState.PUBLISHING)
                    // Publication is a local commit of a complete file. Finish it atomically
                    // with respect to UI cancellation to avoid duplicate published media on retry.
                    val destination = try {
                        withContext(NonCancellable) { publisher.publish(file, entry) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw DownloadException(CameraDownloadError.MEDIASTORE_ERROR)
                    }
                    mutableStatus.value = status.value.copy(destination = destination)
                    withContext(Dispatchers.IO) { file.delete() }
                    partial = null
                }
                mutableStatus.value = status.value.copy(
                    state = CameraDownloadState.COMPLETED, downloadedBytes = entry.sizeBytes,
                    cameraConfirmed = confirmed || status.value.cameraConfirmed
                )
                diagnostic("complete bytes=" + entry.sizeBytes)
                done = true
            } catch (_: CancellationException) {
                failure = stopReason
            } catch (error: DownloadException) {
                failure = error.reason
            } catch (error: CameraMediaException) {
                failure = when (error.kind) {
                    CameraMediaError.TIMEOUT -> CameraDownloadError.CONTROL_TIMEOUT
                    CameraMediaError.REJECTED -> CameraDownloadError.CAMERA_REJECTED
                    CameraMediaError.BUSY -> CameraDownloadError.BUSY
                    else -> CameraDownloadError.NETWORK_LOST
                }
            } catch (_: IOException) {
                failure = CameraDownloadError.EOF_EARLY
            } catch (_: Exception) {
                failure = CameraDownloadError.STORAGE_ERROR
            } finally {
                runCatching { dataSocket?.close() }
                dataSocket = null
                lease?.let { client.releaseTransfer(it) }
                if (discardOnFinish || isThumb && !done) {
                    partial?.delete()
                    partial = null
                }
                if (!done) {
                    val reason = failure ?: stopReason
                    val bytes = partial?.length() ?: 0
                    mutableStatus.value = status.value.copy(
                        state = when (reason) {
                            CameraDownloadError.CANCELLED -> CameraDownloadState.CANCELLED
                            CameraDownloadError.NETWORK_BLOCKED -> CameraDownloadState.PAUSED
                            else -> CameraDownloadState.ERROR
                        },
                        downloadedBytes = bytes, error = reason,
                        canResume = partial != null && (bytes < entry.sizeBytes || publishingOnly) && reason != CameraDownloadError.SIZE_MISMATCH,
                    )
                    diagnostic("Error=" + reason + " bytesPersistidos=" + bytes)
                }
                if (discardOnFinish) {
                    selected = null
                    mutableStatus.value = CameraDownloadStatus()
                }
            }
        }
        return true
    }

    fun cancel(reason: CameraDownloadError = CameraDownloadError.CANCELLED) {
        if (!busy || status.value.state == CameraDownloadState.PUBLISHING) return
        stopReason = reason
        job?.cancel()
        runCatching { dataSocket?.close() }
    }

    suspend fun preemptThumbnail() {
        if (busy && status.value.thumbnail) {
            cancel()
            job?.join()
        }
    }

    fun discard() {
        confirmationJob?.cancel()
        if (busy) {
            discardOnFinish = true; cancel(); return
        }
        partial?.delete()
        partial = null
        selected = null
        publishingOnly = false
        mutableStatus.value = CameraDownloadStatus()
    }

    fun shutdown() {
        discard()
        thumbnailFiles.values.forEach { it.delete() }
        thumbnailFiles.clear()
        mutableThumbnails.value = emptyMap()
    }
}
