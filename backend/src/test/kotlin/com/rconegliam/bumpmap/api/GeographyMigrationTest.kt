package com.rconegliam.bumpmap.api

import db.migration.V6__seed_pilot_geography
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.migration.Context
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeographyMigrationTest {
    @Test
    fun loadsValidPilotDataAndMatchesSyntheticObservationPoints() {
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            pool.connection.use { connection ->
                assertEquals(
                    listOf("BR", "BR-SP", "BR-SP-3505708", "BR-SP-3550308"),
                    connection.createStatement().use { sql ->
                        sql.executeQuery("SELECT id FROM map_admin_areas ORDER BY id").use { rows ->
                            buildList { while (rows.next()) add(rows.getString(1)) }
                        }
                    }
                )
                assertEquals("BR-SP-3505708", adminArea(connection, -46.876, -23.511))
                assertEquals("BR-SP-3550308", adminArea(connection, -46.634, -23.550))
                assertEquals("BR-SP", adminArea(connection, -47.061, -22.906))
                assertEquals("BR", adminArea(connection, -43.94, -19.92))
                assertNull(adminArea(connection, -58.382, -34.604))

                assertNotNull(nearbyWay(connection, -46.876, -23.511), "Barueri")
                assertNotNull(nearbyWay(connection, -46.634, -23.550), "São Paulo")
                assertNull(nearbyWay(connection, -47.061, -22.906), "Campinas is outside pilot road coverage")

                assertEquals(4, allValid(connection, "map_admin_areas", "MULTIPOLYGON"))
                assertEquals(99_324, allValid(connection, "map_street_ways", "LINESTRING"))
            }
        }
    }

    @Test
    fun seedUpsertsTheSameRowsOnReplay() {
        databasePool(System.getenv()).use { pool ->
            migrate(pool)
            pool.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val roadCount = allValid(connection, "map_street_ways", "LINESTRING")
                    connection.createStatement().use { sql ->
                        assertEquals(1, sql.executeUpdate("UPDATE map_admin_areas SET name = 'synthetic' WHERE id = 'BR'"))
                    }
                    V6__seed_pilot_geography().migrate(object : Context {
                        override fun getConfiguration() = Flyway.configure().dataSource(pool)
                        override fun getConnection() = connection
                    })
                    assertEquals(roadCount, allValid(connection, "map_street_ways", "LINESTRING"))
                    connection.createStatement().use { sql ->
                        sql.executeQuery("SELECT name FROM map_admin_areas WHERE id = 'BR'").use { rows ->
                            assertTrue(rows.next())
                            assertEquals("Brasil", rows.getString(1))
                        }
                    }
                } finally {
                    connection.rollback()
                }
            }
        }
    }

    private fun allValid(connection: Connection, table: String, type: String): Int =
        connection.createStatement().use { sql ->
            sql.executeQuery(
                """
                SELECT count(*), bool_and(
                    ST_SRID(geom) = 4326 AND GeometryType(geom) = '$type'
                    AND ST_IsValid(geom) AND NOT ST_IsEmpty(geom) AND name <> ''
                ) FROM $table
                """.trimIndent()
            ).use { rows ->
                assertTrue(rows.next())
                assertTrue(rows.getBoolean(2), "$table contains invalid or unnamed features")
                rows.getInt(1)
            }
        }

    private fun adminArea(connection: Connection, lon: Double, lat: Double) = pointLookup(
        connection, lon, lat,
        """
        SELECT id FROM map_admin_areas AS area CROSS JOIN point
        WHERE ST_Covers(area.geom, point.geom)
        ORDER BY CASE level WHEN 'city' THEN 1 WHEN 'state' THEN 2 ELSE 3 END, id
        LIMIT 1
        """.trimIndent()
    )

    private fun nearbyWay(connection: Connection, lon: Double, lat: Double) = pointLookup(
        connection, lon, lat,
        """
        SELECT name FROM map_street_ways AS way CROSS JOIN point
        WHERE ST_DWithin(way.geom::geography, point.geom::geography, 100)
        ORDER BY ST_Distance(way.geom::geography, point.geom::geography), id
        LIMIT 1
        """.trimIndent()
    )

    private fun pointLookup(connection: Connection, lon: Double, lat: Double, query: String): String? =
        connection.prepareStatement("WITH point AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geom) $query").use { sql ->
            sql.setDouble(1, lon)
            sql.setDouble(2, lat)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
}
