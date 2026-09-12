package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.SocketTimeoutException

class CameraDownloadProtocolTest {
    private fun message(raw: String) = cameraJson.decodeFromString<CameraMessage>(raw)
    @Test
    fun longAckAndResumeValidation() {
        val size = 5_000_000_000L
        val request = CameraFileRequest("movie.mp4", 4096, size - 4096)
        val ack = validateFileAck(
            message("""{"msg_id":1285,"rval":0,"size":5000000000,"rem_size":4999995904}"""),
            request,
            size
        )
        assertEquals(size, ack.size)
        assertEquals(size - 4096, ack.remaining)
        for (raw in listOf(
            """{"msg_id":1285,"rval":-4}""",
            """{"msg_id":1285,"rval":0,"size":100}""",
            """{"msg_id":1285,"rval":0,"rem_size":100}""",
            """{"msg_id":1285,"rval":0,"size":100,"rem_size":-1}""",
            """{"msg_id":1285,"rval":0,"size":100,"rem_size":101}""",
            """{"msg_id":1285,"rval":0,"size":"bad","rem_size":10}""",
        )) assertThrows(DownloadException::class.java) {
            validateFileAck(
                message(raw),
                CameraFileRequest("a.jpg", 0, 100),
                100
            )
        }
        assertThrows(DownloadException::class.java) {
            validateFileAck(
                message("""{"msg_id":1285,"rval":0,"size":100,"rem_size":0}"""),
                CameraFileRequest("a.jpg", 101, 0),
                100
            )
        }
        assertEquals(
            10L, validateFileAck(
                message("""{"msg_id":1285,"rval":0,"size":100,"rem_size":10}"""),
                CameraFileRequest("a.jpg", 0, 100), 100
            ).remaining
        )
    }

    @Test
    fun transferEventsTolerateMissingMetadataAndNeverValidateMd5() {
        val complete =
            parseFileEvent(message("""{"msg_id":7,"type":"get_file_complete","param":[{"bytes sent":72582},{"md5sum":"not-a-payload-checksum"}]}"""))
                    as CameraFileTransferEvent.Complete
        assertEquals(72582L, complete.bytesSent)
        assertTrue(complete.md5Present)
        assertEquals(
            CameraFileTransferEvent.Complete(null, false),
            parseFileEvent(message("""{"msg_id":7,"type":"get_file_complete","param":"unknown"}"""))
        )
        assertEquals(
            CameraFileTransferEvent.Fail(231424),
            parseFileEvent(message("""{"msg_id":7,"type":"get_file_fail","param":231424}"""))
        )
        assertNull(parseFileEvent(message("""{"msg_id":1285,"rval":0}""")))
    }

    @Test
    fun exactCopyHandlesMillionsOfBytesInPartialReadsWithoutEof() = runBlocking {
        val size = 3310683L
        var generated = 0L
        var writes = 0L
        var largestBuffer = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(generated < size) { "Must never read beyond expected" }
                largestBuffer = maxOf(largestBuffer, buffer.size)
                val count = minOf(length, if (generated % 2 == 0L) 1024 else 4344, (size - generated).toInt())
                repeat(count) { buffer[offset + it] = ((generated + it) % 251).toByte() }
                generated += count
                return count
            }
        }
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Bulk writes required")
            override fun write(buffer: ByteArray, offset: Int, count: Int) {
                repeat(count) { assertEquals(((writes + it) % 251).toByte(), buffer[offset + it]) }
                writes += count
            }
        }
        copyCameraBytes(input, output, size) {}
        assertEquals(size, writes)
        assertEquals(65536, largestBuffer)
    }

    @Test
    fun earlyEofTimeoutAndStorageFailureAreDistinct() = runBlocking {
        suspend fun failure(input: InputStream, output: OutputStream = ByteArrayOutputStream()): CameraDownloadError =
            (runCatching { copyCameraBytes(input, output, 100) {} }.exceptionOrNull() as DownloadException).reason
        assertEquals(CameraDownloadError.EOF_EARLY, failure(ByteArrayInputStream(ByteArray(10))))
        assertEquals(CameraDownloadError.READ_TIMEOUT, failure(object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException()
        }))
        assertEquals(
            CameraDownloadError.STORAGE_ERROR,
            failure(ByteArrayInputStream(ByteArray(100)), object : OutputStream() {
                override fun write(value: Int) = throw IOException()
            })
        )
    }

    @Test
    fun downloadPathsAndMimeRemainRestricted() {
        assertEquals("image/jpeg", downloadMime(CameraMediaType.THUMBNAIL))
        assertEquals("image/jpeg", downloadMime(CameraMediaType.PHOTO))
        assertEquals("video/mp4", downloadMime(CameraMediaType.VIDEO))
        assertThrows(DownloadException::class.java) {
            validateDownloadEntry(CameraMediaEntry("a.jpg", "/etc/a.jpg", CameraMediaType.PHOTO, 100, null))
        }
        assertThrows(DownloadException::class.java) {
            validateDownloadEntry(
                CameraMediaEntry(
                    "../a.jpg",
                    CAMERA_SD_ROOT + "/a.jpg",
                    CameraMediaType.PHOTO,
                    100,
                    null
                )
            )
        }
    }
}
