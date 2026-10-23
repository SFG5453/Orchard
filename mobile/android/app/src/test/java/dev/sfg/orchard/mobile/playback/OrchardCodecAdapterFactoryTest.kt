package dev.sfg.orchard.mobile.playback

import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class OrchardCodecAdapterFactoryTest {
    @Test
    fun encryptedSoftwareAacUsesSynchronousQueueing() {
        assertEquals("synchronous", selectedFactory(MimeTypes.AUDIO_AAC, encrypted = true))
    }

    @Test
    fun otherStreamsKeepDefaultQueueing() {
        assertEquals("default", selectedFactory(MimeTypes.AUDIO_AAC, encrypted = false))
        assertEquals("default", selectedFactory(MimeTypes.AUDIO_AAC, encrypted = true, software = false))
        assertEquals("default", selectedFactory(MimeTypes.AUDIO_OPUS, encrypted = true))
        assertEquals("default", selectedFactory(MimeTypes.VIDEO_H264, encrypted = true))
    }

    private fun selectedFactory(mime: String, encrypted: Boolean, software: Boolean = true): String {
        val format = Format.Builder().setSampleMimeType(mime).apply {
            if (encrypted) setDrmInitData(DrmInitData(
                DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", byteArrayOf(1)),
            ))
        }.build()
        val info = MediaCodecInfo.newInstance(
            "test.decoder", mime, mime, null, !software, software, false, false, false,
        )
        val configuration = MediaCodecAdapter.Configuration.createForAudioDecoding(info, MediaFormat(), format, null, null)
        val factory = OrchardCodecAdapterFactory(
            { throw IOException("default") },
            { throw IOException("synchronous") },
        )
        return try {
            factory.createAdapter(configuration)
            error("Expected the selected delegate to throw")
        } catch (error: IOException) {
            error.message!!
        }
    }
}
