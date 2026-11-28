package com.rconegliam.bumpmap.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MapStatisticsHeaders = createRouteScopedPlugin("MapStatisticsHeaders") {
    onCall { call ->
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
    }
}

internal fun Route.mapStatisticsRoutes(store: ObservationStore) {
    route("/v1/map/statistics") {
        install(MapStatisticsHeaders)
        authenticate("map-read") {
            get {
                val query = MapStatisticsQuery.parse(call.request.queryParameters)
                if (query == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_statistics_query"))
                    return@get
                }
                call.respond(withContext(Dispatchers.IO) { store.mapStatistics(query) })
            }
        }
    }
}

internal enum class MapStatisticsLevel(val parameter: String) {
    CITY("city"), STATE("state"), COUNTRY("country"), STREET("street"),
}

internal enum class MapStatisticsSort(val parameter: String, val order: String) {
    BEST("best", "average_penalty_points ASC"),
    WORST("worst", "average_penalty_points DESC"),
    COVERAGE("coverage", "segment_count DESC"),
}

internal data class MapStatisticsQuery(
    val level: MapStatisticsLevel,
    val sort: MapStatisticsSort,
    val limit: Int,
    val offset: Int,
) {
    companion object {
        fun parse(parameters: Parameters): MapStatisticsQuery? {
            val level = MapStatisticsLevel.entries.find { it.parameter == parameters["level"] } ?: return null
            val sort = MapStatisticsSort.entries.find {
                it.parameter == (parameters["sort"] ?: "best")
            } ?: return null
            val limit = (parameters["limit"] ?: "50").toIntOrNull() ?: return null
            val offset = (parameters["offset"] ?: "0").toIntOrNull() ?: return null
            if (limit !in 1..100 || offset < 0) return null
            return MapStatisticsQuery(level, sort, limit, offset)
        }
    }
}
