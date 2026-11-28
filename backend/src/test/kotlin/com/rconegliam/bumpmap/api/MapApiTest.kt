package com.rconegliam.bumpmap.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class MapApiTest {
    @Test
    fun readTokenDoesNotGrantLegacyAccess() {
        databasePool(System.getenv()).use { pool ->
            testApplication {
                application { bumpMapApi(ObservationStore(pool), RecordingStore(pool), WRITE_TOKEN, READ_TOKEN) }
                for (token in listOf(null, "wrong-token", WRITE_TOKEN)) {
                    val response = client.get(MAP_URL) { token?.let { bearerAuth(it) } }
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    assertMapHeaders(response)
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/observations?$BBOX") {
                    bearerAuth(READ_TOKEN)
                }.status)
                assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/observations") {
                    bearerAuth(READ_TOKEN)
                }.status)
                assertEquals(HttpStatusCode.Unauthorized, client.put(ARCHIVE_PATH) {
                    bearerAuth(READ_TOKEN)
                }.status)
                assertEquals(HttpStatusCode.NotFound, client.get(ARCHIVE_PATH) {
                    bearerAuth(READ_TOKEN)
                }.status)
                assertEquals(HttpStatusCode.MethodNotAllowed, client.post(MAP_URL) {
                    bearerAuth(READ_TOKEN)
                }.status)
                for (path in listOf("/v1/map/observations", "/v1/map/observations?bbox=0,0,2,1", "$MAP_URL&after=bad")) {
                    val response = client.get(path) { bearerAuth(READ_TOKEN) }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("""{"error":"invalid_bbox_or_cursor"}""", response.bodyAsText())
                    assertMapHeaders(response)
                }
            }
        }
    }

    @Test
    fun missingReadTokenDisablesAccessAndReusingWriteTokenIsInvalid() {
        databasePool(System.getenv()).use { pool ->
            val store = ObservationStore(pool)
            val recordings = RecordingStore(pool)
            testApplication {
                application { bumpMapApi(store, recordings, WRITE_TOKEN) }
                val response = client.get(MAP_URL) { bearerAuth(READ_TOKEN) }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertMapHeaders(response)
            }
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    application { bumpMapApi(store, recordings, WRITE_TOKEN, WRITE_TOKEN) }
                    startApplication()
                }
            }
        }
    }

    @Test
    fun paginatesOnlyIntersectingRecordingObservationsAndProjectsMapProperties() {
        val ids = List(PAGE_SIZE + 1) { UUID.randomUUID().toString() }.sorted()
        val recordingId = UUID.randomUUID().toString()
        val unrecordedId = UUID.randomUUID().toString()
        val outOfBoundsId = UUID.randomUUID().toString()
        val segment = Observation(
            type = "Feature",
            id = ids.first(),
            geometry = Json.decodeFromString("""{"type":"LineString","coordinates":[[0.1,0.1],[0.2,0.2]]}"""),
            properties = ObservationProperties(
                kind = "segment", speedKmh = 42.75, quality = "good", score = 70, roughness = 0.2,
                recordingId = recordingId, potholes = 2, speedBumps = 1,
            ),
        )
        val calibratedSegments = listOf(0.61, 1.60, null).mapIndexed { index, roughness ->
            segment.copy(
                id = ids[index + 1],
                properties = segment.properties.copy(
                    speedKmh = 30.0, roughness = roughness, potholes = 0, speedBumps = 0,
                ),
            )
        }
        val event = Observation(
            type = "Feature",
            id = ids.last(),
            geometry = Json.decodeFromString("""{"type":"Point","coordinates":[0.2,0.2]}"""),
            properties = ObservationProperties(
                kind = "event", speedKmh = 28.0, eventType = "pothole", peak = 5.1, recordingId = recordingId,
            ),
        )
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            val store = ObservationStore(pool)
            try {
                val observations = listOf(segment) + calibratedSegments + ids.drop(4).map { event.copy(id = it) }
                assertTrue(store.save(observations + listOf(
                    event.copy(id = unrecordedId, properties = event.properties.copy(recordingId = null)),
                    event.copy(id = outOfBoundsId, geometry = Json.decodeFromString(
                        """{"type":"Point","coordinates":[2.0,2.0]}""",
                    )),
                )))
                testApplication {
                    application { bumpMapApi(store, RecordingStore(pool), WRITE_TOKEN, READ_TOKEN) }
                    val response = client.get("$MAP_URL&from_recordings=false") { bearerAuth(READ_TOKEN) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(ContentType.parse("application/geo+json"), response.contentType()?.withoutParameters())
                    assertMapHeaders(response)
                    val first = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals("FeatureCollection", first.getValue("type").jsonPrimitive.content)
                    val features = first.getValue("features").jsonArray.map { it.jsonObject }
                    assertEquals(ids.take(PAGE_SIZE), features.map { it.getValue("id").jsonPrimitive.content })
                    assertTrue(features.none { "recording_id" in it.getValue("properties").jsonObject })
                    val segmentProperties = features.first().getValue("properties").jsonObject
                    assertEquals(Json.parseToJsonElement("""
                        {"kind":"segment","speed_kmh":42.75,"quality":"good","score":70,"roughness":0.2,
                        "potholes":2,"speed_bumps":1,"map_quality":"bad","penalty_points":55}
                    """.trimIndent()), segmentProperties)
                    assertEquals(
                        listOf("good" to 5L, "fair" to 18L, "fair" to 13L),
                        features.subList(1, 4).map {
                            val properties = it.getValue("properties").jsonObject
                            properties.getValue("map_quality").jsonPrimitive.content to
                                properties.getValue("penalty_points").jsonPrimitive.long
                        },
                    )
                    val cursor = first.getValue("next_cursor").jsonPrimitive.content
                    assertEquals(ids[PAGE_SIZE - 1], cursor)

                    val response2 = client.get("$MAP_URL&after=$cursor") { bearerAuth(READ_TOKEN) }
                    assertEquals(HttpStatusCode.OK, response2.status)
                    val second = Json.parseToJsonElement(response2.bodyAsText()).jsonObject
                    assertFalse("next_cursor" in second)
                    val last = second.getValue("features").jsonArray.single().jsonObject
                    assertEquals(event.id, last.getValue("id").jsonPrimitive.content)
                    assertEquals(Json.parseToJsonElement("""
                        {"kind":"event","speed_kmh":28.0,"type":"pothole","peak":5.1}
                    """.trimIndent()), last.getValue("properties"))
                }
            } finally {
                pool.connection.use { connection ->
                    connection.prepareStatement(
                        "DELETE FROM road_observations WHERE feature->'properties'->>'recording_id' = ? OR id = ?",
                    ).use { statement ->
                        statement.setString(1, recordingId)
                        statement.setObject(2, UUID.fromString(unrecordedId))
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun assertMapHeaders(response: HttpResponse) {
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    private companion object {
        const val WRITE_TOKEN = "synthetic-test-write-token-unused-outside-tests"
        const val READ_TOKEN = "synthetic-test-map-token-unused-outside-tests"
        const val BBOX = "bbox=0,0,1,1"
        const val MAP_URL = "/v1/map/observations?$BBOX"
        const val ARCHIVE_PATH = "/v1/recordings/00000000-0000-0000-0000-000000000001/csv/0?parts=1&name=samples.csv"
    }
}
