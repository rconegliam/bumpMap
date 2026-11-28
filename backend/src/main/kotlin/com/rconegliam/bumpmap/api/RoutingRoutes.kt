package com.rconegliam.bumpmap.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val PUBLIC_CAR_BASE = "https://routing.openstreetmap.de/routed-car"
private const val DEFAULT_TIMEOUT_MS = 5_000L
private const val MAX_RESPONSE_BYTES = 512 * 1024
private const val MAX_ROUTE_POINTS = 16_000
private const val MAX_DIRECT_DISTANCE_KM = 1_000.0
private const val USER_AGENT = "BumpMap/1.0 (+https://github.com/rconegliam/bumpMap)"
private val COORDINATE = Regex("-?[0-9]{1,3}(?:\\.[0-9]{1,8})?")
private val osrmJson = Json { ignoreUnknownKeys = true }

private val RoutingHeaders = createRouteScopedPlugin("RoutingHeaders") {
    onCall { call ->
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
    }
}

/**
 * GET /v1/map/route?from=longitude,latitude&to=longitude,latitude with a map-read bearer.
 * Coordinates must be distinct, with up to eight decimal places and at most 1,000 km apart.
 * A successful response contains [CarRoute], with a GeoJSON LineString and estimates in meters and seconds.
 * Errors use [ApiError]. 400 = invalid inputs, 404 = no route or nearby road, 429 = busy,
 * 502 = invalid or failed upstream response, 503 = provider disabled/unavailable, 504 = upstream timeout.
 * GET /v1/map/route/config uses the same bearer and indicates whether a provider is enabled and external.
 */
internal fun Route.routingRoutes(provider: OsrmRoutingProvider?, public: Boolean = false) {
    route(if (public) "/v1/public/map/route" else "/v1/map/route") {
        install(RoutingHeaders)
        val endpoints: Route.() -> Unit = {
            get("/config") {
                call.respond(RoutingConfig(provider != null, provider?.external == true))
            }
            get {
                val query = call.request.queryParameters
                val from = RoutePoint.parse(query.getAll("from")?.singleOrNull())
                val to = RoutePoint.parse(query.getAll("to")?.singleOrNull())
                if (from == null || to == null || from == to) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_route_points"))
                    return@get
                }
                if (from.distanceTo(to) > MAX_DIRECT_DISTANCE_KM) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("route_too_far"))
                    return@get
                }
                when (val result = provider?.lookup(from, to) ?: RouteFailure.Disabled) {
                    is CarRoute -> call.respond(result)
                    is RouteFailure -> {
                        if (result == RouteFailure.Busy) call.response.header(HttpHeaders.RetryAfter, "1")
                        call.respond(result.status, ApiError(result.code))
                    }
                }
            }
        }
        if (public) endpoints() else authenticate("map-read", build = endpoints)
    }
}

