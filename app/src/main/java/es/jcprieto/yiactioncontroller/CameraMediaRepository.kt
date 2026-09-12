package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Service-owned, main-thread browser. The injected executor uses the existing TCP queue. */
internal class CameraMediaRepository(
    private val scope: CoroutineScope,
    private val execute: suspend (CameraMediaOperation) -> CameraMessage,
    private val availability: () -> CameraMediaError?,
    private val sessionIdentity: () -> Any?,
    private val diagnostic: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(CameraMediaBrowserState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var foreground = false
    private var visibilityEpoch = 0L
    private var generation = 0L

    fun setForeground(value: Boolean) {
        if (foreground && !value) visibilityEpoch++
        foreground = value
    }

    fun open() {
        if (state.value.currentPath == null) browse(CAMERA_DCIM_ROOT, storage = true, verifyCurrent = false)
    }

    fun refresh() {
        val current = state.value.currentPath
        browse(current ?: CAMERA_DCIM_ROOT, storage = true, verifyCurrent = current != null)
    }

    fun root() = openDirectory(CAMERA_SD_ROOT)

    fun parent() {
        if (state.value.currentPath == null || state.value.currentPath == CAMERA_SD_ROOT) {
            rejectPath(CameraMediaError.OUTSIDE_ROOT)
        } else openDirectory("../")
    }

    fun openDirectory(requested: String) {
        val target = resolveCameraDirectory(state.value.currentPath ?: CAMERA_SD_ROOT, requested)
        if (target == null) {
            rejectPath(
                if (requested.isBlank() || requested.any { it.isISOControl() || it == '\\' })
                    CameraMediaError.INVALID_PATH else CameraMediaError.OUTSIDE_ROOT
            )
            return
        }
        browse(target, storage = false, verifyCurrent = false)
    }

    private fun rejectPath(kind: CameraMediaError) {
        diagnostic("path rechazado fuera de SD root o inválido")
        if (!state.value.loading) showError(CameraMediaException(kind))
    }

    private fun browse(target: String, storage: Boolean, verifyCurrent: Boolean) {
        if (state.value.loading || !foreground) return
        availability()?.let { showError(CameraMediaException(it)); return }
        val identity =
            sessionIdentity() ?: run { showError(CameraMediaException(CameraMediaError.DISCONNECTED)); return }
        val epoch = visibilityEpoch
        val ticket = ++generation
        mutableState.value = state.value.copy(loading = true, error = null, errorType = null)
        job = scope.launch {
            suspend fun request(operation: CameraMediaOperation): CameraMessage {
                currentCoroutineContext().ensureActive()
                if (ticket != generation) throw CancellationException()
                if (!foreground || epoch != visibilityEpoch) throw NavigationStopped()
                availability()?.let { throw CameraMediaException(it) }
                if (sessionIdentity() !== identity) throw CameraMediaException(CameraMediaError.DISCONNECTED)
                val response = execute(operation)
                currentCoroutineContext().ensureActive()
                if (sessionIdentity() !== identity) throw CameraMediaException(CameraMediaError.DISCONNECTED)
                if (response.messageId != operation.id || response.rval == null)
                    throw CameraMediaException(CameraMediaError.PROTOCOL)
                if (response.rval != 0) {
                    diagnostic("${operation.label} rval=${response.rval}")
                    throw CameraMediaException(CameraMediaError.REJECTED, response.rval)
                }
                return response
            }
            try {
                if (storage) {
                    val total = capacityRaw(request(CameraMediaOperation.Total))
                    diagnostic("storage total recibido")
                    val free = capacityRaw(request(CameraMediaOperation.Free))
                    diagnostic("storage free recibido")
                    val info = storageInfo(total, free) ?: throw CameraMediaException(CameraMediaError.INVALID_STORAGE)
                    mutableState.value = state.value.copy(storage = info)
                }
                val directory =
                    request(if (verifyCurrent) CameraMediaOperation.Pwd else CameraMediaOperation.ChangeDirectory(target))
                val rawPwd = (directory.pwd as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw CameraMediaException(CameraMediaError.INVALID_PATH)
                val pwd = resolveCameraDirectory(CAMERA_SD_ROOT, rawPwd)
                    ?: throw CameraMediaException(CameraMediaError.OUTSIDE_ROOT)
                if (!rawPwd.startsWith('/') || pwd != target) throw CameraMediaException(CameraMediaError.INVALID_PATH)
                if (pwd != state.value.currentPath) mutableState.value = state.value.copy(
                    currentPath = pwd,
                    entries = emptyList(),
                    gallery = emptyList(),
                    hasListing = false,
                    malformedEntries = 0,
                )
                diagnostic("cwd=$pwd")
                val result = parseMediaListing(request(CameraMediaOperation.ListDirectory).listing, pwd)
                // LIST may finish after onStop. Retain its result without starting another request.
                mutableState.value = state.value.copy(
                    entries = result.entries, gallery = buildGallery(result.entries),
                    hasListing = true, malformedEntries = result.malformedEntries,
                )
                diagnostic("list entries=${result.entries.size}")
                if (result.malformedEntries > 0) diagnostic("metadata inválida entries=${result.malformedEntries}")
            } catch (_: NavigationStopped) {
                diagnostic("Secuencia detenida al salir de foreground; petición actual completada")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CameraMediaException) {
                val actual =
                    if (error.kind == CameraMediaError.DISCONNECTED && availability() == CameraMediaError.BLOCKED)
                        CameraMediaException(CameraMediaError.BLOCKED) else error
                showError(actual)
            } catch (_: Exception) {
                showError(CameraMediaException(CameraMediaError.PROTOCOL))
            } finally {
                if (ticket == generation) mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    /** In-flight requests get their precise timeout/EOF result from the client's completion. */
    fun onDisconnected() {
        if (!state.value.loading) mutableState.value = CameraMediaBrowserState(
            error = state.value.error ?: CameraMediaError.DISCONNECTED.description,
            errorType = state.value.errorType ?: CameraMediaError.DISCONNECTED,
        )
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = CameraMediaBrowserState()
    }

    /** A resumed transfer may select a different directory from the one currently displayed. */
    fun workingDirectoryChanged(path: String) {
        if (state.value.currentPath != path) mutableState.value = state.value.copy(
            currentPath = path, entries = emptyList(), gallery = emptyList(), hasListing = false, malformedEntries = 0,
        )
    }

    private fun showError(error: CameraMediaException) {
        val previous = if (sessionIdentity() == null || error.kind in setOf(
                CameraMediaError.DISCONNECTED,
                CameraMediaError.TIMEOUT
            )
        )
            CameraMediaBrowserState(loading = state.value.loading) else state.value
        mutableState.value = previous.copy(error = error.message, errorType = error.kind)
        diagnostic("Error=${error.kind}")
    }

    private fun capacityRaw(response: CameraMessage): Long =
        (response.param as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..Long.MAX_VALUE / 1024 }
            ?: throw CameraMediaException(CameraMediaError.INVALID_STORAGE)

    private class NavigationStopped : Exception()
}
