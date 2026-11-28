package com.rconegliam.bumpmap.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PublicMapApiTest {
    @Test
    fun installationsContributeToTheSamePublicMapWithoutExposingTripGeometry() {
        val installations = List(2) { UUID.randomUUID() }
        val tokens = listOf("a".repeat(64), "b".repeat(64))
        val recordings = installations.map { UUID.nameUUIDFromBytes("$it/bumpmap_trip".encodeToByteArray()) }
        val road = "test-road-${UUID.randomUUID()}"
        val trips = recordings.mapIndexed { index, recording ->
            Observation(
                type = "Feature",
                id = UUID.randomUUID().toString(),
                geometry = Json.decodeFromString(
                    """{"type":"LineString","coordinates":[[0.1003,0.1001],[0.1006,0.1001]]}""",
                ),
                properties = ObservationProperties(
                    kind = "segment", quality = "good", score = 90, roughness = 0.7, speedKmh = 20.0 + index * 20,
                    recordingId = recording.toString(), potholes = index, speedBumps = 0,
                ),
            )
        }
        val independentEvent = Observation(
            type = "Feature",
            id = UUID.randomUUID().toString(),
            geometry = Json.decodeFromString("""{"type":"Point","coordinates":[0.1003,0.1001]}"""),
            properties = ObservationProperties(
                kind = "event", speedKmh = 22.0, eventType = "speed_bump", peak = 3.5,
                recordingId = recordings[0].toString(),
            ),
        )
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            pool.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO map_street_ways (id, name, geom)
                    VALUES (?, 'Fixture Way', ST_GeomFromText('LINESTRING(0.1 0.1, 0.101 0.1)', 4326))
                    """.trimIndent(),
                ).use {
                    it.setString(1, road)
                    it.executeUpdate()
                }
            }
            try {
                testApplication {
                    application {
                        bumpMapApi(
                            ObservationStore(pool), RecordingStore(pool), "legacy-test-token",
                            installations = InstallationStore(pool),
                        )
                    }
                    suspend fun register(id: UUID, token: String) = client.post("/v1/installations") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"id":"$id"}""")
                    }.status
                    suspend fun upload(id: UUID, token: String?) =
                        client.put("/v1/recordings/$id/ndjson/0?parts=1&name=bumpmap_trip.ndjson") {
                            token?.let { bearerAuth(it) }
                            contentType(ContentType.Application.OctetStream)
                            setBody(byteArrayOf())
                        }.status
                    suspend fun publish(trip: Observation, token: String?) = client.post("/v1/observations") {
                        token?.let { bearerAuth(it) }
                        contentType(ContentType.Application.Json)
                        setBody("""{"type":"FeatureCollection","features":[${Json.encodeToString(trip)}]}""")
                    }.status

                    assertEquals(HttpStatusCode.Unauthorized, upload(recordings[0], null))
                    assertEquals(HttpStatusCode.Unauthorized, publish(trips[0], null))
                    for (index in installations.indices) {
                        assertEquals(HttpStatusCode.NoContent, register(installations[index], tokens[index]))
                    }
                    assertEquals(HttpStatusCode.NoContent, register(installations[0], tokens[0]))
                    assertEquals(HttpStatusCode.Conflict, register(installations[0], tokens[1]))
                    assertEquals(HttpStatusCode.Forbidden, upload(recordings[0], tokens[1]))
                    assertEquals(HttpStatusCode.Forbidden, publish(trips[0], tokens[0]))
                    for (index in installations.indices) {
                        repeat(2) {
                            assertEquals(HttpStatusCode.NoContent, upload(recordings[index], tokens[index]))
                            assertEquals(HttpStatusCode.OK, publish(trips[index], tokens[index]))
                        }
                    }
                    assertEquals(HttpStatusCode.Forbidden, publish(trips[0], tokens[1]))
                    assertEquals(HttpStatusCode.OK, publish(independentEvent, tokens[0]))
                    assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/observations?bbox=0,0,1,1") {
                        bearerAuth(tokens[0])
                    }.status)
                    val map = client.get("/v1/public/map/observations?bbox=0,0,0.2,0.2")
                    assertEquals(HttpStatusCode.OK, map.status)
                    val page = Json.decodeFromString<MapObservationPage>(map.bodyAsText())
                    val segment = page.features.single { it.properties.kind == "segment" }
                    assertEquals(30.0, segment.properties.speedKmh)
                    assertEquals(1, segment.properties.potholes)
                    assertEquals("fair", segment.properties.mapQuality)
                    assertEquals(
                        Json.decodeFromString<Geometry>("""{"type":"LineString","coordinates":[[0.1,0.1],[0.101,0.1]]}"""),
                        segment.geometry,
                    )
                    val obstacles = page.features.filter { it.properties.kind == "event" }
                    assertEquals(setOf("pothole", "speed_bump"), obstacles.map { it.properties.eventType }.toSet())
                    assertTrue(obstacles.none { it.geometry == independentEvent.geometry })
                    assertFalse(map.bodyAsText().contains("recording_id"))
                    assertTrue((trips + independentEvent).none { trip -> page.features.any { it.id == trip.id } })
                    assertEquals(HttpStatusCode.OK, client.get("/v1/public/map/statistics?level=city").status)
                    assertEquals(HttpStatusCode.OK, client.get("/v1/public/map/route/config").status)
                }
            } finally {
                pool.connection.use { connection ->
                    for (recording in recordings) {
                        connection.prepareStatement("DELETE FROM recording_files WHERE recording_id = ?").use {
                            it.setObject(1, recording)
                            it.executeUpdate()
                        }
                    }
                    for (trip in trips + independentEvent) {
                        connection.prepareStatement("DELETE FROM road_observations WHERE id = ?").use {
                            it.setObject(1, UUID.fromString(trip.id))
                            it.executeUpdate()
                        }
                    }
                    for (id in installations) {
                        connection.prepareStatement("DELETE FROM app_installations WHERE id = ?").use {
                            it.setObject(1, id)
                            it.executeUpdate()
                        }
                    }
                    connection.prepareStatement("DELETE FROM map_street_ways WHERE id = ?").use {
                        it.setString(1, road)
                        it.executeUpdate()
                    }
                }
            }
        }
    }
}
