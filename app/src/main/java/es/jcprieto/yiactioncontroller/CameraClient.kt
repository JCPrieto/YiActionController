package es.jcprieto.yiactioncontroller

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
        val commands = Channel<Int>(Channel.CONFLATED)
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

    fun refresh() = synchronized(lock) {
        if (mutableState.value.connection == ConnectionStatus.CONNECTED) {
            session?.commands?.trySend(0)
        }
        Unit
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
            var token: Int? = null

            fun send(id: Int) {
                if (id in pending) return
                val request = CameraRequest(id, if (id == 257) 0 else checkNotNull(token))
                output.write(cameraJson.encodeToString(request).toByteArray(Charsets.UTF_8))
                output.flush()
                pending[id] = System.nanoTime()
                update(current) { it.copy(pending = pending.keys.toSet()) }
            }

            update(current) { it.copy(connection = ConnectionStatus.AUTHENTICATING) }
            send(257)
            while (currentCoroutineContext().isActive) {
                if (current.commands.tryReceive().isSuccess && token != null) {
                    update(current) { it.copy(error = null) }
                    send(13)
                    send(3)
                }
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
                    val message = cameraJson.decodeFromString<CameraMessage>(raw)
                    if (message.messageId == 257 && 257 in pending) {
                        check(message.rval == 0) { "Inicio de sesión rechazado: rval=${message.rval}" }
                        token = message.param.text()?.toIntOrNull()?.takeIf { it > 0 }
                            ?: error("Token de sesión no válido")
                        pending.remove(257)
                        update(current) {
                            it.copy(connection = ConnectionStatus.CONNECTED, token = token)
                        }
                        send(13)
                        send(3)
                    } else if (message.rval != null) {
                        pending.remove(message.messageId)
                    }
                    update(current) {
                        it.applyMessage(message, raw).copy(pending = pending.keys.toSet())
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
                    mutableState.value = CameraState(error = failure)
                }
            }
        }
    }
}
