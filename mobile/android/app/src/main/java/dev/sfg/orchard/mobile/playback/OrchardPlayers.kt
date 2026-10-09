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

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import dev.sfg.orchard.mobile.playback.smart.MixSplicer
import dev.sfg.orchard.mobile.playback.smart.SplicingAudioChain
import dev.sfg.orchard.mobile.playback.smart.TransitionFilter

internal val ORCHARD_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .setUsage(C.USAGE_MEDIA)
    .build()

private const val BUFFER_FOR_PLAYBACK_MS = 500
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
private const val WHOLE_TRACK_BUFFER_MS = 20 * 60 * 1_000
// Two players plus playlist preloading share the app heap with audio analysis.
private const val TARGET_BUFFER_BYTES = 8 * 1024 * 1024

/** One deck of the crossfade pair, with Orchard's processors inside its audio sink. */
@UnstableApi
internal fun buildOrchardPlayer(
    context: Context,
    mediaSourceFactory: MediaSource.Factory,
    splicer: MixSplicer,
    filter: TransitionFilter,
    eq: EqualizerAudioProcessor,
    volume: PlaybackVolumeState,
    handlesAudioFocus: Boolean,
): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            WHOLE_TRACK_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
    // The fades, splices and filter rides have to happen inside the sink; player volume only
    // scales the whole signal, and only from the main thread.
    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink = VolumeAudioSink(DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(SplicingAudioChain(splicer, filter, eq))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build(), volume)
    }
    return ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .build()
        .apply {
            // Give Media3 room to prepare upcoming periods while the active deck is playing.
            setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
            setAudioAttributes(ORCHARD_AUDIO_ATTRIBUTES, handlesAudioFocus)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            keepQueueOrderUnshuffled()
        }
}

/**
 * Makes shuffle mode a flag rather than a second, invisible running order.
 *
 * Orchard shuffles by reordering the queue, but ExoPlayer's own shuffle is a separate random
 * permutation driving next/previous while the queue is projected in timeline order, so with
 * shuffle on, Next would play something the list never showed. The order clones itself across
 * edits, so only a wholesale `setMediaItems` needs a re-assert.
 */
@UnstableApi
internal fun ExoPlayer.keepQueueOrderUnshuffled() {
    if (shuffleOrder is ShuffleOrder.UnshuffledShuffleOrder) return
    setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(mediaItemCount))
}
