package com.rconegliam.bumpmap.api

import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json

internal class ObservationStore(private val database: DataSource) {
    fun ready(): Boolean = database.connection.use { it.isValid(2) }

    fun mapStatistics(query: MapStatisticsQuery): MapStatisticsPage = database.readMapStatistics(query)

    fun save(features: List<Observation>): Boolean {
        val json = Json.encodeToString(features)
        return database.connection.use { connection ->
            connection.autoCommit = false
            val stored = connection.prepareStatement(
                """
                INSERT INTO road_observations (id, feature, geometry)
                SELECT (feature->>'id')::uuid, feature, ST_GeomFromGeoJSON(feature->'geometry')
                FROM jsonb_array_elements(?::jsonb) AS input(feature)
                ORDER BY (feature->>'id')::uuid
                ON CONFLICT (id) DO UPDATE SET feature = EXCLUDED.feature
                WHERE road_observations.feature = EXCLUDED.feature
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, json)
                statement.executeUpdate() == features.size
            }
            if (stored) connection.commit() else connection.rollback()
            stored
        }
    }

    fun lookup(box: BoundingBox, after: UUID?, fromRecordings: Boolean): ObservationPage {
        val features = database.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT feature FROM road_observations
                WHERE ST_Intersects(geometry, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                  AND (?::uuid IS NULL OR id > ?::uuid)
                  AND (NOT ? OR feature->'properties'->>'recording_id' IS NOT NULL)
                ORDER BY id LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setDouble(1, box.west)
                statement.setDouble(2, box.south)
                statement.setDouble(3, box.east)
                statement.setDouble(4, box.north)
                statement.setObject(5, after)
                statement.setObject(6, after)
                statement.setBoolean(7, fromRecordings)
                statement.setInt(8, PAGE_SIZE + 1)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(Json.decodeFromString<Observation>(rows.getString(1)))
                    }
                }
            }
        }
        val page = features.take(PAGE_SIZE)
        return ObservationPage(
            type = "FeatureCollection",
            features = page,
            nextCursor = if (features.size > PAGE_SIZE) page.last().id else null,
        )
    }
}
