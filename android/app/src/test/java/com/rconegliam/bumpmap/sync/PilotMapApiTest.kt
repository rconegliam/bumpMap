package com.rconegliam.bumpmap.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class PilotMapApiTest {
    private data class RecordedRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class StubResponse(val status: Int, val body: String = "")

    @Test
    fun readViewportCallsUnauthenticatedPublicEndpointWithCursor() {
        val requests = mutableListOf<RecordedRequest>()
        withStubServer(
            handler = { request ->
                requests += request
                val body = if ("after=" !in request.target) {
                    """{"type":"FeatureCollection","features":[{"type":"Feature","id":"11111111-1111-1111-1111-111111111111",""" +
                        """"geometry":{"type":"LineString","coordinates":[[-46.7,-23.5],[-46.7001,-23.5001]]},""" +
                        """"properties":{"kind":"segment","speed_kmh":40.0,"potholes":0,"speed_bumps":1,"map_quality":"fair","penalty_points":15}}],""" +
                        """"next_cursor":"cursor-1"}"""
                } else {
                    """{"type":"FeatureCollection","features":[]}"""
                }
                StubResponse(200, body)
            },
        ) { baseUrl ->
            val viewport = runBlocking { PilotMapApi.readViewport("-46.8,-23.6,-46.6,-23.4", baseUrl) }
            assertEquals(1, viewport.features.size)
            assertFalse(viewport.hasMore)
            assertEquals(
                listOf(
                    "/v1/public/map/observations?bbox=-46.8,-23.6,-46.6,-23.4",
                    "/v1/public/map/observations?bbox=-46.8,-23.6,-46.6,-23.4&after=cursor-1",
                ),
                requests.map { it.target },
            )
            assertEquals(listOf("GET", "GET"), requests.map { it.method })
            assertNull(requests[0].headers["authorization"])
            assertNull(requests[1].headers["authorization"])
        }
    }

    @Test
    fun registerInstallationSendsJsonIdAndBearerToken() {
        val requests = mutableListOf<RecordedRequest>()
        val statuses = ArrayDeque(listOf(204, 409, 429))
        withStubServer(
            handler = { request ->
                requests += request
                StubResponse(statuses.removeFirst())
            },
        ) { baseUrl ->
            val token = "a".repeat(64)
            assertEquals(204, PilotMapApi.registerInstallation("install-123", token, baseUrl))
            assertEquals(409, PilotMapApi.registerInstallation("install-123", token, baseUrl))
            assertEquals(429, PilotMapApi.registerInstallation("install-123", token, baseUrl))
            val first = requests.first()
            assertEquals("POST", first.method)
            assertEquals("/v1/installations", first.target)
            assertEquals("application/json", first.headers["content-type"])
            assertEquals("Bearer $token", first.headers["authorization"])
            assertEquals("""{"id":"install-123"}""", first.body)
        }
    }

    private fun withStubServer(handler: (RecordedRequest) -> StubResponse, block: (String) -> Unit) {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { conn ->
                    val input = BufferedInputStream(conn.getInputStream())
                    val requestLine = readLine(input) ?: return@use
                    val parts = requestLine.split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyBytes, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    val response = handler(RecordedRequest(parts[0], parts[1], headers, bodyBytes.decodeToString()))
                    val payload = response.body.encodeToByteArray()
                    val output = BufferedOutputStream(conn.getOutputStream())
                    val head = buildString {
                        append("HTTP/1.1 ").append(response.status).append(" OK\r\n")
                        append("Connection: close\r\n")
                        if (response.status != 204) {
                            append("Content-Length: ").append(payload.size).append("\r\n")
                        }
                        append("\r\n")
                    }
                    output.write(head.encodeToByteArray())
                    if (response.status != 204 && payload.isNotEmpty()) output.write(payload)
                    output.flush()
                }
            }
        }
        try {
            block("http://127.0.0.1:${socket.localPort}")
        } finally {
            socket.close()
            worker.join(2_000)
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (next == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().decodeToString()
            }
            bytes += next.toByte()
        }
    }
}
