/* Copyright (C) 2026 SFG545. Licensed under the GNU Affero General Public License v3 or later. */
package dev.sfg.orchard.mobile.playback.apple

import android.media.MediaCodecList
import android.media.MediaFormat

/** Shared by playback and settings so the option is shown only on capable devices. */
internal object AppleAtmosCapability {
    val decoderAvailable: Boolean by lazy {
        runCatching {
            val format = MediaFormat.createAudioFormat("audio/eac3-joc", 48_000, 6)
            MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format) != null
        }.getOrDefault(false)
    }
}
