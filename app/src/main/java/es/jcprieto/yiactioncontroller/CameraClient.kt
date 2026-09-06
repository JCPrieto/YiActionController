package es.jcprieto.yiactioncontroller

import es.jcprieto.yiactioncontroller.CameraCommand.EVENT
import es.jcprieto.yiactioncontroller.CameraCommand.GET_BATTERY
import es.jcprieto.yiactioncontroller.CameraCommand.GET_CONFIG
import es.jcprieto.yiactioncontroller.CameraCommand.LOGIN
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** One IO worker owns all reads, writes and pending requests for a session. */
class CameraClient(
    private val host: String = "192.168.42.1",
    private val port: Int = 7878,
    private val responseTimeoutMillis: Long = 5_000,
) : AutoCloseable {
    private class Session {
        val socket = Socket()
        val commands = Channel<List<Int>>(1)

        // Guarded by lock; reserve immediately so rapid taps cannot queue multiple actions.
        var reservedCommands: Set<Int> = emptySet()
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var session: Session? = null
    private val mutableState = MutableStateFlow(CameraState())
    val state = mutableState.asStateFlow()

    fun connect() = synchronized(lock) {
        if (session != null) return@synchronized
        val current = Session()
        session = current
        mutableState.value = CameraState(connection = ConnectionStatus.CONNECTING)
        current.job = scope.launch { runSession(current) }
    }

    fun refresh() = submit(listOf(GET_BATTERY, GET_CONFIG))
    fun takePhoto() = submit(listOf(CameraCommand.TAKE_PHOTO), CameraAction.TAKE_PHOTO)
    fun startRecording() = submit(listOf(CameraCommand.START_RECORDING), CameraAction.START_RECORDING)
    fun stopRecording() = submit(listOf(CameraCommand.STOP_RECORDING), CameraAction.STOP_RECORDING)

    private fun submit(commands: List<Int>, action: CameraAction? = null) = synchronized(lock) {
        val current = session ?: return@synchronized
        val previous = mutableState.value
        val allowed = when (action) {
            CameraAction.TAKE_PHOTO -> previous.canTakePhoto
            CameraAction.START_RECORDING -> previous.canStartRecording
            CameraAction.STOP_RECORDING -> previous.canStopRecording
            null -> previous.canSendCommand
        }
        if (!allowed || !current.commands.trySend(commands).isSuccess) return@synchronized
        current.reservedCommands = commands.toSet()
        mutableState.value = previous.copy(
            pending = current.reservedCommands,
            pendingAction = action,
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
        session = null // An old worker must never overwrite a new session's state.
        previous?.commands?.close()
        previous?.job?.cancel()
        runCatching { previous?.socket?.close() } // Unblocks read/connect immediately.
        mutableState.value = CameraState()
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
        try {
            val socket = current.socket
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 250 // Wake up for queued commands and response deadlines.
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val framer = JsonObjectFramer()
            val bytes = ByteArray(4096)
            val pending = mutableMapOf<Int, Long>()
            val queue = ArrayDeque<Int>()
            var token: Int? = null

            fun publishPending() = update(current) {
                it.copy(pending = pending.keys + queue + current.reservedCommands)
            }

            fun send(id: Int) {
                check(pending.isEmpty()) { "Solo se permite una petición en vuelo" }
                val request = CameraRequest(id, if (id == LOGIN) 0 else checkNotNull(token))
                val raw = cameraJson.encodeToString(request)
                update(current) { it.copy(lastRequest = raw) }
                output.write(raw.toByteArray(Charsets.UTF_8))
                output.flush()
                pending[id] = System.nanoTime()
                publishPending()
            }

            fun enqueueQueries() {
                if (pending.isNotEmpty() || queue.isNotEmpty()) return
                queue.addLast(GET_BATTERY)
                queue.addLast(GET_CONFIG)
                publishPending()
            }

            update(current) { it.copy(connection = ConnectionStatus.AUTHENTICATING) }
            send(LOGIN)
            while (currentCoroutineContext().isActive) {
                synchronized(lock) {
                    current.commands.tryReceive().getOrNull()?.let { commands ->
                        queue.addAll(commands)
                        current.reservedCommands = emptySet()
                    }
                }
                if (pending.isEmpty() && queue.isNotEmpty()) send(queue.removeFirst())
                val expired = pending.entries.firstOrNull {
                    (System.nanoTime() - it.value) / 1_000_000 >= responseTimeoutMillis
                }
                if (expired != null) throw SocketTimeoutException("Sin respuesta al comando ${expired.key}")
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
                    update(current) { it.copy(lastMessage = raw) }
                    val message = cameraJson.decodeFromString<CameraMessage>(raw)
                    val completesRequest = message.messageId != EVENT && message.rval != null &&
                            message.messageId in pending
                    if (message.messageId == LOGIN && LOGIN in pending) {
                        check(message.rval == 0) { "Inicio de sesión rechazado: rval=${message.rval}" }
                        token = message.param.text()?.toIntOrNull()?.takeIf { it > 0 }
                            ?: error("Token de sesión no válido")
                        pending.remove(LOGIN)
                        update(current) {
                            it.copy(connection = ConnectionStatus.CONNECTED, token = token)
                        }
                        enqueueQueries()
                    } else if (completesRequest) {
                        pending.remove(message.messageId)
                    }
                    update(current) {
                        var next = it.applyMessage(message, raw)
                        if (completesRequest && it.pendingAction?.commandId == message.messageId) {
                            if (it.pendingAction != CameraAction.TAKE_PHOTO) {
                                // ACK (including rejection) is not a report of actual recording state.
                                // Preserve any real status event already received, then request fresh status.
                                if (next.recording == RecordingState.STARTING || next.recording == RecordingState.STOPPING) {
                                    next = next.copy(recording = RecordingState.UNKNOWN)
                                }
                                queue.addLast(GET_CONFIG)
                            }
                            next = next.copy(pendingAction = null)
                        }
                        next.copy(pending = pending.keys + queue + current.reservedCommands)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            failure = exception.message ?: exception.javaClass.simpleName
        } finally {
            runCatching { current.socket.close() }
            current.commands.close()
            synchronized(lock) {
                if (session === current) {
                    session = null
                    val previous = mutableState.value
                    mutableState.value = CameraState(
                        error = failure,
                        lastRequest = previous.lastRequest,
                        lastMessage = previous.lastMessage,
                        lastEvent = previous.lastEvent,
                    )
                }
            }
        }
    }
}
