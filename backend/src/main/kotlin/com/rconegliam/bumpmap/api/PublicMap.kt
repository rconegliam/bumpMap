package com.rconegliam.bumpmap.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class PublicReads {
    private val queries = Semaphore(2)

    suspend fun <T : Any> run(call: ApplicationCall, query: () -> T): T? {
        if (!queries.tryAcquire()) {
            call.response.header(HttpHeaders.RetryAfter, "2")
            call.respond(HttpStatusCode.TooManyRequests, ApiError("map_busy"))
            return null
        }
        return try {
            withContext(Dispatchers.IO) { query() }
        } finally {
            queries.release()
        }
    }

    suspend inline fun <reified T : Any> respond(call: ApplicationCall, noinline query: () -> T) {
        run(call, query)?.let { call.respond(it) }
    }
}

internal fun Route.publicMapRoutes(store: ObservationStore, reads: PublicReads) {
    route("/v1/public/map/observations") {
        install(MapReadHeaders)
        get {
            val box = BoundingBox.parse(call.request.queryParameters["bbox"])
            val rawCursor = call.request.queryParameters["after"]
            val after = rawCursor?.let(::parseId)
            if (box == null || (rawCursor != null && after == null)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bbox_or_cursor"))
                return@get
            }
            val page = reads.run(call) { store.publicMap(box, after) } ?: return@get
            call.respondText(Json.encodeToString(page), ContentType("application", "geo+json"))
        }
    }
}

internal fun DataSource.readPublicMap(box: BoundingBox, after: UUID?): MapObservationPage =
    connection.use { connection ->
        connection.prepareStatement(publicMapSql).use { statement ->
            statement.queryTimeout = 10
            statement.setDouble(1, box.west)
            statement.setDouble(2, box.south)
            statement.setDouble(3, box.east)
            statement.setDouble(4, box.north)
            statement.setObject(5, after)
            statement.setObject(6, after)
            statement.setInt(7, PAGE_SIZE + 1)
            val features = statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val kind = rows.getString("kind")
                        val penalty = rows.getLong("penalty")
                        val speed = rows.getDouble("speed")
                        val properties = if (kind == "segment") {
                            MapProperties(
                                kind = kind,
                                speedKmh = speed,
                                potholes = rows.getInt("potholes"),
                                speedBumps = rows.getInt("speed_bumps"),
                                mapQuality = mapQuality(penalty),
                                penaltyPoints = penalty,
                            )
                        } else MapProperties(kind, speed, eventType = rows.getString("event_type"))
                        add(MapObservation(
                            "Feature",
                            rows.getString("id"),
                            Json.decodeFromString<Geometry>(rows.getString("geometry")),
                            properties,
                        ))
                    }
                }
            }
            val page = features.take(PAGE_SIZE)
            MapObservationPage("FeatureCollection", page, page.lastOrNull()?.id?.takeIf { features.size > PAGE_SIZE })
        }
    }

// Associate each measurement with a mapped road before generating public geometry or identifiers.
// Start with roads intersecting the viewport. The envelope of those roads includes every
// measurement that could map to them, so their averages are independent of the viewport.
private val publicMapSql = """
    WITH viewport AS (
        SELECT ST_MakeEnvelope(?, ?, ?, ?, 4326) AS geom
    ), visible AS MATERIALIZED (
        SELECT way.id, way.geom FROM map_street_ways way, viewport
        WHERE ST_Intersects(way.geom, viewport.geom)
    ), search_area AS (
        SELECT ST_SetSRID(ST_Expand(ST_Extent(geom), 0.001)::geometry, 4326) AS geom FROM visible
    ), measurements AS MATERIALIZED (
        SELECT ST_LineInterpolatePoint(observation.geometry, 0.5) AS midpoint,
               LEAST((props->>'speed_kmh')::double precision, 300) AS speed,
               (props->>'roughness')::double precision AS roughness,
               (props->>'score')::integer AS score,
               (props->>'potholes')::integer AS potholes,
               (props->>'speed_bumps')::integer AS speed_bumps
        FROM road_observations observation CROSS JOIN search_area
        CROSS JOIN LATERAL (SELECT observation.feature->'properties' AS props) input
        WHERE observation.geometry && search_area.geom
          AND ST_GeometryType(observation.geometry) = 'ST_LineString'
          AND props->>'kind' = 'segment' AND props->>'recording_id' IS NOT NULL
          AND props->>'potholes' IS NOT NULL AND props->>'speed_bumps' IS NOT NULL
          AND (props->>'roughness' IS NOT NULL OR props->>'score' IS NOT NULL)
    ), matched AS (
        SELECT way.id, measurements.*,
               CASE WHEN roughness IS NULL THEN score ELSE
                   ${roundPositive("100 * (1 - LEAST(1., GREATEST(0., (roughness - 0.6) / (2.5::double precision - 0.6))))")}
               END AS pavement_score
        FROM measurements
        JOIN LATERAL (
            SELECT way.id FROM map_street_ways way
            WHERE ST_DWithin(way.geom::geography, midpoint::geography, 35)
            ORDER BY ST_Distance(way.geom::geography, midpoint::geography), way.id COLLATE "C"
            LIMIT 1
        ) way ON true
    ), scored AS (
        SELECT *, (100 - LEAST(100, GREATEST(0, pavement_score))) * 0.25::double precision
               + (1 - LEAST(60., GREATEST(0., speed)) / 60) * 10 AS base_penalty
        FROM matched
    ), grouped AS (
        SELECT scored.id,
               AVG(speed) AS speed,
               LEAST(SUM(potholes), 2147483647)::integer AS potholes,
               LEAST(SUM(speed_bumps), 2147483647)::integer AS speed_bumps,
               ROUND(AVG(${roundPositive("base_penalty")} + potholes * 20::bigint + speed_bumps * 12::bigint))::bigint AS penalty
        FROM scored JOIN visible ON visible.id = scored.id
        GROUP BY scored.id
    ), features AS (
        SELECT md5('bumpmap/road/' || visible.id || '/' || layer.kind || COALESCE(layer.event_type, ''))::uuid AS id,
               layer.kind, layer.event_type, layer.geom, grouped.speed, grouped.potholes,
               grouped.speed_bumps, grouped.penalty
        FROM grouped JOIN visible USING (id)
        CROSS JOIN LATERAL (
            SELECT 'segment' AS kind, NULL::text AS event_type, visible.geom
            UNION ALL
            SELECT 'event', 'pothole', ST_LineInterpolatePoint(visible.geom, 0.45) WHERE grouped.potholes > 0
            UNION ALL
            SELECT 'event', 'speed_bump', ST_LineInterpolatePoint(visible.geom, 0.55) WHERE grouped.speed_bumps > 0
        ) layer
    )
    SELECT id, kind, event_type, ST_AsGeoJSON(features.geom) AS geometry, speed, potholes, speed_bumps, penalty
    FROM features, viewport
    WHERE (?::uuid IS NULL OR id > ?::uuid) AND ST_Intersects(features.geom, viewport.geom)
    ORDER BY id LIMIT ?
""".trimIndent()
