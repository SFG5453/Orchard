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
        val quality = qualityProvider()
        val cacheKey = "$videoId:${quality.name}"
        cached(cacheKey)?.let { return it }
        // MAX uses NewPipe for the highest bitrate stream.
        if (quality == AudioQuality.MAX) {
            val newPipeStream = newPipeResolver.resolve(videoId)
            if (newPipeStream != null) {
                streams[cacheKey] = newPipeStream
                return newPipeStream
            }
            // NewPipe failed; fall back to HIGH and tell the user.
            Log.w(TAG, "NewPipe extraction failed for $videoId; falling back to HIGH quality")
            onWarning?.invoke("Max quality unavailable, using High")
            val fallbackKey = "$videoId:${AudioQuality.HIGH.name}"
            cached(fallbackKey)?.let { streams[cacheKey] = it; return it }
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
            Log.d(TAG, "Resolving $videoId as ${if (account != null) "account" else "guest"}")
            val response = runCatching {
                androidVrPlayer(videoId, account ?: warmVisitor(videoId))
            }
                // A stored identity can go stale, and YouTube then answers with a bot check.
                // One retry on a fresh identity is cheaper than never trusting the cache.
                // Signed in, this is also the guest retry: the account may simply
                // not be entitled to this track.
                .recoverCatching {
                    Log.w(TAG, "Retrying with a fresh visitor identity", it)
                    androidVrPlayer(videoId, loadVisitor(videoId).also(::rememberVisitor))
                }
                .onFailure { Log.w(TAG, "Android VR playback client was unavailable; trying iOS", it) }
                .getOrElse {
                    runCatching { iosPlayer(videoId) }
                        .onFailure { failures[videoId] = System.currentTimeMillis() }
                        .getOrThrow()
                }
            val stream = chooseAudio(response, quality)
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

    /**
     * Adds the two cookies a browser would always be carrying. Without a consent
     * record YouTube treats the request as one that has never seen a consent
     * wall, which reads as automation no matter whose account is attached.
     */
    private fun playbackCookie(cookie: String): String {
        val parts = cookie.split(';').map(String::trim).filter(String::isNotEmpty)
        val names = parts.map { it.substringBefore('=') }.toSet()
        return buildList {
            addAll(parts)
            if ("SOCS" !in names) add("SOCS=CAI")
            if ("PREF" !in names) add("PREF=f2=8000000&hl=en")
        }.joinToString("; ")
    }

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

    /**
     * Reads a visitor identity from a watch page. [cookie], when given, fetches
     * the page as the signed-in account so the identity that comes back belongs
     * to it; the account's own cookie is then kept rather than the page's.
     */
    private fun loadVisitor(videoId: String, cookie: String = ""): Visitor {
        // The warm-up path has no track in hand; the home page carries an identity just as well.
        val url = if (videoId.isBlank()) "https://www.youtube.com/"
        else "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_USER_AGENT)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        client.newCall(request).execute().use { response ->
            val html = response.body.string()
            if (!response.isSuccessful) error("YouTube visitor setup failed with HTTP ${response.code}")
            val id = VISITOR_PATTERN.find(html)?.groupValues?.getOrNull(1)
                ?: error("YouTube did not return a visitor identity")
            if (cookie.isNotBlank()) return Visitor(id, cookie)
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
                    header("X-Goog-Visitor-Id", it.id)
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun iosPlayer(videoId: String): JSONObject {
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
                        .put("gl", "US"),
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
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun executePlayer(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val status = payload.optJSONObject("playabilityStatus")
            if (!response.isSuccessful || status?.optString("status") != "OK") {
                val reason = status?.optString("reason").orEmpty().ifBlank { "HTTP ${response.code}" }
                error("YouTube could not play this track: $reason")
            }
            return payload
        }
    }

    private fun chooseAudio(payload: JSONObject, quality: AudioQuality): ResolvedStream {
        val streaming = payload.optJSONObject("streamingData") ?: error("No streaming data returned")
        val formats = streaming.optJSONArray("adaptiveFormats") ?: JSONArray()
        val candidates = buildList {
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val mime = format.optString("mimeType")
                val url = format.optString("url")
                if (mime.startsWith("audio/") && url.isNotBlank()) add(format)
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
        val url = chosen.getString("url")
        val expirySeconds = EXPIRY_PATTERN.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val expiry = expirySeconds?.let { TimeUnit.SECONDS.toMillis(it) }
            ?: System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(45)
        val bitrateKbps = (chosen.optInt("bitrate") / 1000).coerceAtLeast(0)
        Log.d(TAG, "Resolved ${chosen.optString("mimeType").substringBefore(';')} audio @ ${bitrateKbps}kbps")
        return ResolvedStream(url, chosen.optString("mimeType"), expiry, bitrateKbps)
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
        private const val IOS_CLIENT_VERSION = "20.11.6"
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)"
        private const val PUBLIC_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private val FAILURE_BACKOFF_MS = TimeUnit.SECONDS.toMillis(20)
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VISITOR_PATTERN = Regex("\\\"visitorData\\\":\\\"([^\\\"]+)")
        private val EXPIRY_PATTERN = Regex("[?&]expire=(\\d+)")
        private const val TAG = "YouTubeStreamResolver"
    }
}
