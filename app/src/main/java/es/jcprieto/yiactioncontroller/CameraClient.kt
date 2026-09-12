package es.jcprieto.yiactioncontroller

import es.jcprieto.yiactioncontroller.CameraCommand.EVENT
import es.jcprieto.yiactioncontroller.CameraCommand.GET_BATTERY
import es.jcprieto.yiactioncontroller.CameraCommand.GET_CONFIG
import es.jcprieto.yiactioncontroller.CameraCommand.LOGIN
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.net.SocketFactory

/** One IO worker owns all reads, writes and pending requests for a session. */
class CameraClient(
    private val host: String = "192.168.42.1",
    private val port: Int = 7878,
    private val responseTimeoutMillis: Long = 5_000,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    val diagnostics: DiagnosticHistory = DiagnosticHistory(),
) : AutoCloseable {
    private data class Outbound(
        val id: Int,
        val extras: Map<String, String> = emptyMap(),
        val response: CompletableDeferred<CameraMessage>? = null,
        val settings: CameraSettingsOperation? = null,
    )
    private class Session(val factory: SocketFactory) {
        var socket: Socket? = null
        var previewResult: Pair<Int, CompletableDeferred<CameraControlResult>>? = null
        var stopConfirmation: CompletableDeferred<Boolean>? = null
        var stopConfirmationArmed = false
        val commands = Channel<List<Outbound>>(1)

        // Covers queued, in-flight and abandoned awaits until reply/terminal cleanup.
        var mediaResponse: CompletableDeferred<CameraMessage>? = null
        var transfer: CameraTransferLease? = null
        val settingsAllowed = mutableMapOf<String, List<String>>()

        // Guarded by lock; reserve immediately so rapid taps cannot queue multiple actions.
        var reservedCommands: Set<Int> = emptySet()
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var session: Session? = null
    private val mutableState = MutableStateFlow(CameraState())
    val state = mutableState.asStateFlow()

    internal fun sessionIdentity(): Any? = synchronized(lock) { session }
    internal fun hasTransferBarrier(): Boolean = synchronized(lock) {
        session?.transfer?.let { it.released && it.armed && !it.terminal.isCompleted } == true
    }

    internal fun acquireTransfer(): CameraTransferLease = synchronized(lock) {
        val current = session ?: throw DownloadException(CameraDownloadError.NETWORK_LOST)
        current.transfer?.let {
            if (!it.released || !it.terminal.isCompleted) throw DownloadException(CameraDownloadError.CONTROL_DRAIN)
        }
        if (!state.value.canSendCommand) throw DownloadException(CameraDownloadError.BUSY)
        CameraTransferLease().also { current.transfer = it }
    }

    internal suspend fun awaitTransferDrain(): Boolean {
        val previous = synchronized(lock) { session?.transfer }
        if (previous == null || previous.terminal.isCompleted) return true
        if (!previous.released) return false
        return withTimeoutOrNull(5_000) { previous.terminal.await(); true } ?: false
    }

    internal fun releaseTransfer(lease: CameraTransferLease) = synchronized(lock) {
        lease.released = true
        // Keep the event barrier after cancellation/missing confirmation. It clears on a
        // terminal event or a fresh TCP session, never by attributing an old event to a new file.
        if (!lease.armed) lease.terminal.complete(null)
    }

    fun connect(factory: SocketFactory = socketFactory) = synchronized(lock) {
        if (session != null) return@synchronized
        val current = Session(factory)
        session = current
        diagnostics.append("TCP", "Nueva conexión")
        mutableState.value = CameraState(connection = ConnectionStatus.CONNECTING)
        current.job = scope.launch { runSession(current) }
    }

    fun refresh() = submit(listOf(GET_BATTERY, GET_CONFIG))
    fun takePhoto() = submit(listOf(CameraCommand.TAKE_PHOTO), CameraAction.TAKE_PHOTO)
    fun startRecording() = submit(listOf(CameraCommand.START_RECORDING), CameraAction.START_RECORDING)
    fun stopRecording() = submit(listOf(CameraCommand.STOP_RECORDING), CameraAction.STOP_RECORDING)

    /** Uses the very same worker, pending slot and timeout as media/control. */
    internal suspend fun requestSettings(operation: CameraSettingsOperation): CameraMessage {
        val deferred = CompletableDeferred<CameraMessage>()
        synchronized(lock) {
            val current = session ?: throw CameraSettingsException(CameraSettingsError.DISCONNECTED)
            val key = when (operation) {
                is CameraSettingsOperation.ReadAllowed -> operation.key
                is CameraSettingsOperation.SetValue -> operation.key
                CameraSettingsOperation.ReadAll -> null
            }
            if (key != null && key !in EDITABLE_SETTING_KEYS)
                throw CameraSettingsException(CameraSettingsError.UNSUPPORTED_SETTING)
            if (current.transfer?.released == false) throw CameraSettingsException(CameraSettingsError.DOWNLOAD_ACTIVE)
            if (mutableState.value.connection != ConnectionStatus.CONNECTED)
                throw CameraSettingsException(CameraSettingsError.DISCONNECTED)
            if (!mutableState.value.canSendCommand) throw CameraSettingsException(CameraSettingsError.BUSY)
            if (operation is CameraSettingsOperation.SetValue) {
                if (mutableState.value.recording != RecordingState.IDLE)
                    throw CameraSettingsException(CameraSettingsError.NOT_IDLE)
                if (operation.value !in current.settingsAllowed[operation.key].orEmpty())
                    throw CameraSettingsException(CameraSettingsError.UNSUPPORTED_VALUE)
                current.settingsAllowed.clear()
            }
            if (!current.commands.trySend(
                    listOf(
                        Outbound(
                            operation.id,
                            operation.arguments(),
                            deferred,
                            operation
                        )
                    )
                ).isSuccess
            )
                throw CameraSettingsException(CameraSettingsError.DISCONNECTED)
            current.mediaResponse = deferred
            current.reservedCommands = setOf(operation.id)
            mutableState.value = mutableState.value.copy(pending = setOf(operation.id), error = null)
        }
        return try {
            deferred.await()
        } catch (e: CameraMediaException) {
            throw CameraSettingsException(
                if (e.kind == CameraMediaError.TIMEOUT) CameraSettingsError.TIMEOUT
                else if (e.kind == CameraMediaError.PROTOCOL) CameraSettingsError.INVALID_RESPONSE else CameraSettingsError.DISCONNECTED
            )
        }
    }

    internal suspend fun requestMedia(
        operation: CameraMediaOperation,
        owner: CameraTransferLease? = null
    ): CameraMessage {
        val deferred = CompletableDeferred<CameraMessage>()
        synchronized(lock) {
            val current = session ?: throw CameraMediaException(CameraMediaError.DISCONNECTED)
            if (current.transfer?.released == false && current.transfer !== owner)
                throw CameraMediaException(CameraMediaError.BUSY)
            if (owner != null && (current.transfer !== owner || owner.released))
                throw CameraMediaException(CameraMediaError.DISCONNECTED)
            if (operation is CameraMediaOperation.GetFile) {
                if (owner == null) throw CameraMediaException(CameraMediaError.BUSY)
                val file = operation.request
                if (file.offset < 0 || file.fetchSize < 0 || file.name.isBlank() ||
                    file.name.contains('/') || file.name.contains('\\') || file.name.any { it.isISOControl() }
                )
                    throw CameraMediaException(CameraMediaError.INVALID_PATH)
            }
            if (mutableState.value.connection != ConnectionStatus.CONNECTED)
                throw CameraMediaException(CameraMediaError.DISCONNECTED)
            if (!mutableState.value.canSendCommand) throw CameraMediaException(CameraMediaError.BUSY)
            if (!current.commands.trySend(listOf(Outbound(operation.id, operation.arguments(), deferred))).isSuccess)
                throw CameraMediaException(CameraMediaError.DISCONNECTED)
            // The queued GET_FILE still owns its eventual terminal event if its caller
            // cancels before the worker writes it. Do not clear that barrier early.
            if (operation is CameraMediaOperation.GetFile) owner?.armed = true
            current.mediaResponse = deferred
            current.reservedCommands = setOf(operation.id)
            mutableState.value = mutableState.value.copy(pending = setOf(operation.id), error = null)
        }
        // Cancelling a caller never permits a new wire request before this one finishes.
        val message = deferred.await()
        if (message.rval != 0) {
            diagnostics.append("MEDIA", "${operation.label} rval=${message.rval}")
            throw CameraMediaException(CameraMediaError.REJECTED, message.rval)
        }
        return message
    }

    suspend fun startPreview(): CameraControlResult = previewCommand(CameraCommand.START_PREVIEW)
    suspend fun stopPreview(): CameraControlResult = previewCommand(CameraCommand.STOP_PREVIEW)

    /** Explicit recovery only. The continuous reader captures a fresh vf_stop, even before ACK. */
    suspend fun stopPreviewAndAwaitVfStop(): CameraControlResult {
        val current: Session
        val confirmation = CompletableDeferred<Boolean>()
        val response: CompletableDeferred<CameraControlResult>
        synchronized(lock) {
            current = session ?: return CameraControlResult(false, "Cámara desconectada")
            if (current.transfer?.released == false || !mutableState.value.canSendCommand || mutableState.value.recording != RecordingState.IDLE)
                return CameraControlResult(false, "Reinicio no permitido: cámara ocupada o grabación no inactiva")
            response = CompletableDeferred()
            current.previewResult = CameraCommand.STOP_PREVIEW to response
            current.stopConfirmation = confirmation
            current.stopConfirmationArmed = false
            submit(listOf(CameraCommand.STOP_PREVIEW))
            mutableState.value = mutableState.value.copy(awaitingPreviewStop = true)
            diagnostics.append("RECOVERY", "Enviar 260 y esperar ACK + vf_stop nuevo")
        }
        try {
            val result = response.await()
            if (!result.accepted) return result
            val stopped = withTimeoutOrNull(PREVIEW_STOP_EVENT_TIMEOUT_MS) { confirmation.await() }
            return when (stopped) {
                true -> CameraControlResult(true)
                false -> CameraControlResult(false, "Confirmación vf_stop cancelada: desconexión o grabación activa")
                null -> CameraControlResult(false, "Timeout esperando vf_stop tras aceptar 260")
            }.also { diagnostics.append("RECOVERY", it.error ?: "260 aceptado y vf_stop recibido") }
        } finally {
            synchronized(lock) {
                if (current.stopConfirmation === confirmation) {
                    current.stopConfirmation = null
                    current.stopConfirmationArmed = false
                    if (session === current) mutableState.value = mutableState.value.copy(awaitingPreviewStop = false)
                }
            }
        }
    }

    private suspend fun previewCommand(id: Int): CameraControlResult {
        val result = synchronized(lock) {
            val current = session
            if (current == null || current.transfer?.released == false || !mutableState.value.canSendCommand) null else {
                val deferred = CompletableDeferred<CameraControlResult>()
                current.previewResult = id to deferred
                submit(listOf(id))
                if (id == CameraCommand.START_PREVIEW) mutableState.value =
                    mutableState.value.copy(previewControlRequested = true)
                deferred
            }
        } ?: return CameraControlResult(false, "Cámara desconectada o petición pendiente")
        return result.await()
    }

    private fun submit(commands: List<Int>, action: CameraAction? = null) = synchronized(lock) {
        val current = session ?: return@synchronized
        if (current.transfer?.released == false) return@synchronized
        val previous = mutableState.value
        val allowed = when (action) {
            CameraAction.TAKE_PHOTO -> previous.canTakePhoto
            CameraAction.START_RECORDING -> previous.canStartRecording
            CameraAction.STOP_RECORDING -> previous.canStopRecording
            null -> previous.canSendCommand
        }
        val outbound = commands.map(::Outbound)
        if (!allowed || !current.commands.trySend(outbound).isSuccess) return@synchronized
        current.reservedCommands = commands.toSet()
        mutableState.value = previous.copy(
            pending = current.reservedCommands,
            pendingAction = action,
            recordingStopRequested = when (action) {
                CameraAction.STOP_RECORDING -> true
                CameraAction.START_RECORDING -> false
                else -> previous.recordingStopRequested
            },
            error = null,
            recording = when (action) {
                CameraAction.START_RECORDING -> RecordingState.STARTING
                CameraAction.STOP_RECORDING -> RecordingState.STOPPING
                else -> previous.recording
            },
        )
    }

    fun disconnect() = synchronized(lock) {
        val previous = session
        if (previous != null) diagnostics.append("TCP", "Desconexión solicitada")
        session = null // An old worker must never overwrite a new session's state.
        previous?.commands?.close()
        previous?.job?.cancel()
        previous?.previewResult?.second?.complete(CameraControlResult(false, "Cámara desconectada"))
        previous?.stopConfirmation?.complete(false)
        runCatching { previous?.socket?.close() } // Unblocks read/connect immediately.
        mutableState.value = CameraState()
        previous?.mediaResponse?.completeExceptionally(CameraMediaException(CameraMediaError.DISCONNECTED))
        previous?.mediaResponse = null
        previous?.transfer?.terminal?.complete(null)
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    private fun update(current: Session, change: (CameraState) -> CameraState) = synchronized(lock) {
        if (session === current) mutableState.value = change(mutableState.value)
    }

    private suspend fun runSession(current: Session) {
        var failure: String? = null
        var mediaFailure = CameraMediaError.DISCONNECTED
        try {
            val socket = current.factory.createSocket()
            synchronized(lock) {
                if (session !== current) {
                    socket.close()
                    return
                }
                current.socket = socket
            }
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 250 // Wake up for queued commands and response deadlines.
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val framer = JsonObjectFramer()
            val bytes = ByteArray(4096)
            val pending = mutableMapOf<Int, Pair<Long, Outbound>>()
            val queue = ArrayDeque<Outbound>()
            var token: Int? = null
            var actionRecordingRevision = 0L
            var configRecordingRevision = 0L
            var actionConfigRevision: Long? = null

            fun publishPending() = update(current) {
                it.copy(pending = pending.keys + queue.map { command -> command.id } + current.reservedCommands)
            }

            fun send(command: Outbound) {
                val id = command.id
                check(pending.isEmpty()) { "Solo se permite una petición en vuelo" }
                val request = buildJsonObject {
                    put("msg_id", id)
                    put("token", if (id == LOGIN) 0 else checkNotNull(token))
                    command.extras.forEach { (key, value) ->
                        if (id == CameraCommand.GET_FILE && key in setOf("offset", "fetch_size")) put(
                            key,
                            value.toLong()
                        )
                        else put(key, value)
                    }
                }
                val raw = request.toString()
                update(current) {
                    if (id == CameraCommand.STOP_PREVIEW && current.stopConfirmation != null)
                        current.stopConfirmationArmed = true
                    if (id == CameraCommand.START_RECORDING || id == CameraCommand.STOP_RECORDING) {
                        actionRecordingRevision = it.recordingRevision
                    }
                    if (id == GET_CONFIG) {
                        // The automatic snapshot may lag behind an event even before the ACK.
                        configRecordingRevision = actionConfigRevision ?: it.recordingRevision
                        actionConfigRevision = null
                    }
                    // Keep the diagnostic request shape, never the live authentication token.
                    it.copy(lastRequest = buildJsonObject {
                        request.forEach { (key, value) -> if (key == "token") put(key, 0) else put(key, value) }
                    }.toString())
                }
                if (id == CameraCommand.GET_FILE) synchronized(lock) { current.transfer?.armed = true }
                output.write(raw.toByteArray(Charsets.UTF_8))
                output.flush()
                update(current) { diagnostics.sent(id); it }
                pending[id] = System.nanoTime() to command
                publishPending()
            }

            fun enqueueQueries() {
                if (pending.isNotEmpty() || queue.isNotEmpty()) return
                queue.addLast(Outbound(GET_BATTERY))
                queue.addLast(Outbound(GET_CONFIG))
                publishPending()
            }

            update(current) { it.copy(connection = ConnectionStatus.AUTHENTICATING) }
            send(Outbound(LOGIN))
            while (currentCoroutineContext().isActive) {
                synchronized(lock) {
                    current.commands.tryReceive().getOrNull()?.let { commands ->
                        queue.addAll(commands)
                        current.reservedCommands = emptySet()
                    }
                }
                if (pending.isEmpty() && queue.isNotEmpty()) send(queue.removeFirst())
                val expired = pending.entries.firstOrNull {
                    (System.nanoTime() - it.value.first) / 1_000_000 >= responseTimeoutMillis
                }
                if (expired != null) {
                    update(current) { diagnostics.append("TIMEOUT", "msg_id=${expired.key}"); it }
                    throw SocketTimeoutException("Sin respuesta al comando ${expired.key}")
                }
                val count = try {
                    input.read(bytes)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count == -1) {
                    framer.endOfInput()
                    throw EOFException("La cámara cerró la conexión")
                }
                // Frame byte-for-byte first: ASCII JSON delimiters cannot occur inside a UTF-8
                // multibyte sequence. Decode only complete objects, even across read timeouts.
                for (frame in framer.feed(String(bytes, 0, count, Charsets.ISO_8859_1))) {
                    val raw = Charsets.UTF_8.newDecoder().decode(
                        ByteBuffer.wrap(frame.toByteArray(Charsets.ISO_8859_1)),
                    ).toString()
                    val message = cameraJson.decodeFromString<CameraMessage>(raw)
                    synchronized(lock) {
                        if (session === current) {
                            val transfer = current.transfer
                            if (transfer?.armed == true) parseFileEvent(message)?.let { transfer.terminal.complete(it) }
                            if (message.messageId == CameraCommand.GET_FILE && message.rval != null && message.rval != 0)
                                transfer?.terminal?.complete(null)
                        }
                    }
                    update(current) { diagnostics.received(message); it }
                    synchronized(lock) {
                        if (session === current && current.stopConfirmationArmed && message.messageId == EVENT) {
                            if (message.type == "vf_stop") current.stopConfirmation?.complete(true)
                            if (message.type == "start_video_record") current.stopConfirmation?.complete(false)
                        }
                    }
                    val pendingCommand = pending[message.messageId]?.second
                    val completesRequest = message.messageId != EVENT && message.rval != null &&
                            message.messageId in pending
                    if (message.messageId == LOGIN && LOGIN in pending) {
                        check(message.rval == 0) { "Inicio de sesión rechazado: rval=${message.rval}" }
                        token = message.param.text()?.toIntOrNull()?.takeIf { it > 0 }
                            ?: error("Token de sesión no válido")
                        pending.remove(LOGIN)
                        update(current) {
                            it.copy(connection = ConnectionStatus.CONNECTED, authenticated = true)
                        }
                        enqueueQueries()
                    } else if (completesRequest) {
                        pending.remove(message.messageId)
                    }
                    update(current) {
                        val detail = pendingCommand?.settings is CameraSettingsOperation.ReadAllowed
                        var next = it.applyMessage(message, raw, fullConfiguration = !detail)
                        if (completesRequest && current.previewResult?.first == message.messageId) {
                            if (message.messageId == CameraCommand.STOP_PREVIEW && message.rval == 0 ||
                                message.messageId == CameraCommand.START_PREVIEW && message.rval != 0
                            ) {
                                next = next.copy(previewControlRequested = false)
                            }
                            current.previewResult?.second?.complete(
                                CameraControlResult(
                                    message.rval == 0,
                                    if (message.rval == 0) null else "Comando ${message.messageId}: rval=${message.rval}",
                                    message.rval,
                                )
                            )
                            current.previewResult = null
                        }
                        if (message.messageId == GET_CONFIG && completesRequest &&
                            it.recordingRevision != configRecordingRevision
                        ) {
                            next = next.copy(recording = it.recording)
                        }
                        if (completesRequest && it.pendingAction?.commandId == message.messageId) {
                            if (message.rval != 0) next = next.copy(recordingStopRequested = false)
                            if (it.pendingAction != CameraAction.TAKE_PHOTO) {
                                // ACK (including rejection) is not a report of actual recording state.
                                // Preserve any real status event already received, then request fresh status.
                                if (next.recording == RecordingState.STARTING || next.recording == RecordingState.STOPPING) {
                                    next = next.copy(recording = RecordingState.UNKNOWN)
                                }
                                actionConfigRevision = actionRecordingRevision
                                queue.addLast(Outbound(GET_CONFIG))
                            }
                            next = next.copy(pendingAction = null)
                        }
                        next.copy(pending = pending.keys + queue.map { command -> command.id } + current.reservedCommands)
                    }
                    if (completesRequest) synchronized(lock) {
                        val response = pendingCommand?.response
                        if (response != null) {
                            val detail = pendingCommand.settings as? CameraSettingsOperation.ReadAllowed
                            if (detail != null && session === current) {
                                current.settingsAllowed[detail.key] = if (message.rval == 0)
                                    parseSettable(parseCameraConfiguration(message.param)?.values?.get(detail.key))
                                else emptyList()
                            }
                            if (current.mediaResponse === response) current.mediaResponse = null
                            // State must be idle before a resumed caller submits total/free or CD/LIST.
                            if (session === current) response.complete(message)
                            else response.completeExceptionally(CameraMediaException(CameraMediaError.DISCONNECTED))
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            mediaFailure = when (exception) {
                is SocketTimeoutException -> CameraMediaError.TIMEOUT
                is kotlinx.serialization.SerializationException -> CameraMediaError.PROTOCOL
                else -> CameraMediaError.DISCONNECTED
            }
            update(current) { diagnostics.tcpFailure(exception); it }
            // Serialization errors can embed the full incoming JSON, including Wi-Fi credentials.
            failure = if (exception is kotlinx.serialization.SerializationException) "Respuesta JSON no válida"
            else exception.message ?: exception.javaClass.simpleName
        } finally {
            runCatching { current.socket?.close() }
            current.commands.close()
            synchronized(lock) {
                current.previewResult?.second?.complete(CameraControlResult(false, failure ?: "Cámara desconectada"))
                current.stopConfirmation?.complete(false)
                if (session === current) {
                    diagnostics.append(
                        "TCP",
                        if (failure == null) "Sesión terminada" else "Sesión terminada con error; consultar diagnóstico TCP"
                    )
                    session = null
                    val previous = mutableState.value
                    mutableState.value = CameraState(
                        error = failure,
                        lastRequest = previous.lastRequest,
                        lastMessage = previous.lastMessage,
                        lastEvent = previous.lastEvent,
                    )
                }
                current.mediaResponse?.completeExceptionally(CameraMediaException(mediaFailure))
                current.mediaResponse = null
                current.transfer?.terminal?.complete(null)
            }
        }
    }

    private companion object {
        const val PREVIEW_STOP_EVENT_TIMEOUT_MS = 5_000L
    }
}
