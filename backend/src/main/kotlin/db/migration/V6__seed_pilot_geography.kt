package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.postgresql.PGConnection
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.GZIPInputStream

class V6__seed_pilot_geography : BaseJavaMigration() {
    private val areas = "/geography/v6-admin-areas.csv.gz"
    private val ways = "/geography/v6-street-ways.csv.gz"

    override fun getChecksum(): Int {
        val checksum = CRC32()
        for (path in listOf(areas, ways)) {
            CheckedInputStream(resource(path), checksum).use { input ->
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) Unit
            }
        }
        return checksum.value.toInt()
    }

    override fun migrate(context: Context) {
        val connection = context.connection
        val copy = connection.unwrap(PGConnection::class.java).copyAPI
        connection.createStatement().use { sql ->
            sql.execute("CREATE TEMP TABLE pilot_admin_areas (LIKE map_admin_areas) ON COMMIT DROP")
            GZIPInputStream(resource(areas)).use { input ->
                copy.copyIn("COPY pilot_admin_areas (id, level, name, parent_id, geom) FROM STDIN WITH (FORMAT csv)", input)
            }
            sql.executeUpdate(
                """
                INSERT INTO map_admin_areas (id, level, name, parent_id, geom)
                SELECT id, level, name, parent_id, geom FROM pilot_admin_areas
                ON CONFLICT (id) DO UPDATE
                SET level = EXCLUDED.level, name = EXCLUDED.name, parent_id = EXCLUDED.parent_id, geom = EXCLUDED.geom
                """.trimIndent()
            )
            sql.execute("DROP TABLE pilot_admin_areas")

            sql.execute("CREATE TEMP TABLE pilot_street_ways (LIKE map_street_ways) ON COMMIT DROP")
            GZIPInputStream(resource(ways)).use { input ->
                copy.copyIn("COPY pilot_street_ways (id, name, geom) FROM STDIN WITH (FORMAT csv)", input)
            }
            sql.executeUpdate(
                """
                INSERT INTO map_street_ways (id, name, geom)
                SELECT id, name, geom FROM pilot_street_ways
                ON CONFLICT (id) DO UPDATE
                SET name = EXCLUDED.name, geom = EXCLUDED.geom
                """.trimIndent()
            )
            sql.execute("DROP TABLE pilot_street_ways")
            sql.execute("ANALYZE map_admin_areas")
            sql.execute("ANALYZE map_street_ways")
        }
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream(path)) {
        "Missing packaged geography snapshot: $path"
    }
}
