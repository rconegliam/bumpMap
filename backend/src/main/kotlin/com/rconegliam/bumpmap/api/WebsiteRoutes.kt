package com.rconegliam.bumpmap.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.resolveResource
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.websiteRoutes() {
    get("/") {
        call.respondWebsiteResource("web/index.html")
    }
    get("/assets/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty()
        if (segments.isEmpty() || segments.any { segment ->
            segment.isBlank() || segment == "." || segment == ".." ||
                segment.any { it == '/' || it == '\\' || it.isISOControl() }
        }) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondWebsiteResource("web/assets/${segments.joinToString("/")}")
    }
}

private suspend fun ApplicationCall.respondWebsiteResource(path: String) {
    val content = resolveResource(path)
    if (content == null) {
        respond(HttpStatusCode.NotFound)
    } else {
        response.header("X-Content-Type-Options", "nosniff")
        respond(content)
    }
}
