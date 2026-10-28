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

package dev.sfg.orchard.mobile.playback.apple

import android.media.MediaDrm
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import dev.sfg.orchard.mobile.applemusic.AppleMusicCatalog
import dev.sfg.orchard.mobile.applemusic.AppleMusicException
import dev.sfg.orchard.mobile.playback.MediaItemMapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Apple Music videos as HLS with Widevine. The master playlist is served filtered to the
 * renditions this device may decrypt; the picture and its soundtrack carry different keys,
 * each licensed by its own playlist key URI. Calls block, so only Media3 threads use it.
 */
@UnstableApi
internal class AppleMusicVideos(
    private val catalog: AppleMusicCatalog,
    private val http: OkHttpClient,
    /** Null while Apple videos may play, else what the listener should do first. */
    private val blocked: () -> String?,
    /** Default picture height, 0 for the best available. */
    private val maxHeight: () -> Int,
) {
    private class Video(val reply: JSONObject, val expiresAtMs: Long)

    private val videos = ConcurrentHashMap<String, Video>()
    /** Init segment path to the key its playlist names; see [initSegmentKey]. */
    private val keyIds = ConcurrentHashMap<String, ByteArray>()

    /** Phones with hardware Widevine may play Apple's HD renditions; others stop at 486p. */
    private val hardware: Boolean by lazy {
        runCatching { MediaDrm(C.WIDEVINE_UUID).use { it.getPropertyString("securityLevel") == "L1" } }.getOrDefault(false)
    }

    fun mediaSourceFactory(httpFactory: DataSource.Factory): HlsMediaSource.Factory =
        HlsMediaSource.Factory(DataSource.Factory { VideoDataSource(httpFactory.createDataSource(), ::master, keyIds) })
            .setDrmSessionManagerProvider { item -> drm(item, httpFactory) }

    fun clear() {
        videos.clear()
        keyIds.clear()
    }

    private fun video(sourceId: String): JSONObject {
        videos[sourceId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.reply }
        blocked()?.let { throw AppleMusicPlaybackException(it) }
        val reply = apple { catalog.video(sourceId) }
        videos[sourceId] = Video(reply, System.currentTimeMillis() + LIFETIME_MS)
        return reply
    }

    private fun master(uri: Uri): ByteArray {
        val sourceId = MediaItemMapper.sourceId(uri)
        val url = video(sourceId).optString("master")
        val text = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Apple Music's video playlist failed (HTTP ${response.code})")
            response.body.string()
        }
        val height = MediaItemMapper.videoHeight(uri) ?: maxHeight()
        return filterMaster(text, url, hardware, height).toByteArray()
    }

    private fun license(sourceId: String, keyUri: String, challenge: ByteArray): ByteArray {
        if (keyUri.isBlank()) throw AppleMusicPlaybackException("Apple Music's video names no Widevine key.")
        val stream = JSONObject(video(sourceId).toString()).put("keyUri", keyUri)
        return apple { catalog.license(stream, challenge) }.also {
            if (it.isEmpty()) throw AppleMusicPlaybackException("Apple Music did not license this video.")
        }
    }

    private fun drm(item: MediaItem, httpFactory: DataSource.Factory): DrmSessionManager {
        val sourceId = MediaItemMapper.sourceId(item.localConfiguration!!.uri)
        val provisioning = HttpMediaDrmCallback(null, httpFactory)
        val callback = object : MediaDrmCallback {
            override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) =
                provisioning.executeProvisionRequest(uuid, request)

            override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest) =
                MediaDrmCallback.Response(license(sourceId, request.licenseServerUrl, request.data))
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                KeyUriMediaDrm(FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(uuid))
            }
            // The picture and the soundtrack each need their own key session.
            .setMultiSession(true)
            .build(callback)
    }

    private fun <T> apple(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (error: AppleMusicException) {
        throw AppleMusicPlaybackException(error.message ?: "Apple Music could not play this video.", error)
    } catch (error: IllegalStateException) {
        throw AppleMusicPlaybackException(error.message ?: "Connect Apple Music in Settings to play this video.", error)
    }

    companion object {
        private const val LIFETIME_MS = 60 * 60_000L

        fun isAppleVideo(uri: Uri?): Boolean =
            uri != null && MediaItemMapper.isVideoUri(uri) && MediaItemMapper.sourceId(uri).startsWith("am.music-videos.")
    }
}

/**
 * Hands the playlist key behind each request to the license callback as the request's URL.
 * Apple licenses by that `data:` URI, which holds exactly the pssh box Media3 parsed from it.
 */
@UnstableApi
private class KeyUriMediaDrm(private val inner: ExoMediaDrm) : ExoMediaDrm by inner {
    override fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: List<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?,
    ): ExoMediaDrm.KeyRequest {
        val request = inner.getKeyRequest(scope, schemeDatas, keyType, optionalParameters)
        val pssh = schemeDatas?.firstOrNull { it.matches(C.WIDEVINE_UUID) && it.data != null }?.data
        val keyUri = pssh?.let { "data:text/plain;base64," + Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()
        return ExoMediaDrm.KeyRequest(request.data, keyUri, request.requestType)
    }
}

/**
 * Serves an Apple video's filtered master playlist and fetches the rest through [http]. Media
 * playlists are read to learn each init segment's key, which is written into that segment.
 */
@UnstableApi
private class VideoDataSource(
    private val http: DataSource,
    private val master: (Uri) -> ByteArray,
    private val keyIds: MutableMap<String, ByteArray>,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        http.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val path = segmentPath(uri.toString())
        val bytes = when {
            AppleMusicVideos.isAppleVideo(uri) -> master(uri)
            path.endsWith(".m3u8") -> download(dataSpec).also { playlist ->
                initSegmentKey(String(playlist), uri.toString())?.let { (init, keyId) -> keyIds[init] = keyId }
            }
            else -> keyIds[path]?.let { keyId -> withKeyId(download(dataSpec), keyId) }
        }
        val source = bytes?.let { ByteArrayDataSource(it).also { served -> listeners.forEach(served::addTransferListener) } } ?: http
        current = source
        // The bytes already cover the requested range.
        return source.open(if (bytes != null) dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build() else dataSpec)
    }

    private fun download(dataSpec: DataSpec): ByteArray {
        http.open(dataSpec)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = http.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } finally {
            http.close()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
