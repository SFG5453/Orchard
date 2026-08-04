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

package dev.sfg.orchard.mobile.playback.smart

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The filter ride a DJ does on a channel during a blend, as a Media3 audio processor.
 *
 * This is what the `dj_assisted` tier needs and a volume ramp cannot give it. An equal-power fade
 * holds both full arrangements at -3 dB through the middle of the overlap, and two mixes played
 * together are correlated, so they sum hardest exactly where their spectra collide. Taking the
 * outgoing track's top away first and its mids last makes it *thin out and recede* rather than
 * merely get quieter, and because the corner is moving, the ear follows the movement, which is
 * what covers the seam.
 *
 * Separate from [TransitionRenderer], which does the same job offline for beat-matched pairs by
 * rendering a finished buffer. That path only applies when both grids are trusted and the tempi are
 * within a few percent; this one applies to everything else, which is most pairs.
 *
 * Parameters are read fresh per block rather than per sample. Coefficient updates are cheap at that
 * granularity and inaudible, whereas recomputing per sample would put three transcendentals in the
 * inner loop of a real-time callback.
 */
@UnstableApi
class TransitionFilter : BaseAudioProcessor() {

    /**
     * Low-pass corner in hertz. [OPEN] leaves the band untouched, which is the resting state; a
     * filter that is always in circuit colours playback even when no transition is happening.
     */
    @Volatile var lowPassHz: Double = OPEN

    /**
     * Linear gain on the band below [BASS_CROSSOVER_HZ]. The bass handover works by taking the low
     * end off one track while the other keeps it, so the two kick drums never occupy it at once.
     */
    @Volatile var bassGain: Double = 1.0

    /** Overall linear gain, so the fade curve and the filter ride are applied in one pass. */
    @Volatile var gain: Double = 1.0

    private var channelCount = 0
    private var sampleRate = 0

    // Per-channel biquad state, two samples of history each.
    private var lowPassState = Array(0) { DoubleArray(4) }
    private var bassState = Array(0) { DoubleArray(4) }

    // Cached coefficients, recomputed only when the parameters they depend on move.
    private var cachedLowPassHz = -1.0
    private var cachedBassGain = -1.0
    private val lowPass = DoubleArray(5)
    private val lowShelf = DoubleArray(5)

    /** True when nothing is being altered, so the processor can be skipped entirely. */
    val transparent: Boolean
        get() = lowPassHz >= OPEN && abs(bassGain - 1.0) < 1e-4 && abs(gain - 1.0) < 1e-4

    /** Returns every parameter to its resting state. Named to avoid colliding with `reset()`. */
    fun clearAutomation() {
        lowPassHz = OPEN
        bassGain = 1.0
        gain = 1.0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 16-bit only: it is what the decoders here emit, and supporting float as well would mean a
        // second inner loop for a case that does not arise.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        lowPassState = Array(channelCount) { DoubleArray(4) }
        bassState = Array(channelCount) { DoubleArray(4) }
        cachedLowPassHz = -1.0
        cachedBassGain = -1.0
        return inputAudioFormat
    }

    override fun isActive(): Boolean = sampleRate != 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames == 0) return
        val output = replaceOutputBuffer(inputBuffer.remaining())

        // Snapshotted once per block: the ramp writes these from another thread, and a parameter
        // that changed halfway through a block would put a discontinuity in the middle of it.
        val corner = lowPassHz
        val bass = bassGain
        val level = gain

        if (corner < OPEN) updateLowPass(corner)
        if (abs(bass - 1.0) >= 1e-4) updateLowShelf(bass)
        val filtering = corner < OPEN
        val shelving = abs(bass - 1.0) >= 1e-4

        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                var sample = inputBuffer.short.toDouble() / 32768.0
                if (filtering) sample = biquad(lowPass, lowPassState[channel], sample)
                if (shelving) sample = biquad(lowShelf, bassState[channel], sample)
                sample *= level
                output.putShort((sample.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    /** Direct-form I, which needs four words of state but is numerically kinder than form II. */
    private fun biquad(coefficients: DoubleArray, state: DoubleArray, input: Double): Double {
        val output = coefficients[0] * input + coefficients[1] * state[0] + coefficients[2] * state[1] -
            coefficients[3] * state[2] - coefficients[4] * state[3]
        state[1] = state[0]
        state[0] = input
        state[3] = state[2]
        state[2] = output
        return output
    }

    /** RBJ low-pass at Butterworth Q, which is flat in the passband; a resonant corner would sing. */
    private fun updateLowPass(hz: Double) {
        if (abs(hz - cachedLowPassHz) < 1.0) return
        cachedLowPassHz = hz
        val w0 = 2 * PI * hz.coerceIn(30.0, sampleRate * 0.45) / sampleRate
        val alpha = sin(w0) / (2 * Q)
        val cosW0 = cos(w0)
        val a0 = 1 + alpha
        lowPass[0] = ((1 - cosW0) / 2) / a0
        lowPass[1] = (1 - cosW0) / a0
        lowPass[2] = lowPass[0]
        lowPass[3] = (-2 * cosW0) / a0
        lowPass[4] = (1 - alpha) / a0
    }

    /**
     * RBJ low shelf at [BASS_CROSSOVER_HZ].
     *
     * A shelf rather than a high-pass because the bass handover is a *level* change on the low band,
     * not its removal: the outgoing track keeps its weight until it hands over, and a high-pass
     * would take the body out of it the moment the transition started.
     */
    private fun updateLowShelf(linearGain: Double) {
        if (abs(linearGain - cachedBassGain) < 1e-3) return
        cachedBassGain = linearGain
        val a = linearGain.coerceIn(0.001, 4.0).pow(0.5)
        val w0 = 2 * PI * BASS_CROSSOVER_HZ / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2 * kotlin.math.sqrt((a + 1 / a) * (1 / SHELF_SLOPE - 1) + 2)
        val twoSqrtAAlpha = 2 * kotlin.math.sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha
        lowShelf[0] = (a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha)) / a0
        lowShelf[1] = (2 * a * ((a - 1) - (a + 1) * cosW0)) / a0
        lowShelf[2] = (a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha)) / a0
        lowShelf[3] = (-2 * ((a - 1) + (a + 1) * cosW0)) / a0
        lowShelf[4] = ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha) / a0
    }

    override fun onReset() {
        clearAutomation()
        lowPassState = Array(0) { DoubleArray(4) }
        bassState = Array(0) { DoubleArray(4) }
        channelCount = 0
        sampleRate = 0
    }

    companion object {
        /** Above the top of hearing: the resting state, and where a sweep starts from. */
        const val OPEN = 20_000.0

        /** Everything below this belongs to exactly one track at a time during the handover. */
        const val BASS_CROSSOVER_HZ = 200.0

        /** Where a sweep starts. Deliberately inaudible at first, or the transition announces itself. */
        const val SWEEP_START_HZ = 18_000.0

        private const val Q = 0.70710678
        private const val SHELF_SLOPE = 1.0
    }
}
