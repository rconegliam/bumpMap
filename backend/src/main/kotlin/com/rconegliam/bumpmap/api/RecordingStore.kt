package com.rconegliam.bumpmap.api

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

internal const val RECORDING_PART_BYTES = 512 * 1024
internal const val DEFAULT_ARCHIVE_MAX_BYTES = 512L * 1024 * 1024
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

internal enum class RecordingSaveResult { STORED, CONFLICT, MISSING_EARLIER_PARTS, ARCHIVE_FULL }

internal class RecordingStore(
    private val database: DataSource,
    private val maxBytes: Long = DEFAULT_ARCHIVE_MAX_BYTES,
) {
    fun save(part: RecordingPart, bytes: ByteArray): RecordingSaveResult = database.connection.use { connection ->
        connection.autoCommit = false
        // Serialize the quota check and part insert across all API processes.
        val hasCapacity = connection.prepareStatement(
            """SELECT charged_bytes <= ? - (?::bigint + 4096)
               FROM recording_archive_usage WHERE singleton FOR UPDATE""",
        ).use { statement ->
            statement.setLong(1, maxBytes)
            statement.setInt(2, bytes.size)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Recording archive accounting is missing" }
                rows.getBoolean(1)
            }
        }
        val existing = existingPart(connection, part, bytes)
        val result = when {
            existing == RecordingSaveResult.CONFLICT -> existing
            !hasEarlierParts(connection, part) -> RecordingSaveResult.MISSING_EARLIER_PARTS
            existing != null -> existing
            !hasCapacity -> RecordingSaveResult.ARCHIVE_FULL
            else -> {
                insertPart(connection, part, bytes)
                RecordingSaveResult.STORED
            }
        }
        connection.commit()
        result
    }

    private fun hasEarlierParts(connection: Connection, part: RecordingPart): Boolean {
        if (part.number == 0) return true
        return connection.prepareStatement(
            """SELECT count(*) FROM recording_file_parts
               WHERE recording_id = ? AND kind = ? AND part_number < ?""",
        ).use { statement ->
            statement.setObject(1, part.id)
            statement.setString(2, part.kind)
            statement.setInt(3, part.number)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1) == part.number.toLong()
            }
        }
    }

    private fun existingPart(connection: Connection, part: RecordingPart, bytes: ByteArray): RecordingSaveResult? =
        connection.prepareStatement(
            """
            SELECT (files.file_name = ? AND files.part_count = ?) AS same_file,
                   parts.contents = ? AS same_part
            FROM recording_files AS files
            LEFT JOIN recording_file_parts AS parts
              ON parts.recording_id = files.recording_id AND parts.kind = files.kind AND parts.part_number = ?
            WHERE files.recording_id = ? AND files.kind = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, part.fileName)
            statement.setInt(2, part.partCount)
            statement.setBytes(3, bytes)
            statement.setInt(4, part.number)
            statement.setObject(5, part.id)
            statement.setString(6, part.kind)
            statement.executeQuery().use result@{ rows ->
                if (!rows.next()) return@result null
                if (!rows.getBoolean("same_file")) return@result RecordingSaveResult.CONFLICT
                val samePart = rows.getBoolean("same_part")
                when {
                    rows.wasNull() -> null
                    samePart -> RecordingSaveResult.STORED
                    else -> RecordingSaveResult.CONFLICT
                }
            }
        }

    private fun insertPart(connection: Connection, part: RecordingPart, bytes: ByteArray) {
        connection.prepareStatement(
            """
            INSERT INTO recording_files (recording_id, kind, file_name, part_count)
            VALUES (?, ?, ?, ?) ON CONFLICT (recording_id, kind) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, part.id)
            statement.setString(2, part.kind)
            statement.setString(3, part.fileName)
            statement.setInt(4, part.partCount)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO recording_file_parts (recording_id, kind, part_number, contents) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, part.id)
            statement.setString(2, part.kind)
            statement.setInt(3, part.number)
            statement.setBytes(4, bytes)
            statement.executeUpdate()
        }
    }
}
