package com.rconegliam.bumpmap.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val MapReadHeaders = createRouteScopedPlugin("MapReadHeaders") {
    onCall { call ->
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
    }
}

internal fun mapTokenDigest(token: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())

internal fun Route.mapRoutes(store: ObservationStore) {
    route("/v1/map/observations") {
        install(MapReadHeaders)
        authenticate("map-read") {
            get {
                val box = BoundingBox.parse(call.request.queryParameters["bbox"])
                val rawCursor = call.request.queryParameters["after"]
                val after = rawCursor?.let(::parseId)
                if (box == null || (rawCursor != null && after == null)) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bbox_or_cursor"))
                    return@get
                }
                val page = withContext(Dispatchers.IO) { store.lookup(box, after, fromRecordings = true) }
                val result = MapObservationPage(page.type, page.features.map(::mapObservation), page.nextCursor)
                call.respondText(Json.encodeToString(result), ContentType("application", "geo+json"))
            }
        }
    }
}

@Serializable
private data class MapObservationPage(
    val type: String,
    val features: List<MapObservation>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
private data class MapObservation(
    val type: String,
    val id: String,
    val geometry: Geometry,
    val properties: MapProperties,
)

@Serializable
private data class MapProperties(
    val kind: String,
    @SerialName("speed_kmh") val speedKmh: Double,
    val quality: String? = null,
    val score: Int? = null,
    val roughness: Double? = null,
    @SerialName("type") val eventType: String? = null,
    val peak: Double? = null,
    val potholes: Int? = null,
    @SerialName("speed_bumps") val speedBumps: Int? = null,
    @SerialName("map_quality") val mapQuality: String? = null,
    @SerialName("penalty_points") val penaltyPoints: Long? = null,
)

private fun mapObservation(feature: Observation): MapObservation {
    val properties = feature.properties
    val pavementScore = properties.roughness?.let {
        (100 * (1 - ((it - 0.6) / (2.5 - 0.6)).coerceIn(0.0, 1.0))).roundToInt()
    } ?: properties.score
    val penaltyPoints = if (
        feature.geometry.type == "LineString" && properties.speedKmh.isFinite() && properties.speedKmh >= 0 &&
        pavementScore != null && properties.potholes != null && properties.speedBumps != null
    ) {
        mapPenaltyPoints(pavementScore, properties.speedKmh, properties.potholes, properties.speedBumps)
    } else null
    return MapObservation(
        feature.type, feature.id, feature.geometry,
        MapProperties(
            kind = properties.kind,
            speedKmh = properties.speedKmh,
            quality = properties.quality,
            score = properties.score,
            roughness = properties.roughness,
            eventType = properties.eventType,
            peak = properties.peak,
            potholes = properties.potholes,
            speedBumps = properties.speedBumps,
            mapQuality = penaltyPoints?.let(::mapQuality),
            penaltyPoints = penaltyPoints,
        ),
    )
}

internal fun mapPenaltyPoints(score: Int, speedKmh: Double, potholes: Int, speedBumps: Int): Long =
    ((100 - score.coerceIn(0, 100)) * 0.25 +
        (1.0 - speedKmh.coerceIn(0.0, 60.0) / 60.0) * 10).roundToInt() +
        potholes * 20L + speedBumps * 12L

internal fun mapQuality(penaltyPoints: Long): String = when {
    penaltyPoints < 8 -> "good"
    penaltyPoints < 20 -> "fair"
    penaltyPoints < 40 -> "poor"
    else -> "bad"
}
