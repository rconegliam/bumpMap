package com.rconegliam.bumpmap.api

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.postgresql.ds.PGSimpleDataSource

class MapStatisticsApiTest {
    @Test
    fun authenticatesAndValidatesBeforeQuerying() {
        databasePool(System.getenv()).use { pool ->
            testApplication {
                application { statisticsApi(pool) }
                for (token in listOf(null, WRITE_TOKEN, "wrong-map-token")) {
                    val response = client.get("$STATISTICS_URL?level=city") {
                        token?.let { bearerAuth(it) }
                    }
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
                val invalidQueries = listOf(
                    "", "level=continent", "level=city&sort=name", "level=city&limit=0",
                    "level=city&limit=101", "level=city&limit=two", "level=city&offset=-1",
                    "level=city&offset=2147483648",
                )
                for (query in invalidQueries) {
                    val response = client.get("$STATISTICS_URL?$query") { bearerAuth(READ_TOKEN) }
                    assertEquals(HttpStatusCode.BadRequest, response.status, query)
                    assertEquals("""{"error":"invalid_statistics_query"}""", response.bodyAsText(), query)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
            }
        }
    }

    @Test
    fun aggregatesMeasuredPassagesAtEachAdministrativeLevelAndMatchesMapScoring() = withSchema { database ->
        val store = ObservationStore(database)
        val west = seedObservations(store)
        testApplication {
            application { statisticsApi(database) }
            val cities = client.statistics("level=city")
            assertEquals(3L, cities.total)
            assertEquals(listOf("XT-01-B", "XT-01-C", "XT-01-A"), cities.items.map { it.id })
            val alpha = cities.items.last()
            assertEquals("Alpha", alpha.name)
            assertEquals("Alpha", alpha.city)
            assertEquals("Central", alpha.state)
            assertEquals("Testland", alpha.country)
            assertEquals(5L, alpha.segmentCount)
            assertEquals(5 * MEASURED_KM, alpha.observedKm, 0.00001)
            assertEquals(56.4, alpha.averageSpeedKmh, 0.00001)
            assertEquals(1L, alpha.potholes)
            assertEquals(2L, alpha.speedBumps)

            val mapResponse = client.get("/v1/map/observations?bbox=-0.05,-0.05,0.1,0.1") {
                bearerAuth(READ_TOKEN)
            }
            assertEquals(HttpStatusCode.OK, mapResponse.status)
            val westScores = Json.parseToJsonElement(mapResponse.bodyAsText()).jsonObject.getValue("features")
                .jsonArray.map { it.jsonObject }
                .filter { it.getValue("id").jsonPrimitive.content in west }
                .map { it.getValue("properties").jsonObject.getValue("penalty_points").jsonPrimitive.long }
            assertEquals(listOf(0L, 0L, 13L, 37L, 50L), westScores.sorted())
            assertEquals(westScores.average(), alpha.averagePenaltyPoints)

            // Some measurements have state or country coverage only.
            val state = client.statistics("level=state").items.single()
            assertEquals("XT-01", state.id)
            assertEquals("Central", state.state)
            assertEquals("Testland", state.country)
            assertEquals(8L, state.segmentCount)
            assertEquals(13.5, state.averagePenaltyPoints)

            val country = client.statistics("level=country").items.single()
            assertEquals("XT", country.id)
            assertEquals("Testland", country.country)
            assertEquals(9L, country.segmentCount)
            assertEquals(9 * MEASURED_KM, country.observedKm, 0.00001)
        }
    }

    @Test
    fun ranksTheNearestNamedStreetAndUsesStablePaging() = withSchema { database ->
        val store = ObservationStore(database)
        testApplication {
            application { statisticsApi(database) }
            assertEquals(MapStatisticsPage(emptyList(), 0), client.statistics("level=street"))
            seedObservations(store)

            val streets = client.statistics("level=street&sort=coverage")
            assertEquals(2L, streets.total)
            val (alpha, beta) = streets.items
            assertEquals("Shared Road", alpha.name)
            assertEquals(alpha.name, beta.name)
            assertNotEquals(alpha.id, beta.id)
            assertEquals("Alpha", alpha.city)
            assertEquals("Beta", beta.city)
            assertEquals(3L, alpha.segmentCount)
            assertEquals(1L, beta.segmentCount)
            assertEquals(3 * MEASURED_KM, alpha.observedKm, 0.00001)
            assertEquals(64.0, alpha.averageSpeedKmh)
            assertEquals(29.0, alpha.averagePenaltyPoints)
            assertEquals(4.0, beta.averagePenaltyPoints)
            assertEquals(1L, alpha.potholes)
            assertEquals(2L, alpha.speedBumps)
            assertEquals(listOf(beta.id, alpha.id), client.statistics("level=street&sort=best").items.map { it.id })
            assertEquals(listOf(alpha.id, beta.id), client.statistics("level=street&sort=worst").items.map { it.id })

            val first = client.statistics("level=city&sort=coverage&limit=1&offset=1")
            val second = client.statistics("level=city&sort=coverage&limit=1&offset=2")
            assertEquals("XT-01-B", first.items.single().id)
            assertEquals("XT-01-C", second.items.single().id)
            assertEquals(3L, first.total)
            assertEquals(3L, second.total)
            assertEquals(MapStatisticsPage(emptyList(), 3), client.statistics("level=city&limit=1&offset=3"))
        }
    }

    private fun Application.statisticsApi(database: DataSource) {
        bumpMapApi(ObservationStore(database), RecordingStore(database), WRITE_TOKEN, READ_TOKEN)
    }

    private suspend fun HttpClient.statistics(query: String): MapStatisticsPage {
        val response = get("$STATISTICS_URL?$query") { bearerAuth(READ_TOKEN) }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        return Json.decodeFromString(body)
    }

    private fun seedObservations(store: ObservationStore): Set<String> {
        val recording = UUID.randomUUID().toString()
        val otherRecording = UUID.randomUUID().toString()
        fun segment(
            start: Double, end: Double, latitude: Double, speed: Double, roughness: Double?,
            potholes: Int = 0, bumps: Int = 0, recordingId: String = recording,
        ): Observation = Observation(
            "Feature", UUID.randomUUID().toString(),
            Json.decodeFromString("""{"type":"LineString","coordinates":[[$start,$latitude],[$end,$latitude]]}"""),
            ObservationProperties(
                kind = "segment", speedKmh = speed, quality = "good", score = 70, roughness = roughness,
                recordingId = recordingId, potholes = potholes, speedBumps = bumps,
            ),
        )

        val west = listOf(
            segment(0.0049, 0.0051, 0.00520, 75.0, 0.1), // About 22 m from Shared Road.
            segment(0.0049, 0.0051, 0.00520, 30.0, 1.60, 1, 1, otherRecording),
            segment(0.0069, 0.0071, 0.005, 87.0, 2.9, bumps = 1),
            segment(0.0049, 0.0051, 0.00523, 30.0, null), // About 25.4 m from Shared Road.
            segment(0.0099, 0.0101, 0.010, 60.0, 0.6), // Midpoint is on the two-city border.
        )
        val event = Observation(
            "Feature", UUID.randomUUID().toString(),
            Json.decodeFromString("""{"type":"Point","coordinates":[0.005,0.005]}"""),
            ObservationProperties(
                kind = "event", speedKmh = 30.0, eventType = "pothole", peak = 8.0, recordingId = recording,
            ),
        )
        val unrecorded = segment(0.0049, 0.0051, 0.005, 35.0, 0.9).let {
            it.copy(properties = it.properties.copy(recordingId = null, potholes = null, speedBumps = null))
        }
        assertTrue(store.save(west + listOf(
            segment(0.0149, 0.0151, 0.005, 90.0, 0.9),
            segment(0.0049, 0.0051, 0.025, 90.0, 0.9),
            segment(0.0249, 0.0251, 0.005, 60.0, 0.6),
            segment(0.0349, 0.0351, 0.005, 60.0, 0.6),
            segment(0.0599, 0.0601, 0.005, 15.0, 4.0, potholes = 10),
            unrecorded, event,
        )))
        return west.map { it.id }.toSet()
    }

    private fun withSchema(test: (DataSource) -> Unit) {
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            val schema = "map_stats_" + UUID.randomUUID().toString().replace("-", "")
            pool.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            }
            try {
                val original = pool.dataSource as PGSimpleDataSource
                val database = PGSimpleDataSource().apply {
                    setURL(original.getURL())
                    user = original.user
                    password = original.password
                    currentSchema = "$schema,public"
                }
                database.connection.use { connection ->
                    connection.createStatement().use { it.execute(FIXTURE_SQL) }
                }
                test(database)
            } finally {
                pool.connection.use { connection ->
                    connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                }
            }
        }
    }

