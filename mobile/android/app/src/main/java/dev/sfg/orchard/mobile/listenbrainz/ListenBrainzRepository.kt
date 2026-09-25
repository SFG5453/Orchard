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

package dev.sfg.orchard.mobile.listenbrainz

import android.content.Context
import androidx.core.content.edit
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.scrobble.ScrobbleProgress
import dev.sfg.orchard.mobile.scrobble.ScrobbleProgressEvent
import dev.sfg.orchard.mobile.scrobble.ScrobbleTrack
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed interface ListenBrainzState {
    data object SignedOut : ListenBrainzState
    data object Validating : ListenBrainzState
    data class Connected(val user: String) : ListenBrainzState
    data class Error(val message: String) : ListenBrainzState
}

/** Direct ListenBrainz token validation, now-playing updates, and completed listens. */
class ListenBrainzRepository(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private data class Session(val user: String, val token: String)

    private val store = SecureListenBrainzStore(context)
    private val progress = ScrobbleProgress()
    private val requestMutex = Mutex()
    private val mutableState = MutableStateFlow<ListenBrainzState>(ListenBrainzState.SignedOut)
    val state: StateFlow<ListenBrainzState> = mutableState.asStateFlow()

    @Volatile private var session: Session? = null
    private var lastRequestAtMs = 0L

    init {
        scope.launch(Dispatchers.IO) {
            store.load()?.let {
                session = it
                mutableState.value = ListenBrainzState.Connected(it.user)
            }
        }
    }

    suspend fun connect(rawToken: String): Boolean = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (token.length !in 16..512 || token.any(Char::isWhitespace)) {
            mutableState.value = ListenBrainzState.Error("Enter a valid ListenBrainz user token.")
            return@withContext false
        }
        mutableState.value = ListenBrainzState.Validating
        runCatching {
            val request = Request.Builder()
                .url("$API_ROOT/1/validate-token")
                .header("Authorization", "Token $token")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val payload = http.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body.string()) }.getOrDefault(JSONObject())
                if (!response.isSuccessful) error(json.optString("message").ifBlank { "Token validation failed (${response.code})." })
                json
            }
            require(payload.optBoolean("valid")) { "ListenBrainz rejected that token." }
            val connected = Session(payload.optString("user_name").ifBlank { "ListenBrainz" }, token)
            store.save(connected)
            session = connected
            progress.reset()
            mutableState.value = ListenBrainzState.Connected(connected.user)
            true
        }.getOrElse { error ->
            mutableState.value = ListenBrainzState.Error(error.message ?: "Could not connect ListenBrainz.")
            false
        }
    }

    fun disconnect() {
        session = null
        progress.reset()
        store.clear()
        mutableState.value = ListenBrainzState.SignedOut
    }

    fun updatePlayback(snapshot: PlaybackSnapshot) {
        val activeSession = session ?: return
        progress.update(snapshot).forEach { event ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    when (event) {
                        is ScrobbleProgressEvent.NowPlaying -> submit(
                            session = activeSession,
                            listenType = "playing_now",
                            track = event.track,
                        )
                        is ScrobbleProgressEvent.Completed -> submit(
                            session = activeSession,
                            listenType = "single",
                            track = event.track,
                            timestamp = event.startedAtEpochSeconds,
                        )
                    }
                }.onFailure { error ->
                    if (error is ListenBrainzUnauthorizedException) {
                        disconnect()
                        mutableState.value = ListenBrainzState.Error("ListenBrainz disconnected because the token expired.")
                    }
                }
            }
        }
    }

    private suspend fun submit(
        session: Session,
        listenType: String,
        track: ScrobbleTrack,
        timestamp: Long? = null,
    ) = requestMutex.withLock {
        val remaining = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMs)
        if (remaining > 0) delay(remaining)

        val additionalInfo = JSONObject()
            .put("media_player", "Orchard Mobile")
            .put("submission_client", "Orchard Mobile")
            .put("music_service", "music.youtube.com")
            .put("origin_url", "https://music.youtube.com/watch?v=${track.id}")
            .put("duration_ms", track.durationMs)
        val metadata = JSONObject()
            .put("artist_name", track.artist)
            .put("track_name", track.title)
            .put("additional_info", additionalInfo)
        if (track.album.isNotBlank()) metadata.put("release_name", track.album)
        val listen = JSONObject().put("track_metadata", metadata)
        timestamp?.let { listen.put("listened_at", it) }
        val body = JSONObject()
            .put("listen_type", listenType)
            .put("payload", JSONArray().put(listen))
        val request = Request.Builder()
            .url("$API_ROOT/1/submit-listens")
            .header("Authorization", "Token ${session.token}")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val payload = runCatching { JSONObject(response.body.string()) }.getOrDefault(JSONObject())
                if (!response.isSuccessful) {
                    val message = payload.optString("error").ifBlank {
                        payload.optString("message").ifBlank { "ListenBrainz request failed (${response.code})." }
                    }
                    if (response.code == 401) throw ListenBrainzUnauthorizedException(message)
                    error(message)
                }
            }
        } finally {
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    private class SecureListenBrainzStore(context: Context) {
        private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        private val cipher = AndroidKeystoreCipher(KEY_ALIAS)

        fun load(): Session? = runCatching {
            val encrypted = preferences.getString(SESSION, null) ?: return null
            val json = JSONObject(cipher.decrypt(encrypted))
            Session(json.getString("user"), json.getString("token"))
                .takeIf { it.token.length in 16..512 }
        }.getOrNull()

        fun save(session: Session) {
            val json = JSONObject().put("user", session.user).put("token", session.token)
            preferences.edit(commit = true) {
                putString(SESSION, cipher.encrypt(json.toString()))
            }
        }

        fun clear() {
            preferences.edit(commit = true) { remove(SESSION) }
        }

        private companion object {
            const val FILE = "orchard_secure_scrobbling"
            const val SESSION = "listenbrainz_session"
            const val KEY_ALIAS = "orchard_listenbrainz_token_v1"
        }
    }

    private class ListenBrainzUnauthorizedException(message: String) : IllegalStateException(message)

    private companion object {
        const val API_ROOT = "https://api.listenbrainz.org"
        const val USER_AGENT = "OrchardMobile/2.0 (https://github.com/SFG5453/Orchard)"
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
