package com.rconegliam.bumpmap.api

import java.util.UUID
import javax.sql.DataSource

internal const val RECORDING_PART_BYTES = 512 * 1024
private val recordingNamePattern = Regex("[A-Za-z0-9_.-]{1,120}")

internal data class RecordingPart(
    val id: UUID,
    val kind: String,
    val number: Int,
    val partCount: Int,
    val fileName: String,
) {
    fun valid() =
        (kind == "csv" || kind == "ndjson") && number in 0 until partCount &&
            recordingNamePattern.matches(fileName) && fileName.endsWith(".$kind")

    fun validSize(size: Int) = when {
        number < partCount - 1 -> size == RECORDING_PART_BYTES
        partCount == 1 -> size in 0..RECORDING_PART_BYTES
        else -> size in 1..RECORDING_PART_BYTES
    }
}

internal class RecordingStore(private val database: DataSource) {
    fun save(part: RecordingPart, bytes: ByteArray): Boolean = database.connection.use { connection ->
        connection.prepareStatement(
            """
            WITH file AS (
                INSERT INTO recording_files (recording_id, kind, file_name, part_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (recording_id, kind) DO UPDATE SET file_name = EXCLUDED.file_name
                WHERE recording_files.file_name = EXCLUDED.file_name
                  AND recording_files.part_count = EXCLUDED.part_count
                RETURNING recording_id, kind
            )
            INSERT INTO recording_file_parts (recording_id, kind, part_number, contents)
            SELECT recording_id, kind, ?, ? FROM file
            ON CONFLICT (recording_id, kind, part_number) DO UPDATE SET contents = EXCLUDED.contents
            WHERE recording_file_parts.contents = EXCLUDED.contents
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, part.id)
            statement.setString(2, part.kind)
            statement.setString(3, part.fileName)
            statement.setInt(4, part.partCount)
            statement.setInt(5, part.number)
            statement.setBytes(6, bytes)
            statement.executeUpdate() == 1
        }
    }
}
