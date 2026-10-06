package com.rconegliam.bumpmap.recorder

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class RecordingStoreTest {
    @Test
    fun fileNameHasTimestampAndCsvExtension() {
        val name = RecordingStore.fileName(Date())
        assertTrue(name, Regex("""bumpmap_\d{8}_\d{6}\.csv""").matches(name))
    }
}
