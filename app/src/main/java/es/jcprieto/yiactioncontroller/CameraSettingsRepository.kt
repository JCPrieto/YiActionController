package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Service-owned RAM cache. All calls and state transitions run on the service main dispatcher. */
internal class CameraSettingsRepository(
    private val scope: CoroutineScope,
    private val execute: suspend (CameraSettingsOperation) -> CameraMessage,
    private val availability: (write: Boolean) -> CameraSettingsError?,
    private val sessionIdentity: () -> Any?,
    private val preparePreview: suspend () -> Boolean,
    private val diagnostic: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(CameraSettingsState())
    val state = mutableState.asStateFlow()
    private var identity: Any? = null
    private var foreground = false
    private var visibilityEpoch = 0L
    private var generation = 0L
    private var job: Job? = null

    fun setForeground(value: Boolean) {
        if (foreground && !value) visibilityEpoch++
        foreground = value
    }

    fun sessionChanged() {
        if (identity !== sessionIdentity()) {
            identity = sessionIdentity()
            // Let the current wire request finish, but invalidate its logical sequence.
            generation++
            mutableState.value = CameraSettingsState()
        }
    }

    fun open() {
        sessionChanged()
        if (!state.value.discovered) refresh()
    }

    fun refresh() {
        sessionChanged()
        if (state.value.busy || !foreground) return
        availability(false)?.let { mutableState.value = state.value.copy(error = it); return }
        launch(null)
    }

    fun apply(key: String, value: String) {
        sessionChanged()
        if (state.value.busy || !foreground) return
        if (key !in EDITABLE_SETTING_KEYS) {
            mutableState.value = state.value.copy(error = CameraSettingsError.UNSUPPORTED_SETTING)
            return // Never put untrusted keys/values in mutation state or diagnostics.
        }
        val setting = state.value.settings.firstOrNull { it.key == key }
        val invalid = when {
            setting?.access != CameraSettingAccess.EDITABLE -> CameraSettingsError.UNSUPPORTED_SETTING
            value !in setting.allowedValues -> CameraSettingsError.UNSUPPORTED_VALUE
            else -> availability(true)
        }
        if (invalid != null) {
            mutableState.value = state.value.copy(mutation = CameraSettingsMutationState.Failed(key, invalid))
            return
        }
        launch(CameraSettingsOperation.SetValue(key, value))
    }

    private fun launch(change: CameraSettingsOperation.SetValue?) {
        val session = sessionIdentity() ?: run {
            mutableState.value = state.value.copy(error = CameraSettingsError.DISCONNECTED); return
        }
        identity = session
        val ticket = ++generation
        val epoch = visibilityEpoch
        mutableState.value = state.value.copy(
            loading = true, error = null,
            mutation = change?.let { CameraSettingsMutationState.Applying(it.key, it.value) }
                ?: CameraSettingsMutationState.Idle,
        )
        job = scope.launch {
            fun checkSession() {
                if (ticket != generation || sessionIdentity() !== session) throw SessionChanged()
            }

            fun checkVisible() {
                if (!foreground || visibilityEpoch != epoch) throw DiscoveryStopped()
            }

            suspend fun request(op: CameraSettingsOperation, finishMutation: Boolean = false): CameraMessage {
                checkSession()
                if (!finishMutation) checkVisible()
                availability(op is CameraSettingsOperation.SetValue)?.let { throw CameraSettingsException(it) }
                val response = execute(op)
                checkSession()
                if (response.messageId != op.id || response.rval == null)
                    throw CameraSettingsException(CameraSettingsError.INVALID_RESPONSE)
                return response
            }

            fun replaceConfig(response: CameraMessage): Map<String, String> {
                if (response.rval != 0) throw CameraSettingsException(
                    CameraSettingsError.CAMERA_REJECTED,
                    response.rval
                )
                val parsed = parseCameraConfiguration(response.param)
                    ?: throw CameraSettingsException(CameraSettingsError.INVALID_RESPONSE)
                mutableState.value = state.value.copy(
                    settings = parsed.values.map { (key, value) ->
                        CameraSetting(key, value, category = settingCategory(key))
                    },
                    malformedEntries = parsed.malformedEntries, discovered = false,
                )
                diagnostic("config entries=" + parsed.values.size + " malformed=" + parsed.malformedEntries)
                return parsed.values
            }
            try {
                diagnostic(if (change == null) "load start" else "apply " + change.key)
                if (change != null) {
                    if (!preparePreview()) throw CameraSettingsException(CameraSettingsError.PREVIEW_STOP_FAILED)
                    checkSession()
                    mutableState.value = state.value.copy(
                        settings = state.value.settings.map {
                            it.copy(
                                allowedValues = emptyList(),
                                access = CameraSettingAccess.READ_ONLY
                            )
                        },
                        discovered = false,
                    )
                    val ack = request(change)
                    diagnostic("ack " + change.key + " rval=" + ack.rval)
                    if (ack.rval != 0) throw CameraSettingsException(CameraSettingsError.CAMERA_REJECTED, ack.rval)
                    // Once SET is on the wire, VERIFY belongs to the service even after Home.
                    val actual = replaceConfig(request(CameraSettingsOperation.ReadAll, finishMutation = true))
                    if (actual[change.key] != change.value) throw CameraSettingsException(CameraSettingsError.VERIFY_FAILED)
                    mutableState.value =
                        state.value.copy(mutation = CameraSettingsMutationState.Verified(change.key, change.value))
                    diagnostic("verified " + change.key)
                } else replaceConfig(request(CameraSettingsOperation.ReadAll))

                for (key in EDITABLE_SETTING_KEYS) {
                    if (state.value.settings.none { it.key == key }) continue
                    val detail = request(CameraSettingsOperation.ReadAllowed(key))
                    val allowed = if (detail.rval == 0) {
                        parseSettable(parseCameraConfiguration(detail.param)?.values?.get(key))
                    } else {
                        diagnostic("unsupported " + key + " rval=" + detail.rval)
                        emptyList()
                    }
                    mutableState.value = state.value.copy(settings = state.value.settings.map {
                        if (it.key == key) it.copy(
                            allowedValues = allowed,
                            access = if (allowed.isNotEmpty()) CameraSettingAccess.EDITABLE else CameraSettingAccess.READ_ONLY,
                        ) else it
                    })
                    diagnostic("capabilities " + key + " values=" + allowed.size)
                }
                mutableState.value = state.value.copy(discovered = true)
            } catch (_: DiscoveryStopped) {
                diagnostic("Descubrimiento detenido al salir de Ajustes")
                if (state.value.mutation is CameraSettingsMutationState.Applying)
                    mutableState.value = state.value.copy(mutation = CameraSettingsMutationState.Idle)
            } catch (_: SessionChanged) {
                if (ticket == generation) {
                    identity = sessionIdentity()
                    mutableState.value = CameraSettingsState(error = CameraSettingsError.DISCONNECTED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CameraSettingsException) {
                if (ticket != generation) return@launch
                diagnostic("Error=" + error.kind + (error.rval?.let { " rval=" + it } ?: ""))
                mutableState.value =
                    if (change != null && state.value.mutation !is CameraSettingsMutationState.Verified)
                        state.value.copy(mutation = CameraSettingsMutationState.Failed(change.key, error.kind))
                    else state.value.copy(error = error.kind)
            } catch (_: Exception) {
                if (ticket != generation) return@launch
                mutableState.value = state.value.copy(
                    error = CameraSettingsError.INVALID_RESPONSE,
                    mutation = change?.let {
                        CameraSettingsMutationState.Failed(
                            it.key,
                            CameraSettingsError.INVALID_RESPONSE
                        )
                    }
                        ?: CameraSettingsMutationState.Idle)
            } finally {
                if (ticket == generation) mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    fun reset() {
        generation++
        job?.cancel()
        identity = null
        mutableState.value = CameraSettingsState()
    }

    private class SessionChanged : Exception()
    private class DiscoveryStopped : Exception()
}
