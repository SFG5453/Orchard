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

import android.media.MediaCrypto
import android.media.MediaDataSource
import android.media.MediaDrm
import android.media.MediaExtractor
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import dev.sfg.orchard.mobile.playback.MediaItemMapper
import java.util.UUID

/** Widevine for Apple Music items through Apple's license endpoint; other items go to [fallback]. */
@UnstableApi
internal class AppleMusicDrmProvider(
    private val streams: AppleMusicStreams,
    private val http: DataSource.Factory,
    private val fallback: DrmSessionManagerProvider,
) : DrmSessionManagerProvider {
    override fun get(mediaItem: MediaItem): DrmSessionManager {
        val uri = mediaItem.localConfiguration?.uri
        if (!AppleMusicDataSource.isApple(uri)) return fallback.get(mediaItem)
        // One manager per item: the license request names the item's song.
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(Callback(streams, MediaItemMapper.sourceId(uri!!), HttpMediaDrmCallback(null, http)))
    }

    private class Callback(
        private val streams: AppleMusicStreams,
        private val sourceId: String,
        private val provisioning: HttpMediaDrmCallback,
    ) : MediaDrmCallback {
        override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
            provisioning.executeProvisionRequest(uuid, request)

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response =
            MediaDrmCallback.Response(streams.license(sourceId, request.data))
    }
}

/** A cached Apple Music song for analysis, with what [PcmDecoder] needs to decrypt it. */
internal class LicensedMediaDataSource(
    private val inner: MediaDataSource,
    val license: (ByteArray) -> ByteArray,
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = inner.readAt(position, buffer, offset, size)
    override fun getSize() = inner.size
    override fun close() = inner.close()
}

/** A Widevine session holding one song's key, for a MediaCodec decoding outside the player. */
internal class DecoderDrm private constructor(
    private val drm: MediaDrm,
    private val session: ByteArray,
    val crypto: MediaCrypto,
) : AutoCloseable {
    override fun close() {
        runCatching { crypto.release() }
        runCatching { drm.closeSession(session) }
        runCatching { drm.close() }
    }

    companion object {
        private const val TAG = "OrchardAppleMusic"

        /** Null when the extractor's file names no Widevine key or the device cannot decrypt it. */
        fun open(extractor: MediaExtractor, mime: String, license: (ByteArray) -> ByteArray): DecoderDrm? {
            val data = extractor.psshInfo?.get(C.WIDEVINE_UUID) ?: return null
            val drm = MediaDrm(C.WIDEVINE_UUID)
            var session: ByteArray? = null
            try {
                // The player provisions the device when it first plays an Apple song.
                session = drm.openSession()
                val request = drm.getKeyRequest(session, psshBox(data), "audio/mp4", MediaDrm.KEY_TYPE_STREAMING, null)
                drm.provideKeyResponse(session, license(request.data))
                val crypto = MediaCrypto(C.WIDEVINE_UUID, session)
                if (crypto.requiresSecureDecoderComponent(mime)) {
                    crypto.release()
                    throw IllegalStateException("$mime needs a secure decoder")
                }
                return DecoderDrm(drm, session, crypto)
            } catch (error: Exception) {
                Log.w(TAG, "No key for analysis", error)
                session?.let { runCatching { drm.closeSession(it) } }
                drm.close()
                return null
            }
        }

        // MediaExtractor hands back the pssh payload; key requests take the whole box.
        private fun psshBox(data: ByteArray): ByteArray {
            val isBox = data.size >= 8 && String(data, 4, 4, Charsets.US_ASCII) == "pssh"
            return if (isBox) data else AppleMusicStream.psshBox(data)
        }
    }
}
