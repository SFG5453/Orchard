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

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

@UnstableApi
class PlaybackVolumeTest {
    private class Output {
        var gain = -1f
        var updates = 0
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "setVolume" -> { gain = args!![0] as Float; updates++; null }
                "handleBuffer" -> true
                else -> null
            }
        } as AudioSink
    }

    @Test
    fun curveKeepsMuteAndFullVolumeAndAddsLowVolumePrecision() {
        val state = PlaybackVolumeState()
        state.systemFraction = 0.2f
        assertEquals(0.5f, state.outputGain(0.5f), 0f)
        state.exponentialEnabled = true
        state.systemFraction = 1f
        val levels = listOf(0f to 0f, 0.05f to 0.000125f, 0.1f to 0.001f, 0.2f to 0.008f, 0.5f to 0.125f, 1f to 1f)
        levels.forEach { (volume, expected) -> assertEquals(expected, state.outputGain(volume), 1e-7f) }
        assertEquals(0f, state.outputGain(-1f), 0f)
        assertEquals(1f, state.outputGain(2f), 0f)
        assertEquals(1f, state.outputGain(Float.NaN), 0f)
    }

    @Test
    fun settingsAndHardwareVolumeReachBothDecksOnTheNextBuffer() {
        val state = PlaybackVolumeState()
        val outputs = listOf(Output(), Output())
        val sinks = outputs.map { VolumeAudioSink(it.sink, state) }
        fun buffer() = sinks.forEach { it.handleBuffer(ByteBuffer.allocate(4), 0, 1) }
        sinks.forEach { it.setVolume(0.5f) }
        outputs.forEach { assertEquals(0.5f, it.gain, 0f) }
        state.exponentialEnabled = true
        buffer()
        outputs.forEach { assertEquals(0.125f, it.gain, 0f) }
        state.systemFraction = 0.5f
        buffer()
        outputs.forEach { assertEquals(0.03125f, it.gain, 0f) }
        state.systemFraction = 0f
        buffer()
        outputs.forEach { assertEquals(0f, it.gain, 0f) }
        state.exponentialEnabled = false
        buffer()
        outputs.forEach { assertEquals(0.5f, it.gain, 0f) }
    }

    @Test
    fun sinkRetainsItsVolumeAcrossResetAndAvoidsRedundantUpdates() {
        val state = PlaybackVolumeState().apply { exponentialEnabled = true }
        val output = Output()
        val sink = VolumeAudioSink(output.sink, state)
        sink.setVolume(0.5f)
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(1, output.updates)
        sink.reset()
        sink.play()
        assertEquals(2, output.updates)
        assertEquals(0.125f, output.gain, 0f)
    }
}
