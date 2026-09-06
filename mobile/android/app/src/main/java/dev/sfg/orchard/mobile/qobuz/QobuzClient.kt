/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.qobuz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

fun qobuzRequestSignature(
    method: String,
    args: Map<String, Any>,
    timestamp: String,
    secret: String,
): String {
    val serialized = args.entries
        .sortedBy { it.key }
        .joinToString("") { "${it.key}${it.value}" }
    val input = "$method$serialized$timestamp$secret"
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

class QobuzClient(
    private val bootstrapLoader: QobuzBootstrapLoader,
    private val credentialsProvider: suspend () -> QobuzSession?,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val softwareVersion: String = "OrchardMobile/1.0.0",
) {
    private val sessionMutex = Mutex()
    @Volatile
    private var activeSession: SessionInfo? = null

    data class SessionInfo(
        val sessionId: String,
        val infos: String,
        val expiresAtMs: Long,
    )

    data class StreamingResponse(
        val urlTemplate: String,
        val formatId: Int,
        val durationSeconds: Int,
        val blob: String,
        val key: String,
        val bitDepth: Int?,
        val sampleRate: Int?,
        val session: SessionInfo,
        val rngInit: String,
        val rawJson: JSONObject,
    )

    suspend fun ensureSession(refresh: Boolean = false): SessionInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!refresh) {
            activeSession?.takeIf { it.expiresAtMs > now + 60_000L }?.let { return@withContext it }
        }
        sessionMutex.withLock {
            if (!refresh) {
                activeSession?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000L }?.let { return@withLock it }
            }
            val bootstrap = bootstrapLoader.get()
            val creds = credentialsProvider() ?: throw IllegalStateException("Qobuz is not connected")
            val timestamp = (System.currentTimeMillis() / 1000L).toString()
            val args = mapOf("profile" to "qbz-1")
            val sig = qobuzRequestSignature("sessionstart", args, timestamp, bootstrap.rngInit)

            val form = FormBody.Builder()
                .add("profile", "qbz-1")
                .add("request_ts", timestamp)
                .add("request_sig", sig)
                .build()

            val request = Request.Builder()
                .url("$QOBUZ_BASE_URL/session/start")
                .header("Accept", "application/json")
                .header("User-Agent", QOBUZ_USER_AGENT)
                .header("X-App-Id", bootstrap.appId)
                .header("X-User-Auth-Token", creds.token)
                .post(form)
                .build()

            val json = executeJson(request, "Qobuz session/start")
            val sessionId = json.optString("session_id")
            val infos = json.optString("infos")
            val expiresAtSec = json.optLong("expires_at", 0L)
            if (sessionId.isBlank() || infos.isBlank()) {
                throw IllegalStateException("Qobuz returned an incomplete playback session")
            }
            val session = SessionInfo(
                sessionId = sessionId,
                infos = infos,
                expiresAtMs = expiresAtSec * 1000L,
            )
            activeSession = session
            session
        }
    }

    suspend fun search(query: String, limit: Int = 20): JSONObject = withContext(Dispatchers.IO) {
        val bootstrap = bootstrapLoader.get()
        val creds = credentialsProvider() ?: throw IllegalStateException("Qobuz is not connected")
        val url = "$QOBUZ_BASE_URL/catalog/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", QOBUZ_USER_AGENT)
            .header("X-App-Id", bootstrap.appId)
            .header("X-User-Auth-Token", creds.token)
            .get()
            .build()

        executeJson(request, "Qobuz catalog/search")
    }

    suspend fun streamingInfo(
        trackId: Long,
        quality: QobuzQuality,
        retry: Boolean = true,
    ): StreamingResponse = withContext(Dispatchers.IO) {
        val session = ensureSession()
        val bootstrap = bootstrapLoader.get()
        val creds = credentialsProvider() ?: throw IllegalStateException("Qobuz is not connected")
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val formatId = quality.formatId
        val args = mapOf<String, Any>(
            "format_id" to formatId,
            "intent" to "stream",
            "track_id" to trackId,
        )
        val sig = qobuzRequestSignature("fileurl", args, timestamp, bootstrap.rngInit)

        val urlBuilder = "$QOBUZ_BASE_URL/file/url".toHttpUrl().newBuilder()
        args.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v.toString()) }
        urlBuilder.addQueryParameter("request_ts", timestamp)
        urlBuilder.addQueryParameter("request_sig", sig)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("User-Agent", QOBUZ_USER_AGENT)
            .header("X-App-Id", bootstrap.appId)
            .header("X-User-Auth-Token", creds.token)
            .header("X-Session-Id", session.sessionId)
            .get()
            .build()

        try {
            val json = executeJson(request, "Qobuz file/url")
            val urlTemplate = json.optString("url_template")
            if (urlTemplate.isBlank()) {
                throw IllegalStateException("Qobuz returned no segmented playback URL")
            }
            StreamingResponse(
                urlTemplate = urlTemplate,
                formatId = json.optInt("format_id", formatId),
                durationSeconds = json.optInt("duration", 0),
                blob = json.optString("blob", ""),
                key = json.optString("key", ""),
                bitDepth = json.optInt("bit_depth", 0).takeIf { it > 0 }
                    ?: json.optInt("bits_depth", 0).takeIf { it > 0 },
                sampleRate = json.optInt("sampling_rate", 0).takeIf { it > 0 },
                session = session,
                rngInit = bootstrap.rngInit,
                rawJson = json,
            )
        } catch (e: Exception) {
            if (retry && e.message?.contains(Regex("401|403|410")) == true) {
                activeSession = null
                ensureSession(refresh = true)
                return@withContext streamingInfo(trackId, quality, retry = false)
            }
            throw e
        }
    }

    suspend fun reportStreamingStart(
        trackId: Long,
        startedAtUnix: Long,
        formatId: Int,
        userId: Long,
    ) = withContext(Dispatchers.IO) {
        val bootstrap = bootstrapLoader.get()
        val creds = credentialsProvider() ?: return@withContext
        val events = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("track_id", trackId)
                    put("date", startedAtUnix)
                    put("user_id", userId)
                    put("format_id", formatId)
                }
            )
        }
        val form = FormBody.Builder()
            .add("events", events.toString())
            .build()
        val request = Request.Builder()
            .url("$QOBUZ_BASE_URL/track/reportStreamingStart")
            .header("Accept", "application/json")
            .header("User-Agent", QOBUZ_USER_AGENT)
            .header("X-App-Id", bootstrap.appId)
            .header("X-User-Auth-Token", creds.token)
            .post(form)
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
    }

    suspend fun reportStreamingEnd(
        event: JSONObject,
    ) = withContext(Dispatchers.IO) {
        val bootstrap = bootstrapLoader.get()
        val creds = credentialsProvider() ?: return@withContext
        val payload = JSONObject().apply {
            put("events", JSONArray().put(event))
            put("renderer_context", JSONObject().put("software_version", softwareVersion))
        }
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$QOBUZ_BASE_URL/track/reportStreamingEndJson")
            .header("Accept", "application/json")
            .header("User-Agent", QOBUZ_USER_AGENT)
            .header("X-App-Id", bootstrap.appId)
            .header("X-User-Auth-Token", creds.token)
            .post(requestBody)
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
    }

    fun reset() {
        activeSession = null
    }

    private fun executeJson(request: Request, label: String): JSONObject {
        val response = httpClient.newCall(request).execute()
        val body = response.body.string()
        if (!response.isSuccessful) {
            val errorMsg = "$label failed (${response.code}): ${body.take(240)}"
            throw IllegalStateException(errorMsg)
        }
        return JSONObject(body)
    }
}