internal data class RoutePoint(val longitude: Double, val latitude: Double) {
    fun distanceTo(other: RoutePoint): Double {
        val latitudeDelta = Math.toRadians(other.latitude - latitude)
        val longitudeDelta = Math.toRadians(other.longitude - longitude)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * 6371.0 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    // BigDecimal renders small valid coordinates without exponent notation for OSRM's URL grammar.
    fun asPath() = "${longitude.toBigDecimal().stripTrailingZeros().toPlainString()}," +
        latitude.toBigDecimal().stripTrailingZeros().toPlainString()

    companion object {
        fun parse(raw: String?): RoutePoint? {
            if (raw == null || raw.length > 27) return null
            val parts = raw.split(',')
            if (parts.size != 2 || parts.any { !COORDINATE.matches(it) }) return null
            val longitude = parts[0].toDouble()
            val latitude = parts[1].toDouble()
            if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null
            return RoutePoint(longitude + 0.0, latitude + 0.0)
        }
    }
}

internal sealed interface RouteLookupResult

@Serializable
private data class RoutingConfig(val enabled: Boolean, val external: Boolean)

@Serializable
internal data class CarRoute(
    val geometry: Geometry,
    @SerialName("distance_m") val distance: Double,
    @SerialName("duration_s") val duration: Double,
) : RouteLookupResult

internal enum class RouteFailure(val status: HttpStatusCode, val code: String) : RouteLookupResult {
    NoRoute(HttpStatusCode.NotFound, "route_not_found"),
    Busy(HttpStatusCode.TooManyRequests, "route_busy"),
    BadGateway(HttpStatusCode.BadGateway, "route_provider_error"),
    Unavailable(HttpStatusCode.ServiceUnavailable, "route_provider_unavailable"),
    Disabled(HttpStatusCode.ServiceUnavailable, "routing_disabled"),
    Timeout(HttpStatusCode.GatewayTimeout, "route_provider_timeout"),
}

internal class OsrmRoutingProvider(
    private val base: URI,
    private val publicOrigin: String? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    val external = !isPrivateHost(checkNotNull(base.host))
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val inFlight = Mutex()
    private var lastRequestStarted: Long? = null

    suspend fun lookup(from: RoutePoint, to: RoutePoint): RouteLookupResult {
        if (!inFlight.tryLock()) return RouteFailure.Busy
        try {
            val now = System.nanoTime()
            if (lastRequestStarted?.let { now - it < TimeUnit.SECONDS.toNanos(1) } == true) {
                return RouteFailure.Busy
            }
            val uri = URI.create(
                "${base.toString().trimEnd('/')}/route/v1/driving/${from.asPath()};${to.asPath()}" +
                    "?overview=simplified&geometries=geojson&alternatives=false&steps=false" +
                    "&generate_hints=false&skip_waypoints=true&radiuses=250;250",
            )
            val request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .apply { publicOrigin?.let { header("Referer", it) } }
                .GET().build()
            val response = try {
                // The JDK request timeout alone does not cover downloading a streaming body.
                withTimeoutOrNull(timeoutMs) {
                    lastRequestStarted = System.nanoTime()
                    send(request)
                } ?: return RouteFailure.Timeout
            } catch (_: HttpTimeoutException) {
                return RouteFailure.Timeout
            } catch (_: IOException) {
                return RouteFailure.BadGateway
            }
            return decodeResponse(response.statusCode(), response.body())
        } finally {
            inFlight.unlock()
        }
    }

    private suspend fun send(request: HttpRequest): HttpResponse<ByteArray> =
        suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(request) { BoundedBody() }
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (error == null) continuation.resume(response)
                else continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
            }
        }

    companion object {
        /**
         * Custom OSRM: set BUMPMAP_ROUTING_BASE_URL to its deployment root, before /route/v1/driving.
         * For private Railway networking, use http://<service>.railway.internal:<port>.
         * For local OSRM, use http://127.0.0.1:5000 and run osrm-routed with --verbosity NONE
         * because its request paths contain the coordinates.
         * Public FOSSGIS pilot: set BUMPMAP_ROUTING_PUBLIC_ORIGIN to the real HTTPS site origin
         * and display a public operator email on that site. Use one backend replica for public routing.
         * Leave both settings unset to disable route lookups. See
         * https://www.fossgis.de/arbeitsgruppen/osm-server/nutzungsbedingungen/.
         */
        fun fromEnvironment(env: Map<String, String>): OsrmRoutingProvider? {
            val origin = env["BUMPMAP_ROUTING_PUBLIC_ORIGIN"]?.takeIf(String::isNotBlank)?.let {
                configuredUri(it, "BUMPMAP_ROUTING_PUBLIC_ORIGIN").also { uri ->
                    require(uri.scheme == "https" && uri.rawPath in listOf("", "/")) {
                        "BUMPMAP_ROUTING_PUBLIC_ORIGIN must be an HTTPS origin"
                    }
                }.toString().trimEnd('/') + "/"
            }
            val customBase = env["BUMPMAP_ROUTING_BASE_URL"]?.takeIf(String::isNotBlank)
            val base = configuredUri(
                customBase ?: if (origin != null) PUBLIC_CAR_BASE else return null,
                "BUMPMAP_ROUTING_BASE_URL",
            )
            val host = checkNotNull(base.host).lowercase().trimEnd('.')
            if (host == "routing.openstreetmap.de") {
                require(origin != null) { "FOSSGIS routing requires BUMPMAP_ROUTING_PUBLIC_ORIGIN" }
            }
            require(base.scheme == "https" || isPrivateHost(host)) {
                "BUMPMAP_ROUTING_BASE_URL requires HTTPS outside Railway private networking or loopback"
            }
            return OsrmRoutingProvider(base, origin)
        }

        private fun isPrivateHost(value: String): Boolean {
            val host = value.lowercase().trimEnd('.')
            return host == "localhost" || host == "127.0.0.1" || host == "[::1]" ||
                host.endsWith(".railway.internal")
        }

        private fun configuredUri(raw: String, key: String): URI {
            val uri = runCatching { URI(raw) }.getOrNull()
            require(
                uri != null && uri.scheme in setOf("https", "http") && uri.host != null &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535),
            ) { "$key must be an absolute URL without credentials, a query or a fragment" }
            return uri
        }
    }
}

private class BoundedBody(
    private val delegate: HttpResponse.BodySubscriber<ByteArray> = HttpResponse.BodySubscribers.ofByteArray(),
) : HttpResponse.BodySubscriber<ByteArray> by delegate {
    private lateinit var subscription: Flow.Subscription
    private var remaining = MAX_RESPONSE_BYTES

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        delegate.onSubscribe(subscription)
    }

    override fun onNext(items: List<ByteBuffer>) {
        for (item in items) {
            remaining -= item.remaining()
            if (remaining < 0) {
                subscription.cancel()
                delegate.onError(IOException("Routing upstream response exceeded the byte limit"))
                return
            }
        }
        delegate.onNext(items)
    }
}

@Serializable
private data class OsrmResponse(val code: String, val routes: List<OsrmRoute> = emptyList())

@Serializable
private data class OsrmRoute(val distance: Double, val duration: Double, val geometry: Geometry)

private fun decodeResponse(status: Int, body: ByteArray): RouteLookupResult {
    if (status == 429 || status == 503) return RouteFailure.Unavailable
    if (status != 200 && status != 400) return RouteFailure.BadGateway
    val payload = try {
        osrmJson.decodeFromString<OsrmResponse>(body.decodeToString())
    } catch (_: SerializationException) {
        return RouteFailure.BadGateway
    }
    if (payload.code == "NoRoute" || payload.code == "NoSegment") return RouteFailure.NoRoute
    val route = payload.routes.singleOrNull()
    if (status != 200 || payload.code != "Ok" || route == null ||
        route.distance !in 0.0..10_000_000.0 || route.duration !in 0.0..604_800.0 ||
        route.geometry.type != "LineString" || route.geometry.coordinates.size !in 2..MAX_ROUTE_POINTS ||
        !route.geometry.coordinates.all(::validPosition)
    ) return RouteFailure.BadGateway
    return CarRoute(route.geometry, route.distance, route.duration)
}

private fun validPosition(value: JsonElement): Boolean {
    val position = value as? JsonArray ?: return false
    if (position.size != 2) return false
    val longitude = (position[0] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return false
    val latitude = (position[1] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return false
    return longitude in -180.0..180.0 && latitude in -90.0..90.0
}
