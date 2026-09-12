package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import javax.net.SocketFactory

class CameraPreviewControllerTest {
    @Test
    fun settingsNeverSendsSetAfterStopAckUntilVfStopAndDoesNotRestartPreview() = runBlocking {
        for (confirmedSuccessfully in listOf(true, false)) {
            val engine = FakePlayback()
            val ack = CompletableDeferred<Unit>()
            val vfStop = CompletableDeferred<Boolean>()
            var confirmedStops = 0
            var ordinaryStops = 0
            val controller = CameraPreviewController(
                this, engine, { true },
                { CameraControlResult(true) },
                { ordinaryStops++; CameraControlResult(true) },
                stopForSettingsControl = {
                    confirmedStops++
                    ack.await()
                    if (vfStop.await()) CameraControlResult(true) else CameraControlResult(false, "Timeout vf_stop")
                })
            var currentValue = "on"
            val commands = mutableListOf<CameraSettingsOperation>()
            val identity = Any()
            val repository = CameraSettingsRepository(this, { op ->
                commands += op
                when (op) {
                    CameraSettingsOperation.ReadAll -> CameraMessage(
                        3, 0,
                        param = JsonArray(listOf(buildJsonObject { put("auto_low_light", currentValue) }))
                    )

                    is CameraSettingsOperation.ReadAllowed -> cameraJson.decodeFromString<CameraMessage>(
                        """{"msg_id":3,"rval":0,"param":[{"auto_low_light":"settable:on#off"}]}"""
                    )

                    is CameraSettingsOperation.SetValue -> {
                        currentValue = op.value
                        CameraMessage(2, 0)
                    }
                }
            }, { null }, { identity }, controller::stopForSettings, {})
            try {
                controller.start(transport); yield()
                repository.setForeground(true); repository.open()
                withTimeout(1000) { repository.state.first { it.discovered } }
                commands.clear()
                repository.apply("auto_low_light", "off"); yield(); yield()
                ack.complete(Unit); yield()
                assertEquals(1, confirmedStops)
                assertEquals(0, ordinaryStops)
                assertEquals(PreviewState.STOPPING, controller.status.value.state)
                assertTrue(controller.status.value.controlPending)
                assertTrue(commands.isEmpty()) // Exact regression: ACK alone cannot send msg_id=2.
                vfStop.complete(confirmedSuccessfully)
                withTimeout(1000) { repository.state.first { !it.busy } }
                if (confirmedSuccessfully) {
                    assertEquals(1, commands.count { it is CameraSettingsOperation.SetValue })
                    assertEquals(
                        CameraSettingsMutationState.Verified("auto_low_light", "off"),
                        repository.state.value.mutation
                    )
                    assertEquals(PreviewState.IDLE, controller.status.value.state)
                } else {
                    assertTrue(commands.isEmpty())
                    assertEquals(
                        CameraSettingsMutationState.Failed("auto_low_light", CameraSettingsError.PREVIEW_STOP_FAILED),
                        repository.state.value.mutation
                    )
                }
                assertEquals(1, engine.starts)
                assertFalse(engine.allocated)
            } finally {
                repository.reset(); controller.release()
            }
        }
    }

    @Test
    fun recoveryUsesLiveOwnerStateWhenUiStillShowsAwaitingStop() = runBlocking {
        val live = kotlinx.coroutines.flow.MutableStateFlow(
            CameraState(connection = ConnectionStatus.CONNECTED, authenticated = true, recording = RecordingState.IDLE)
        )
        var uiSnapshot = live.value
        val engine = FakePlayback()
        var starts = 0
        val confirmed = CompletableDeferred<Unit>()
        val controller = CameraPreviewController(
            this, engine, { true }, {
            starts++
            if (starts == 1) CameraControlResult(false, "rval=-21", -21) else CameraControlResult(true)
        }, { CameraControlResult(true) },
            { previewRecoveryAllowed(live.value, networkReady = true, controlAvailable = true) },
            {
                live.value = live.value.copy(awaitingPreviewStop = true)
                uiSnapshot = live.value
                confirmed.await()
                // CameraClient's finally clears the reservation before returning; UI collection can lag.
                live.value = live.value.copy(awaitingPreviewStop = false)
                CameraControlResult(true)
            })
        try {
            controller.start(transport); yield()
            controller.restart(transport); yield()
            assertEquals(1, starts)
            confirmed.complete(Unit); yield()
            assertFalse(uiSnapshot.canSendCommand)
            assertTrue(live.value.canSendCommand)
            assertEquals(2, starts)
            assertEquals(1, engine.starts)
            assertNull(controller.status.value.errorType)
        } finally {
            controller.release()
        }
    }

