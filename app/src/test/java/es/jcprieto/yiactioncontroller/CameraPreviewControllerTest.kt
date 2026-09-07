package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import javax.net.SocketFactory

class CameraPreviewControllerTest {
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
                    PreviewError.NETWORK_LOST -> controller.networkLost()
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
