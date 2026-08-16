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
    /**
     * The client identity the URL was issued to. A CDN URL is bound to the client that
     * asked for it, so fetching one minted by a fallback client under the identity of
     * the first client in the chain is answered with a 403 — resolution appears to work
     * and only the audio fetch fails.
     */
    val userAgent: String = YouTubeStreamResolver.CLIENT_USER_AGENT,
    /** Exact progressive-stream size, used for progress and completeness validation. */
    val contentLength: Long = 0,
    val origin: String? = null,
    val referer: String? = null,
    /** Stable key used to avoid immediately retrying a CDN-rejected client profile. */
    val clientKey: String = "",
    /** NewPipe/MAX URLs can safely be fetched as independent bounded ranges. */
    val supportsParallelRanges: Boolean = false,
) {
    val requestHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", userAgent)
            origin?.let { put("Origin", it) }
            referer?.let { put("Referer", it) }
        }
}

/**
 * Thrown when YouTube refuses playback because the content is age-restricted.
 * Carrying a dedicated type lets the retry chain distinguish an age gate from
 * a generic refusal without parsing message strings at every call site.
 */
private class AgeGateException(message: String) : RuntimeException(message)

/**
 * Thrown when YouTube refuses playback because the content belongs to an account rather than to
 * the public catalog: a YouTube Music upload, or a video the owner set to private.
 *
 * No guest client can ever play these, so the retry chain treats one refusal as the answer for
 * every anonymous profile and moves straight to the signed-in player.
 */
private class AccountOnlyException(message: String) : RuntimeException(message)

