package dev.sfg.orchard.mobile.playback.apple

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import dev.sfg.orchard.mobile.applemusic.AppleMusicCatalog
import dev.sfg.orchard.mobile.playback.MediaItemMapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
internal class AppleMusicAtmos(private val catalog: AppleMusicCatalog, private val http: OkHttpClient) {
    private data class Decision(val info: JSONObject, val expires: Long)
    private val decisions = ConcurrentHashMap<String, Decision>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val songs = ConcurrentHashMap<String, JSONObject>()
    private val initKeys = ConcurrentHashMap<String, List<ByteArray>>()
    private val bitrates = ConcurrentHashMap<String, Int>()
    private data class Prepared(val playlist: AtmosPlaylist, val expires: Long)
    private val prepared = ConcurrentHashMap<String, Prepared>()

    fun mediaSourceFactory(httpFactory: DataSource.Factory): HlsMediaSource.Factory =
        HlsMediaSource.Factory(DataSource.Factory { AtmosDataSource(httpFactory.createDataSource(), ::playlist, initKeys) })
            .setDrmSessionManagerProvider { item ->
                val id = MediaItemMapper.sourceId(item.localConfiguration!!.uri)
                val provision = HttpMediaDrmCallback(null, httpFactory)
                DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { KeyUriMediaDrm(FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(it)) }
                    .setMultiSession(true)
                    .build(object : MediaDrmCallback {
                        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) = provision.executeProvisionRequest(uuid, request)
                        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
                            val info = JSONObject(songs[id].toString()).put("keyUri", request.licenseServerUrl)
                            return MediaDrmCallback.Response(runBlocking { catalog.license(info, request.data) })
                        }
                    })
            }

    private fun download(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw java.io.IOException("Apple Music playlist HTTP ${it.code}")
        it.body.string()
    }

    fun available(id: String): Boolean {
        fun cached(): Boolean? = decisions[id]?.takeIf { it.expires > System.currentTimeMillis() }?.info?.optBoolean("available")
        cached()?.let { return it }
        return synchronized(locks.computeIfAbsent(id) { Any() }) {
            cached()?.let { return@synchronized it }
            val info = runBlocking { catalog.atmos(id) }
            // Building the playlist here lets a warmed song skip both playlist downloads at play time.
            val offered = info.optBoolean("available")
            val ready = offered && runCatching { prepare(id, info) }
                .onFailure { Log.i(TAG, "Atmos playlist unavailable for $id: ${it.message}") }.isSuccess
            if (!ready) info.put("available", false)
            // A failed playlist fetch may be transient, so it is retried sooner than Apple's answer.
            val ttl = if (offered && !ready) 300_000L else 3_600_000L
            decisions[id] = Decision(info.apply { remove("masterText") }, System.currentTimeMillis() + ttl)
            ready
        }
    }

    /** Resolves Atmos for an upcoming song off the playback path. */
    fun warm(id: String) {
        runCatching { available(id) }
    }

    private fun prepare(id: String, info: JSONObject): AtmosPlaylist {
        val master = info.getString("master")
        val variant = atmosVariant(info.optString("masterText").ifEmpty { download(master) }, master)
        val result = atmosPlaylist(download(variant.url), variant.url)
        songs[id] = info
        bitrates[id] = variant.bitrateKbps
        initKeys[result.initPath] = result.keys
        prepared[id] = Prepared(result, System.currentTimeMillis() + PLAYLIST_TTL_MS)
        return result
    }

    fun selected(id: String): Boolean = decisions[id]?.let { it.expires > System.currentTimeMillis() && it.info.optBoolean("available") } == true

    fun bitrateKbps(id: String): Int = bitrates[id] ?: 0

    private fun playlist(uri: Uri): ByteArray {
        val id = MediaItemMapper.sourceId(uri)
        prepared[id]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it.playlist.text.toByteArray() }
        val info = songs[id] ?: throw java.io.IOException("Atmos metadata expired")
        return prepare(id, info).text.toByteArray()
    }

    private companion object {
        const val TAG = "OrchardAtmos"
        const val PLAYLIST_TTL_MS = 10 * 60_000L
    }

}

@UnstableApi
private class AtmosDataSource(private val upstream: DataSource, private val playlist: (Uri) -> ByteArray,
    private val keys: Map<String, List<ByteArray>>) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; upstream.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val bytes = when {
            uri.scheme == "orchard" -> playlist(uri)
            dataSpec.position == 0L && keys.containsKey(uri.toString().substringBefore('?')) -> {
                if (dataSpec.length <= 0 || dataSpec.length > 1_048_576) throw java.io.IOException("Invalid Atmos init range")
                upstream.open(dataSpec)
                val out = ByteArrayOutputStream()
                try { val b = ByteArray(16384); while (true) { val n = upstream.read(b, 0, b.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(b, 0, n) } }
                finally { upstream.close() }
                val keyIds = keys[uri.toString().substringBefore('?')]!!
                patchAtmosInit(out.toByteArray(), keyIds)
            }
            else -> null
        }
        val source = bytes?.let { ByteArrayDataSource(it).also { b -> listeners.forEach(b::addTransferListener) } } ?: upstream
        current = source
        return source.open(if (bytes == null) dataSpec else dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders() = current?.responseHeaders ?: emptyMap()
    override fun close() { current?.close(); current = null }
}
