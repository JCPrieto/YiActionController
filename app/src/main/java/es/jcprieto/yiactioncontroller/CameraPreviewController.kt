package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.net.SocketFactory

enum class PreviewState { IDLE, STARTING, PLAYING, BUFFERING, STOPPING, ERROR }
enum class PreviewError { START_CONTROL, STOP_CONTROL, RTSP, RTSP_TIMEOUT, MEDIA3, NETWORK_NOT_FOUND, NETWORK_LOST, CAMERA_DISCONNECTED }
enum class PreviewControlState { UNKNOWN, START_ACCEPTED, STOP_ACCEPTED }
data class PreviewStatus(
    val state: PreviewState = PreviewState.IDLE,
    val error: String? = null,
    val errorType: PreviewError? = null,
    val control: PreviewControlState = PreviewControlState.UNKNOWN,
    val controlPending: Boolean = false,
)

/** Test seam for playback callbacks; Android/Media3 remains in Media3PreviewPlayer. */
interface PreviewPlayback {
    val status: StateFlow<PreviewStatus>
    fun start(socketFactory: SocketFactory)
    fun release()
}

/** Main-thread owner. Control acceptance and playback callbacks are independent. */
class CameraPreviewController(
    private val scope: CoroutineScope,
    private val playback: PreviewPlayback,
    private val connected: () -> Boolean,
    private val startControl: suspend () -> CameraControlResult,
    private val stopControl: suspend () -> CameraControlResult,
) {
    private val mutableStatus = MutableStateFlow(PreviewStatus())
    val status = mutableStatus.asStateFlow()
    private var wanted = false
    private var engineActive = false
    private var cameraMayStream = false
    private var operation: Job? = null
    private var cleanup: Job? = null
    private var released = false
    private val observer = scope.launch {
        playback.status.collect { update ->
            if (engineActive && wanted) {
                if (update.state == PreviewState.ERROR) stop(update.errorType ?: PreviewError.MEDIA3, update.error)
                else mutableStatus.value = update.copy(control = mutableStatus.value.control)
            }
        }
    }

    fun start(socketFactory: SocketFactory?) {
        if (released || wanted || operation?.isActive == true || cleanup?.isActive == true) return
        if (!connected()) {
            mutableStatus.value =
                PreviewStatus(PreviewState.ERROR, "Cámara desconectada", PreviewError.CAMERA_DISCONNECTED)
            return
        }
        if (socketFactory == null) {
            mutableStatus.value = PreviewStatus(
                PreviewState.ERROR,
                "No se encontró la Wi-Fi con ruta a la YI",
                PreviewError.NETWORK_NOT_FOUND
            )
            return
        }
        wanted = true
        mutableStatus.value = PreviewStatus(PreviewState.STARTING, controlPending = true)
        operation = scope.launch {
            if (!wanted) return@launch
            val result = startControl()
            cameraMayStream = cameraMayStream || result.accepted
            if (!wanted) return@launch // Stop/background/loss during the TCP handshake.
            if (!result.accepted) {
                wanted = false
                mutableStatus.value = PreviewStatus(PreviewState.ERROR, result.error, PreviewError.START_CONTROL)
                return@launch
            }
            mutableStatus.value = PreviewStatus(PreviewState.STARTING, control = PreviewControlState.START_ACCEPTED)
            engineActive = true
            try {
                playback.start(socketFactory)
            } catch (_: Exception) {
                stop(PreviewError.MEDIA3, "No se pudo crear el reproductor")
            }
        }
    }

    fun stop(errorType: PreviewError? = null, error: String? = null) {
        if (released) return
        val hadWork = wanted || engineActive || cameraMayStream || operation?.isActive == true
        wanted = false
        engineActive = false
        playback.release() // Always local-first, including rejected or delayed STOP_PREVIEW.
        if (!hadWork && errorType == null) return
        mutableStatus.value = mutableStatus.value.copy(
            state = if (errorType == null) PreviewState.STOPPING else PreviewState.ERROR,
            error = error, errorType = errorType, controlPending = true,
        )
        if (cleanup?.isActive == true) return
        val starting = operation
        cleanup = scope.launch {
            starting?.join()
            val sentStop = cameraMayStream && connected()
            val result = if (sentStop) stopControl() else CameraControlResult(true)
            cameraMayStream = !result.accepted
            val currentErrorType = mutableStatus.value.errorType
            val currentError = mutableStatus.value.error
            mutableStatus.value = mutableStatus.value.copy(
                state = if (currentErrorType != null || !result.accepted) PreviewState.ERROR else PreviewState.IDLE,
                error = currentError ?: result.error,
                errorType = currentErrorType ?: if (result.accepted) null else PreviewError.STOP_CONTROL,
                control = if (sentStop && result.accepted) PreviewControlState.STOP_ACCEPTED else PreviewControlState.UNKNOWN,
                controlPending = false,
            )
        }
    }

    fun cameraDisconnected() {
        if (!wanted && !engineActive && !cameraMayStream && cleanup?.isActive != true) return
        releaseWork()
        mutableStatus.value = PreviewStatus(PreviewState.ERROR, "Cámara desconectada", PreviewError.CAMERA_DISCONNECTED)
    }

    fun networkLost() {
        if (wanted || engineActive || cameraMayStream) stop(
            PreviewError.NETWORK_LOST,
            "Se perdió la Wi-Fi de la cámara"
        )
    }

    private fun releaseWork() {
        wanted = false
        engineActive = false
        cameraMayStream = false
        operation?.cancel()
        cleanup?.cancel()
        playback.release()
    }

    fun release() {
        if (released) return
        released = true
        releaseWork()
        observer.cancel()
        mutableStatus.value = PreviewStatus()
    }
}

/** Pure callback mapping used by the real Player.Listener and unit tests. */
class PreviewPlaybackSignals {
    private val mutableStatus = MutableStateFlow(PreviewStatus())
    val status = mutableStatus.asStateFlow()
    fun starting() {
        mutableStatus.value = PreviewStatus(PreviewState.STARTING)
    }

    fun buffering() {
        mutableStatus.value = PreviewStatus(PreviewState.BUFFERING)
    }

    fun ready(isPlaying: Boolean) {
        mutableStatus.value = PreviewStatus(if (isPlaying) PreviewState.PLAYING else PreviewState.STARTING)
    }

    fun error(type: PreviewError, message: String) {
        mutableStatus.value = PreviewStatus(PreviewState.ERROR, message, type)
    }

    fun idle() {
        mutableStatus.value = PreviewStatus()
    }
}
