package com.rconegliam.bumpmap.sync

import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.rconegliam.bumpmap.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val OBSERVATIONS = "/v1/observations"
private const val MAX_VIEWPORT_PAGES = 4

// Tiny area in the Atlantic Ocean, used only to check that the server accepts a token.
private const val TOKEN_CHECK_BBOX = "-30.001,-20.001,-30.0,-20.0"

internal data class PilotViewport(val features: List<Feature>, val hasMore: Boolean)

internal enum class TokenCheck { ACCEPTED, REJECTED, UNREACHABLE }

internal object PilotMapApi {
    fun publish(features: List<Feature>, token: String): Int {
        val body = FeatureCollection.fromFeatures(features).toJson().encodeToByteArray()
        return connection(OBSERVATIONS, token).run {
            try {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/geo+json")
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
                responseCode
            } finally {
                disconnect()
            }
        }
    }

    fun putPart(path: String, bytes: ByteArray, token: String): Int =
        connection(path, token).run {
            try {
                requestMethod = "PUT"
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
                responseCode
            } finally {
                disconnect()
            }
        }

    fun checkToken(token: String): TokenCheck {
        val connection = connection("$OBSERVATIONS?bbox=$TOKEN_CHECK_BBOX", token)
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> TokenCheck.ACCEPTED
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> TokenCheck.REJECTED
                else -> TokenCheck.UNREACHABLE
            }
        } catch (_: IOException) {
            TokenCheck.UNREACHABLE
        } finally {
            connection.disconnect()
        }
    }

    suspend fun readViewport(bbox: String, token: String): PilotViewport {
        val features = mutableListOf<Feature>()
        var cursor: String? = null
        repeat(MAX_VIEWPORT_PAGES) {
            currentCoroutineContext().ensureActive()
            val after = cursor?.let { "&after=$it" }.orEmpty()
            val connection = connection("$OBSERVATIONS?bbox=$bbox&from_recordings=true$after", token)
            val json = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Pilot map unavailable")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val page = JsonParser.parseString(json).asJsonObject
            page.getAsJsonArray("features").forEach { features.add(Feature.fromJson(it.toString())) }
            cursor = page.get("next_cursor")?.takeUnless { it.isJsonNull }?.asString
            if (cursor == null) return PilotViewport(features, hasMore = false)
        }
        return PilotViewport(features, hasMore = true)
    }

    private fun connection(path: String, token: String) =
        (URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
}