    @Test
    fun liveRecoveryGuardStillRejectsActualBusyRecordingAndUnavailableNetwork() {
        val ready =
            CameraState(connection = ConnectionStatus.CONNECTED, authenticated = true, recording = RecordingState.IDLE)
        assertTrue(previewRecoveryAllowed(ready, true, true))
        for (recording in listOf(
            RecordingState.UNKNOWN,
            RecordingState.STARTING,
            RecordingState.RECORDING,
            RecordingState.STOPPING
        ))
            assertFalse(previewRecoveryAllowed(ready.copy(recording = recording), true, true))
        assertFalse(previewRecoveryAllowed(ready.copy(awaitingPreviewStop = true), true, true))
        assertFalse(previewRecoveryAllowed(ready.copy(pending = setOf(CameraCommand.GET_CONFIG)), true, true))
        assertFalse(previewRecoveryAllowed(ready, false, true))
        assertFalse(previewRecoveryAllowed(ready, true, false))
    }

    @Test
    fun explicitRecoveryWaitsForConfirmedStopAndNewStartAck() = runBlocking {
        val engine = FakePlayback()
        val confirmed = CompletableDeferred<CameraControlResult>()
        val newAck = CompletableDeferred<CameraControlResult>()
        var starts = 0
        var resets = 0
        var idle = false
        val controller = CameraPreviewController(this, engine, { true }, {
            starts++
            if (starts == 1) CameraControlResult(false, "rval=-21", CameraErrorCode.PREVIEW_RESTART_CANDIDATE)
            else newAck.await()
        }, { CameraControlResult(true) }, { idle }, { resets++; confirmed.await() })
        try {
            controller.start(transport)
            yield()
            assertTrue(controller.status.value.recoveryAvailable)
            controller.restart(transport) // Recording/unknown/occupied must not reset the camera.
            yield()
            assertEquals(0, resets)
            idle = true
            controller.restart(transport)
            controller.restart(transport) // Double tap cannot queue another recovery.
            yield()
            assertEquals(1, resets)
            assertEquals(1, starts)
            assertEquals(0, engine.starts)
            confirmed.complete(CameraControlResult(true))
            yield()
            assertEquals(2, starts)
            assertEquals(0, engine.starts)
            newAck.complete(CameraControlResult(true))
            yield()
            assertEquals(1, engine.starts)
            assertFalse(controller.status.value.recoveryAvailable)
        } finally {
            controller.release()
        }
    }

    @Test
    fun recoveryFailureOrBackgroundNeverStartsPlayerOrLoops() = runBlocking {
        for (background in listOf(false, true)) {
            val engine = FakePlayback()
            val confirm = CompletableDeferred<CameraControlResult>()
            var starts = 0
            val controller = CameraPreviewController(this, engine, { true }, {
                starts++; CameraControlResult(false, "rval=-21", -21)
            }, { CameraControlResult(true) }, { true }, { confirm.await() })
            try {
                controller.start(transport)
                yield()
                controller.restart(transport)
                yield()
                if (background) controller.stop()
                confirm.complete(CameraControlResult(false, "Timeout vf_stop"))
                yield()
                yield()
                assertEquals(1, starts)
                assertEquals(0, engine.starts)
                assertFalse(controller.status.value.recoveryAvailable)
                if (!background) assertEquals(PreviewError.STOP_CONTROL, controller.status.value.errorType)
            } finally {
                controller.release()
            }
        }
    }

    private val transport = PreviewTransport(SocketFactory.getDefault()) { }
    private class FakePlayback : PreviewPlayback {
        val signals = PreviewPlaybackSignals()
        override val status = signals.status
        var allocated = false
        var starts = 0
        var releases = 0
        var receivedTransport: PreviewTransport? = null
        override fun start(transport: PreviewTransport) {
            receivedTransport = transport
            allocated = true; starts++; signals.starting()
        }

