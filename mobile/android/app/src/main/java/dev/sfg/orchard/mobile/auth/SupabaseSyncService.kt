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

package dev.sfg.orchard.mobile.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.sfg.orchard.mobile.playback.smart.TrackFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val DEFAULT_SUPABASE_URL = "https://hhosnulqxwjbqqjkxuqv.supabase.co"
const val DEFAULT_SUPABASE_ANON_KEY = "sb_publishable_E9OfGgsFZDXZm-AXP2_-1g_Fe48ECNC"

const val SUPABASE_SYNC_DISCLAIMER =
    "Audio analysis metadata (BPM, musical key, downbeats, cue points) is shared publicly with the Orchard Cloud cache by Track Video ID. No personal listening history, user playlists, or identifying info is included."

class SupabaseSyncService(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("orchard_supabase_prefs", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = prefs.getString("supabase_url", null) ?: DEFAULT_SUPABASE_URL
        set(value) = prefs.edit().putString("supabase_url", value.trim().trimEnd('/')).apply()

    var anonKey: String
        get() = prefs.getString("anon_key", null) ?: DEFAULT_SUPABASE_ANON_KEY
        set(value) = prefs.edit().putString("anon_key", value.trim()).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        private set(value) = prefs.edit().putString("access_token", value).apply()

    /**
     * The long-lived half of the session, exchanged for a new access token when that one expires.
     *
     * Access tokens last an hour by default. Without this the service simply kept presenting the
     * expired one, so every read failed with 401 from an hour after sign-in until the user signed
     * out and back in — which is what made analysis written by the desktop app invisible here.
     */
    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        private set(value) = prefs.edit().putString("refresh_token", value).apply()

    var userEmail: String
        get() = prefs.getString("user_email", "") ?: ""
        private set(value) = prefs.edit().putString("user_email", value).apply()

    fun isConfigured(): Boolean = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    fun isAuthenticated(): Boolean = isConfigured() && accessToken.isNotBlank()

    fun signOut() {
        accessToken = ""
        refreshToken = ""
        userEmail = ""
    }

    suspend fun signIn(email: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("Supabase not configured"))
        try {
            val endpoint = "$supabaseUrl/auth/v1/token?grant_type=password"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", anonKey)
                doOutput = true
            }

            val body = JSONObject().apply {
                put("email", email)
                put("password", pass)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = BufferedReader(InputStreamReader(responseStream)).use { it.readText() }

            if (responseCode !in 200..299) {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val msg = errJson?.optString("msg")
                    ?: errJson?.optString("error_description")
                    ?: errJson?.optString("message")
                    ?: "Sign in failed with code $responseCode"
                return@withContext Result.failure(Exception(msg))
            }

            val json = JSONObject(responseText)
            accessToken = json.optString("access_token", "")
            refreshToken = json.optString("refresh_token", "")
            userEmail = json.optJSONObject("user")?.optString("email", email) ?: email
            Result.success(userEmail)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signUp(email: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("Supabase not configured"))
        try {
            val endpoint = "$supabaseUrl/auth/v1/signup"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", anonKey)
                doOutput = true
            }

            val body = JSONObject().apply {
                put("email", email)
                put("password", pass)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = BufferedReader(InputStreamReader(responseStream)).use { it.readText() }

            if (responseCode !in 200..299) {
                val errJson = runCatching { JSONObject(responseText) }.getOrNull()
                val msg = errJson?.optString("msg")
                    ?: errJson?.optString("error_description")
                    ?: errJson?.optString("message")
                    ?: "Sign up failed with code $responseCode"
                return@withContext Result.failure(Exception(msg))
            }

            val json = JSONObject(responseText)
            val token = json.optString("access_token", "")
            if (token.isNotBlank()) accessToken = token
            json.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { refreshToken = it }
            userEmail = json.optJSONObject("user")?.optString("email", email) ?: email
            Result.success(userEmail)
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    /**
     * Trades the refresh token for a fresh access token. True when the session is usable again.
     *
     * Synchronized because Supabase rotates refresh tokens: the old one is spent by a successful
     * exchange, so two callers racing here would have the loser present a token that no longer
     * exists and lose the session entirely. A failure clears both tokens rather than leaving a
     * dead session in place to fail every later call — the user has to sign in again, and the
     * caller can still fall back to the public key meanwhile.
     */
    @Synchronized
    private fun refreshSession(): Boolean {
        val token = refreshToken
        if (!isConfigured() || token.isBlank()) return false
        return try {
            val endpoint = "$supabaseUrl/auth/v1/token?grant_type=refresh_token"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", anonKey)
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream).use {
                it.write(JSONObject().put("refresh_token", token).toString())
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Could not refresh the session: HTTP $code; signing out")
                accessToken = ""
                refreshToken = ""
                return false
            }

            val json = JSONObject(
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() },
            )
            val fresh = json.optString("access_token", "")
            if (fresh.isBlank()) return false
            accessToken = fresh
            json.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { refreshToken = it }
            Log.d(TAG, "Session refreshed")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh the session", e)
            false
        }
    }

    /** One GET against PostgREST as [bearer]. Body is null unless the call succeeded. */
    private fun getAs(endpoint: String, bearer: String): Pair<Int, String?> {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                // Drained so the connection can be pooled rather than torn down.
                conn.errorStream?.use { it.readBytes() }
                null
            }
            code to body
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchTrackFeatures(videoIds: List<String>): Map<String, TrackFeatures.Features> = withContext(Dispatchers.IO) {
        if (!isConfigured() || videoIds.isEmpty()) return@withContext emptyMap()

        val cleanIds = videoIds.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return@withContext emptyMap()

        try {
            val filterParam = "(" + cleanIds.joinToString(",") { "\"$it\"" } + ")"
            val encodedFilter = URLEncoder.encode(filterParam, "UTF-8")
            val endpoint = "$supabaseUrl/rest/v1/track_analysis?video_id=in.$encodedFilter&select=*"

            var response = getAs(endpoint, accessToken.ifBlank { anonKey })

            // A 401 here means the access token aged out, which it does every hour. Refresh and
            // retry once.
            if (response.first == HttpURLConnection.HTTP_UNAUTHORIZED && accessToken.isNotBlank()) {
                Log.i(TAG, "Track analysis query was rejected; refreshing the session")
                if (refreshSession()) response = getAs(endpoint, accessToken)
            }

            // Still refused, or there was never a session to refresh. This table is the public
            // analysis cache, so a signed-out read of it is worth trying before giving up: the
            // alternative is Best Mix silently returning the queue in the order it arrived.
            if (response.first == HttpURLConnection.HTTP_UNAUTHORIZED && anonKey.isNotBlank()) {
                response = getAs(endpoint, anonKey)
            }

            val responseText = response.second ?: run {
                Log.w(TAG, "Failed to query track analysis: HTTP ${response.first}")
                return@withContext emptyMap()
            }
            val array = JSONArray(responseText)
            val results = mutableMapOf<String, TrackFeatures.Features>()

            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val videoId = row.optString("video_id", "")
                if (videoId.isBlank()) continue

                val analysisData = row.optJSONObject("analysis_data") ?: row
                val features = TrackFeatures.parse(analysisData)
                if (features.bpm > 0.0) {
                    results[videoId] = features
                }
            }

            results
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching track features from Supabase", e)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "SupabaseSyncService"
    }
}