/** A player/fetch identity kept together, following ArchiveTune's client catalog model. */
private data class PlayerClientProfile(
    val key: String,
    val name: String,
    val version: String,
    val id: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val useSignatureTimestamp: Boolean = false,
    val youtubeOrigin: Boolean = false,
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
    private val challengeSolver: YouTubeChallengeSolver? = null,
    private val downloadManager: dev.sfg.orchard.mobile.download.DownloadManager? = null,
) {
    private val newPipeResolver: NewPipeStreamResolver by lazy { NewPipeStreamResolver(client, sessionProvider) }

    private val playerConfig = YouTubePlayerConfig(client)

    /**
     * Playback requests get a whole-call deadline the shared client does not set,
     * so a stalled attempt cannot hold up the fallback chain behind it.
     */
    private val playerClient: OkHttpClient =
        client.newBuilder().callTimeout(PLAYER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    /**
     * [signedIn] distinguishes an account identity from a guest one. A cookie
     * alone leaves the player request anonymous as far as YouTube is concerned;
     * the signed request also needs the SAPISIDHASH authorization the catalog
     * calls already build.
     */
    private data class Visitor(val id: String, val cookie: String, val signedIn: Boolean = false)
    private val streams = ConcurrentHashMap<String, ResolvedStream>()

    /**
     * When every client refused a track, briefly, and why. A failed open makes
     * ExoPlayer retry immediately, and each retry is another player request: one track
     * YouTube will not serve turned into dozens of refusals a minute, which is how an
     * address earns a longer block. Failing fast for a few seconds costs nothing, since
     * the answer will not have changed.
     *
     * The reason is kept because a backed-off attempt has no way to work one out for
     * itself, and several resolves race for the same track: without it the second
     * attempt reports the backoff and buries the explanation the first attempt had.
     */
    private val failures = ConcurrentHashMap<String, FailedResolve>()

    /** CDN-rejected profiles are skipped per track for a short period, not globally. */
    private val rejectedClientsUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var lastSuccessfulClientKey: String? = null

    /**
     * Tracks videos for which a player client returned an actual age gate. Catalog metadata's
     * "explicit" flag is only a lyrics advisory and must never select a lower-quality stream.
     */
    private val ageGatedVideos = ConcurrentHashMap.newKeySet<String>()

    private data class FailedResolve(val atMs: Long, val cause: Throwable)
    private val locks = ConcurrentHashMap<String, Any>()
    private val prefetchExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "orchard-stream-prefetch").apply { isDaemon = true }
    }
    private val visitorLock = Any()
    @Volatile private var visitor: Visitor? = null
    @Volatile private var lastVisitorRefreshAtMs: Long = 0L

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
        failures[videoId]?.let { failed ->
            if (System.currentTimeMillis() - failed.atMs < FAILURE_BACKOFF_MS) {
                throw failed.cause
            }
            failures.remove(videoId)
        }
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            cached(cacheKey)?.let { return it }
            val started = System.currentTimeMillis()
            // Each fallback client costs another round trip. Without a shared deadline a
            // few slow-but-not-failing attempts stack into a minute of silence, which
            // reads as the app being broken rather than the track being unavailable.
            fun withinBudget(lastError: Throwable) {
                if (System.currentTimeMillis() - started >= RESOLVE_BUDGET_MS) {
                    Log.w(TAG, "Giving up on $videoId after ${System.currentTimeMillis() - started}ms")
                    throw lastError
                }
            }
            val account = runCatching { accountIdentity(videoId) }
                .onFailure { Log.w(TAG, "Could not build an account identity for playback", it) }
                .getOrNull()
            var visitorIdentity = warmVisitor(videoId)
            var refreshedVisitor = false
            var lastError: Throwable = IllegalStateException("No YouTube player client was available")
            var stream: ResolvedStream? = null

            // ArchiveTune's important reliability property is the profile catalog: native
            // clients are anonymous/visitor requests, use the Music Innertube host, and carry
            // their exact identity into the subsequent googlevideo request. Account cookies are
            // deliberately reserved for web clients that actually support cookie authentication.
            for (profile in orderedClientProfiles(videoId)) {
                withinBudget(lastError)
                val attempt = runCatching {
                    chooseAudio(player(videoId, visitorIdentity, profile), quality)
                }
                if (attempt.isSuccess) {
                    stream = attempt.getOrThrow()
                    lastSuccessfulClientKey = profile.key
                    break
                }
                lastError = attempt.exceptionOrNull() ?: lastError
                if (lastError is AgeGateException) ageGatedVideos += videoId
                Log.w(TAG, "${profile.key} failed for $videoId", lastError)

                // An upload or private video is refused identically by every anonymous client, so
                // walking the rest of the catalog only burns the budget the signed-in player needs.
                if (lastError is AccountOnlyException && account != null) {
                    Log.d(TAG, "$videoId is account-only; skipping the remaining guest clients")
                    break
                }

                // Refresh a persisted guest identity once. Repeating this for every profile
                // creates exactly the burst of watch-page traffic that causes larger batches to
                // fail after their first few tracks.
                if (!refreshedVisitor) {
                    refreshedVisitor = true
                    runCatching { refreshVisitor(videoId) }
                        .onSuccess { visitorIdentity = it }
                        .onFailure { Log.w(TAG, "Could not refresh visitor identity", it) }
                }
            }

            // Signed web playback remains the last normal fallback for account-only or
            // age-gated music. It is never replaced with NewPipe; NewPipe belongs to MAX only.
            // Deliberately outside the budget check: this is the only client that can play
            // account-only music, and dropping it because the guest attempts ran long turns a
            // playable upload into "Video unavailable".
            if (stream == null && account != null) {
                runCatching { chooseAudio(webRemixPlayer(videoId, account), quality) }
                    .onSuccess { stream = it }
                    .onFailure {
                        lastError = it
                        if (it is AgeGateException) ageGatedVideos += videoId
                    }
            }
            val resolved = stream ?: run {
                failures[videoId] = FailedResolve(System.currentTimeMillis(), lastError)
                throw lastError
            }
            if (maxQualityFallback) {
                onWarning?.invoke("Max quality unavailable, using High")
            }
            streams[cacheKey] = resolved
            Log.d(
                TAG,
                "Resolved $videoId as ${if (account != null) "account" else "guest"} " +
                    "in ${System.currentTimeMillis() - started}ms",
            )
            return resolved
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

    /**
     * Resolves a progressive stream through the signed-in Music client.
     *
     * Current YouTube requires a GVS proof token for most direct WEB formats, but
     * deliberately exempts itag 18. Unlike Safari's HLS path, this URL is not held
     * behind the account's pre-roll availability window, so playback can begin as
     * soon as the player request and signature challenge finish.
     */
    fun resolveAuthenticatedDirect(videoId: String): ResolvedStream {
        require(videoId.isNotBlank()) { "A YouTube video id is required" }
        val cacheKey = "$videoId:AUTHENTICATED_DIRECT"
        cached(cacheKey)?.let { return it }
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            cached(cacheKey)?.let { return it }
            val account =
                accountIdentity(videoId)
                    ?: error("Sign in to YouTube to play age-restricted tracks")
            val stream = chooseAuthenticatedItag18(webRemixPlayer(videoId, account))
            streams[cacheKey] = stream
            return stream
        }
    }

    /** Slower fallback for sessions where the direct authenticated format is rejected. */
    fun resolveAuthenticatedHls(videoId: String): ResolvedStream {
        require(videoId.isNotBlank()) { "A YouTube video id is required" }
        val cacheKey = "$videoId:AUTHENTICATED_HLS"
        cached(cacheKey)?.let { return it }
        val lock = locks.computeIfAbsent(cacheKey) { Any() }
        synchronized(lock) {
            cached(cacheKey)?.let { return it }
            val account = accountIdentity(videoId)
                ?: error("Sign in to YouTube to play age-restricted tracks")
            val payload = webSafariPlayer(videoId, account)
            val stream = chooseAudio(payload, qualityProvider(), allowHls = true)
            check(stream.mimeType == HLS_MIME_TYPE) { "Safari did not return an HLS stream" }
            streams[cacheKey] = stream
            return stream
        }
    }

    private fun chooseAuthenticatedItag18(payload: JSONObject): ResolvedStream {
        val streaming = payload.optJSONObject("streamingData") ?: error("No streaming data returned")
        val formats = streaming.optJSONArray("formats") ?: error("No muxed formats returned")
        val format =
            (0 until formats.length())
                .mapNotNull(formats::optJSONObject)
                .firstOrNull { it.optInt("itag") == AUTHENTICATED_DIRECT_ITAG }
                ?: error("YouTube did not return authenticated itag $AUTHENTICATED_DIRECT_ITAG")
        return resolvedFormat(payload, format)
    }

    private fun resolvedFormat(payload: JSONObject, format: JSONObject): ResolvedStream {
        var rawUrl = format.optString("url")
        if (rawUrl.isBlank()) {
            val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
            val parsed = parseQuery(cipher)
            val target =
                parsed["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: error("No url in authenticated signatureCipher")
            val signature = parsed["s"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            val signatureParameter =
                parsed["sp"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "sig"
            rawUrl =
                challengeSolver?.decipherUrl(target, signature, signatureParameter)
                    ?: error("Challenge solver required for authenticated playback")
        }
        val expirySeconds = EXPIRY_PATTERN.find(rawUrl)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val expiry =
            expirySeconds?.let { TimeUnit.SECONDS.toMillis(it) }
                ?: System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(45)
        val bitrateKbps = (format.optInt("bitrate") / 1000).coerceAtLeast(0)
        val fallbackUserAgent = payload.optString(REQUEST_USER_AGENT_KEY).ifBlank { WEB_USER_AGENT }
        val identity = YouTubeStreamRequestIdentity.fromUrl(rawUrl, fallbackUserAgent)
        Log.d(TAG, "Resolved authenticated itag ${format.optInt("itag")} @ ${bitrateKbps}kbps")
        return ResolvedStream(
            url = rawUrl,
            mimeType = format.optString("mimeType"),
            expiresAtMs = expiry,
            bitrateKbps = bitrateKbps,
            userAgent = identity.userAgent,
            contentLength = resolvedContentLength(format, rawUrl),
            origin = identity.origin,
            referer = identity.referer,
            clientKey = identity.clientKey,
        )
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
        return streams["$videoId:AUTHENTICATED_DIRECT"]?.bitrateKbps?.takeIf { it > 0 }
            ?: streams["$videoId:${quality.name}"]?.bitrateKbps?.takeIf { it > 0 }
            ?: streams["$videoId:${AudioQuality.HIGH.name}"]?.bitrateKbps?.takeIf { it > 0 }
            ?: 0
    }

    fun invalidate(videoId: String) {
        streams.keys.removeAll { it.startsWith("$videoId:") }
    }

    /**
     * Records a failed media fetch so the next resolve changes client instead of minting the same
     * rejected URL. Only response codes that indicate an expired/rejected CDN URL trigger this.
     */
    fun reject(videoId: String, stream: ResolvedStream, responseCode: Int) {
        if (responseCode !in RETRYABLE_STREAM_RESPONSE_CODES || stream.clientKey.isBlank()) return
        rejectedClientsUntil["$videoId:${stream.clientKey}"] =
            System.currentTimeMillis() + REJECTED_CLIENT_BACKOFF_MS
        invalidate(videoId)
        failures.remove(videoId)
    }

    /**
     * Consumes evidence that normal resolution encountered an age gate. The playback service uses
     * this only after the normal stream fails, keeping authenticated itag 18 a real fallback.
     */
    fun consumeAgeGate(videoId: String): Boolean = ageGatedVideos.remove(videoId)

    private fun orderedClientProfiles(videoId: String): List<PlayerClientProfile> {
        val now = System.currentTimeMillis()
        rejectedClientsUntil.entries.removeIf { it.value <= now }
        val preferred = lastSuccessfulClientKey?.let { key -> STREAM_CLIENT_PROFILES.find { it.key == key } }
        return buildList {
            preferred?.let(::add)
            addAll(STREAM_CLIENT_PROFILES)
        }.distinctBy { it.key }.filterNot { profile ->
            rejectedClientsUntil["$videoId:${profile.key}"]?.let { it > now } == true
        }
    }

    /** Builds the same client-shaped player request used by ArchiveTune's Innertube core. */
    private fun player(videoId: String, visitor: Visitor, profile: PlayerClientProfile): JSONObject {
        val origin = if (profile.youtubeOrigin) YOUTUBE_ORIGIN else YouTubeSessionAuth.MUSIC_ORIGIN
        val referer = if (profile.youtubeOrigin) "$YOUTUBE_ORIGIN/tv" else "$origin/"
        val clientContext = JSONObject()
            .put("clientName", profile.name)
            .put("clientVersion", profile.version)
            .put("hl", "en")
            .put("gl", "US")
            .put("visitorData", visitor.id)
            .apply {
                profile.osName?.let { put("osName", it) }
                profile.osVersion?.let { put("osVersion", it) }
                profile.deviceMake?.let { put("deviceMake", it) }
                profile.deviceModel?.let { put("deviceModel", it) }
                profile.androidSdkVersion?.let { put("androidSdkVersion", it) }
            }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .apply {
                if (profile.useSignatureTimestamp) {
                    put(
                        "playbackContext",
                        JSONObject().put(
                            "contentPlaybackContext",
                            JSONObject()
                                .put("signatureTimestamp", signatureTimestamp())
                                .put("vis", 0)
                                .put("splay", false)
                                .put("lactMilliseconds", "-1"),
                        ),
                    )
                }
            }
        val request = Request.Builder()
            .url("$origin/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.id)
            .header("X-YouTube-Client-Version", profile.version)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-Goog-Visitor-Id", visitor.id)
            .header("X-Origin", origin)
            .header("Referer", referer)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    private fun loadVisitor(videoId: String, cookie: String? = null): Visitor {
        val url = if (videoId.isNotBlank()) "https://www.youtube.com/watch?v=$videoId" else "https://www.youtube.com"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CLIENT_USER_AGENT)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        playerClient.newCall(request).execute().use { response ->
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

    /** Coalesces refreshes from a multi-track download batch into one watch-page request. */
    private fun refreshVisitor(videoId: String): Visitor = synchronized(visitorLock) {
        val now = System.currentTimeMillis()
        if (now - lastVisitorRefreshAtMs < VISITOR_REFRESH_COALESCE_MS) {
            visitor?.let { return it }
        }
        return loadVisitor(videoId).also {
            rememberVisitor(it)
            lastVisitorRefreshAtMs = now
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
            // A signed-in player request without the account's own visitorData resolves against a
            // different identity than the library page did, which reads as "video unavailable" for
            // anything only that account can see, such as a YouTube Music upload.
            .apply { visitor?.id?.takeIf(String::isNotBlank)?.let { put("visitorData", it) } }
        val userContext = JSONObject()
            .put("lockedSafetyMode", false)
            // Uploads belong to a channel, not to the Google account, so the delegation id is what
            // ties the request to the channel that owns them.
            .apply {
                if (visitor?.signedIn == true) {
                    sessionProvider?.session()?.dataSyncId?.takeIf(String::isNotBlank)
                        ?.let { put("onBehalfOfUser", it) }
                }
            }
        val playbackContext = JSONObject()
            .put(
                "contentPlaybackContext",
                JSONObject().put("signatureTimestamp", signatureTimestamp()),
            )
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
                        header("X-Youtube-Bootstrap-Logged-In", "true")
                    } else if (it.id.isNotBlank()) {
                        header("X-Goog-Visitor-Id", it.id)
                    }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executePlayer(request)
    }

    /**
     * Safari's web player exposes HLS for signed-in non-Premium accounts. YouTube's
     * direct WEB/WEB_REMIX URLs now require a GVS proof-of-origin token, while HLS
     * remains playable without one.
     */
    private fun webSafariPlayer(videoId: String, visitor: Visitor?): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", WEB_CLIENT_VERSION)
            .put("userAgent", WEB_SAFARI_USER_AGENT)
            .put("hl", "en")
            .put("gl", "US")
            .apply { visitor?.id?.takeIf(String::isNotBlank)?.let { put("visitorData", it) } }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject()
                        .put("html5Preference", "HTML5_PREF_WANTS")
                        .put("signatureTimestamp", signatureTimestamp()),
                ),
            )
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", WEB_SAFARI_USER_AGENT)
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", WEB_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .apply {
                visitor?.let {
                    if (it.id.isNotBlank()) header("X-Goog-Visitor-Id", it.id)
                    if (it.cookie.isNotBlank()) header("Cookie", it.cookie)
                    if (it.signedIn) {
                        YouTubeSessionAuth.authorization(it.cookie, origin = "https://www.youtube.com")?.let { auth ->
                            header("Authorization", auth)
                        }
                        header("X-Goog-AuthUser", "0")
                        header("X-Origin", "https://www.youtube.com")
                        header("X-Youtube-Bootstrap-Logged-In", "true")
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
                .put("signatureTimestamp", signatureTimestamp()))
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
        playerClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            Log.d(TAG, "executePlayer for ${request.url} (code ${response.code}): $text")
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val status = payload.optJSONObject("playabilityStatus")
            if (!response.isSuccessful || status?.optString("status") != "OK") {
                val reason = status?.optString("reason").orEmpty().ifBlank { "HTTP ${response.code}" }
                val statusCode = status?.optString("status").orEmpty()
                // YouTube says this, and only this, when the quoted signature timestamp
                // belongs to a player it has since rotated away from. Dropping the cached
                // value lets the next attempt re-read it rather than repeating a request
                // already known to be refused.
                if (reason.contains("needs to be reloaded", ignoreCase = true) ||
                    status?.optJSONObject("errorScreen")
                        ?.toString()
                        .orEmpty()
                        .contains("needs to be reloaded", ignoreCase = true)
                ) {
                    Log.w(TAG, "Player rejected the signature timestamp; refreshing it")
                    playerConfig.invalidate()
                    challengeSolver?.invalidatePlayer()
                }
                // Checked before the age gate: an upload refusal also arrives as LOGIN_REQUIRED,
                // and only the private/upload wording tells the two apart.
                if (isAccountOnlyResponse(reason)) {
                    throw AccountOnlyException("YouTube could not play this track: $reason")
                }
                // Distinguish age-gated refusals so the retry chain can
                // route them to the web/music players instead of a guest retry.
                if (isAgeGateResponse(statusCode, reason)) {
                    throw AgeGateException("YouTube could not play this track: $reason")
                }
                error("YouTube could not play this track: $reason")
            }
            // Which client asked is not recoverable from the response, and the CDN URLs
            // inside it are only valid for that client, so the identity travels with the
            // payload to whoever picks a format out of it.
            request.header("User-Agent")?.let { payload.put(REQUEST_USER_AGENT_KEY, it) }
            return payload
        }
    }

    private fun isAgeGateResponse(status: String, reason: String): Boolean {
        val text = "$status $reason"
        return AGE_GATE_PATTERN.containsMatchIn(text)
    }

    private fun isAccountOnlyResponse(reason: String): Boolean = ACCOUNT_ONLY_PATTERN.containsMatchIn(reason)

    /**
     * Uses the timestamp from the same player script that will decipher returned
     * signatures. [playerConfig] remains the fallback for resolver-only tests and
     * callers that do not install the WebView challenge solver.
     */
    private fun signatureTimestamp(): Int =
        challengeSolver?.signatureTimestamp() ?: playerConfig.signatureTimestamp()

    private fun chooseAudio(
        payload: JSONObject,
        quality: AudioQuality,
        allowHls: Boolean = false,
    ): ResolvedStream {
        val streaming = payload.optJSONObject("streamingData") ?: error("No streaming data returned")
        val userAgent = payload.optString(REQUEST_USER_AGENT_KEY).ifBlank { CLIENT_USER_AGENT }
        streaming.optString("hlsManifestUrl").takeIf { allowHls && it.isNotBlank() }?.let { rawHlsUrl ->
            val hlsUrl = challengeSolver?.decipherManifestUrl(rawHlsUrl) ?: rawHlsUrl
            val expirySeconds = EXPIRY_PATTERN.find(hlsUrl)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val expiry = expirySeconds?.let { TimeUnit.SECONDS.toMillis(it) }
                ?: System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(45)
            Log.d(TAG, "Resolved HLS manifest for authenticated web playback")
            val identity = YouTubeStreamRequestIdentity.fromUrl(hlsUrl, userAgent)
            return ResolvedStream(
                hlsUrl,
                HLS_MIME_TYPE,
                expiry,
                0,
                identity.userAgent,
                origin = identity.origin,
                referer = identity.referer,
                clientKey = identity.clientKey,
            )
        }
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
        val identity = YouTubeStreamRequestIdentity.fromUrl(url, userAgent)
        Log.d(TAG, "Resolved ${chosen.optString("mimeType").substringBefore(';')} audio @ ${bitrateKbps}kbps")
        return ResolvedStream(
            url = url,
            mimeType = chosen.optString("mimeType"),
            expiresAtMs = expiry,
            bitrateKbps = bitrateKbps,
            userAgent = identity.userAgent,
            contentLength = resolvedContentLength(chosen, url),
            origin = identity.origin,
            referer = identity.referer,
            clientKey = identity.clientKey,
        )
    }

    private fun resolvedContentLength(format: JSONObject, url: String): Long =
        format.optString("contentLength").toLongOrNull()
            ?: CONTENT_LENGTH_PATTERN.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: 0L

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
        // Profile values are adapted from ArchiveTune's GPL-3.0 Innertube client catalog:
        // https://github.com/rukamori/ArchiveTune (inspected 2026-08-15).
        private val STREAM_CLIENT_PROFILES = listOf(
            PlayerClientProfile(
                key = "ANDROID_VR@1.65.10",
                name = "ANDROID_VR",
                version = "1.65.10",
                id = "28",
                userAgent = CLIENT_USER_AGENT,
                osName = "Android",
                osVersion = "12L",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = 32,
            ),
            PlayerClientProfile(
                key = "ANDROID_VR@1.61.48",
                name = "ANDROID_VR",
                version = "1.61.48",
                id = "28",
                userAgent = YouTubeStreamRequestIdentity.ANDROID_VR_1_61_USER_AGENT,
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = 32,
            ),
            PlayerClientProfile(
                key = "IOS@21.26.4",
                name = "IOS",
                version = "21.26.4",
                id = "5",
                userAgent = YouTubeStreamRequestIdentity.IOS_USER_AGENT,
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
            ),
            PlayerClientProfile(
                key = "ANDROID@21.26.364",
                name = "ANDROID",
                version = "21.26.364",
                id = "3",
                userAgent = YouTubeStreamRequestIdentity.ANDROID_USER_AGENT,
                osName = "Android",
                osVersion = "11",
                useSignatureTimestamp = true,
            ),
            PlayerClientProfile(
                key = "ANDROID_MUSIC@7.27.52",
                name = "ANDROID_MUSIC",
                version = "7.27.52",
                id = "21",
                userAgent = YouTubeStreamRequestIdentity.ANDROID_MUSIC_USER_AGENT,
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
                useSignatureTimestamp = true,
            ),
            PlayerClientProfile(
                key = "IOS_MUSIC@7.27.0",
                name = "IOS_MUSIC",
                version = "7.27.0",
                id = "26",
                userAgent = YouTubeStreamRequestIdentity.IOS_MUSIC_USER_AGENT,
                osName = "iOS",
                osVersion = "17.5.1.21F90",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
            ),
            PlayerClientProfile(
                key = "ANDROID_TESTSUITE@1.9",
                name = "ANDROID_TESTSUITE",
                version = "1.9",
                id = "30",
                userAgent = YouTubeStreamRequestIdentity.ANDROID_TESTSUITE_USER_AGENT,
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
            ),
            PlayerClientProfile(
                key = "ANDROID_UNPLUGGED@8.49.0",
                name = "ANDROID_UNPLUGGED",
                version = "8.49.0",
                id = "29",
                userAgent = YouTubeStreamRequestIdentity.ANDROID_UNPLUGGED_USER_AGENT,
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = 35,
                useSignatureTimestamp = true,
            ),
            PlayerClientProfile(
                key = "IOS@19.22.3",
                name = "IOS",
                version = "19.22.3",
                id = "5",
                userAgent = YouTubeStreamRequestIdentity.IPAD_USER_AGENT,
                osName = "iPadOS",
                osVersion = "17.7.10.21H450",
                deviceMake = "Apple",
                deviceModel = "iPad7,6",
            ),
            PlayerClientProfile(
                key = "VISIONOS@0.1",
                name = "VISIONOS",
                version = "0.1",
                id = "101",
                userAgent = YouTubeStreamRequestIdentity.VISION_OS_USER_AGENT,
                osName = "visionOS",
                osVersion = "1.3.21O771",
                deviceMake = "Apple",
                deviceModel = "RealityDevice14,1",
            ),
            PlayerClientProfile(
                key = "TVHTML5@7.20260707.07.00",
                name = "TVHTML5",
                version = "7.20260707.07.00",
                id = "7",
                userAgent = YouTubeStreamRequestIdentity.TV_USER_AGENT,
                useSignatureTimestamp = true,
                youtubeOrigin = true,
            ),
        )

        /**
         * Where [executePlayer] records the identity a response was fetched under. Not
         * part of YouTube's schema; it rides along inside the parsed payload because the
         * format chosen from it is only usable by that same client.
         */
        private const val REQUEST_USER_AGENT_KEY = "__orchardRequestUserAgent"

        const val CLIENT_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val CLIENT_VERSION = "1.65.10"
        private const val ANDROID_CLIENT_VERSION = "21.26.364"
        private const val ANDROID_USER_AGENT = YouTubeStreamRequestIdentity.ANDROID_USER_AGENT
        private const val IOS_CLIENT_VERSION = "21.26.4"
        private const val IOS_USER_AGENT = YouTubeStreamRequestIdentity.IOS_USER_AGENT
        private const val WEB_REMIX_VERSION = "1.20260707.12.00"
        private const val WEB_CLIENT_VERSION = "2.20260708.00.00"
        const val WEB_SAFARI_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_4) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.3 Safari/605.1.15"
        private const val PUBLIC_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private val FAILURE_BACKOFF_MS = TimeUnit.SECONDS.toMillis(20)
        private val REJECTED_CLIENT_BACKOFF_MS = TimeUnit.MINUTES.toMillis(10)
        private val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        private val VISITOR_REFRESH_COALESCE_MS = TimeUnit.SECONDS.toMillis(30)

        /**
         * Ceiling on a single player or watch-page request. The shared client only
         * bounds connect and read separately, so one stalled attempt could otherwise
         * burn 37s on its own and the fallback chain would multiply that.
         */
        private const val PLAYER_CALL_TIMEOUT_SECONDS = 10L

        /**
         * Ceiling on resolving one track across every fallback client. Once it is
         * spent, the last failure is reported instead of trying the next client:
         * a caller waiting a minute for audio has already given up.
         */
        private val RESOLVE_BUDGET_MS = TimeUnit.SECONDS.toMillis(25)
        private const val WEB_USER_AGENT = YouTubeStreamRequestIdentity.WEB_USER_AGENT
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VISITOR_PATTERN = Regex("\\\"visitorData\\\":\\\"([^\\\"]+)")
        private val EXPIRY_PATTERN = Regex("[?&]expire=(\\d+)")
        private val CONTENT_LENGTH_PATTERN = Regex("[?&]clen=(\\d+)")
        private val AGE_GATE_PATTERN = Regex(
            """(?i)confirm[\s_-]*your[\s_-]*age|age[\s_-]*restrict|inappropriate for some users|LOGIN_REQUIRED""",
        )

        /**
         * Mirrors the desktop's private-playback detection so both clients route uploads the same
         * way. YouTube Music uploads refuse guest players with the private-video wording.
         */
        internal val ACCOUNT_ONLY_PATTERN = Regex(
            """(?i)(?:this )?video (?:is|has been set to) private|private video|only available to (?:the )?owner""",
        )
        private const val AUTHENTICATED_DIRECT_ITAG = 18
        private const val HLS_MIME_TYPE = "application/x-mpegURL"
        private const val TAG = "YouTubeStreamResolver"
    }
}
