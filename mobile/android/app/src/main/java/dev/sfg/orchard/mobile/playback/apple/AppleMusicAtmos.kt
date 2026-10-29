package dev.sfg.orchard.mobile.playback.apple

import android.net.Uri
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
            decisions[id] = Decision(info, System.currentTimeMillis() + 3_600_000L)
            if (info.optBoolean("available")) songs[id] = info
            info.optBoolean("available")
        }
    }

    fun selected(id: String): Boolean = decisions[id]?.let { it.expires > System.currentTimeMillis() && it.info.optBoolean("available") } == true

    fun bitrateKbps(id: String): Int = bitrates[id] ?: 0

    private fun playlist(uri: Uri): ByteArray {
        val id = MediaItemMapper.sourceId(uri)
        val info = songs[id] ?: throw java.io.IOException("Atmos metadata expired")
        val master = info.getString("master")
        val variant = atmosVariant(download(master), master)
        val result = atmosPlaylist(download(variant.url), variant.url)
        bitrates[id] = variant.bitrateKbps
        initKeys[result.initPath] = result.keys
        return result.text.toByteArray()
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
