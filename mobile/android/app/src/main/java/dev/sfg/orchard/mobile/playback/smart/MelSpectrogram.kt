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

import kotlin.math.abs

/**
 * The log-mel front end the Beat This! model expects, backed by Earmark's Rust analyzer.
 *
 * Every constant is dictated by the trained network rather than chosen: it was trained on
 * torchaudio's MelSpectrogram at 22,050 Hz with n_fft 1024, hop 441 (so exactly 50 frames per
 * second), 128 Slaney mel bands from 30 Hz to 11 kHz, and `log1p(1000 * magnitude)`. A front end
 * that differs even in window convention feeds the model something it has never seen, which is why
 * this is a port of the desktop C++ rather than a reimplementation.
 *
 * [SAMPLE_RATE] is required, not preferred: audio at any other rate is refused rather than
 * resampled here, because resampling belongs upstream where full-bandwidth samples still exist.
 */
object MelSpectrogram {

    /** True when the native library loaded. Analysis is optional, so this is a fact, not a fault. */
    val available: Boolean = runCatching {
        System.loadLibrary("orchard_resampler")
        System.loadLibrary("orchard_earmark")
    }.isSuccess

    /** Mel bands per frame; the model's input width. */
    val mels: Int by lazy { if (available) nativeMelCount() else 128 }

    /** The only sample rate the front end accepts. */
    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 22_050.0 }

    /** Samples between frame starts; 441 at 22,050 Hz is exactly 20 ms. */
    val hop: Int by lazy { if (available) nativeHop() else 441 }

    /** Frames per second of output, which is what beat times are derived from. */
    val frameRate: Double get() = sampleRate / hop

    /**
     * Computes the spectrogram for contiguous mono float PCM at [SAMPLE_RATE].
     *
     * Returns null when the native library is missing, the rate is wrong, or the input is shorter
     * than one padded frame. Callers treat that as "no model prediction available" rather than as
     * an error; the transition policy already degrades on absent evidence.
     */
    fun compute(samples: FloatArray, sampleRate: Double = this.sampleRate): Spectrogram? {
        if (!available || samples.isEmpty()) return null
        val values = nativeCompute(samples, sampleRate)
        if (values.isEmpty()) return null
        return Spectrogram(values = values, frames = values.size / mels, mels = mels)
    }

    /**
     * A flattened spectrogram, row-major over frames: frame `f` band `b` lives at
     * `f * mels + b`. Flat because the only consumer hands it straight to an ONNX tensor of
     * shape `[1, frames, mels]`.
     */
    data class Spectrogram(val values: FloatArray, val frames: Int, val mels: Int) {
        /** Seconds covered, useful for turning frame indices back into track times. */
        val durationSeconds: Double get() = frames / (sampleRate / hop)

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Spectrogram && frames == other.frames && mels == other.mels &&
                    values.contentEquals(other.values))

        override fun hashCode(): Int = 31 * (31 * values.contentHashCode() + frames) + mels
    }

    /**
     * Converts mono float PCM to [sampleRate], the only rate [compute] accepts.
     *
     * A windowed-sinc conversion rather than decimation: dropping samples would fold everything
     * above 11 kHz back into the band the mel filterbank reads, and an aliased spectrogram yields
     * a *wrong* beat grid rather than a noisy one, which the planner would then trust.
     *
     * Returns the input unchanged when the rates already match, and null when the native library
     * is missing or the rates are unusable.
     */
    fun resample(samples: FloatArray, inputRate: Double, outputRate: Double = sampleRate): FloatArray? {
        if (!available || samples.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        // Matching rates are the ordinary case wherever a decode already targeted the rate that is
        // wanted, and the native call would copy a whole track in and back out to return it
        // unchanged. That copy is megabytes on the analysis path and buys nothing.
        if (abs(inputRate - outputRate) < 1e-6) return samples
        return nativeResample(samples, inputRate, outputRate).takeIf { it.isNotEmpty() }
    }

    /**
     * Input samples spanning a whole number of output samples, or 0 when the rates admit no such
     * period and the stream must be resampled in one call.
     *
     * Blocks measured in whole periods land on the same output grid the whole stream would have
     * produced, which is what makes [resampleInterior] exact rather than approximate.
     */
    fun resamplePeriod(inputRate: Double, outputRate: Double): Int =
        if (available) nativeResamplePeriod(inputRate, outputRate) else 0

    /** Input samples of filter context [resampleInterior] needs either side of a block. */
    fun resampleContext(inputRate: Double, outputRate: Double): Int =
        if (available) nativeResampleContext(inputRate, outputRate) else 0

    /**
     * Resamples one block of a longer stream, returning only that block's own output.
     *
     * The window is [leadingContext] samples of preceding audio, then the block, then
     * [trailingContext] samples of what follows; a zero trailing context means the block runs to
     * the end of the stream. Given at least [resampleContext] of each, the concatenated results
     * carry the same sample count as resampling the whole stream at once, and are identical to the
     * bit across every block boundary, so none is left for the beat tracker to read as an onset.
     */
    fun resampleInterior(
        samples: FloatArray,
        offset: Int,
        length: Int,
        inputRate: Double,
        outputRate: Double,
        leadingContext: Int,
        trailingContext: Int,
    ): FloatArray =
        if (available) {
            nativeResampleInterior(
                samples, offset, length, inputRate, outputRate, leadingContext, trailingContext,
            )
        } else {
            FloatArray(0)
        }

    @JvmStatic private external fun nativeCompute(samples: FloatArray, sampleRate: Double): FloatArray
    @JvmStatic private external fun nativeResample(
        samples: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): FloatArray
    @JvmStatic private external fun nativeResamplePeriod(inputRate: Double, outputRate: Double): Int
    @JvmStatic private external fun nativeResampleContext(inputRate: Double, outputRate: Double): Int
    @JvmStatic private external fun nativeResampleInterior(
        samples: FloatArray,
        offset: Int,
        length: Int,
        inputRate: Double,
        outputRate: Double,
        leadingContext: Int,
        trailingContext: Int,
    ): FloatArray
    @JvmStatic private external fun nativeMelCount(): Int
    @JvmStatic private external fun nativeSampleRate(): Double
    @JvmStatic private external fun nativeHop(): Int
}
