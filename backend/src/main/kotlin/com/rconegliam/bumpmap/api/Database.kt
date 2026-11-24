package com.rconegliam.bumpmap.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

internal fun databasePool(env: Map<String, String>): HikariDataSource {
    val postgres = PGSimpleDataSource().apply {
        serverNames = arrayOf(env.getValue("PGHOST"))
        portNumbers = intArrayOf(env["PGPORT"]?.toInt() ?: 5432)
        databaseName = env.getValue("PGDATABASE")
        user = env.getValue("PGUSER")
        password = env.getValue("PGPASSWORD")
        connectTimeout = 5
        socketTimeout = 10
    }
    return HikariDataSource(HikariConfig().apply {
        dataSource = postgres
        maximumPoolSize = 4
        minimumIdle = 0
        connectionTimeout = 3_000
    })
}

internal fun migrate(database: DataSource) {
    Flyway.configure().dataSource(database).load().migrate()
}
