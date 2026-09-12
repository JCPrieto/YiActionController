package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

class CameraMediaDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private fun Socket.request(): JsonObject {
        val framer = JsonObjectFramer()
        while (true) {
            val byte = getInputStream().read()
            check(byte >= 0)
            framer.feed(byte.toChar().toString()).firstOrNull()?.let { return Json.parseToJsonElement(it).jsonObject }
        }
    }

    private fun Socket.send(raw: String) {
        getOutputStream().write(raw.toByteArray()); getOutputStream().flush()
    }

    private fun ack(size: Long, remaining: Long) = buildJsonObject {
        put("msg_id", 1285); put("rval", 0); put("size", size); put("rem_size", remaining)
    }.toString()

    private val complete = """{"msg_id":7,"type":"get_file_complete","param":[{"md5sum":"not-validated"}]}"""

    private class CountingFactory : SocketFactory() {
        var created = 0
        override fun createSocket(): Socket {
            created++; return Socket()
        }

        override fun createSocket(h: String, p: Int) = error("Unbound overload")
        override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) = error("Unbound overload")
        override fun createSocket(h: java.net.InetAddress, p: Int) = error("Unbound overload")
        override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int) =
            error("Unbound overload")
    }

    private inner class Harness(scope: CoroutineScope, val token: Int = 37, confirmation: Long = 150) : AutoCloseable {
        val controlServer = ServerSocket(0)
        val dataServer = ServerSocket(0).apply { soTimeout = 4000 }
        val factory = CountingFactory()
        val client = CameraClient("127.0.0.1", controlServer.localPort, socketFactory = factory)
        lateinit var peer: Socket
        val folder = temporary.newFolder()
        var publications = 0
        var published: ByteArray? = null
        var publishFails = false
        val downloader = CameraMediaDownloader(
            scope, client, { factory }, { folder },
            MediaPublisher { file, entry ->
                assertEquals(entry.sizeBytes, file.length())
                if (publishFails) error("Simulated publishing failure")
                publications++
                published = file.readBytes()
                "content://test/" + entry.name
            }, { null }, { true }, {}, host = "127.0.0.1", port = dataServer.localPort,
            readTimeoutMillis = 500, confirmationMillis = confirmation
        )

        suspend fun initialize() {
            withContext(Dispatchers.IO) {
                client.connect()
                peer = controlServer.accept().apply { soTimeout = 4000 }
                assertEquals(0, peer.request()["token"]!!.jsonPrimitive.int)
                peer.send(buildJsonObject { put("msg_id", 257); put("rval", 0); put("param", token) }.toString())
                peer.request(); peer.send("""{"msg_id":13,"rval":0,"type":"battery","param":"90"}""")
                peer.request(); peer.send("""{"msg_id":3,"rval":0,"param":[{"app_status":"idle"}]}""")
            }
            withTimeout(4000) { client.state.first { it.canSendCommand } }
        }

        fun entry(name: String = "file.mp4", size: Long = 8192, type: CameraMediaType = CameraMediaType.VIDEO) =
            CameraMediaEntry(name, CAMERA_DCIM_ROOT + "/101MEDIA/" + name, type, size, null)

        fun beginRequest(offset: Long, fetch: Long): Socket {
            val cd = peer.request()
            assertEquals(1283, cd["msg_id"]!!.jsonPrimitive.int)
            assertEquals(CAMERA_DCIM_ROOT + "/101MEDIA", cd["param"]!!.jsonPrimitive.content)
            peer.send("""{"msg_id":1283,"rval":0,"pwd":"/tmp/fuse_d/DCIM/101MEDIA"}""")
            // Must connect the data socket BEFORE sending 1285.
            val data = dataServer.accept().apply { soTimeout = 4000 }
            val command = peer.request()
            assertEquals(1285, command["msg_id"]!!.jsonPrimitive.int)
            assertEquals(token, command["token"]!!.jsonPrimitive.int)
            assertFalse(command["offset"]!!.jsonPrimitive.isString)
            assertFalse(command["fetch_size"]!!.jsonPrimitive.isString)
            assertEquals(offset, command["offset"]!!.jsonPrimitive.long)
            assertEquals(fetch, command["fetch_size"]!!.jsonPrimitive.long)
            return data
        }

        suspend fun state(expected: CameraDownloadState) =
            withTimeout(5000) { downloader.status.first { it.state == expected } }

        override fun close() {
            downloader.shutdown(); client.close(); if (::peer.isInitialized) peer.close(); controlServer.close(); dataServer.close()
        }
    }

    @Test
    fun exactDownloadFinishesWithoutServerEofAndUsesBoundFactory() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val original = ByteArray(8192) { (it % 251).toByte() }
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(complete) // Event may precede the ACK.
                    h.peer.send(ack(8192, 8192))
                    original.asList().chunked(233).forEach { data.getOutputStream().write(it.toByteArray()) }
                    data.getOutputStream().flush()
                    assertEquals(-1, data.getInputStream().read()) // Client closes at N; server did not send EOF.
                }
            }
            assertTrue(h.downloader.start(h.entry()))
            assertFalse(h.downloader.start(h.entry("other.mp4")))
            val result = h.state(CameraDownloadState.COMPLETED)
            assertEquals(8192, result.downloadedBytes)
            assertTrue(result.cameraConfirmed)
            actor.await()
            assertArrayEquals(original, h.published)
            assertEquals(1, h.publications)
            assertEquals(2, h.factory.created) // Control + data from precisely the injected factory.
            assertTrue(h.folder.listFiles()!!.isEmpty())
            assertTrue(h.client.state.value.canSendCommand)
        }
    }

    @Test
    fun partialAckThenResumeAppendsAndAccumulatesProgress() = runBlocking {
        Harness(this, token = 53).use { h ->
            h.initialize()
            val original = ByteArray(8192) { (it % 239).toByte() }
            val first = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 4096))
                    data.getOutputStream().write(original, 0, 4096)
                    h.peer.send(complete)
                    assertEquals(-1, data.getInputStream().read())
                }
            }
            h.downloader.start(h.entry())
            val paused = h.state(CameraDownloadState.PAUSED)
            assertEquals(4096, paused.downloadedBytes)
            assertTrue(paused.canResume)
            assertEquals(0, h.publications)
            first.await()
            val second = async(Dispatchers.IO) {
                h.beginRequest(4096, 4096).use { data ->
                    h.peer.send(ack(8192, 4096))
                    data.getOutputStream().write(original, 4096, 4096)
                    h.peer.send(complete)
                }
            }
            assertTrue(h.downloader.resume())
            h.state(CameraDownloadState.COMPLETED)
            second.await()
            assertArrayEquals(original, h.published)
            assertEquals(3, h.factory.created)
        }
    }

    @Test
    fun cancelClosesDataConservesPartialAndLateFailDoesNotPoisonNextRequest() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192))
                    data.getOutputStream().write(ByteArray(4096))
                    assertEquals(-1, data.getInputStream().read())
                    h.peer.send("""{"msg_id":7,"type":"get_file_fail","param":4096}""")
                }
            }
            h.downloader.start(h.entry())
            withTimeout(4000) { h.downloader.status.first { it.downloadedBytes >= 4096 } }
            h.downloader.cancel()
            val cancelled = h.state(CameraDownloadState.CANCELLED)
            assertEquals(4096, cancelled.downloadedBytes)
            assertTrue(cancelled.canResume)
            assertEquals(4096, h.folder.listFiles()!!.single().length())
            actor.await()
            assertTrue(h.client.awaitTransferDrain())
            assertEquals(ConnectionStatus.CONNECTED, h.client.state.value.connection)
            assertEquals(0, h.publications)
            h.downloader.discard()
            assertTrue(h.folder.listFiles()!!.isEmpty())
            assertEquals(CameraDownloadState.IDLE, h.downloader.status.value.state)
        }
    }

    @Test
    fun changedRemoteSizePreventsAppend() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 4096)); data.getOutputStream().write(ByteArray(4096)); h.peer.send(complete)
                }
            }
            h.downloader.start(h.entry()); h.state(CameraDownloadState.PAUSED); actor.await()
            val mismatch = async(Dispatchers.IO) {
                h.beginRequest(4096, 4096).use { h.peer.send(ack(9000, 4904)); h.peer.send(complete) }
            }
            h.downloader.resume()
            assertEquals(CameraDownloadError.SIZE_MISMATCH, h.state(CameraDownloadState.ERROR).error)
            mismatch.await()
            assertEquals(4096, h.folder.listFiles()!!.single().length())
            assertFalse(h.downloader.status.value.canResume)
            assertEquals(0, h.publications)
        }
    }

    @Test
    fun thmStaysPrivateAndNeverPublishes() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val entry = h.entry("sample.THM", 100, CameraMediaType.THUMBNAIL)
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 100).use { data ->
                    h.peer.send(ack(100, 100)); data.getOutputStream().write(ByteArray(100)); h.peer.send(complete)
                }
            }
            h.downloader.start(entry)
            h.state(CameraDownloadState.COMPLETED); actor.await()
            val cached = File(h.downloader.thumbnails.value.getValue(thumbnailKey(entry)))
            assertEquals(h.folder.canonicalPath, cached.parentFile!!.canonicalPath)
            assertEquals(100, cached.length())
            assertEquals(0, h.publications)
        }
    }

    @Test
    fun publishingFailureKeepsCompletePrivateFileAndRetryDoesNotDownloadAgain() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            h.publishFails = true
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192)); data.getOutputStream().write(ByteArray(8192)); h.peer.send(complete)
                }
            }
            h.downloader.start(h.entry("file.jpg", type = CameraMediaType.PHOTO))
            assertEquals(CameraDownloadError.MEDIASTORE_ERROR, h.state(CameraDownloadState.ERROR).error)
            actor.await()
            assertEquals(8192, h.folder.listFiles()!!.single().length())
            h.publishFails = false
            assertTrue(h.downloader.resume())
            h.state(CameraDownloadState.COMPLETED)
            assertEquals(2, h.factory.created)
            assertEquals(1, h.publications)
        }
    }

    @Test
    fun missingConfirmationPublishesBytesButKeepsEventBarrier() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192)); data.getOutputStream().write(ByteArray(8192))
                    assertEquals(-1, data.getInputStream().read())
                }
            }
            h.downloader.start(h.entry())
            assertFalse(h.state(CameraDownloadState.COMPLETED).cameraConfirmed)
            actor.await()
            assertEquals(1, h.publications)
            assertThrows(DownloadException::class.java) { h.client.acquireTransfer() }
            h.peer.send("""{"msg_id":7,"type":"get_file_fail"}""")
            assertEquals(CameraDownloadError.GET_FILE_FAIL, h.state(CameraDownloadState.ERROR).error)
            assertEquals(1, h.publications) // Exact bytes already published are not silently deleted.
            Unit
        }
    }

    @Test
    fun readTimeoutAndEarlyEofNeverPublish() = runBlocking {
        for (timeout in listOf(false, true)) Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192))
                    data.getOutputStream().write(ByteArray(100))
                    if (timeout) assertEquals(-1, data.getInputStream().read())
                }
            }
            h.downloader.start(h.entry())
            val state = h.state(CameraDownloadState.ERROR)
            actor.await()
            assertEquals(if (timeout) CameraDownloadError.READ_TIMEOUT else CameraDownloadError.EOF_EARLY, state.error)
            assertEquals(100, state.downloadedBytes)
            assertEquals(0, h.publications)
        }
    }

    @Test
    fun blockedPausesAndConservesOffsetWithoutAutomaticResume() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192)); data.getOutputStream().write(ByteArray(100))
                    assertEquals(-1, data.getInputStream().read())
                    h.peer.send("""{"msg_id":7,"type":"get_file_fail"}""")
                }
            }
            h.downloader.start(h.entry())
            withTimeout(4000) { h.downloader.status.first { it.downloadedBytes > 0 } }
            h.downloader.cancel(CameraDownloadError.NETWORK_BLOCKED)
            val state = h.state(CameraDownloadState.PAUSED)
            actor.await()
            assertEquals(100, state.downloadedBytes)
            assertEquals(CameraDownloadError.NETWORK_BLOCKED, state.error)
            assertEquals(2, h.factory.created)
            assertEquals(ConnectionStatus.CONNECTED, h.client.state.value.connection)
        }
    }

    @Test
    fun controlLeaseSurvivesAckAndQueuedCancellationUntilTerminalEvent() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val lease = h.client.acquireTransfer()
            val request = async {
                runCatching {
                    h.client.requestMedia(CameraMediaOperation.GetFile(CameraFileRequest("file.mp4", 0, 8192)), lease)
                }
            }
            yield() // Queues command, cancellation can happen before its socket write.
            request.cancelAndJoin()
            h.client.releaseTransfer(lease)
            assertThrows(DownloadException::class.java) { h.client.acquireTransfer() }
            withContext(Dispatchers.IO) {
                assertEquals(1285, h.peer.request()["msg_id"]!!.jsonPrimitive.int)
                h.peer.send(ack(8192, 8192))
            }
            withTimeout(4000) { h.client.state.first { it.canSendCommand } }
            // ACK alone does not release the terminal-event barrier.
            assertThrows(DownloadException::class.java) { h.client.acquireTransfer() }
            h.peer.send("""{"msg_id":7,"type":"get_file_fail"}""")
            assertTrue(h.client.awaitTransferDrain())
            val next = h.client.acquireTransfer()
            val blocked = runCatching { h.client.requestMedia(CameraMediaOperation.Pwd) }.exceptionOrNull()
            assertEquals(CameraMediaError.BUSY, (blocked as CameraMediaException).kind)
            assertFalse(h.client.startPreview().accepted)
            h.client.takePhoto()
            assertTrue(h.client.state.value.pending.isEmpty())
            h.client.releaseTransfer(next)
        }
    }

    @Test
    fun cameraFailClosesDataAndNeverPublishes() = runBlocking {
        Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192))
                    data.getOutputStream().write(ByteArray(100))
                    h.peer.send("""{"msg_id":7,"type":"get_file_fail","param":100}""")
                    assertEquals(-1, data.getInputStream().read())
                }
            }
            h.downloader.start(h.entry())
            assertEquals(CameraDownloadError.GET_FILE_FAIL, h.state(CameraDownloadState.ERROR).error)
            actor.await()
            assertEquals(0, h.publications)
            assertEquals(ConnectionStatus.CONNECTED, h.client.state.value.connection)
        }
    }

    @Test
    fun networkLossAndExplicitShutdownCloseDataWithoutPublishing() = runBlocking {
        for (shutdown in listOf(false, true)) Harness(this).use { h ->
            h.initialize()
            val actor = async(Dispatchers.IO) {
                h.beginRequest(0, 8192).use { data ->
                    h.peer.send(ack(8192, 8192)); data.getOutputStream().write(ByteArray(100))
                    assertEquals(-1, data.getInputStream().read())
                }
            }
            h.downloader.start(h.entry())
            withTimeout(4000) { h.downloader.status.first { it.downloadedBytes > 0 } }
            if (shutdown) {
                h.downloader.shutdown()
                h.state(CameraDownloadState.IDLE)
                assertTrue(h.folder.listFiles()!!.isEmpty())
            } else {
                h.downloader.cancel(CameraDownloadError.NETWORK_LOST)
                assertEquals(CameraDownloadError.NETWORK_LOST, h.state(CameraDownloadState.ERROR).error)
            }
            actor.await()
            assertEquals(0, h.publications)
        }
    }
}
