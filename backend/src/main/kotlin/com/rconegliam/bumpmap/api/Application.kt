package com.rconegliam.bumpmap.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.reflect.TypeInfo
import java.nio.charset.Charset
import java.security.MessageDigest
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_BODY_BYTES = 256L * 1024
private val GEO_JSON = ContentType("application", "geo+json")

fun main() {
    val env = System.getenv()
    val token = env.getValue("BUMPMAP_API_TOKEN")
    require(token.length >= 32) { "BUMPMAP_API_TOKEN must be at least 32 characters" }
    val archiveLimit = env["BUMPMAP_ARCHIVE_MAX_BYTES"]?.toLong() ?: DEFAULT_ARCHIVE_MAX_BYTES
    require(archiveLimit > 0) { "BUMPMAP_ARCHIVE_MAX_BYTES must be positive" }
    val routingProvider = OsrmRoutingProvider.fromEnvironment(env)
    databasePool(env).use { pool ->
        migrate(pool)
        embeddedServer(Netty, host = "0.0.0.0", port = env["PORT"]?.toInt() ?: 8080) {
            bumpMapApi(
                ObservationStore(pool), RecordingStore(pool, archiveLimit), token,
                env["BUMPMAP_MAP_READ_TOKEN"], routingProvider, InstallationStore(pool),
            )
        }.start(wait = true)
    }
}

