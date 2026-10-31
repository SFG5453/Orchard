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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.playback.smart.TransitionFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

internal class PlaybackVolumeState {
    @Volatile var exponentialEnabled = false
    @Volatile var systemFraction = 1f

    fun outputGain(volume: Float): Float {
        val bounded = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f
        if (!exponentialEnabled) return bounded
        val fraction = if (systemFraction.isFinite()) systemFraction.coerceIn(0f, 1f) else 1f
        // Android applies its own media gain; extra attenuation follows the volume steps.
        return bounded * bounded * bounded * fraction * fraction
    }
}

internal class PlaybackVolumeMonitor(
    private val context: Context,
    val state: PlaybackVolumeState = PlaybackVolumeState(),
) : AutoCloseable {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val receiverRegistered: Boolean
    private val observerRegistered: Boolean

    init {
        refresh()
        receiverRegistered = runCatching {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
                addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.isSuccess
        observerRegistered = runCatching {
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }.isSuccess
    }

    private fun refresh() {
        val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        state.systemFraction = if (maximum > minimum) {
            ((volume - minimum).toFloat() / (maximum - minimum)).coerceIn(0f, 1f)
        } else 1f
    }

    override fun close() {
        if (receiverRegistered) context.unregisterReceiver(receiver)
        if (observerRegistered) context.contentResolver.unregisterContentObserver(observer)
    }
}

@UnstableApi
internal class VolumeAudioSink(sink: AudioSink, private val state: PlaybackVolumeState) : ForwardingAudioSink(sink) {
    private var volume = 1f
    private var appliedGain = Float.NaN

    override fun setVolume(volume: Float) {
        this.volume = volume
        applyVolume()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // Reapply on the playback thread so settings and hardware changes reach both decks.
        applyVolume()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun play() {
        applyVolume()
        super.play()
    }

    override fun reset() {
        super.reset()
        appliedGain = Float.NaN
    }

    private fun applyVolume() {
        val gain = state.outputGain(volume)
        if (gain == appliedGain) return
        super.setVolume(gain)
        appliedGain = gain
    }
}

@UnstableApi
internal fun observeAudioSettings(
    graph: OrchardGraph,
    scope: CoroutineScope,
    volume: PlaybackVolumeState,
    filters: List<TransitionFilter>,
    equalizers: List<EqualizerAudioProcessor>,
) {
    volume.exponentialEnabled = graph.settings.settings.value.exponentialVolumeEnabled
    scope.launch {
        graph.settings.settings.combine(graph.maxActive) { settings, max -> settings to max }.collect { (settings, max) ->
            volume.exponentialEnabled = settings.exponentialVolumeEnabled
            filters.forEach { it.volumeNormalizationEnabled = settings.volumeNormalizationEnabled }
            // MAX streams Qobuz bytes untouched by the equalizer.
            val eq = if (max) settings.equalizerConfig.copy(enabled = false) else settings.equalizerConfig
            equalizers.forEach { it.config = eq }
        }
    }
}
