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

package dev.sfg.orchard.mobile.lastfm

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.scrobble.ScrobbleProgress
import dev.sfg.orchard.mobile.scrobble.ScrobbleProgressEvent
import dev.sfg.orchard.mobile.scrobble.ScrobbleTrack
import dev.sfg.orchard.mobile.scrobble.cleanScrobbleText
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONObject

sealed interface LastfmState {
    data object SignedOut : LastfmState
    data object Connecting : LastfmState
    data class Pending(val expiresAtMs: Long) : LastfmState
    data class Connected(val user: String) : LastfmState
    data class Error(val message: String) : LastfmState
}

/** Last.fm auth and scrobbling through Orchard's credential-signing Worker. */
class LastfmRepository(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private data class Session(val user: String, val key: String)
    private data class PendingAuth(val token: String, val expiresAtMs: Long)

    private val store = SecureLastfmStore(context)
    private val progress = ScrobbleProgress()
    private val requestMutex = Mutex()
    private val mutableState = MutableStateFlow<LastfmState>(LastfmState.SignedOut)
    val state: StateFlow<LastfmState> = mutableState.asStateFlow()

    @Volatile private var session: Session? = null
    @Volatile private var pending: PendingAuth? = null

    init {
        scope.launch {
            store.load()?.let {
                session = it
                mutableState.value = LastfmState.Connected(it.user)
            }
        }
    }

    /** Starts browser authorization and returns the HTTPS URL the UI should open. */
    suspend fun connect(): String = withContext(Dispatchers.IO) {
        mutableState.value = LastfmState.Connecting
        runCatching {
            val response = workerRequest("/auth/token", JSONObject())
            val token = response.optString("token").trim()
            val authorizationUrl = response.optString("authorizationUrl").trim()
            val uri = authorizationUrl.toUri()
            require(token.length in 16..512) { "Last.fm returned an invalid authorization token." }
            require(uri.scheme == "https" && uri.host == "www.last.fm") {
                "Last.fm returned an invalid authorization URL."
            }
            pending = PendingAuth(token, System.currentTimeMillis() + AUTH_WINDOW_MS)
            mutableState.value = LastfmState.Pending(pending!!.expiresAtMs)
            authorizationUrl
        }.getOrElse { error ->
            mutableState.value = LastfmState.Error(error.userMessage("Could not start Last.fm connection."))
            throw error
        }
    }

    suspend fun complete(): Boolean = withContext(Dispatchers.IO) {
        val authorization = pending
        if (authorization == null || System.currentTimeMillis() >= authorization.expiresAtMs) {
            pending = null
            mutableState.value = LastfmState.Error("Start Last.fm connection again; authorization expired.")
            return@withContext false
        }
        runCatching {
            val response = workerRequest(
                "/auth/session",
                JSONObject().put("token", authorization.token),
            )
            val connected = Session(
                user = response.optString("user").cleanScrobbleText(100),
                key = response.optString("sessionKey").trim(),
            )
            require(connected.user.isNotBlank() && connected.key.length in 16..512) {
                "Last.fm returned an invalid session."
            }
            store.save(connected)
            session = connected
            pending = null
            progress.reset()
            mutableState.value = LastfmState.Connected(connected.user)
            true
        }.getOrElse { error ->
            mutableState.value = LastfmState.Error(error.userMessage("Last.fm authorization has not been approved yet."))
            false
        }
    }

    fun disconnect() {
        session = null
        pending = null
        progress.reset()
        store.clear()
        mutableState.value = LastfmState.SignedOut
    }

    fun updatePlayback(snapshot: PlaybackSnapshot) {
        val activeSession = session ?: return
        progress.update(snapshot).forEach { event ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    when (event) {
                        is ScrobbleProgressEvent.NowPlaying -> submit(
                            path = "/now-playing",
                            session = activeSession,
                            track = event.track,
                        )
                        is ScrobbleProgressEvent.Completed -> submit(
                            path = "/scrobble",
                            session = activeSession,
                            track = event.track,
                            timestamp = event.startedAtEpochSeconds,
                        )
                    }
                }.onFailure { error ->
                    if (error is LastfmUnauthorizedException) {
                        disconnect()
                        mutableState.value = LastfmState.Error("Last.fm disconnected because the session expired.")
                    }
                }
            }
        }
    }

    private suspend fun submit(
        path: String,
        session: Session,
        track: ScrobbleTrack,
        timestamp: Long? = null,
    ) = requestMutex.withLock {
        val trackJson = JSONObject()
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("duration", track.durationMs / 1_000)
        val body = JSONObject()
            .put("sessionKey", session.key)
            .put("track", trackJson)
        timestamp?.let { body.put("timestamp", it) }
        workerRequest(path, body)
    }

    private fun workerRequest(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(WORKER_ENDPOINT + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val payload = runCatching { JSONObject(response.body.string()) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val message = payload.optString("error").ifBlank { "Last.fm request failed (${response.code})." }
                if (response.code == 401) throw LastfmUnauthorizedException(message)
                error(message)
            }
            return payload
        }
    }

    private class SecureLastfmStore(context: Context) {
        private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        private val cipher = AndroidKeystoreCipher(KEY_ALIAS)

        fun load(): Session? = runCatching {
            val encrypted = preferences.getString(SESSION, null) ?: return null
            val json = JSONObject(cipher.decrypt(encrypted))
            Session(json.getString("user"), json.getString("key"))
                .takeIf { it.user.isNotBlank() && it.key.length in 16..512 }
        }.getOrNull()

        fun save(session: Session) {
            val json = JSONObject().put("user", session.user).put("key", session.key)
            preferences.edit(commit = true) {
                putString(SESSION, cipher.encrypt(json.toString()))
            }
        }

        fun clear() {
            preferences.edit(commit = true) { remove(SESSION) }
        }

        private companion object {
            const val FILE = "orchard_secure_scrobbling"
            const val SESSION = "lastfm_session"
            const val KEY_ALIAS = "orchard_lastfm_session_v1"
        }
    }

    private class LastfmUnauthorizedException(message: String) : IllegalStateException(message)

    private fun Throwable.userMessage(fallback: String): String = message?.takeIf(String::isNotBlank) ?: fallback

    private companion object {
        const val WORKER_ENDPOINT = "https://lastfm.sfg545.dev"
        const val USER_AGENT = "OrchardMobile/2.0"
        const val AUTH_WINDOW_MS = 10 * 60_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