    private companion object {
        const val WRITE_TOKEN = "synthetic-test-write-token-unused-outside-tests"
        const val READ_TOKEN = "synthetic-test-map-token-unused-outside-tests"
        const val STATISTICS_URL = "/v1/map/statistics"
        const val MEASURED_KM = 0.0222639
        val FIXTURE_SQL = """
            CREATE TABLE road_observations (LIKE public.road_observations INCLUDING ALL);
            CREATE TABLE map_admin_areas (
                id text PRIMARY KEY,
                level text NOT NULL CHECK (level IN ('country', 'state', 'city')),
                name text NOT NULL,
                parent_id text REFERENCES map_admin_areas,
                geom geometry(MultiPolygon, 4326) NOT NULL
            );
            CREATE INDEX ON map_admin_areas USING GIST (geom);
            CREATE TABLE map_street_ways (
                id text PRIMARY KEY,
                name text NOT NULL,
                geom geometry(LineString, 4326) NOT NULL
            );
            CREATE INDEX ON map_street_ways USING GIST ((geom::geography));
            INSERT INTO map_admin_areas
            SELECT id, level, name, parent_id, ST_Multi(ST_MakeEnvelope(xmin, ymin, xmax, ymax, 4326))
            FROM (VALUES
                ('XT',      'country', 'Testland', NULL,    -0.02, -0.02, 0.04, 0.04),
                ('XT-01',   'state',   'Central',  'XT',    -0.01, -0.01, 0.03, 0.03),
                ('XT-01-A', 'city',    'Alpha',    'XT-01',  0.00,  0.00, 0.01, 0.02),
                ('XT-01-B', 'city',    'Beta',     'XT-01',  0.01,  0.00, 0.02, 0.02),
                ('XT-01-C', 'city',    'Gamma',    'XT-01',  0.00,  0.02, 0.01, 0.03),
                ('XT-01-D', 'city',    'Empty',    'XT-01', -0.01,  0.00, 0.00, 0.02)
            ) AS bounds(id, level, name, parent_id, xmin, ymin, xmax, ymax);
            INSERT INTO map_street_ways VALUES
                ('1', 'Parallel Road', ST_GeomFromText('LINESTRING (0.0065 0.00516, 0.0075 0.00516)', 4326)),
                ('2', 'Shared Road',   ST_GeomFromText('LINESTRING (0.0045 0.005, 0.0055 0.005)', 4326)),
                ('3', 'Shared Road',   ST_GeomFromText('LINESTRING (0.0065 0.005, 0.0075 0.005)', 4326)),
                ('4', 'Shared Road',   ST_GeomFromText('LINESTRING (0.0145 0.005, 0.0155 0.005)', 4326)),
                ('5', '  ',           ST_GeomFromText('LINESTRING (0.0045 0.025, 0.0055 0.025)', 4326));
        """.trimIndent()
    }
}
