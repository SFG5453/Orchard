package dev.sfg.orchard.mobile.playback

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter

/** Avoids per-frame callback and crypto worker handoffs for encrypted software AAC. */
@UnstableApi
internal class OrchardCodecAdapterFactory(
    private val defaultFactory: MediaCodecAdapter.Factory,
    private val aacFactory: MediaCodecAdapter.Factory = SynchronousMediaCodecAdapter.Factory(),
) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val synchronous = configuration.format.sampleMimeType == MimeTypes.AUDIO_AAC &&
            configuration.format.drmInitData != null && configuration.codecInfo.softwareOnly
        return (if (synchronous) aacFactory else defaultFactory).createAdapter(configuration)
    }
}
