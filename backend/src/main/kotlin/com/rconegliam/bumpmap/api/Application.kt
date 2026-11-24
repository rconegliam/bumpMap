package com.rconegliam.bumpmap.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private const val MAX_BODY_BYTES = 256L * 1024

fun main() {
    val env = System.getenv()
    val token = env.getValue("BUMPMAP_API_TOKEN")
    require(token.length >= 32) { "BUMPMAP_API_TOKEN must be at least 32 characters" }
    databasePool(env).use { pool ->
        migrate(pool)
        embeddedServer(Netty, host = "0.0.0.0", port = env["PORT"]?.toInt() ?: 8080) {
            bumpMapApi(ObservationStore(pool), token)
        }.start(wait = true)
    }
}

internal fun Application.bumpMapApi(store: ObservationStore, token: String) {
    val tokenBytes = token.toByteArray()
    val logger = environment.log
    install(ContentNegotiation) { json() }
    install(Authentication) {
        bearer {
            authenticate { credential ->
                if (MessageDigest.isEqual(tokenBytes, credential.token.toByteArray())) UserIdPrincipal("pilot")
                else null
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
        get("/health") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (withContext(Dispatchers.IO) { store.ready() }) {
                call.respond(mapOf("status" to "ok"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiError("database_unavailable"))
            }
        }
        authenticate {
            route("/v1/observations") {
                install(RequestBodyLimit) { bodyLimit { MAX_BODY_BYTES } }
                get {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val box = BoundingBox.parse(call.request.queryParameters["bbox"])
                    val rawCursor = call.request.queryParameters["after"]
                    val after = rawCursor?.let(::parseId)
                    if (box == null || (rawCursor != null && after == null)) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_bbox_or_cursor"))
                        return@get
                    }
                    call.respond(withContext(Dispatchers.IO) { store.lookup(box, after) })
                }
                post {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (!call.request.contentType().match(ContentType.Application.Json)) {
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

@Serializable
private data class ApiError(val error: String)

@Serializable
private data class AcceptedObservations(val accepted: Int)
