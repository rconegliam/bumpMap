package com.rconegliam.bumpmap.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RecordingApiTest {
    @Test
    fun storesEveryPartAndPreservesOriginalBytesAcrossRetries() {
        val token = "synthetic-token-for-local-tests-only"
        val id = UUID.randomUUID()
        val csvStart = ByteArray(RECORDING_PART_BYTES) { it.toByte() }
        val csvEnd = "m,123,pothole\n".encodeToByteArray()
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            testApplication {
                application { bumpMapApi(ObservationStore(pool), RecordingStore(pool), token) }

                suspend fun upload(
                    number: Int,
                    bytes: ByteArray,
                    kind: String = "csv",
                    parts: Int = 2,
                    name: String = "bumpmap_test.$kind",
                    authenticated: Boolean = true,
                ) = client.put("/v1/recordings/$id/$kind/$number?parts=$parts&name=$name") {
                    if (authenticated) bearerAuth(token)
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }.status

                assertEquals(HttpStatusCode.Unauthorized, upload(0, csvStart, authenticated = false))
                assertEquals(HttpStatusCode.PayloadTooLarge, upload(0, ByteArray(RECORDING_PART_BYTES + 1)))
                assertEquals(HttpStatusCode.BadRequest, upload(0, csvEnd))
                assertEquals(HttpStatusCode.BadRequest, upload(2, csvEnd))
                assertEquals(HttpStatusCode.NoContent, upload(0, csvStart))
                assertEquals(0, completedFiles(pool, id))
                assertEquals(HttpStatusCode.NoContent, upload(0, csvStart))
                assertEquals(HttpStatusCode.Conflict, upload(0, ByteArray(RECORDING_PART_BYTES)))
                assertEquals(HttpStatusCode.Conflict, upload(2, csvEnd, parts = 3))
                assertEquals(HttpStatusCode.NoContent, upload(1, csvEnd))
                assertEquals(HttpStatusCode.NoContent, upload(0, byteArrayOf(), "ndjson", 1))
                assertEquals(2, completedFiles(pool, id))
            }

            pool.connection.use { connection ->
                connection.prepareStatement(
                    """SELECT contents FROM recording_file_parts
                       WHERE recording_id = ? AND kind = 'csv' ORDER BY part_number""",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { result ->
                        val saved = buildList {
                            while (result.next()) add(result.getBytes(1))
                        }
                        assertEquals(2, saved.size)
                        assertContentEquals(csvStart, saved[0])
                        assertContentEquals(csvEnd, saved[1])
                    }
                }
            }
        }
    }

    private fun completedFiles(database: DataSource, id: UUID): Int =
        database.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM completed_recording_files WHERE recording_id = ?",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
}
