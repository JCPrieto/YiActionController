package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CameraMediaRepositoryTest {
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val operations = mutableListOf<CameraMediaOperation>()
        var identity: Any? = Any()
        var error: CameraMediaError? = null
        var cwd = CAMERA_DCIM_ROOT
        var gateOperation: CameraMediaOperation? = null
        val gate = CompletableDeferred<Unit>()
        val browser = CameraMediaRepository(scope, { operation ->
            operations += operation
            if (operation == gateOperation) gate.await()
            when (operation) {
                CameraMediaOperation.Total -> CameraMessage(messageId = 5, rval = 0, param = JsonPrimitive(31154688))
                CameraMediaOperation.Free -> CameraMessage(messageId = 5, rval = 0, param = JsonPrimitive(29852096))
                CameraMediaOperation.Pwd -> CameraMessage(messageId = 1283, rval = 0, pwd = JsonPrimitive(cwd))
                is CameraMediaOperation.ChangeDirectory -> {
                    cwd = operation.path
                    CameraMessage(messageId = 1283, rval = 0, pwd = JsonPrimitive(cwd))
                }

                CameraMediaOperation.ListDirectory -> CameraMessage(
                    messageId = 1282, rval = 0,
                    listing = Json.parseToJsonElement("""[{"101MEDIA/":"0 bytes|date"},{"a.jpg":"10 bytes|date"}]""")
                )
            }
        }, { error }, { identity }, {})

        init {
            browser.setForeground(true)
        }
    }

    @Test
    fun initialSequenceRefreshAndNavigationPreserveDirectory() {
        val h = Harness()
        h.browser.open()
        assertEquals(
            listOf(
                CameraMediaOperation.Total, CameraMediaOperation.Free,
                CameraMediaOperation.ChangeDirectory(CAMERA_DCIM_ROOT), CameraMediaOperation.ListDirectory
            ), h.operations
        )
        assertTrue(h.browser.state.value.hasListing)
        h.browser.openDirectory("101MEDIA/")
        assertEquals("$CAMERA_DCIM_ROOT/101MEDIA", h.browser.state.value.currentPath)
        h.operations.clear()
        h.browser.open() // Re-observation/rotation is not another navigation.
        assertTrue(h.operations.isEmpty())
        h.browser.refresh()
        assertEquals(CameraMediaOperation.Pwd, h.operations[2])
        assertEquals("$CAMERA_DCIM_ROOT/101MEDIA", h.browser.state.value.currentPath)
        h.browser.parent()
        assertEquals(CAMERA_DCIM_ROOT, h.browser.state.value.currentPath)
        h.browser.parent()
        assertEquals(CAMERA_SD_ROOT, h.browser.state.value.currentPath)
        h.operations.clear()
        h.browser.parent()
        assertEquals(CameraMediaError.OUTSIDE_ROOT, h.browser.state.value.errorType)
        assertTrue(h.operations.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun backgroundStopsFollowupsEvenAfterQuickReturnAndDuplicateTap() {
        val h = Harness()
        h.gateOperation = CameraMediaOperation.Total
        h.browser.open()
        h.browser.refresh()
        assertEquals(1, h.operations.size)
        h.browser.setForeground(false)
        h.browser.setForeground(true)
        h.gate.complete(Unit)
        assertEquals(1, h.operations.size)
        assertFalse(h.browser.state.value.loading)
        h.scope.cancel()
    }

    @Test
    fun listFinishesInBackgroundButNeverStartsMoreWork() {
        val h = Harness()
        h.gateOperation = CameraMediaOperation.ListDirectory
        h.browser.open()
        h.browser.setForeground(false)
        h.gate.complete(Unit)
        assertTrue(h.browser.state.value.hasListing)
        assertEquals(2, h.browser.state.value.entries.size)
        h.browser.refresh()
        assertEquals(4, h.operations.size)
        h.scope.cancel()
    }

    @Test
    fun staleSessionResultIsRejectedAndResetIsSafe() {
        val h = Harness()
        h.gateOperation = CameraMediaOperation.ListDirectory
        h.browser.open()
        h.identity = Any()
        h.gate.complete(Unit)
        assertEquals(CameraMediaError.DISCONNECTED, h.browser.state.value.errorType)
        assertNull(h.browser.state.value.currentPath)
        h.browser.reset()
        assertEquals(CameraMediaBrowserState(), h.browser.state.value)
        h.scope.cancel()
    }

    @Test
    fun unavailableCameraNeverSendsCommands() {
        for (error in listOf(
            CameraMediaError.BLOCKED, CameraMediaError.DISCONNECTED,
            CameraMediaError.SD_MISSING, CameraMediaError.BUSY
        )) {
            val h = Harness()
            h.error = error
            h.browser.open()
            assertEquals(error, h.browser.state.value.errorType)
            assertTrue(h.operations.isEmpty())
            h.scope.cancel()
        }
    }

    @Test
    fun protocolErrorsAreRecoverableAndTimeoutIsNotOverwrittenByDisconnect() {
        for ((response, expected) in listOf(
            CameraMessage(messageId = 1283, rval = -21) to CameraMediaError.REJECTED,
            CameraMessage(messageId = 1283, rval = 0, pwd = JsonPrimitive("/")) to CameraMediaError.OUTSIDE_ROOT,
            CameraMessage(
                messageId = 1283,
                rval = 0,
                pwd = JsonPrimitive("$CAMERA_SD_ROOT/other")
            ) to CameraMediaError.INVALID_PATH,
        )) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val identity = Any()
            val browser = CameraMediaRepository(scope, { response }, { null }, { identity }, {})
            browser.setForeground(true)
            browser.root()
            assertEquals(expected, browser.state.value.errorType)
            assertFalse(browser.state.value.loading)
            scope.cancel()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = CameraMediaRepository(
            scope, { throw CameraMediaException(CameraMediaError.TIMEOUT) },
            { null }, { this }, {})
        browser.setForeground(true)
        browser.root()
        browser.onDisconnected()
        assertEquals(CameraMediaError.TIMEOUT, browser.state.value.errorType)
        scope.cancel()
    }
}
