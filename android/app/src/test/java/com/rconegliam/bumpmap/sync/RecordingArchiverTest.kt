package com.rconegliam.bumpmap.sync

import com.rconegliam.bumpmap.detection.archiveRecordingId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.maplibre.geojson.Feature
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

private const val INSTALLATION = "installation"
private const val SEGMENT =
    """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-46.7,-23.5],[-46.7001,-23.5001]]},""" +
        """"properties":{"kind":"segment","quality":"good","score":90,"roughness":0.1,"speed_kmh":40.0}}"""

class RecordingArchiverTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** In-memory stand-in for the PostgreSQL archive and observation API. */
    private class FakeServer : ArchiveApi {
        val parts = mutableMapOf<String, MutableMap<Int, ByteArray>>()
        val observations = mutableMapOf<String, String>()
        val putRequests = mutableListOf<String>()
        var putStatus: ((String, Int) -> Int?) = { _, _ -> null }
        var publishStatus: (List<Feature>) -> Int? = { null }

        override fun putPart(id: UUID, kind: String, number: Int, parts: Int, name: String, bytes: ByteArray): Int {
            val key = "$id/$kind/$name/$parts"
            putRequests += "$kind:$number"
            putStatus(kind, number)?.let { return it }
            val stored = this.parts.getOrPut(key) { mutableMapOf() }
            if ((0 until number).any { it !in stored }) return 412
            stored[number]?.let { return if (it.contentEquals(bytes)) 204 else 409 }
            stored[number] = bytes
            return 204
        }

        override fun publish(features: List<Feature>): Int {
            publishStatus(features)?.let { return it }
            if (features.any { observations[it.id()]?.let { old -> old != it.toJson() } == true }) return 409
            features.forEach { observations[it.id()!!] = it.toJson() }
            return 200
        }

        fun file(id: UUID, kind: String): ByteArray? =
            parts.entries.firstOrNull { it.key.startsWith("$id/$kind/") }?.value?.let { stored ->
                ByteArrayOutputStream().apply { stored.toSortedMap().values.forEach(::write) }.toByteArray()
            }
    }

    private class MemoryCheckpoints : ArchiveCheckpoints {
        val values = mutableMapOf<String, Any>()
        override fun getInt(key: String) = values[key] as? Int ?: 0
        override fun putInt(key: String, value: Int) = true.also { values[key] = value }
        override fun getBoolean(key: String) = values[key] as? Boolean ?: false
        override fun putBoolean(key: String, value: Boolean) = true.also { values[key] = value }
        override fun remove(keys: List<String>) = true.also { keys.forEach(values::remove) }
    }

    private val server = FakeServer()
    private val checkpoints = MemoryCheckpoints()
    private fun archiver() = RecordingArchiver(server, checkpoints, INSTALLATION)
    private fun id(baseName: String) = archiveRecordingId(INSTALLATION, baseName)

    private fun recording(baseName: String, csvBytes: Int = 1000, detections: Int = 3): List<File> {
        val csv = File(folder.root, "$baseName.csv").apply { writeBytes(ByteArray(csvBytes) { (it % 251).toByte() }) }
        val ndjson = File(folder.root, "$baseName.ndjson").apply { writeText((1..detections).joinToString("\n") { SEGMENT }) }
        return listOf(csv, ndjson)
    }

    @Test
    fun deletesRecordingOnlyAfterServerHasFilesAndMap() {
        val files = recording("bumpmap_20261128_100000", csvBytes = ARCHIVE_PART_BYTES * 2 + 17)
        val expectedCsv = files[0].readBytes()
        val expectedNdjson = files[1].readBytes()

        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(files))

        val id = id("bumpmap_20261128_100000")
        assertArrayEquals(expectedCsv, server.file(id, "csv"))
        assertArrayEquals(expectedNdjson, server.file(id, "ndjson"))
        assertEquals(3, server.observations.size)
        assertFalse(files.any { it.exists() })
        assertTrue(checkpoints.getBoolean(archivedKey(id)))
        assertEquals(setOf(archivedKey(id)), checkpoints.values.keys)
    }

    @Test
    fun keepsFilesWhenMapPublicationFails() {
        val files = recording("bumpmap_20261128_100000")
        server.publishStatus = { 503 }

        assertEquals(ArchiveOutcome.RETRY, archiver().run(files))

        assertTrue(files.all { it.exists() })
        assertFalse(checkpoints.getBoolean(archivedKey(id("bumpmap_20261128_100000"))))

        server.publishStatus = { null }
        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(files))
        assertFalse(files.any { it.exists() })
    }

    @Test
    fun keepsFilesWhenUploadFailsAndResumesFromCheckpoint() {
        val files = recording("bumpmap_20261128_100000", csvBytes = ARCHIVE_PART_BYTES * 2 + 1)
        server.putStatus = { kind, number -> if (kind == "csv" && number == 2) 500 else null }

        assertEquals(ArchiveOutcome.RETRY, archiver().run(files))
        assertTrue(files.all { it.exists() })

        server.putStatus = { _, _ -> null }
        server.putRequests.clear()
        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(files))
        assertEquals(listOf("csv:2", "ndjson:0"), server.putRequests)
        assertFalse(files.any { it.exists() })
    }

    @Test
    fun legacyCompleteCheckpointIsConfirmedBeforeDeleting() {
        val files = recording("bumpmap_20261001_080000", csvBytes = ARCHIVE_PART_BYTES + 5)
        val id = id("bumpmap_20261001_080000")
        // A previous app version finished the upload and map, then the server lost the archive.
        checkpoints.putInt("$id/csv", 2)
        checkpoints.putInt("$id/ndjson", 1)
        checkpoints.putBoolean("$id/map-v1", true)

        assertEquals(ArchiveOutcome.RETRY, archiver().run(files))
        assertTrue(files.all { it.exists() })
        assertEquals(0, checkpoints.getInt("$id/csv"))

        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(files))
        assertArrayEquals(ByteArray(ARCHIVE_PART_BYTES + 5) { (it % 251).toByte() }, server.file(id, "csv"))
        assertEquals(3, server.observations.size)
        assertFalse(files.any { it.exists() })
    }

    @Test
    fun legacyCheckpointResendsOnlyTheLastPartWhenServerHasTheFile() {
        val files = recording("bumpmap_20261001_080000", csvBytes = ARCHIVE_PART_BYTES * 3)
        assertEquals(ArchiveOutcome.SUCCESS, RecordingArchiver(server, MemoryCheckpoints(), INSTALLATION).run(files.map { it.copyTo(File(folder.newFolder(), it.name)) }))
        val id = id("bumpmap_20261001_080000")
        checkpoints.putInt("$id/csv", 3)
        checkpoints.putInt("$id/ndjson", 1)
        server.putRequests.clear()

        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(files))
        assertEquals(listOf("csv:2", "ndjson:0"), server.putRequests)
        assertFalse(files.any { it.exists() })
    }

    @Test
    fun observationConflictStillConfirmsEachObservationIndividually() {
        val files = recording("bumpmap_20261128_100000", detections = 2)
        val first = archiveRecordingId(INSTALLATION, "bumpmap_20261128_100000")
        val firstFeatureId = UUID.nameUUIDFromBytes("$first/map/0".encodeToByteArray()).toString()
        server.observations[firstFeatureId] = "published by an older app version"
        var calls = 0
        server.publishStatus = { calls++; null }

        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(files))
        assertEquals(3, calls)
        assertEquals(2, server.observations.size)
        assertFalse(files.any { it.exists() })
    }

    @Test
    fun archiveFullKeepsThatTripAndContinuesWithOthers() {
        val full = recording("bumpmap_20261128_100000")
        val ok = recording("bumpmap_20261128_110000")
        server.putStatus = { _, _ -> null }
        val fullId = id("bumpmap_20261128_100000")
        val api = object : ArchiveApi by server {
            override fun putPart(id: UUID, kind: String, number: Int, parts: Int, name: String, bytes: ByteArray) =
                if (id == fullId) 507 else server.putPart(id, kind, number, parts, name, bytes)
        }

        assertEquals(ArchiveOutcome.ARCHIVE_FULL, RecordingArchiver(api, checkpoints, INSTALLATION).run(full + ok))
        assertTrue(full.all { it.exists() })
        assertFalse(ok.any { it.exists() })
    }

    @Test
    fun rejectedTokenStopsWithoutDeleting() {
        val files = recording("bumpmap_20261128_100000")
        server.putStatus = { _, _ -> 401 }

        assertEquals(ArchiveOutcome.UNAUTHORIZED, archiver().run(files))
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun conflictingBytesAreRejectedAndKept() {
        val files = recording("bumpmap_20261128_100000")
        val id = id("bumpmap_20261128_100000")
        checkpoints.putInt("$id/csv", 1)
        server.putPart(id, "csv", 0, 1, files[0].name, ByteArray(1000))

        assertEquals(ArchiveOutcome.REJECTED, archiver().run(files))
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun cancellationKeepsFiles() {
        val files = recording("bumpmap_20261128_100000")
        val outcome = RecordingArchiver(server, checkpoints, INSTALLATION, isCancelled = { true }).run(files)

        assertEquals(ArchiveOutcome.RETRY, outcome)
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun legacyDetectionsWithoutCsvAreArchivedAndRemoved() {
        val ndjson = recording("bumpmap_20261001_080000")[1]
        File(folder.root, "bumpmap_20261001_080000.csv").delete()

        assertEquals(ArchiveOutcome.SUCCESS, archiver().run(listOf(ndjson)))
        assertFalse(ndjson.exists())
        assertEquals(3, server.observations.size)
    }
}
