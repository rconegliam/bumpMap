package com.rconegliam.bumpmap.api

import java.sql.ResultSet
import javax.sql.DataSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class MapStatisticsPage(val items: List<MapStatisticsItem>, val total: Long)

@Serializable
internal data class MapStatisticsItem(
    val id: String,
    val name: String,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    @SerialName("segment_count") val segmentCount: Long,
    @SerialName("observed_km") val observedKm: Double,
    @SerialName("average_speed_kmh") val averageSpeedKmh: Double,
    @SerialName("average_penalty_points") val averagePenaltyPoints: Double,
    val potholes: Long,
    @SerialName("speed_bumps") val speedBumps: Long,
)

internal fun DataSource.readMapStatistics(query: MapStatisticsQuery): MapStatisticsPage =
    connection.use { connection ->
        connection.prepareStatement(statisticsSql(query)).use { statement ->
            statement.queryTimeout = 15
            val areaLevel = if (query.level == MapStatisticsLevel.STREET) MapStatisticsLevel.CITY else query.level
            statement.setString(1, areaLevel.parameter)
            statement.setInt(2, query.limit)
            statement.setInt(3, query.offset)
            statement.executeQuery().use { rows ->
                check(rows.next()) // The total is returned even when the requested page is empty.
                val total = rows.getLong("total")
                val items = buildList {
                    do {
                        if (rows.getString("area_id") != null) add(rows.statisticsItem(query.level))
                    } while (rows.next())
                }
                MapStatisticsPage(items, total)
            }
        }
    }

private fun ResultSet.statisticsItem(level: MapStatisticsLevel): MapStatisticsItem {
    val areaId = getString("area_id")
    val areaName = getString("area_name")
    val parent = getString("parent_name")
    val grandparent = getString("grandparent_name")
    val street = getString("street_name")
    return MapStatisticsItem(
        id = street?.let { "$areaId:$it" } ?: areaId,
        name = street ?: areaName,
        city = areaName.takeIf { level == MapStatisticsLevel.CITY || level == MapStatisticsLevel.STREET },
        state = when (level) {
            MapStatisticsLevel.CITY, MapStatisticsLevel.STREET -> parent
            MapStatisticsLevel.STATE -> areaName
            MapStatisticsLevel.COUNTRY -> null
        },
        country = when (level) {
            MapStatisticsLevel.CITY, MapStatisticsLevel.STREET -> grandparent
            MapStatisticsLevel.STATE -> parent
            MapStatisticsLevel.COUNTRY -> areaName
        },
        segmentCount = getLong("segment_count"),
        observedKm = getDouble("observed_km"),
        averageSpeedKmh = getDouble("average_speed_kmh"),
        averagePenaltyPoints = getDouble("average_penalty_points"),
        potholes = getLong("potholes"),
        speedBumps = getLong("speed_bumps"),
    )
}

private fun statisticsSql(query: MapStatisticsQuery): String {
    val matchStreets = query.level == MapStatisticsLevel.STREET
    val streetName = if (matchStreets) "street.name" else "NULL::text"
    val streetMatch = if (matchStreets) STREET_MATCH else ""
    val order = "${query.sort.order}, area_id COLLATE \"C\", street_name COLLATE \"C\""
    return """
        WITH matched AS (
            SELECT area.id AS area_id, $streetName AS street_name,
                   ST_Length(observation.geometry::geography) AS length_metres,
                   LEAST((props.value->>'speed_kmh')::double precision, 300) AS speed_kmh,
                   (props.value->>'roughness')::double precision AS roughness,
                   (props.value->>'score')::integer AS score,
                   (props.value->>'potholes')::integer AS potholes,
                   (props.value->>'speed_bumps')::integer AS speed_bumps
            FROM road_observations observation
            CROSS JOIN LATERAL (SELECT observation.feature->'properties' AS value) props
            CROSS JOIN LATERAL (
                SELECT CASE WHEN ST_GeometryType(observation.geometry) = 'ST_LineString'
                            THEN ST_LineInterpolatePoint(observation.geometry, 0.5) END AS geom
            ) midpoint
            JOIN LATERAL (
                SELECT area.id, area.geom
                FROM map_admin_areas area
                WHERE area.level = ? AND ST_Covers(area.geom, midpoint.geom)
                ORDER BY area.id COLLATE "C"
                LIMIT 1
            ) area ON true
            $streetMatch
            WHERE props.value->>'kind' = 'segment'
              AND props.value->>'recording_id' IS NOT NULL
              AND props.value->>'potholes' IS NOT NULL
              AND props.value->>'speed_bumps' IS NOT NULL
              AND (props.value->>'roughness' IS NOT NULL OR props.value->>'score' IS NOT NULL)
        ), calibrated AS (
            SELECT *, COALESCE(
                ${roundPositive("roughness_score")}, score
            ) AS pavement_score
            FROM matched
            CROSS JOIN LATERAL (
                SELECT CASE WHEN roughness IS NOT NULL
                            THEN 100 * (1 - LEAST(1., GREATEST(0., (roughness - 0.6) / (2.5::double precision - 0.6))))
                       END AS roughness_score
            ) AS calibration
        ), scored AS (
            SELECT *, (100 - LEAST(100, GREATEST(0, pavement_score))) * 0.25::double precision
                      + (1 - LEAST(60., GREATEST(0., speed_kmh)) / 60) * 10 AS base_penalty
            FROM calibrated
        ), grouped AS MATERIALIZED (
            SELECT area_id, street_name, COUNT(*) AS segment_count, SUM(length_metres) / 1000 AS observed_km,
                   AVG(speed_kmh) AS average_speed_kmh,
                   AVG(${roundPositive("base_penalty")} + potholes * 20::bigint + speed_bumps * 12::bigint)
                       AS average_penalty_points,
                   SUM(potholes) AS potholes, SUM(speed_bumps) AS speed_bumps
            FROM scored
            GROUP BY area_id, street_name
        )
        SELECT totals.total, page.*, area.name AS area_name, parent.name AS parent_name,
               grandparent.name AS grandparent_name
        FROM (SELECT COUNT(*) AS total FROM grouped) totals
        LEFT JOIN (SELECT * FROM grouped ORDER BY $order LIMIT ? OFFSET ?) page ON true
        LEFT JOIN map_admin_areas area ON area.id = page.area_id
        LEFT JOIN map_admin_areas parent ON parent.id = area.parent_id
        LEFT JOIN map_admin_areas grandparent ON grandparent.id = parent.parent_id
        ORDER BY $order
    """.trimIndent()
}

// Match Kotlin's roundToInt for nonnegative scores, including ties, without rounding the fraction first.
internal fun roundPositive(value: String): String = "(FLOOR($value)::int + ($value - FLOOR($value) >= 0.5)::int)"

// ST_DWithin can use a GiST index on geom::geography. Use exact metres to select one of the nearby ways.
private val STREET_MATCH = """
    JOIN LATERAL (
        SELECT btrim(way.name) AS name
        FROM map_street_ways way
        WHERE btrim(way.name) <> ''
          AND ST_DWithin(way.geom::geography, midpoint.geom::geography, 25)
          AND ST_Intersects(way.geom, area.geom)
        ORDER BY ST_Distance(way.geom::geography, midpoint.geom::geography), way.id COLLATE "C"
        LIMIT 1
    ) street ON true
""".trimIndent()
