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

package dev.sfg.orchard.mobile.catalog

import android.util.Log
import dev.sfg.orchard.mobile.auth.YouTubeSession
import dev.sfg.orchard.mobile.auth.YouTubeSessionAuth
import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Narrow native boundary for YouTube Music's JSON catalog API.
 *
 * Client metadata is refreshed from the public Music page when possible and
 * no browser/Electron API leaks into repositories or presentation state.
 */
class InnerTubeClient(
    private val http: OkHttpClient,
    private val sessionProvider: YouTubeSessionProvider = YouTubeSessionProvider { null },
) {
    private data class ClientIdentity(val key: String, val version: String, val visitor: String)
    private data class HttpResult(val successful: Boolean, val code: Int, val body: String)
    @Volatile private var identity: ClientIdentity? = null

    fun search(query: String): JSONObject = post("search", JSONObject().put("query", query))

    /**
     * Search restricted to songs, so music videos never come back as candidates.
     *
     * Sent anonymously: this runs on the app's behalf while resolving a track the listener already
     * picked, and an authenticated search writes the query into the account's YouTube search
     * history. Only searches the listener actually typed belong there.
     */
    fun searchSongs(query: String): JSONObject =
        post("search", JSONObject().put("query", query).put("params", SONGS_FILTER), anonymous = true)

    fun browse(browseId: String): JSONObject = post("browse", JSONObject().put("browseId", browseId))

    fun browsePayload(browseId: String, params: String = ""): JSONObject {
        val body = JSONObject().put("browseId", browseId)
        if (params.isNotBlank()) body.put("params", params)
        return post("browse", body)
    }

    /**
     * The radio YouTube Music would roll into after [videoId] finishes.
     *
     * `RDAMVM<videoId>` is the mix the web player seeds from a single song, so the result reads as
     * a continuation of what was just playing rather than a generic recommendation shelf. Audio
     * only, because Autoplay feeds a music queue and the video cuts run long against the album ones.
     */
    fun upNext(videoId: String): JSONObject = post(
        "next",
        JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("isAudioOnly", true),
    )

    fun browseContinuation(token: String): JSONObject =
        post("browse", JSONObject().put("continuation", token))

    /** Resolves the optional YouTube Music lyrics tab for a playable video. */
    fun lyrics(videoId: String): String {
        if (videoId.isBlank()) return ""
        val next = post("next", JSONObject().put("videoId", videoId))
        val lyricsTab = JsonTraversal.renderers(next, "tabRenderer").firstOrNull { tab ->
            val endpoint = tab.optJSONObject("endpoint") ?: JsonTraversal.navigation(tab)
            tab.optString("title").equals("Lyrics", true) ||
                JsonTraversal.text(tab.optJSONObject("title")).equals("Lyrics", true) ||
                JsonTraversal.pageType(endpoint).contains("LYRICS", true)
        } ?: return ""
        val endpoint = lyricsTab.optJSONObject("endpoint") ?: JsonTraversal.navigation(lyricsTab)
        val browseId = JsonTraversal.browseId(endpoint)
        if (browseId.isBlank()) return ""
        val root = browse(browseId)
        return JsonTraversal.renderers(root, "musicDescriptionShelfRenderer")
            .firstNotNullOfOrNull { shelf ->
                JsonTraversal.text(shelf.optJSONObject("description")).takeIf(String::isNotBlank)
            }.orEmpty()
    }

    /**
     * Name and avatar of the signed-in account, empty when the session is anonymous.
     * Lives on the account menu the web player uses to render its own avatar.
     */
    fun accountInfo(): Pair<String, String> {
        val root = post("account/account_menu", JSONObject())
        val header = JsonTraversal.renderers(root, "activeAccountHeaderRenderer").firstOrNull()
            ?: return "" to ""
        return JsonTraversal.text(header.optJSONObject("accountName")) to
            header.optJSONObject("accountPhoto").largestPhoto()
    }

    /**
     * accountPhoto carries its `thumbnails` array directly, with no wrapping `thumbnail` object,
     * so JsonTraversal.largestThumbnail finds nothing here.
     */
    private fun JSONObject?.largestPhoto(): String {
        val values = this?.optJSONArray("thumbnails") ?: return ""
        var best: JSONObject? = null
        for (index in 0 until values.length()) {
            val candidate = values.optJSONObject(index) ?: continue
            val area = candidate.optInt("width") * candidate.optInt("height")
            if (area > (best?.let { it.optInt("width") * it.optInt("height") } ?: -1)) best = candidate
        }
        return best?.optString("url").orEmpty()
    }

    /** [anonymous] drops the account session, keeping the call out of the listener's YouTube activity. */
    fun post(endpoint: String, payload: JSONObject, anonymous: Boolean = false): JSONObject {
        val activeIdentity = identity ?: loadIdentity().also { identity = it }
        val activeSession = if (anonymous) null else sessionProvider.session()
        val first = execute(endpoint, payload, activeIdentity, activeSession, includeDataSyncId = true)
        val firstMessage = errorMessage(first.body)
        val result = if (
            activeSession?.dataSyncId?.isNotBlank() == true &&
            first.code == 400 &&
            firstMessage.replace('_', ' ').contains("invalid argument", ignoreCase = true)
        ) {
            // Account delegation ids can become stale after a channel/account switch.
            Log.w(TAG, "Retrying authenticated request without stale account context")
            execute(endpoint, payload, activeIdentity, activeSession, includeDataSyncId = false)
        } else first
        if (!result.successful) {
            error(errorMessage(result.body).ifBlank { "Music catalog request failed with HTTP ${result.code}" })
        }
        return runCatching { JSONObject(result.body) }.getOrElse { error("Music catalog returned invalid JSON") }
    }

    private fun execute(
        endpoint: String,
        payload: JSONObject,
        activeIdentity: ClientIdentity,
        session: YouTubeSession?,
        includeDataSyncId: Boolean,
    ): HttpResult {
        val visitorData = session?.visitorData?.takeIf(String::isNotBlank) ?: activeIdentity.visitor
        val context = JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", activeIdentity.version)
                .put("hl", "en")
                .put("gl", "US")
                .put("visitorData", visitorData),
        )
        if (includeDataSyncId && session?.dataSyncId?.isNotBlank() == true) {
            context.put(
                "user",
                JSONObject()
                    .put("lockedSafetyMode", false)
                    .put("onBehalfOfUser", session.dataSyncId),
            )
        }
        val body = JSONObject(payload.toString()).put("context", context)
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?key=${activeIdentity.key}&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Origin", YouTubeSessionAuth.MUSIC_ORIGIN)
            .header("X-Goog-Api-Format-Version", "1")
            .header("User-Agent", USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", activeIdentity.version)
            .apply {
                if (visitorData.isNotBlank()) header("X-Goog-Visitor-Id", visitorData)
                session?.let { signedIn ->
                    header("Cookie", signedIn.cookie)
                    YouTubeSessionAuth.authorization(signedIn.cookie)?.let { header("Authorization", it) }
                    header("X-Goog-AuthUser", "0")
                }
            }
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            return HttpResult(response.isSuccessful, response.code, text)
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val error = JSONObject(body).optJSONObject("error")
        error?.optString("message").orEmpty().ifBlank { error?.optString("status").orEmpty() }
    }.getOrDefault("")

    private fun loadIdentity(): ClientIdentity {
        val request = Request.Builder()
            .url("https://music.youtube.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val html = response.body.string()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                ClientIdentity(
                    key = API_KEY.findValue(html).ifBlank { FALLBACK_KEY },
                    version = CLIENT_VERSION.findValue(html).ifBlank { FALLBACK_VERSION },
                    visitor = VISITOR.findValue(html),
                )
            }
        }.onFailure { Log.w(TAG, "Using bundled public Music client identity", it) }
            .getOrElse { ClientIdentity(FALLBACK_KEY, FALLBACK_VERSION, "") }
    }

    private fun Regex.findValue(input: String): String = find(input)?.groupValues?.getOrNull(1).orEmpty()

    companion object {
        /** InnerTube search filter that restricts results to songs. */
        private const val SONGS_FILTER = "EgWKAQIIAWoKEAoQAxAEEAkQBQ%3D%3D"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val API_KEY = Regex("INNERTUBE_API_KEY(?:\\\"|&quot;)?\\s*:\\s*\\\"([^\\\"]+)")
        private val CLIENT_VERSION = Regex("INNERTUBE_CLIENT_VERSION(?:\\\"|&quot;)?\\s*:\\s*\\\"([^\\\"]+)")
        private val VISITOR = Regex("VISITOR_DATA(?:\\\"|&quot;)?\\s*:\\s*\\\"([^\\\"]+)")
        private const val FALLBACK_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val FALLBACK_VERSION = "1.20260213.01.00"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36"
        private const val TAG = "InnerTubeClient"
    }
}
