package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CameraSettingsRepositoryTest {
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val operations = mutableListOf<CameraSettingsOperation>()
        val log = mutableListOf<String>()
        var identity: Any? = Any()
        var unavailable: CameraSettingsError? = null
        var recording = false
        var previewStops = 0
        var previewCanStop = true
        var applyValue = true
        var setRval = 0
        var detailRval = 0
        var values = linkedMapOf("video_stamp" to "off", "video_standard" to "NTSC")
        var domain = "settable:off#date#time#date/time"
        var gateOn: CameraSettingsOperation? = null
        var gate = CompletableDeferred<Unit>()
        val repository = CameraSettingsRepository(
            scope, { operation ->
            operations += operation
            if (operation == gateOn) gate.await()
            when (operation) {
                CameraSettingsOperation.ReadAll -> CameraMessage(3, 0, param = JsonArray(values.map { (k, v) ->
                    buildJsonObject { put(k, v) }
                }))

                is CameraSettingsOperation.ReadAllowed -> CameraMessage(
                    3, detailRval,
                    param = JsonArray(listOf(buildJsonObject { put(operation.key, domain) }))
                )

                is CameraSettingsOperation.SetValue -> {
                    if (applyValue && setRval == 0) values[operation.key] = operation.value
                    CameraMessage(2, setRval)
                }
            }
        }, { write -> unavailable ?: if (write && recording) CameraSettingsError.NOT_IDLE else null },
            { identity }, { previewStops++; previewCanStop }, log::add
        )

        init {
            repository.setForeground(true)
        }

        fun load() {
            repository.open(); operations.clear()
        }

        fun close() = scope.cancel()
    }

    @Test
    fun loadIsReadOnlyWhitelistAndRotationDoesNotRepeat() {
        val h = Harness()
        h.values["new_setting"] = "settable:danger"
        h.repository.open()
        assertEquals(
            listOf(CameraSettingsOperation.ReadAll, CameraSettingsOperation.ReadAllowed("video_stamp")),
            h.operations
        )
        assertEquals(
            CameraSettingAccess.READ_ONLY,
            h.repository.state.value.settings.first { it.key == "new_setting" }.access
        )
        h.operations.clear()
        h.repository.open()
        assertTrue(h.operations.isEmpty())
        assertEquals(0, h.previewStops)
        h.close()
    }

    @Test
    fun setRequiresGetVerificationAndRediscoversDomainsIncludingRestore() {
        val h = Harness(); h.load()
        for (value in listOf("date", "off")) {
            h.operations.clear()
            h.repository.apply("video_stamp", value)
            assertEquals(
                listOf(
                    CameraSettingsOperation.SetValue("video_stamp", value),
                    CameraSettingsOperation.ReadAll, CameraSettingsOperation.ReadAllowed("video_stamp")
                ), h.operations
            )
            assertEquals(CameraSettingsMutationState.Verified("video_stamp", value), h.repository.state.value.mutation)
            assertEquals(value, h.repository.state.value.settings.first { it.key == "video_stamp" }.currentValue)
        }
        assertEquals(2, h.previewStops); h.close()
    }

    @Test
    fun ackAloneNeverSucceedsAndMismatchNeverRollsBack() = runBlocking {
        val h = Harness(); h.load(); h.applyValue = false
        h.gateOn = CameraSettingsOperation.ReadAll
        h.repository.apply("video_stamp", "date")
        assertTrue(h.repository.state.value.mutation is CameraSettingsMutationState.Applying)
        assertEquals("off", h.repository.state.value.settings.first().currentValue)
        h.gate.complete(Unit)
        assertEquals(
            CameraSettingsMutationState.Failed("video_stamp", CameraSettingsError.VERIFY_FAILED),
            h.repository.state.value.mutation
        )
        assertEquals(1, h.operations.count { it is CameraSettingsOperation.SetValue })
        h.close()
    }

    @Test
    fun rejectedValueKeepsActualValueAndDoesNotRetry() {
        val h = Harness(); h.load(); h.setRval = -13
        h.repository.apply("video_stamp", "date")
        assertEquals(
            CameraSettingsMutationState.Failed("video_stamp", CameraSettingsError.CAMERA_REJECTED),
            h.repository.state.value.mutation
        )
        assertEquals("off", h.repository.state.value.settings.first().currentValue)
        assertEquals(1, h.operations.size); assertTrue(h.log.any { it.contains("rval=-13") }); h.close()
    }

    @Test
    fun unsupportedDetailsAndMissingKeysRemainReadOnly() {
        val h = Harness(); h.detailRval = -25; h.repository.open()
        assertNull(h.repository.state.value.error)
        assertEquals(CameraSettingAccess.READ_ONLY, h.repository.state.value.settings.first().access)
        h.domain = "off"; h.detailRval = 0; h.repository.refresh()
        assertEquals(CameraSettingAccess.READ_ONLY, h.repository.state.value.settings.first().access)
        h.close()
    }

    @Test
    fun invalidLocalValueAndSensitiveKeyNeverGoOnWireOrIntoState() {
        val h = Harness(); h.load()
        h.repository.apply("video_stamp", "on")
        assertEquals(
            CameraSettingsMutationState.Failed("video_stamp", CameraSettingsError.UNSUPPORTED_VALUE),
            h.repository.state.value.mutation
        )
        h.repository.apply("wifi_password", "synthetic-secret")
        assertTrue(h.operations.isEmpty())
        assertFalse(h.repository.state.value.toString().contains("synthetic-secret"))
        assertFalse(h.log.toString().contains("synthetic-secret"))
        h.close()
    }

    @Test
    fun incompatibleOperationsAreBlockedAndPreviewFailureNeverSendsSet() {
        val h = Harness(); h.load()
        for (error in listOf(
            CameraSettingsError.DOWNLOAD_ACTIVE,
            CameraSettingsError.BUSY,
            CameraSettingsError.BLOCKED
        )) {
            h.unavailable = error
            h.repository.refresh(); h.repository.apply("video_stamp", "date")
            assertTrue(h.operations.isEmpty())
        }
        h.unavailable = null; h.recording = true
        h.repository.apply("video_stamp", "date")
        assertTrue(h.operations.isEmpty())
        h.recording = false; h.previewCanStop = false
        h.repository.apply("video_stamp", "date")
        assertEquals(
            CameraSettingsMutationState.Failed("video_stamp", CameraSettingsError.PREVIEW_STOP_FAILED),
            h.repository.state.value.mutation
        )
        assertTrue(h.operations.isEmpty()); h.close()
    }

    @Test
    fun concurrentRefreshAndMutationDoNotDuplicateRequests() = runBlocking {
        val h = Harness()
        h.gateOn = CameraSettingsOperation.ReadAll
        h.repository.open(); h.repository.refresh(); h.repository.apply("video_stamp", "date")
        assertEquals(1, h.operations.size)
        h.gate.complete(Unit); h.operations.clear()
        h.gate = CompletableDeferred(); h.gateOn = CameraSettingsOperation.SetValue("video_stamp", "date")
        h.repository.apply("video_stamp", "date")
        h.repository.apply("video_stamp", "off"); h.repository.refresh()
        assertEquals(1, h.operations.size)
        h.gate.complete(Unit)
        assertEquals(1, h.operations.count { it is CameraSettingsOperation.SetValue }); h.close()
    }

    @Test
    fun setAndVerifyFinishInBackgroundButDiscoveryStops() = runBlocking {
        val h = Harness(); h.load()
        h.gateOn = CameraSettingsOperation.SetValue("video_stamp", "date")
        h.repository.apply("video_stamp", "date")
        h.repository.setForeground(false); h.gate.complete(Unit)
        assertEquals(
            listOf(CameraSettingsOperation.SetValue("video_stamp", "date"), CameraSettingsOperation.ReadAll),
            h.operations
        )
        assertEquals(CameraSettingsMutationState.Verified("video_stamp", "date"), h.repository.state.value.mutation)
        assertFalse(h.repository.state.value.discovered)
        h.repository.setForeground(true)
        assertEquals(2, h.operations.size) // No automatic sequence on foreground.
        h.close()
    }

    @Test
    fun sessionChangeInvalidatesCacheAndAbandonsOutstandingDiscovery() = runBlocking {
        val h = Harness()
        h.gateOn = CameraSettingsOperation.ReadAllowed("video_stamp"); h.repository.open()
        h.identity = Any(); h.repository.sessionChanged(); h.gate.complete(Unit)
        assertTrue(h.repository.state.value.settings.isEmpty())
        h.operations.clear(); h.repository.open()
        assertEquals(CameraSettingsOperation.ReadAll, h.operations.first())
        h.close()
    }

    @Test
    fun leavingDuringReadCompletesCurrentRequestButDoesNotStartDetail() = runBlocking {
        val h = Harness(); h.gateOn = CameraSettingsOperation.ReadAll
        h.repository.open(); h.repository.setForeground(false); h.repository.setForeground(true); h.gate.complete(Unit)
        assertEquals(listOf(CameraSettingsOperation.ReadAll), h.operations)
        assertFalse(h.repository.state.value.loading); assertFalse(h.repository.state.value.discovered); h.close()
    }
}
