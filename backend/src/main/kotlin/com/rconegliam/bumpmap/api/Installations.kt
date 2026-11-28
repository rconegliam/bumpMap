package com.rconegliam.bumpmap.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private const val REGISTRATIONS_PER_HOUR = 300
private const val REQUESTS_PER_DAY = 8_000
private const val BYTES_PER_DAY = 128L * 1024 * 1024
private const val REGISTRATION_LOCK = 1_408_937_612
private val installationToken = Regex("[0-9a-f]{64}")

@Serializable
private data class Registration(val id: String)

internal fun Route.installationRoutes(store: InstallationStore) {
    route("/v1/installations") {
        install(RequestBodyLimit) { bodyLimit { 1024L } }
        post {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val authorization = call.request.headers[HttpHeaders.Authorization]
            val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
            if (token == null || !installationToken.matches(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_installation_token"))
                return@post
            }
            val id = parseId(call.receive<Registration>().id)
            if (id == null || id.version() != 4 || id.variant() != 2) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_installation_id"))
                return@post
            }
            when (withContext(Dispatchers.IO) { store.register(id, token) }) {
                InstallationResult.REGISTERED -> call.respond(HttpStatusCode.NoContent)
                InstallationResult.CONFLICT ->
                    call.respond(HttpStatusCode.Conflict, ApiError("installation_conflict"))
                InstallationResult.LIMITED -> {
                    call.response.header(HttpHeaders.RetryAfter, "60")
                    call.respond(HttpStatusCode.TooManyRequests, ApiError("installation_limit"))
                }
            }
        }
    }
}

internal enum class InstallationResult { REGISTERED, CONFLICT, LIMITED }

internal class InstallationStore(private val database: DataSource) {
    fun register(id: UUID, token: String): InstallationResult = database.connection.use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { lock ->
            lock.setLong(1, REGISTRATION_LOCK.toLong())
            lock.execute()
        }
        val digest = mapTokenDigest(token)
        val existing = connection.prepareStatement("SELECT token_digest FROM app_installations WHERE id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
        }
        val result = if (existing != null) {
            if (MessageDigest.isEqual(existing, digest)) InstallationResult.REGISTERED else InstallationResult.CONFLICT
        } else connection.prepareStatement(
            """
            INSERT INTO app_installations (id, token_digest)
            SELECT ?, ? WHERE (SELECT COUNT(*) FROM app_installations
                               WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '1 hour') < ?
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use {
            it.setObject(1, id)
            it.setBytes(2, digest)
            it.setInt(3, REGISTRATIONS_PER_HOUR)
            when {
                it.executeUpdate() == 1 -> InstallationResult.REGISTERED
                findInstallation(connection, digest) != null -> InstallationResult.CONFLICT
                else -> InstallationResult.LIMITED
            }
        }
        connection.commit()
        result
    }

    fun installationId(token: String): UUID? {
        if (!installationToken.matches(token)) return null
        return database.connection.use { findInstallation(it, mapTokenDigest(token)) }
    }

    private fun findInstallation(connection: Connection, digest: ByteArray): UUID? =
        connection.prepareStatement("SELECT id FROM app_installations WHERE token_digest = ?").use {
            it.setBytes(1, digest)
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }

    fun allowsWrite(id: UUID, bytes: Int = 0): Boolean = database.connection.use { connection ->
        connection.prepareStatement(
            """
            UPDATE app_installations SET
                requests = CASE WHEN usage_day = CURRENT_DATE THEN requests + 1 ELSE 1 END,
                bytes = CASE WHEN usage_day = CURRENT_DATE THEN bytes + ? ELSE ? END,
                usage_day = CURRENT_DATE
            WHERE id = ? AND (usage_day <> CURRENT_DATE OR (requests < ? AND bytes <= ?))
            """.trimIndent(),
        ).use {
            it.setInt(1, bytes)
            it.setInt(2, bytes)
            it.setObject(3, id)
            it.setInt(4, REQUESTS_PER_DAY)
            it.setLong(5, BYTES_PER_DAY - bytes)
            it.executeUpdate() == 1
        }
    }

    fun ownsRecording(installationId: UUID, id: UUID, name: String): Boolean =
        UUID.nameUUIDFromBytes("$installationId/${name.substringBeforeLast('.')}".encodeToByteArray()) == id

    fun ownsCompletedDetections(installationId: UUID, id: UUID): Boolean =
        database.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT file_name FROM completed_recording_files
                WHERE recording_id = ? AND kind = 'ndjson'
                """.trimIndent(),
            ).use {
                it.setObject(1, id)
                it.executeQuery().use { rows ->
                    rows.next() && ownsRecording(installationId, id, rows.getString(1))
                }
            }
        }
}
