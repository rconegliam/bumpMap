package com.rconegliam.bumpmap.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RoutingRoutesTest {
    @Test
    fun usesOnlyMapReadAuthAndRejectsInvalidCoordinatesBeforeUpstream() {
        val upstreamRequests = AtomicInteger()
        upstream({
            upstreamRequests.incrementAndGet()
            sendJson(200, GOOD_RESPONSE)
        }) { base ->
            testApplication {
                installLookup(OsrmRoutingProvider(base))
                for (token in listOf(null, "write-only", "wrong-token")) {
                    val response = client.get(ROUTE) { token?.let { bearerAuth(it) } }
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
                val config = client.get("$PATH/config")
                assertEquals(HttpStatusCode.Unauthorized, config.status)
                assertEquals("no-store", config.headers[HttpHeaders.CacheControl])
                for (query in listOf(
                    "", "?from=1,2", "?from=NaN,2&to=2,3", "?from=2,3&to=1e-3,3",
                    "?from=181,2&to=2,3", "?from=1,91&to=2,3", "?from=1,2&to=1,2",
                    "?from=-0,0&to=0,-0", "?from=1,2&from=2,3&to=2,3", "?from=1,2&to=2,3&to=2,3",
                    "?from=1,2,3&to=2,3", "?from=1.000000001,2&to=2,3",
                    "?from=1,2%3B5,6&to=2,3", "?from=1,2%0A&to=2,3",
                )) {
                    val response = client.get("$PATH$query") { bearerAuth(READ_TOKEN) }
                    assertEquals(HttpStatusCode.BadRequest, response.status, query)
                    assertEquals("""{"error":"invalid_route_points"}""", response.bodyAsText())
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
                val far = client.get("$PATH?from=0,0&to=0,14") { bearerAuth(READ_TOKEN) }
                assertEquals(HttpStatusCode.BadRequest, far.status)
                assertEquals("""{"error":"route_too_far"}""", far.bodyAsText())
                assertEquals(0, upstreamRequests.get())
            }
        }
    }

    @Test
    fun projectsOnlySafeRouteFieldsAndDoesNotForwardCredentialsOrDriveData() {
        val sent = AtomicReference<UpstreamRequest>()
        upstream({
            sent.set(UpstreamRequest(requestURI, requestHeaders.entries.associate { it.key.lowercase() to it.value }))
            sendJson(200, GOOD_RESPONSE)
        }) { base ->
            testApplication {
                installLookup(OsrmRoutingProvider(base, publicOrigin = "https://example.com/"))
                val config = client.get("$PATH/config") { bearerAuth(READ_TOKEN) }
                assertEquals("""{"enabled":true,"external":false}""", config.bodyAsText())
                val response = client.get("$ROUTE&recording_id=synthetic-private-drive") {
                    bearerAuth(READ_TOKEN)
                    headers.append(HttpHeaders.Cookie, "session=synthetic-credential")
                    headers.append("Referer", "https://example.org/private-query")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                assertEquals(
                    Json.parseToJsonElement(
                        """{"geometry":{"type":"LineString","coordinates":[[-46.87,-23.51],[-46.864,-23.507],[-46.86,-23.51]]},"distance_m":1450.5,"duration_s":182.5}""",
                    ),
                    Json.parseToJsonElement(response.bodyAsText()),
                )
                val next = client.get(ROUTE) { bearerAuth(READ_TOKEN) }
                assertEquals(HttpStatusCode.TooManyRequests, next.status)
                assertEquals("1", next.headers[HttpHeaders.RetryAfter])
                assertEquals("no-store", next.headers[HttpHeaders.CacheControl])
                assertEquals("""{"error":"route_busy"}""", next.bodyAsText())
            }
        }
        val request = assertNotNull(sent.get())
        assertEquals("/routed-car/route/v1/driving/-46.87,-23.51;-46.86,-23.51", request.uri.path)
        assertEquals(
            "overview=simplified&geometries=geojson&alternatives=false&steps=false&" +
                "generate_hints=false&skip_waypoints=true&radiuses=250;250",
            request.uri.query,
        )
        assertEquals(listOf("https://example.com/"), request.headers["referer"])
        assertTrue(request.headers["user-agent"]?.single()?.startsWith("BumpMap/") == true)
        assertNull(request.headers["authorization"])
        assertNull(request.headers["cookie"])
    }

    @Test
    fun mapsProviderAndValidationErrorsToStableCodes() {
        val expected = listOf(
            Triple(400, """{"code":"NoSegment","message":"private provider detail"}""", RouteFailure.NoRoute),
            Triple(200, """{"code":"NoRoute"}""", RouteFailure.NoRoute),
            Triple(502, """{"message":"private provider detail"}""", RouteFailure.BadGateway),
            Triple(200, "not JSON", RouteFailure.BadGateway),
            Triple(200, GOOD_RESPONSE.replace("-46.864", "181"), RouteFailure.BadGateway),
            Triple(200, GOOD_RESPONSE.replace("-46.864", "\"-46.864\""), RouteFailure.BadGateway),
            Triple(200, GOOD_RESPONSE.replace("1450.5", "-1"), RouteFailure.BadGateway),
            Triple(200, GOOD_RESPONSE.replace("182.5", "1e309"), RouteFailure.BadGateway),
            Triple(200, GOOD_RESPONSE.replace("LineString", "Polygon"), RouteFailure.BadGateway),
            Triple(200, GOOD_RESPONSE + " ".repeat(512 * 1024), RouteFailure.BadGateway),
            Triple(429, "private provider detail", RouteFailure.Unavailable),
        )
        for ((status, body, failure) in expected) {
            upstream({ sendJson(status, body) }) { base ->
                testApplication {
                    installLookup(OsrmRoutingProvider(base))
                    val response = client.get(ROUTE) { bearerAuth(READ_TOKEN) }
                    assertEquals(failure.status, response.status)
                    assertEquals("""{"error":"${failure.code}"}""", response.bodyAsText())
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
            }
        }
    }

    @Test
    fun timesOutEvenWhenUpstreamHasAlreadyStartedStreaming() {
        val responseStarted = CountDownLatch(1)
        val finishResponse = CountDownLatch(1)
        upstream({
            sendResponseHeaders(200, 0)
            responseBody.write('{'.code)
            responseBody.flush()
            responseStarted.countDown()
            finishResponse.await(10, TimeUnit.SECONDS)
        }) { base ->
            try {
                testApplication {
                    installLookup(OsrmRoutingProvider(base, timeoutMs = 850))
                    val response = client.get(ROUTE) { bearerAuth(READ_TOKEN) }
                    assertEquals(0L, responseStarted.count)
                    assertEquals(HttpStatusCode.GatewayTimeout, response.status)
                    assertEquals("""{"error":"route_provider_timeout"}""", response.bodyAsText())
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                }
            } finally {
                finishResponse.countDown()
            }
        }
    }

    @Test
    fun disablesProviderByDefaultAndEnablesConfiguredPrivateOrPublicHosts() {
        assertNull(OsrmRoutingProvider.fromEnvironment(emptyMap()))
        testApplication {
            installLookup(null)
            val config = client.get("$PATH/config") { bearerAuth(READ_TOKEN) }
            assertEquals("""{"enabled":false,"external":false}""", config.bodyAsText())
            val response = client.get(ROUTE) { bearerAuth(READ_TOKEN) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("""{"error":"routing_disabled"}""", response.bodyAsText())
        }
        val baseUrl = "BUMPMAP_ROUTING_BASE_URL"
        val origin = "BUMPMAP_ROUTING_PUBLIC_ORIGIN"
        for (url in listOf("http://127.0.0.1:5000", "http://routing.railway.internal:5000")) {
            assertFalse(assertNotNull(OsrmRoutingProvider.fromEnvironment(mapOf(baseUrl to url))).external)
        }
        assertTrue(assertNotNull(OsrmRoutingProvider.fromEnvironment(mapOf(origin to "https://example.org"))).external)
        assertTrue(assertNotNull(OsrmRoutingProvider.fromEnvironment(mapOf(baseUrl to "https://example.org/osrm"))).external)
        for (url in listOf(
            "http://public.example.org", "http://routing.railway.internal.example.net:5000",
            "https://user:secret@example.org", "https://example.org?secret=credential",
            "https://example.org#fragment", "https://routing.openstreetmap.de/routed-car",
        )) {
            assertFailsWith<IllegalArgumentException>(url) {
                OsrmRoutingProvider.fromEnvironment(mapOf(baseUrl to url))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            OsrmRoutingProvider.fromEnvironment(mapOf(origin to "https://example.org/private-path"))
        }
    }

    private fun ApplicationTestBuilder.installLookup(provider: OsrmRoutingProvider?) {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                bearer("map-read") {
                    authenticate { if (it.token == READ_TOKEN) UserIdPrincipal("map-reader") else null }
                }
            }
            routing { routingRoutes(provider) }
        }
    }

    private fun upstream(handler: HttpExchange.() -> Unit, verify: (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use(handler) }
        server.start()
        try {
            verify(URI.create("http://127.0.0.1:${server.address.port}/routed-car"))
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.sendJson(status: Int, payload: String) {
        val bytes = payload.encodeToByteArray()
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private data class UpstreamRequest(val uri: URI, val headers: Map<String, List<String>>)

    companion object {
        private const val READ_TOKEN = "synthetic-read-token"
        private const val PATH = "/v1/map/route"
        private const val ROUTE = "$PATH?from=-46.87,-23.51&to=-46.86,-23.51"
        private const val GOOD_RESPONSE = """
            {"code":"Ok","routes":[{"geometry":{"type":"LineString","coordinates":[[-46.87,-23.51],[-46.864,-23.507],[-46.86,-23.51]],"private":"field"},"distance":1450.5,"duration":182.5,"legs":[{"name":"synthetic-private-street"}]}],"waypoints":[]}
        """
    }
}
