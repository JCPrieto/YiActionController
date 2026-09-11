package es.jcprieto.yiactioncontroller

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CameraMediaTest {
    @Test
    fun servicePreconditionsAllowRecordingButNotBlockedMissingSdOrBusy() {
        val camera =
            CameraState(connection = ConnectionStatus.CONNECTED, token = 19, recording = RecordingState.RECORDING)
        assertNull(mediaAvailability(true, false, camera))
        assertEquals(CameraMediaError.BLOCKED, mediaAvailability(true, true, camera))
        assertEquals(CameraMediaError.DISCONNECTED, mediaAvailability(false, false, camera))
        assertEquals(CameraMediaError.DISCONNECTED, mediaAvailability(true, false, camera.copy(token = null)))
        assertEquals(
            CameraMediaError.SD_MISSING, mediaAvailability(
                true, false,
                camera.copy(configuration = mapOf("sd_card_status" to "remove"))
            )
        )
        assertEquals(CameraMediaError.BUSY, mediaAvailability(true, false, camera.copy(pending = setOf(5))))
    }

    private fun parse(vararg pairs: Pair<String, String>) = parseMediaListing(
        JsonArray(pairs.map { (name, metadata) -> buildJsonObject { put(name, metadata) } }),
        CAMERA_DCIM_ROOT,
    )

    @Test
    fun physicalFixturesAndCaseInsensitiveTypes() {
        val result = parse(
            "MISC/" to "0 bytes|2023-02-21 22:31:42",
            "YDXJ0257.jpg" to "3310683 bytes|2008-01-05 12:14:04",
            "YDXJ0250.mp4" to "83846958 bytes|2023-02-24 11:31:06",
            "YDXJ0250.THM" to "72582 bytes|2023-02-24 11:30:40",
            "A.JPEG" to "0 bytes|date", "B.MP4" to "1 bytes|date",
            "C.thm" to "2 bytes|date", "my file.txt" to "0 bytes|date",
        )
        assertEquals(
            listOf(
                CameraMediaType.DIRECTORY, CameraMediaType.PHOTO, CameraMediaType.VIDEO,
                CameraMediaType.THUMBNAIL, CameraMediaType.PHOTO, CameraMediaType.VIDEO,
                CameraMediaType.THUMBNAIL, CameraMediaType.OTHER
            ), result.entries.map { it.type })
        assertEquals(3310683L, result.entries[1].sizeBytes)
        assertEquals("2008-01-05 12:14:04", result.entries[1].timestampRaw)
        assertEquals(83846958L, result.entries[2].sizeBytes)
        assertEquals(72582L, result.entries[3].sizeBytes)
        assertEquals(0, result.malformedEntries)
    }

    @Test
    fun malformedMetadataDoesNotDiscardEntries() {
        val entries = parse(
            "a.jpg" to "bad|date|kept", "b.mp4" to "999999999999999999999 bytes|date",
            "c.txt" to "12 bytes", "d.txt" to "-1 bytes|date", "../" to "0 bytes|date"
        ).entries
        assertNull(entries[0].sizeBytes)
        assertEquals("date|kept", entries[0].timestampRaw)
        assertNull(entries[1].sizeBytes)
        assertEquals(12L, entries[2].sizeBytes)
        assertNull(entries[2].timestampRaw)
        assertNull(entries[3].sizeBytes)
        assertEquals(CameraMediaType.OTHER, entries[4].type)
        assertEquals("", entries[4].path)
        val malformed =
            parseMediaListing(Json.parseToJsonElement("""[null,{},{"a":"x","b":"y"},{"a":42}]"""), CAMERA_SD_ROOT)
        assertEquals(4, malformed.entries.size)
        assertEquals(4, malformed.malformedEntries)
        assertThrows(CameraMediaException::class.java) { parseMediaListing(JsonObject(emptyMap()), CAMERA_SD_ROOT) }
    }

    @Test
    fun thumbnailAssociationIsOptionalAndCaseInsensitive() {
        val raw = parse(
            "YDXJ0250.mp4" to "1 bytes|d", "ydxj0250.THM" to "1 bytes|d",
            "other.MP4" to "1 bytes|d", "orphan.thm" to "1 bytes|d",
            "YDXJ0250.jpg" to "1 bytes|d"
        ).entries
        val gallery = buildGallery(raw)
        assertEquals(3, gallery.size)
        assertNotNull(gallery.single { it.media.name == "YDXJ0250.mp4" }.thumbnail)
        assertNull(gallery.single { it.media.name == "other.MP4" }.thumbnail)
        assertNull(gallery.single { it.media.type == CameraMediaType.PHOTO }.thumbnail)
        assertTrue(buildGallery(raw.filter { it.type == CameraMediaType.THUMBNAIL }).isEmpty())
    }

    @Test
    fun safeNavigationAndNonHardcodedFolders() {
        assertEquals(CAMERA_SD_ROOT, resolveCameraDirectory(CAMERA_SD_ROOT, "."))
        assertEquals(CAMERA_DCIM_ROOT, resolveCameraDirectory(CAMERA_SD_ROOT, "DCIM/"))
        assertEquals("$CAMERA_DCIM_ROOT/101MEDIA", resolveCameraDirectory(CAMERA_DCIM_ROOT, "101MEDIA/"))
        assertEquals(CAMERA_DCIM_ROOT, resolveCameraDirectory("$CAMERA_DCIM_ROOT/100MEDIA", "../"))
        assertEquals(CAMERA_SD_ROOT, resolveCameraDirectory(CAMERA_DCIM_ROOT, "../"))
        for (path in listOf("../", "../../etc", "/tmp/fuse_d_other", "/", "/tmp/fuse_d/../../etc")) {
            assertNull(path, resolveCameraDirectory(CAMERA_SD_ROOT, path))
        }
        for (name in listOf("../", "/etc", "a/b", "a\\b", "bad\nname")) assertNull(safeChildPath(CAMERA_SD_ROOT, name))
        assertEquals("$CAMERA_SD_ROOT/my folder", safeChildPath(CAMERA_SD_ROOT, "my folder/"))
        val message =
            cameraJson.decodeFromString<CameraMessage>("""{"msg_id":1283,"rval":0,"pwd":"\/tmp\/fuse_d\/DCIM"}""")
        assertEquals(CAMERA_DCIM_ROOT, message.pwd!!.jsonPrimitive.content)
    }

    @Test
    fun storageConversionAndInvalidValues() {
        val info = storageInfo(31154688, 29852096)!!
        assertEquals(31154688L, info.totalRaw)
        assertEquals(29852096L, info.freeRaw)
        assertEquals(31154688L * 1024, info.totalBytes)
        assertEquals(29852096L * 1024, info.freeBytes)
        assertEquals(info.totalBytes - info.freeBytes, info.usedBytes)
        assertNull(storageInfo(-1, 0))
        assertNull(storageInfo(1, -1))
        assertNull(storageInfo(1, 2))
        assertNull(storageInfo(Long.MAX_VALUE, 0))
        assertEquals(0L, storageInfo(0, 0)!!.usedBytes)
    }
}
