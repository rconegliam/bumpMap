package com.rconegliam.bumpmap.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class RecordingStoreTest {
    @Test
    fun fileNameHasTimestampAndCsvExtension() {
        val name = RecordingStore.fileName(Date())
        assertTrue(name, Regex("""bumpmap_\d{8}_\d{6}\.csv""").matches(name))
    }

    @Test
    fun sameSecondAfterArchiveCleanupGetsNewSuffix() {
        val archivedOrLocal = setOf("bumpmap_20261128_100000", "bumpmap_20261128_100000-1")
        assertEquals(
            "bumpmap_20261128_100000-2",
            RecordingStore.freeBaseName("bumpmap_20261128_100000") { it in archivedOrLocal },
        )
        assertEquals("bumpmap_20261128_110000", RecordingStore.freeBaseName("bumpmap_20261128_110000") { false })
    }
}
