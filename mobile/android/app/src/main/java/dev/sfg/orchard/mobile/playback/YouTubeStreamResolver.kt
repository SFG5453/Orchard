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

package dev.sfg.orchard.mobile.playback

import android.util.Log
import dev.sfg.orchard.mobile.auth.YouTubeSessionAuth
import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.model.AudioQuality
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ResolvedStream(
    val url: String,
    val mimeType: String,
    val expiresAtMs: Long,
    val bitrateKbps: Int = 0,
)

/**
 * Thrown when YouTube refuses playback because the content is age-restricted.
 * Carrying a dedicated type lets the retry chain distinguish an age gate from
 * a generic refusal without parsing message strings at every call site.
 */
private class AgeGateException(message: String) : RuntimeException(message)

/**
 * Native port of Orchard desktop's direct Android-VR stream fallback.
 *
 * The client returns unciphered, short-lived CDN URLs that Media3 can open
 * directly. URLs are cached only in memory and refreshed before expiry.
 */
/** Persistence for the visitor identity, which YouTube requires but rarely changes. */
interface VisitorIdentityStore {
    fun load(): Pair<String, String>?
    fun save(id: String, cookie: String)
}

class YouTubeStreamResolver(
    private val client: OkHttpClient,
    private val qualityProvider: () -> AudioQuality = { AudioQuality.HIGH },
    private val visitorStore: VisitorIdentityStore? = null,
    private val onWarning: ((String) -> Unit)? = null,
    /**
     * The signed-in web session, when there is one. Playback prefers it: a guest
     * identity is what YouTube answers with "Sign in to confirm you are not a
     * bot", and an account that is already signed in for the catalog has no
     * reason to be anonymous for playback.
     */
    private val sessionProvider: YouTubeSessionProvider? = null,
    private val challengeSolver: YouTubeChallengeSolver? = null,
    private val downloadManager: dev.sfg.orchard.mobile.download.DownloadManager? = null,
) {
    private val newPipeResolver: NewPipeStreamResolver by lazy { NewPipeStreamResolver(client, sessionProvider) }
    /**
     * [signedIn] distinguishes an account identity from a guest one. A cookie
     * alone leaves the player request anonymous as far as YouTube is concerned;
     * the signed request also needs the SAPISIDHASH authorization the catalog
     * calls already build.
     */
    private data class Visitor(val id: String, val cookie: String, val signedIn: Boolean = false)
    private val streams = ConcurrentHashMap<String, ResolvedStream>()

    /**
     * When every client refused a track, briefly. A failed open makes ExoPlayer
     * retry immediately, and each retry is another player request: one track
     * YouTube will not serve turned into dozens of refusals a minute, which is
     * how an address earns a longer block. Failing fast for a few seconds costs
     * nothing, since the answer will not have changed.
     */
    private val failures = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val prefetchExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "orchard-stream-prefetch").apply { isDaemon = true }
    }
    private val visitorLock = Any()
    @Volatile private var visitor: Visitor? = null

    /**
     * The account identity, held separately from the guest one and keyed by the
     * cookie it was derived from, so signing out or switching accounts cannot
     * leave a stale identity behind. Never written to [visitorStore], which
     * outlives the session on disk.
     */
    private val accountLock = Any()
    @Volatile private var accountVisitor: Pair<String, Visitor>? = null

    fun resolve(videoId: String): ResolvedStream {
        require(videoId.isNotBlank()) { "A YouTube video id is required" }
        downloadManager?.let { mgr ->
            dev.sfg.orchard.mobile.download.DownloadPlaybackHelper.resolveOfflineStream(videoId, mgr)?.let { return it }
        }
        val quality = qualityProvider()
        val cacheKey = "$videoId:${quality.name}"
        cached(cacheKey)?.let { return it }
        var maxQualityFallback = false
        // MAX uses NewPipe for the highest bitrate stream.
        if (quality == AudioQuality.MAX) {
            val newPipeStream = newPipeResolver.resolve(videoId)
            if (newPipeStream != null) {
                streams[cacheKey] = newPipeStream
                return newPipeStream
            }
            // NewPipe failed; fall back to HIGH. We only warn the user if HIGH resolution actually succeeds.
            Log.w(TAG, "NewPipe extraction failed for $videoId; attempting fallback to HIGH quality")
            maxQualityFallback = true
            val fallbackKey = "$videoId:${AudioQuality.HIGH.name}"
            cached(fallbackKey)?.let {
                onWarning?.invoke("Max quality unavailable, using High")
                streams[cacheKey] = it
                return it
            }
        }
        // Only one caller resolves a given track; a prefetch already in flight is worth
        // waiting on, since a second player request would cost the same round trip.
        failures[videoId]?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < FAILURE_BACKOFF_MS) {
                error("YouTube refused this track moments ago")
            }
            failures.remove(videoId)
        }
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            cached(cacheKey)?.let { return it }
            val started = System.currentTimeMillis()
            val account = runCatching { accountIdentity(videoId) }
                .onFailure { Log.w(TAG, "Could not build an account identity for playback", it) }
                .getOrNull()
            var isAgeGated = false
            val response = runCatching {
                androidVrPlayer(videoId, account ?: warmVisitor(videoId))
            }
                // A stored identity can go stale, and YouTube then answers with a bot check.
                // One retry on a fresh identity is cheaper than never trusting the cache.
                // Signed in, this is also the guest retry: the account may simply
                // not be entitled to this track.
                .recoverCatching { firstError ->
                    if (firstError is AgeGateException) throw firstError
                    Log.w(TAG, "Retrying with a fresh visitor identity", firstError)
                    androidVrPlayer(videoId, loadVisitor(videoId).also(::rememberVisitor))
                }
                .recoverCatching { secondError ->
                    Log.w(TAG, "ANDROID_VR failed for $videoId; trying ANDROID client", secondError)
                    val authenticatedVisitor = account ?: warmVisitor(videoId)
                    androidPlayer(videoId, authenticatedVisitor)
                }
                .recoverCatching { thirdError ->
                    Log.w(TAG, "ANDROID player failed for $videoId; trying IOS client", thirdError)
                    val authenticatedVisitor = account ?: warmVisitor(videoId)
                    iosPlayer(videoId, authenticatedVisitor)
                }
                .recoverCatching { fourthError ->
                    Log.w(TAG, "IOS player failed for $videoId; trying WEB_REMIX player", fourthError)
                    val authenticatedVisitor = account ?: warmVisitor(videoId)
                    webRemixPlayer(videoId, authenticatedVisitor)
                }
                .recoverCatching { fifthError ->
                    Log.w(TAG, "WEB_REMIX player failed for $videoId; trying TVHTML5 player", fifthError)
                    val authenticatedVisitor = account ?: warmVisitor(videoId)
                    tvHtml5Player(videoId, authenticatedVisitor)
                }
                .onFailure { failures[videoId] = System.currentTimeMillis() }
                .getOrThrow()
            val stream = chooseAudio(response, quality)
            if (maxQualityFallback) {
                onWarning?.invoke("Max quality unavailable, using High")
            }
            streams[cacheKey] = stream
            Log.d(
                TAG,
                "Resolved $videoId as ${if (account != null) "account" else "guest"} " +
                    "in ${System.currentTimeMillis() - started}ms",
            )
            return stream
        }
    }

    /**
     * Fetches the visitor identity off the critical path. It costs a full watch-page download,
     * which is the single largest part of a cold first play.
     */
    fun warmUp() = prefetchExecutor.execute {
        runCatching { warmVisitor("") }
        // Even with an identity in hand, the first player request otherwise pays for DNS and a
        // TLS handshake. A throwaway request leaves a pooled connection ready for it.
        runCatching {
            val request = Request.Builder()
                .url("https://www.youtube.com/generate_204")
                .header("User-Agent", CLIENT_USER_AGENT)
                .build()
            client.newCall(request).execute().close()
        }
    }

    private fun warmVisitor(videoId: String): Visitor {
        visitor?.let { return it }
        synchronized(visitorLock) {
            visitor?.let { return it }
            val stored = visitorStore?.load()?.let { (id, cookie) -> Visitor(id, cookie) }
            val identity = stored ?: loadVisitor(videoId)
            rememberVisitor(identity)
            return identity
        }
    }

    /**
     * The signed-in identity for the player call: the account's own cookie, and
     * the visitor data that belongs with it.
     *
     * Returns null when signed out. The account's `visitorData` is captured at
     * sign-in, but an older session may not carry one, in which case it is read
     * from a watch page fetched *as the account* — a guest visitor id paired
     * with account cookies is the mismatch that draws a bot check.
     */
    private fun accountIdentity(videoId: String): Visitor? {
        val session = sessionProvider?.session()
        if (session == null) {
            Log.d(TAG, "No signed-in session yet; playing as a guest")
            return null
        }
        val cookie = session.cookie.takeIf(String::isNotBlank)?.let(::playbackCookie) ?: return null
        accountVisitor?.takeIf { it.first == cookie }?.let { return it.second }
        synchronized(accountLock) {
            accountVisitor?.takeIf { it.first == cookie }?.let { return it.second }
            val visitorData = session.visitorData.takeIf(String::isNotBlank)
                ?: loadVisitor(videoId, cookie).id
            val identity = Visitor(visitorData, cookie, signedIn = true)
            accountVisitor = cookie to identity
            return identity
        }
    }

    private fun forgetAccountIdentity() = synchronized(accountLock) { accountVisitor = null }

    private fun playbackCookie(raw: String): String = raw
        .split(';')
        .map(String::trim)
        .filterNot { it.startsWith("__Secure-YEC=") }
        .joinToString("; ")

    private fun rememberVisitor(identity: Visitor) {
        visitor = identity
        visitorStore?.save(identity.id, identity.cookie)
    }

    /** Resolves ahead of playback so the load thread finds the URL already cached. */
    fun prefetch(videoId: String) {
        if (videoId.isBlank()) return
        if (cached("$videoId:${qualityProvider().name}") != null) return
        prefetchExecutor.execute { runCatching { resolve(videoId) } }
    }

    private fun cached(cacheKey: String): ResolvedStream? =
        streams[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 60_000 }

    /**
     * The bitrate of the stream already resolved for [videoId], or 0 when none is known.
     *
     * Never resolves anything: the caller is a UI readout, and a readout must not put a network
     * round trip behind a track change. Zero is a real answer here and means "not known", which
     * is the honest state for a track served straight from the media cache, since a cache hit
     * skips the resolver entirely. Reads the map rather than [cached] because an expired entry
     * has a dead URL but a bitrate that was still true of the bytes on disk.
     */
    fun knownBitrateKbps(videoId: String): Int {
        if (videoId.isBlank()) return 0
        val quality = qualityProvider()
        return streams["$videoId:${quality.name}"]?.bitrateKbps?.takeIf { it > 0 }
            ?: streams["$videoId:${AudioQuality.HIGH.name}"]?.bitrateKbps?.takeIf { it > 0 }
            ?: 0
    }

    fun invalidate(videoId: String) {
        streams.keys.removeAll { it.startsWith("$videoId:") }
    }

    private fun loadVisitor(videoId: String, cookie: String? = null): Visitor {
        val url = if (videoId.isNotBlank()) "https://www.youtube.com/watch?v=$videoId" else "https://www.youtube.com"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CLIENT_USER_AGENT)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube returned HTTP ${response.code}" }
            val body = response.body.string()
            val id = VISITOR_PATTERN.find(body)?.groupValues?.getOrNull(1)
                ?: error("YouTube did not return a visitor identity")
            if (!cookie.isNullOrBlank()) return Visitor(id, cookie)
            val issued = response.headers.values("Set-Cookie")
                .map { it.substringBefore(';') }
                .filterNot { it.startsWith("__Secure-YEC=") }
                .joinToString("; ")
            return Visitor(id, issued)
        }
    }

    private fun androidVrPlayer(videoId: String, visitor: Visitor?): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", CLIENT_VERSION)
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("androidSdkVersion", 32)
            .put("userAgent", CLIENT_USER_AGENT)
            .put("osName", "Android")
            .put("osVersion", "12L")
            .put("hl", "en")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
            .apply { visitor?.let { put("visitorData", it.id) } }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", CLIENT_USER_AGENT)
            .header("X-Youtube-Client-Name", "28")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .apply {
                visitor?.let {
                    if (it.id.isNotBlank()) header("X-Goog-Visitor-Id", it.id)
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                    // Signed-in requests need the SAPISIDHASH authorization
                    // matching the Origin header.
                    if (it.signedIn) {
                        YouTubeSessionAuth.authorization(it.cookie, origin = "https://www.youtube.com")?.let { auth ->
                            header("Authorization", auth)
                        }
                        header("X-Goog-AuthUser", "0")
                    }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun androidPlayer(videoId: String, visitor: Visitor?): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", ANDROID_CLIENT_VERSION)
            .put("osName", "Android")
            .put("osVersion", "14")
            .put("hl", "en")
            .put("gl", "US")
            .apply { visitor?.id?.takeIf(String::isNotBlank)?.let { put("visitorData", it) } }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$PUBLIC_API_KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", ANDROID_USER_AGENT)
            .header("X-Youtube-Client-Name", "3")
            .header("X-Youtube-Client-Version", ANDROID_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("X-Origin", "https://www.youtube.com")
            .apply {
                visitor?.let {
                    if (it.id.isNotBlank()) header("X-Goog-Visitor-Id", it.id)
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                    if (it.signedIn) {
                        YouTubeSessionAuth.authorization(it.cookie, origin = "https://www.youtube.com")?.let { auth ->
                            header("Authorization", auth)
                        }
                        header("X-Goog-AuthUser", "0")
                    }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun iosPlayer(videoId: String, visitor: Visitor? = null): JSONObject {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "IOS")
                        .put("clientVersion", IOS_CLIENT_VERSION)
                        .put("deviceModel", "iPhone10,4")
                        .put("osName", "iOS")
                        .put("osVersion", "16.7.7.20H330")
                        .put("hl", "en")
                        .put("gl", "US")
                        .apply { visitor?.id?.takeIf(String::isNotBlank)?.let { put("visitorData", it) } },
                ),
            )
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$PUBLIC_API_KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", IOS_USER_AGENT)
            .header("X-Youtube-Client-Name", "5")
            .header("X-Youtube-Client-Version", IOS_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("X-Origin", "https://www.youtube.com")
            .apply {
                visitor?.let {
                    if (it.id.isNotBlank()) header("X-Goog-Visitor-Id", it.id)
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                    if (it.signedIn) {
                        YouTubeSessionAuth.authorization(it.cookie, origin = "https://www.youtube.com")?.let { auth ->
                            header("Authorization", auth)
                        }
                        header("X-Goog-AuthUser", "0")
                    }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    /**
     * YouTube Music web client (WEB_REMIX). This is the client used by music.youtube.com.
     * When signed in, carrying the user's cookies and matching SAPISIDHASH authorization,
     * it satisfies YouTube's server-side age verification for explicit music tracks.
     */
    private fun webRemixPlayer(videoId: String, visitor: Visitor?): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", WEB_REMIX_VERSION)
            .put("hl", "en")
            .put("gl", "US")
        val userContext = JSONObject()
            .put("lockedSafetyMode", false)
        val playbackContext = JSONObject()
            .put("contentPlaybackContext", JSONObject().put("signatureTimestamp", 19999))
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext).put("user", userContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("playbackContext", playbackContext)
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/player?key=$PUBLIC_API_KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", WEB_USER_AGENT)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", WEB_REMIX_VERSION)
            .header("Origin", YouTubeSessionAuth.MUSIC_ORIGIN)
            .header("Referer", "${YouTubeSessionAuth.MUSIC_ORIGIN}/")
            .header("X-Origin", YouTubeSessionAuth.MUSIC_ORIGIN)
            .header("X-Goog-Api-Format-Version", "1")
            .apply {
                visitor?.let {
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                    if (it.signedIn) {
                        YouTubeSessionAuth.authorization(it.cookie, origin = YouTubeSessionAuth.MUSIC_ORIGIN)?.let { auth ->
                            header("Authorization", auth)
                        }
                        header("X-Goog-AuthUser", "0")
                    } else if (it.id.isNotBlank()) {
                        header("X-Goog-Visitor-Id", it.id)
                    }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun tvHtml5Player(videoId: String, visitor: Visitor?): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", "TVHTML5")
            .put("clientVersion", "5.20260114")
            .put("userAgent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version")
            .put("hl", "en")
            .put("gl", "US")
            .apply { visitor?.id?.takeIf(String::isNotBlank)?.let { put("visitorData", it) } }
        val playbackContext = JSONObject()
            .put("contentPlaybackContext", JSONObject()
                .put("html5Preference", "HTML5_PREF_WANTS")
                .put("signatureTimestamp", 20668))
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("playbackContext", playbackContext)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version")
            .header("X-Youtube-Client-Name", "7")
            .header("X-Youtube-Client-Version", "5.20260114")
            .header("Origin", "https://www.youtube.com")
            .header("X-Origin", "https://www.youtube.com")
            .apply {
                visitor?.let {
                    if (it.id.isNotBlank()) header("X-Goog-Visitor-Id", it.id)
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                    if (it.signedIn) {
                        YouTubeSessionAuth.authorization(it.cookie, origin = "https://www.youtube.com")?.let { auth ->
                            header("Authorization", auth)
                        }
                        header("X-Goog-AuthUser", "0")
                    }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun executePlayer(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            Log.d(TAG, "executePlayer for ${request.url} (code ${response.code}): $text")
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val status = payload.optJSONObject("playabilityStatus")
            if (!response.isSuccessful || status?.optString("status") != "OK") {
                val reason = status?.optString("reason").orEmpty().ifBlank { "HTTP ${response.code}" }
                val statusCode = status?.optString("status").orEmpty()
                // Distinguish age-gated refusals so the retry chain can
                // route them to the web/music players instead of a guest retry.
                if (isAgeGateResponse(statusCode, reason)) {
                    throw AgeGateException("YouTube could not play this track: $reason")
                }
                error("YouTube could not play this track: $reason")
            }
            return payload
        }
    }

    private fun isAgeGateResponse(status: String, reason: String): Boolean {
        val text = "$status $reason"
        return AGE_GATE_PATTERN.containsMatchIn(text)
    }

    private fun chooseAudio(payload: JSONObject, quality: AudioQuality): ResolvedStream {
        val streaming = payload.optJSONObject("streamingData") ?: error("No streaming data returned")
        val formats = streaming.optJSONArray("adaptiveFormats") ?: JSONArray()
        val candidates = buildList {
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val mime = format.optString("mimeType")
                val url = format.optString("url")
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                if (mime.startsWith("audio/") && (url.isNotBlank() || cipher.isNotBlank())) {
                    add(format)
                }
            }
        }
        val preferred = candidates.sortedWith(
            compareByDescending<JSONObject> { formatPreference(it.optString("mimeType")) }
                .thenByDescending { it.optInt("bitrate") },
        )
        val chosen = when (quality) {
            AudioQuality.DATA_SAVER -> preferred.minByOrNull { it.optInt("bitrate", Int.MAX_VALUE) }
            AudioQuality.NORMAL -> preferred.filter { it.optInt("bitrate") <= 140_000 }.maxByOrNull { it.optInt("bitrate") }
                ?: preferred.minByOrNull { it.optInt("bitrate", Int.MAX_VALUE) }
            // MAX falls through to HIGH when NewPipe failed and the innertube path is used.
            AudioQuality.HIGH, AudioQuality.MAX -> preferred.firstOrNull()
        } ?: error("No direct audio format was returned")

        var rawUrl = chosen.optString("url")
        if (rawUrl.isBlank()) {
            val cipher = chosen.optString("signatureCipher").ifBlank { chosen.optString("cipher") }
            if (cipher.isNotBlank()) {
                val parsed = parseQuery(cipher)
                val targetUrl = parsed["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: error("No url in signatureCipher")
                val encSig = parsed["s"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                val sigParam = parsed["sp"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "sig"
                val solver = challengeSolver ?: error("Challenge solver required for ciphered streams")
                rawUrl = solver.decipherUrl(targetUrl, encSig, sigParam)
            }
        }

        val url = rawUrl.takeIf(String::isNotBlank) ?: error("Failed to resolve audio stream URL")
        val expirySeconds = EXPIRY_PATTERN.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val expiry = expirySeconds?.let { TimeUnit.SECONDS.toMillis(it) }
            ?: System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(45)
        val bitrateKbps = (chosen.optInt("bitrate") / 1000).coerceAtLeast(0)
        Log.d(TAG, "Resolved ${chosen.optString("mimeType").substringBefore(';')} audio @ ${bitrateKbps}kbps")
        return ResolvedStream(url, chosen.optString("mimeType"), expiry, bitrateKbps)
    }

    private fun parseQuery(query: String): Map<String, String> {
        return query.split('&').mapNotNull { param ->
            val parts = param.split('=', limit = 2)
            if (parts.isNotEmpty()) {
                parts[0] to (if (parts.size > 1) parts[1] else "")
            } else null
        }.toMap()
    }

    private fun formatPreference(mime: String): Int = when {
        "opus" in mime -> 3
        "mp4a" in mime -> 2
        else -> 1
    }

    companion object {
        const val CLIENT_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val CLIENT_VERSION = "1.65.10"
        private const val ANDROID_CLIENT_VERSION = "19.29.37"
        private const val ANDROID_USER_AGENT =
            "com.google.android.youtube/19.29.37 (Linux; U; Android 14; Pixel 7)"
        private const val IOS_CLIENT_VERSION = "20.11.6"
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)"
        private const val WEB_REMIX_VERSION = "1.20260213.01.00"
        private const val PUBLIC_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private val FAILURE_BACKOFF_MS = TimeUnit.SECONDS.toMillis(20)
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VISITOR_PATTERN = Regex("\\\"visitorData\\\":\\\"([^\\\"]+)")
        private val EXPIRY_PATTERN = Regex("[?&]expire=(\\d+)")
        private val AGE_GATE_PATTERN = Regex(
            """(?i)confirm[\s_-]*your[\s_-]*age|age[\s_-]*restrict|inappropriate for some users|LOGIN_REQUIRED""",
        )
        private const val TAG = "YouTubeStreamResolver"
    }
}
