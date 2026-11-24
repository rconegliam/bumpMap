package com.rconegliam.bumpmap.api

import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ObservationApiTest {
    @Test
    fun acceptsAndServesGeoJsonMediaType() {
        val token = "synthetic-token-for-local-tests-only"
        val id = UUID.randomUUID().toString()
        val geoJson = ContentType.parse("application/geo+json")
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            testApplication {
                application { bumpMapApi(ObservationStore(pool), RecordingStore(pool), token) }
                val submission = client.post("/v1/observations") {
                    bearerAuth(token)
                    contentType(geoJson)
                    accept(geoJson)
                    setBody("""{"type":"FeatureCollection","features":[${pointFeature(id)}]}""")
                }
                assertEquals(HttpStatusCode.OK, submission.status)
                assertEquals(ContentType.Application.Json, submission.contentType()?.withoutParameters())
                assertEquals("""{"accepted":1}""", submission.bodyAsText())

                val response = client.get("/v1/observations?bbox=-46.88,-23.52,-46.87,-23.50") {
                    bearerAuth(token)
                    accept(geoJson)
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(geoJson, response.contentType()?.withoutParameters())
                assertTrue(Json.decodeFromString<ObservationPage>(response.bodyAsText()).features.any { it.id == id })

                val invalidBox = client.get("/v1/observations?bbox=1,2,3,4") {
                    bearerAuth(token)
                    accept(geoJson)
                }
                assertEquals(HttpStatusCode.BadRequest, invalidBox.status)
                assertEquals(ContentType.Application.Json, invalidBox.contentType()?.withoutParameters())
                assertEquals("""{"error":"invalid_bbox_or_cursor"}""", invalidBox.bodyAsText())
            }
        }
    }

    @Test
    fun savesRetriesAndFiltersObservationsInPostgis() {
        val token = "synthetic-token-for-local-tests-only"
        val lineId = UUID.randomUUID().toString()
        val pointId = UUID.randomUUID().toString()
        val rolledBackId = UUID.randomUUID().toString()
        val point = pointFeature(pointId)
        val line = """
            {"type":"Feature","id":"$lineId",
            "geometry":{"type":"LineString","coordinates":[[-46.8772,-23.5112],[-46.8771,-23.5110]]},
            "properties":{"kind":"segment","quality":"good","score":96,"roughness":0.2,"speed_kmh":36.0}}
        """.trimIndent()
        val bbox = "/v1/observations?bbox=-46.88,-23.52,-46.87,-23.50"
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            testApplication {
                application { bumpMapApi(ObservationStore(pool), RecordingStore(pool), token) }
                suspend fun save(vararg features: String) = client.post("/v1/observations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"FeatureCollection","features":[${features.joinToString()}]}""")
                }
                suspend fun read(url: String) =
                    Json.decodeFromString<ObservationPage>(client.get(url) { bearerAuth(token) }.bodyAsText())

                assertEquals(HttpStatusCode.OK, client.get("/health").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get(bbox).status)
                assertEquals(HttpStatusCode.BadRequest,
                    client.get("/v1/observations?bbox=1,2,3,4") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.BadRequest, save(point, point).status)
                assertEquals(HttpStatusCode.BadRequest, save(point.replace("-46.8772", "181.0")).status)
                assertEquals(HttpStatusCode.BadRequest, save(point.replace("speed_kmh", "user_trip_id")).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, save(" ".repeat(256 * 1024)).status)

                assertEquals(HttpStatusCode.OK, save(line, point).status)
                assertEquals(HttpStatusCode.OK, save(point, line).status)
                assertEquals(HttpStatusCode.Conflict,
                    save(pointFeature(rolledBackId), point.replace("pothole", "speed_bump")).status)

                val result = read(bbox)
                assertEquals("FeatureCollection", result.type)
                assertTrue(result.features.any { it.id == lineId && it.geometry.type == "LineString" })
                assertTrue(result.features.any { it.id == pointId && it.properties.eventType == "pothole" })
                assertFalse(result.features.any { it.id == rolledBackId })
                val returnedFeatures = Json.parseToJsonElement(
                    client.get(bbox) { bearerAuth(token) }.bodyAsText(),
                ).jsonObject.getValue("features").jsonArray
                val returnedPoint = returnedFeatures.single { it.jsonObject["id"]?.jsonPrimitive?.content == pointId }
                assertEquals(
                    Json.parseToJsonElement(point).jsonObject["properties"],
                    returnedPoint.jsonObject["properties"],
                )
                val otherArea = read("/v1/observations?bbox=-46.76,-23.60,-46.75,-23.59")
                assertFalse(otherArea.features.any { it.id in setOf(lineId, pointId) })
            }
        }
    }

    private fun pointFeature(id: String): String = """
        {"type":"Feature","id":"$id",
        "geometry":{"type":"Point","coordinates":[-46.8772,-23.5112]},
        "properties":{"kind":"event","type":"pothole","peak":5.1,"speed_kmh":28.0}}
    """.trimIndent()
}
