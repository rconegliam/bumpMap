package com.rconegliam.bumpmap.sync

import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.rconegliam.bumpmap.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val INSTALLATIONS = "/v1/installations"
private const val OBSERVATIONS = "/v1/observations"
private const val PUBLIC_MAP_OBSERVATIONS = "/v1/public/map/observations"
private const val MAX_VIEWPORT_PAGES = 4

internal data class PilotViewport(val features: List<Feature>, val hasMore: Boolean)

internal object PilotMapApi {
    fun registerInstallation(
        installationId: String,
        token: String,
        baseUrl: String = BuildConfig.API_BASE_URL,
    ): Int {
        val body = JsonObject().apply { addProperty("id", installationId) }.toString().encodeToByteArray()
        return connection(INSTALLATIONS, token, baseUrl).run {
            try {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
                responseCode
            } finally {
                disconnect()
            }
        }
    }

    fun publish(
        features: List<Feature>,
        token: String,
        baseUrl: String = BuildConfig.API_BASE_URL,
    ): Int {
        val body = FeatureCollection.fromFeatures(features).toJson().encodeToByteArray()
        return connection(OBSERVATIONS, token, baseUrl).run {
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

    fun putPart(
        path: String,
        bytes: ByteArray,
        token: String,
        baseUrl: String = BuildConfig.API_BASE_URL,
    ): Int =
        connection(path, token, baseUrl).run {
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

    suspend fun readViewport(
        bbox: String,
        baseUrl: String = BuildConfig.API_BASE_URL,
    ): PilotViewport {
        val features = mutableListOf<Feature>()
        var cursor: String? = null
        repeat(MAX_VIEWPORT_PAGES) {
            currentCoroutineContext().ensureActive()
            val after = cursor?.let { "&after=$it" }.orEmpty()
            val connection = connection("$PUBLIC_MAP_OBSERVATIONS?bbox=$bbox$after", baseUrl = baseUrl)
            val json = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Pilot map unavailable")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val page = JsonParser.parseString(json).asJsonObject
            page.getAsJsonArray("features")?.forEach { features.add(Feature.fromJson(it.toString())) }
            cursor = page.get("next_cursor")?.takeUnless { it.isJsonNull }?.asString
            if (cursor == null) return PilotViewport(features, hasMore = false)
        }
        return PilotViewport(features, hasMore = true)
    }

    private fun connection(
        path: String,
        token: String? = null,
        baseUrl: String = BuildConfig.API_BASE_URL,
    ) =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
}