        override fun release() {
            if (allocated) releases++; allocated = false; signals.idle()
        }
    }

    @Test
    fun waitsForAckAndActualPlaybackAndReleasesBeforeStopAck() = runBlocking {
        val engine = FakePlayback()
        val start = CompletableDeferred<CameraControlResult>()
        val stop = CompletableDeferred<CameraControlResult>()
        val controller = CameraPreviewController(this, engine, { true }, { start.await() }, {
            assertFalse(engine.allocated)
            stop.await()
        })
        try {
            controller.start(transport)
            yield()
            assertEquals(0, engine.starts)
            start.complete(CameraControlResult(true))
            yield()
            assertEquals(1, engine.starts)
            assertSame(transport, engine.receivedTransport)
            engine.signals.buffering()
            yield()
            assertEquals(PreviewState.BUFFERING, controller.status.value.state)
            engine.signals.ready(false)
            yield()
            assertNotEquals(PreviewState.PLAYING, controller.status.value.state)
            engine.signals.ready(true)
            yield()
            assertEquals(PreviewState.PLAYING, controller.status.value.state)
            controller.stop()
            assertFalse(engine.allocated)
            yield()
            stop.complete(CameraControlResult(false, "rval=-42"))
            yield()
            assertEquals(PreviewError.STOP_CONTROL, controller.status.value.errorType)
            controller.release()
            controller.release()
            assertEquals(1, engine.releases)
        } finally {
            controller.release()
        }
    }

    @Test
    fun rejectedStartAndMissingNetworkNeverCreatePlayer() = runBlocking {
        val engine = FakePlayback()
        val controller = CameraPreviewController(
            this, engine, { true },
            { CameraControlResult(false, "rval=-42") }, { CameraControlResult(true) })
        try {
            controller.start(null)
            assertEquals(PreviewError.NETWORK_NOT_FOUND, controller.status.value.errorType)
            controller.start(transport)
            yield()
            assertEquals(PreviewError.START_CONTROL, controller.status.value.errorType)
            assertEquals(0, engine.starts)
        } finally {
            controller.release()
        }
    }

    @Test
    fun disconnectNetworkLossAndFatalErrorsReleasePlayer() = runBlocking {
        for (cause in listOf(
            PreviewError.CAMERA_DISCONNECTED,
            PreviewError.NETWORK_LOST,
            PreviewError.RTSP_TIMEOUT,
            PreviewError.MEDIA3
        )) {
            val engine = FakePlayback()
            val controller = CameraPreviewController(
                this, engine, { true },
                { CameraControlResult(true) }, { CameraControlResult(true) })
            try {
                controller.start(transport)
                yield()
                assertTrue(engine.allocated)
                when (cause) {
                    PreviewError.CAMERA_DISCONNECTED -> controller.cameraDisconnected()
                    PreviewError.NETWORK_LOST -> {
                        controller.networkLost()
                        controller.cameraDisconnected() // Subsequent TCP EOF preserves the network cause.
                    }
                    else -> engine.signals.error(cause, "simulated")
                }
                withTimeout(2_000) { controller.status.first { it.state == PreviewState.ERROR && !it.controlPending } }
                assertFalse(engine.allocated)
                assertEquals(cause, controller.status.value.errorType)
                controller.start(transport) // Explicit retry, no auto restart.
                yield()
                assertEquals(2, engine.starts)
            } finally {
                controller.release()
            }
        }
    }

    @Test
    fun backgroundDuringStartDoesNotCreatePlayerAndStopsAfterLateAck() = runBlocking {
        val engine = FakePlayback()
        val ack = CompletableDeferred<CameraControlResult>()
        var stops = 0
        val controller = CameraPreviewController(this, engine, { true }, { ack.await() }, {
            stops++; CameraControlResult(true)
        })
        try {
            controller.start(transport)
            yield()
            controller.stop()
            ack.complete(CameraControlResult(true))
            withTimeout(2_000) { controller.status.first { it.state == PreviewState.IDLE } }
            assertEquals(0, engine.starts)
            assertEquals(1, stops)
        } finally {
            controller.release()
        }
    }
}