internal fun Application.bumpMapApi(
    store: ObservationStore,
    recordings: RecordingStore,
    token: String,
    mapReadToken: String? = null,
    routingProvider: OsrmRoutingProvider? = null,
    installations: InstallationStore? = null,
) {
    val tokenBytes = token.toByteArray()
    val mapReadDigest = mapReadToken?.takeUnless(String::isBlank)?.let(::mapTokenDigest)
    require(mapReadDigest == null || !MessageDigest.isEqual(mapReadDigest, mapTokenDigest(token))) {
        "BUMPMAP_MAP_READ_TOKEN must differ from BUMPMAP_API_TOKEN"
    }
    val logger = environment.log
    install(ContentNegotiation) {
        json(Json)
        register(GEO_JSON, GeoJsonConverter(KotlinxSerializationConverter(Json)))
    }
    install(Authentication) {
        bearer {
            authenticate { credential ->
                if (MessageDigest.isEqual(tokenBytes, credential.token.toByteArray())) UserIdPrincipal("pilot")
                else null
            }
        }
        bearer("write") {
            authenticate { credential ->
                if (MessageDigest.isEqual(tokenBytes, credential.token.toByteArray())) UserIdPrincipal("pilot")
                else withContext(Dispatchers.IO) {
                    installations?.installationId(credential.token)?.let { UserIdPrincipal(it.toString()) }
                }
            }
        }
        bearer("map-read") {
            realm = "bumpmap-map"
            authenticate { credential ->
                if (mapReadDigest != null && MessageDigest.isEqual(mapReadDigest, mapTokenDigest(credential.token))) {
                    UserIdPrincipal("map-reader")
                } else null
            }
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("expected_json"))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ApiError("body_too_large"))
        }
        exception<SQLException> { call, cause ->
            logger.warn("Database request failed (SQLState {})", cause.sqlState)
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("database_unavailable"))
        }
    }

    routing {
        websiteRoutes()
        mapRoutes(store)
        mapStatisticsRoutes(store)
        routingRoutes(routingProvider)
        val publicReads = PublicReads()
        publicMapRoutes(store, publicReads)
        mapStatisticsRoutes(store, publicReads)
        routingRoutes(routingProvider, public = true)
        installations?.let(::installationRoutes)
        get("/health") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (withContext(Dispatchers.IO) { store.ready() }) {
                call.respond(mapOf("status" to "ok"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("database_unavailable"))
            }
        }
        authenticate("write") {
            route("/v1/recordings/{id}/{kind}/{number}") {
                install(RequestBodyLimit) { bodyLimit { RECORDING_PART_BYTES.toLong() } }
                put {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (!call.request.contentType().match(ContentType.Application.OctetStream)) {
                        call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("expected_octet_stream"))
                        return@put
                    }
                    val id = call.parameters["id"]?.let(::parseId)
                    val number = call.parameters["number"]?.toIntOrNull()
                    val partCount = call.request.queryParameters["parts"]?.toIntOrNull()
                    if (id == null || number == null || partCount == null) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_recording_part"))
                        return@put
                    }
                    val part = RecordingPart(
                        id, call.parameters["kind"].orEmpty(), number, partCount,
                        call.request.queryParameters["name"].orEmpty(),
                    )
                    if (!part.valid()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_recording_part"))
                        return@put
                    }
                    val installationId = call.principal<UserIdPrincipal>()?.name?.let(::parseId)
                    if (installationId != null && !checkNotNull(installations).ownsRecording(installationId, id, part.fileName)) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("recording_not_owned"))
                        return@put
                    }
                    val bytes = call.receive<ByteArray>()
                    if (!part.validSize(bytes.size)) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_recording_part_size"))
                    } else {
                        val result = withContext(Dispatchers.IO) {
                            if (installationId != null && !checkNotNull(installations).allowsWrite(installationId, bytes.size)) {
                                return@withContext null
                            }
                            recordings.save(part, bytes)
                        }
                        when (result) {
                            null -> call.respond(HttpStatusCode.TooManyRequests, ApiError("installation_upload_limit"))
                            RecordingSaveResult.STORED -> call.respond(HttpStatusCode.NoContent)
                            RecordingSaveResult.CONFLICT ->
                                call.respond(HttpStatusCode.Conflict, ApiError("recording_part_conflict"))
                            RecordingSaveResult.MISSING_EARLIER_PARTS ->
                                call.respond(HttpStatusCode.PreconditionFailed, ApiError("recording_parts_missing"))
                            RecordingSaveResult.ARCHIVE_FULL ->
                                call.respond(HttpStatusCode.InsufficientStorage, ApiError("recording_archive_full"))
                        }
                    }
                }
            }
        }
        authenticate {
            get("/v1/observations") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val box = BoundingBox.parse(call.request.queryParameters["bbox"])
                val rawCursor = call.request.queryParameters["after"]
                val after = rawCursor?.let(::parseId)
                val fromRecordings = call.request.queryParameters["from_recordings"] == "true"
                if (box == null || (rawCursor != null && after == null)) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bbox_or_cursor"))
                    return@get
                }
                call.respond(withContext(Dispatchers.IO) { store.lookup(box, after, fromRecordings) })
            }
        }
        authenticate("write") {
            route("/v1/observations") {
                install(RequestBodyLimit) { bodyLimit { MAX_BODY_BYTES } }
                post {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val contentType = call.request.contentType()
                    if (!contentType.match(ContentType.Application.Json) && !contentType.match(GEO_JSON)) {
                        call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("expected_json"))
                        return@post
                    }
                    val batch = call.receive<ObservationBatch>()
                    if (batch.type != "FeatureCollection" || batch.features.size !in 1..MAX_BATCH_SIZE ||
                        batch.features.any { !it.valid() } ||
                        batch.features.distinctBy { it.id }.size != batch.features.size
                    ) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_observations"))
                        return@post
                    }
                    val installationId = call.principal<UserIdPrincipal>()?.name?.let(::parseId)
                    if (installationId != null) {
                        val installationStore = checkNotNull(installations)
                        val recordingId = batch.features.map { it.properties.recordingId }.distinct()
                            .singleOrNull()?.let(::parseId)
                        val owns = recordingId != null && withContext(Dispatchers.IO) {
                            installationStore.ownsCompletedDetections(installationId, recordingId)
                        }
                        if (!owns) {
                            call.respond(HttpStatusCode.Forbidden, ApiError("recording_not_owned"))
                            return@post
                        }
                        if (!withContext(Dispatchers.IO) { installationStore.allowsWrite(installationId) }) {
                            call.respond(HttpStatusCode.TooManyRequests, ApiError("installation_upload_limit"))
                            return@post
                        }
                    }
                    if (withContext(Dispatchers.IO) { store.save(batch.features) }) {
                        call.respond(AcceptedObservations(batch.features.size))
                    } else {
                        call.respond(HttpStatusCode.Conflict, ApiError("observation_id_conflict"))
                    }
                }
            }
        }
    }
}

private class GeoJsonConverter(private val json: ContentConverter) : ContentConverter by json {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        val responseType = if (value is ObservationPage) contentType else ContentType.Application.Json
        return json.serialize(responseType, charset, typeInfo, value)
    }
}

@Serializable
internal data class ApiError(val error: String)

@Serializable
private data class AcceptedObservations(val accepted: Int)
